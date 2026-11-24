package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
final class CodexReaderTransport {

    private static final Set<String> METHODS = Set.of("thread/list", "thread/read", "thread/turns/list", "thread/items/list");
    private static final Map<String, List<String>> FIELDS = Map.of(
            "ThreadListParams", List.of("cwd", "useStateDbOnly", "cursor", "limit"),
            "ThreadReadParams", List.of("threadId", "includeTurns"),
            "ThreadTurnsListParams", List.of("threadId", "itemsView", "sortDirection", "cursor"),
            "ThreadItemsListParams", List.of("threadId", "turnId", "cursor"));
    private static final Pattern VERSION = Pattern.compile("codex-cli ([^\\r\\n]{1,100})");
    private static final int SCHEMA_BYTES = 8 * 1024 * 1024;
    private static final int STDIO_BYTES = 2 * 1024 * 1024;
    private static final int LINE_BYTES = 1024 * 1024;
    private static final int REQUEST_BYTES = 16 * 1024;

    private final JsonMapper mapper;
    private final ObjectReader json;
    private final String executable;
    private final List<String> prefix;
    private final Map<String, String> environment;
    private String checkedVersion;

    @Autowired
    CodexReaderTransport(JsonMapper mapper) {
        this(mapper, CodexProcess.executable(System.getenv()), List.of(), System.getenv());
    }

    CodexReaderTransport(JsonMapper mapper, String executable, List<String> prefix, Map<String, String> environment) {
        this.mapper = mapper;
        this.json = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.executable = executable;
        this.prefix = List.copyOf(prefix);
        this.environment = Map.copyOf(environment);
    }

    synchronized ObjectNode probe() {
        try {
            var output = CodexProcess.capture(executable, prefix, List.of("--version"), environment, null, 5, 8192);
            var version = VERSION.matcher(TaskPayload.trim(output));
            if (!version.matches()) {
                throw unavailable();
            }
            if (!version.group(1).equals(checkedVersion)) {
                checkSchemas();
                checkedVersion = version.group(1);
            }
            return mapper.createObjectNode().put("version", checkedVersion);
        } catch (IOException | RuntimeException failure) {
            throw unavailable();
        }
    }

    private void checkSchemas() throws IOException {
        try (var temp = CodexProcess.temporaryDirectory("control-center-codex-schema-")) {
            var directory = temp.path();
            CodexProcess.capture(executable, prefix,
                    List.of("app-server", "generate-json-schema", "--out", directory.toString()),
                    environment, null, 10, 8192);
            var requests = readSchema(directory, directory.resolve("ClientRequest.json"));
            var methods = new HashSet<String>();
            var pending = new ArrayDeque<JsonNode>();
            pending.push(requests);
            while (!pending.isEmpty()) {
                var value = pending.pop();
                for (var method : value.path("properties").path("method").path("enum")) {
                    if (method.isString()) {
                        methods.add(method.stringValue());
                    }
                }
                if (value.isContainer()) {
                    value.forEach(pending::push);
                }
            }
            if (!methods.contains("initialize") || !methods.containsAll(METHODS)) {
                throw unavailable();
            }
            for (var schema : FIELDS.entrySet()) {
                var fields = readSchema(directory, directory.resolve("v2").resolve(schema.getKey() + ".json")).path("properties");
                if (!fields.isObject() || !fields.propertyNames().containsAll(schema.getValue())) {
                    throw unavailable();
                }
            }
        }
    }

    private JsonNode readSchema(Path directory, Path file) throws IOException {
        ProjectFiles.assertProjectAccess(file, List.of(directory.toString()));
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > SCHEMA_BYTES) {
            throw unavailable();
        }
        try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            var body = stream.readNBytes(SCHEMA_BYTES + 1);
            if (body.length > SCHEMA_BYTES) {
                throw unavailable();
            }
            return json.readTree(body);
        }
    }

    <T> T read(CodexCancellation cancellation, Function<Reader, T> action) {
        cancellation.check();
        probe();
        cancellation.check();
        Process process;
        try {
            process = CodexProcess.start(executable, prefix, List.of("app-server", "--listen", "stdio://"), environment, null);
        } catch (IOException | RuntimeException failure) {
            throw unavailable();
        }
        try (var session = new Reader(process, cancellation)) {
            var params = mapper.createObjectNode();
            params.putObject("clientInfo").put("name", "control_center_reader").put("title", "Projects Journal reader")
                    .put("version", "0.1.0");
            params.putObject("capabilities").put("experimentalApi", false);
            if (!session.send("initialize", params).path("userAgent").isString()) {
                throw unavailable();
            }
            session.write(mapper.createObjectNode().put("method", "initialized"));
            var result = action.apply(session);
            cancellation.check();
            return result;
        }
    }

    static TaskError unavailable() {
        return new TaskError(503, "Codex недоступен, ответ слишком велик или протокол не поддерживается. "
                + "Данные не сохранены; остальные функции приложения доступны.");
    }

    final class Reader implements AutoCloseable {
        private final Process process;
        private final CodexCancellation cancellation;
        private final CodexProcess.Lines lines;
        private final CodexCancellation.Registration registration;
        private final ScheduledFuture<?> deadline;
        private int sequence;

        private Reader(Process process, CodexCancellation cancellation) {
            this.process = process;
            this.cancellation = cancellation;
            lines = new CodexProcess.Lines(process.getInputStream(), LINE_BYTES, STDIO_BYTES);
            registration = cancellation.onCancel(() -> CodexProcess.terminate(process));
            deadline = CodexProcess.DEADLINES.schedule(() -> CodexProcess.terminate(process), 20, TimeUnit.SECONDS);
            Thread.ofVirtual().start(() -> CodexProcess.drain(process.getErrorStream()));
        }

        synchronized JsonNode request(String method, JsonNode params) {
            if (!METHODS.contains(method)) {
                throw new TaskError(403, "Разрешены только методы чтения Codex.");
            }
            if (!params.isObject()) {
                throw unavailable();
            }
            var safe = params.asObject().deepCopy();
            if ("thread/list".equals(method)) {
                safe.put("useStateDbOnly", true);
            } else if ("thread/read".equals(method)) {
                safe.put("includeTurns", false);
            }
            return send(method, safe);
        }

        private JsonNode send(String method, JsonNode params) {
            int id = ++sequence;
            var request = mapper.createObjectNode().put("id", id).put("method", method);
            request.set("params", params);
            write(request);
            try {
                for (CodexProcess.Line line; (line = lines.next()) != null; ) {
                    cancellation.check();
                    if (!line.complete()) {
                        break;
                    }
                    var value = json.readTree(line.text());
                    if (!value.isObject()) {
                        throw unavailable();
                    }
                    var responseId = value.get("id");
                    if (value.hasNonNull("method")) {
                        if (responseId != null) {
                            rejectServerRequest(responseId);
                        }
                        continue;
                    }
                    if (responseId != null && responseId.isIntegralNumber() && responseId.canConvertToInt()
                            && responseId.intValue() == id) {
                        if (value.hasNonNull("error") || !value.has("result")) {
                            throw unavailable();
                        }
                        return value.get("result");
                    }
                }
            } catch (IOException | RuntimeException failure) {
                throw unavailable();
            }
            throw unavailable();
        }

        private void rejectServerRequest(JsonNode id) {
            var denied = mapper.createObjectNode();
            denied.set("id", id);
            denied.putObject("error").put("code", -32601).put("message", "Read-only client");
            write(denied);
        }

        private void write(ObjectNode value) {
            cancellation.check();
            var bytes = mapper.writeValueAsBytes(value);
            if (bytes.length > REQUEST_BYTES) {
                throw unavailable();
            }
            try {
                var out = process.getOutputStream();
                out.write(bytes);
                out.write('\n');
                out.flush();
            } catch (IOException failure) {
                throw unavailable();
            }
        }

        @Override
        public void close() {
            deadline.cancel(false);
            registration.close();
            CodexProcess.close(process.getOutputStream());
            try {
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    CodexProcess.terminate(process);
                    CodexProcess.awaitExit(process);
                }
            } catch (InterruptedException interrupted) {
                CodexProcess.terminate(process);
                Thread.currentThread().interrupt();
                throw unavailable();
            } catch (IOException failure) {
                throw unavailable();
            } finally {
                lines.close();
                CodexProcess.close(process.getErrorStream());
            }
        }
    }
}
