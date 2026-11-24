package io.github.dmitryaf.projectsjournal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
final class CodexExecutor {

    static final int EXECUTION_TIMEOUT_SECONDS = 5 * 60;
    private static final int STDIO_BYTES = 1024 * 1024;
    private static final int LINE_BYTES = 128 * 1024;
    private static final int OUTPUT_BYTES = 64 * 1024;
    private static final int LOGIN_BYTES = 32 * 1024;
    private static final int LOGIN_MINUTES = 15;
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final Set<String> ALLOWED_ENV = Set.of("PATH", "PATHEXT", "SYSTEMROOT", "WINDIR", "COMSPEC",
            "LOCALAPPDATA", "APPDATA", "TEMP", "TMP", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "SSL_CERT_FILE", "SSL_CERT_DIR");
    private static final List<String> DISABLED = List.of("shell_tool", "apps", "plugins", "hooks", "browser_use",
            "browser_use_external", "computer_use", "image_generation", "view_image", "code_mode", "code_mode_host",
            "multi_agent", "skill_search", "remote_plugin", "unbounded_connection_retries", "memories", "external_agent_memory_import");
    private static final Set<String> NON_TOOL_ITEMS = Set.of("reasoning", "plan", "todo_list", "error");
    private static final Pattern VERSION = Pattern.compile("codex-cli ([A-Za-z0-9_.+-]{1,100})");
    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}");
    private static final Pattern ITEM_TYPE = Pattern.compile("[a-z_]{1,40}");
    private static final Pattern DEVICE_CODE = Pattern.compile("\\b[A-Z0-9]{4,5}-[A-Z0-9]{4,5}\\b");
    private static final String DEVICE_URL = "https://auth.openai.com/codex/device";
    private static final Pattern DEVICE_LINK = Pattern.compile("(?<![A-Za-z0-9:/])" + Pattern.quote(DEVICE_URL) + "(?![A-Za-z0-9._/?:#%=-])");
    private static final Pattern TERMINAL_CODES = Pattern.compile("\\x1b\\][^\\x07\\x1b]*(?:\\x07|\\x1b\\\\)|\\x1b\\[[0-?]*[ -/]*[@-~]|\\x1b[@-_]");

    private final JsonMapper mapper;
    private final ObjectReader json;
    private final Path dataDirectory;
    private final String executable;
    private final List<String> prefix;
    private final Map<String, String> environment;
    private final ObjectNode reportSchema;
    private final Object loginMonitor = new Object();
    private ObjectNode cached;
    private long checkedAt;
    private ObjectNode loginState;
    private LoginJob loginJob;

    @Autowired
    CodexExecutor(JsonMapper mapper, SqliteStore store) {
        this(mapper, store.path().getParent(), CodexProcess.executable(System.getenv()), List.of(), System.getenv());
    }

    CodexExecutor(JsonMapper mapper, Path dataDirectory, String executable, List<String> prefix, Map<String, String> environment) {
        this.mapper = mapper;
        this.json = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.executable = executable;
        this.prefix = List.copyOf(prefix);
        this.environment = Map.copyOf(environment);
        this.reportSchema = new PeriodReport(mapper, new AnalysisPrivacy(mapper)).schema()
                .put("$schema", "http://json-schema.org/draft-07/schema#");
    }

    ObjectNode probe() {
        return probe(false);
    }

    ObjectNode probe(boolean refresh) {
        synchronized (loginMonitor) {
            if (!refresh && cached != null && System.nanoTime() - checkedAt < TimeUnit.SECONDS.toNanos(30)) {
                return withLogin(cached);
            }
            Path home;
            try {
                home = ensureProfile();
            } catch (IOException | RuntimeException unavailable) {
                return unavailable("Профиль анализа недоступен или содержит AGENTS.md. "
                        + "Для анализа используется отдельный профиль без личных инструкций.");
            }
            try {
                var version = VERSION.matcher(TaskPayload.trim(capture(home, List.of("--version"))));
                if (!version.matches()) {
                    throw new IOException("Unsupported Codex CLI version response");
                }
                var help = capture(home, List.of("exec", "--help"));
                for (var flag : List.of("--ignore-user-config", "--ephemeral", "--output-schema", "--json", "--sandbox")) {
                    if (!help.contains(flag)) {
                        throw new IOException("Unsupported Codex CLI arguments");
                    }
                }
                var flags = featureArguments();
                flags.addAll(List.of("features", "list"));
                var features = capture(home, flags);
                for (var name : DISABLED) {
                    if (!Pattern.compile("^" + name + "\\s+.*\\sfalse\\s*$", Pattern.MULTILINE).matcher(features).find()) {
                        throw new IOException("Codex CLI features could not be disabled");
                    }
                }
                boolean authenticated;
                try {
                    capture(home, List.of("login", "status"));
                    authenticated = true;
                } catch (IOException unavailable) {
                    authenticated = false;
                }
                cached = mapper.createObjectNode().put("available", true).put("version", version.group(1)).putNull("reason")
                        .put("authenticated", authenticated);
                checkedAt = System.nanoTime();
                return withLogin(cached);
            } catch (IOException | RuntimeException unsupported) {
                cached = null;
                return unavailable("Codex CLI недоступен или не поддерживает изолированный анализ. "
                        + "Проверьте установку и вход через codex login. Сохранённые данные доступны.");
            }
        }
    }

    private ObjectNode unavailable(String reason) {
        return mapper.createObjectNode().put("available", false).putNull("version").put("reason", reason);
    }

    private ObjectNode withLogin(ObjectNode status) {
        return status.deepCopy().set("login", loginState == null ? null : loginState.deepCopy());
    }

    private String capture(Path home, List<String> args) throws IOException {
        return CodexProcess.capture(executable, prefix, args, childEnvironment(home), home, 10, 64 * 1024);
    }

    private Path ensureProfile() throws IOException {
        ProjectFiles.assertProjectAccess(dataDirectory, List.of(dataDirectory.getRoot().toString()));
        var home = dataDirectory.resolve("codex-analysis");
        try {
            if (Files.getFileStore(dataDirectory).supportsFileAttributeView("posix")) {
                Files.createDirectory(home, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectory(home);
            }
        } catch (FileAlreadyExistsException ignored) {
            // Check the existing entry without following a replacement link.
        }
        var attributes = Files.readAttributes(home, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("Unsafe Codex profile");
        }
        ProjectFiles.assertProjectAccess(home, List.of(dataDirectory.toString()));
        for (String name : List.of("AGENTS.md", "AGENTS.override.md")) {
            try {
                Files.readAttributes(home.resolve(name), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                throw new IOException("Codex profile contains instructions");
            } catch (NoSuchFileException ignored) {
                // The isolated profile may not provide custom instructions.
            }
        }
        return home;
    }

    private Map<String, String> childEnvironment(Path home) {
        var child = new HashMap<String, String>();
        environment.forEach((key, value) -> {
            if (ALLOWED_ENV.contains(key.toUpperCase(Locale.ROOT))) {
                child.put(key, value);
            }
        });
        for (String variable : List.of("CODEX_HOME", "HOME", "USERPROFILE")) {
            child.put(variable, home.toString());
        }
        return child;
    }

    ObjectNode login() {
        synchronized (loginMonitor) {
            var status = probe();
            if (!status.path("available").asBoolean() || status.path("authenticated").asBoolean(false) || loginJob != null) {
                return status;
            }
            loginState = loginState("starting", null, null, null);
            try {
                var home = ensureProfile();
                var process = CodexProcess.start(executable, prefix, List.of("login", "--device-auth"), childEnvironment(home), home);
                var job = new LoginJob(process);
                loginJob = job;
                Thread.ofVirtual().start(() -> watchLogin(job));
            } catch (IOException | RuntimeException failure) {
                loginState = failedLogin();
                cached = null;
            }
            return withLogin(status);
        }
    }

    private ObjectNode loginState(String state, String code, String url, String error) {
        return mapper.createObjectNode().put("state", state).put("code", code).put("url", url).put("error", error);
    }

    private ObjectNode failedLogin() {
        return loginState("failed", null, null,
                "Вход не завершён. Разрешите вход по коду устройства в настройках безопасности ChatGPT и повторите попытку.");
    }

    private void watchLogin(LoginJob job) {
        var process = job.process;
        CodexProcess.close(process.getOutputStream());
        var stdout = Thread.ofVirtual().start(() -> readLogin(job, process.getInputStream()));
        var stderr = Thread.ofVirtual().start(() -> readLogin(job, process.getErrorStream()));
        try {
            if (!process.waitFor(LOGIN_MINUTES, TimeUnit.MINUTES) || process.exitValue() != 0) {
                job.failed.set(true);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            job.failed.set(true);
        } finally {
            CodexProcess.terminate(process);
            CodexProcess.close(process.getInputStream());
            CodexProcess.close(process.getErrorStream());
            stdout.interrupt();
            stderr.interrupt();
            synchronized (loginMonitor) {
                if (loginJob == job) {
                    loginJob = null;
                    cached = null;
                    if (loginState != null) {
                        loginState = job.failed.get() ? failedLogin() : null;
                    }
                }
            }
            job.done.complete(null);
        }
    }

    private void readLogin(LoginJob job, InputStream stream) {
        try (stream) {
            var bytes = new byte[2048];
            for (int count; (count = stream.read(bytes)) != -1; ) {
                var code = job.capture(bytes, count);
                if (job.failed.get()) {
                    CodexProcess.terminate(job.process);
                    return;
                }
                if (code != null) {
                    synchronized (loginMonitor) {
                        if (loginJob == job && loginState != null) {
                            loginState = loginState("waiting", code, DEVICE_URL, null);
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // Cancellation and normal process exit can independently close these pipes.
        }
    }

    @PreDestroy
    void cancelLogin() {
        LoginJob job;
        synchronized (loginMonitor) {
            loginState = null;
            job = loginJob;
        }
        if (job != null) {
            CodexProcess.terminate(job.process);
            try {
                job.done.get(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException ignored) {
                // No login state is returned for a cancelled child.
            }
        }
    }

    Result execute(String prompt, String model, CodexCancellation cancellation, Consumer<String> onProgress, Runnable beforeDispatch) {
        if (model != null && !model.isEmpty() && !MODEL.matcher(model).matches()) {
            throw failed("Недопустимый идентификатор модели.");
        }
        var status = probe();
        if (!status.path("available").asBoolean()) {
            throw failed("Codex CLI недоступен. Запрос не запущен.");
        }
        if (!status.path("authenticated").asBoolean()) {
            throw failed("Войдите в отдельный профиль Codex для приложения. Запрос не запущен.");
        }
        if (cancellation.cancelled()) {
            throw failed("Запуск отменён до отправки.");
        }
        Result result = null;
        try {
            var home = ensureProfile();
            try (var directory = CodexProcess.temporaryDirectory("control-center-codex-exec-")) {
                var schema = directory.path().resolve("report.schema.json");
                Files.write(schema, mapper.writeValueAsBytes(reportSchema), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                var process = CodexProcess.start(executable, prefix, executionArguments(schema, model), childEnvironment(home), directory.path());
                result = dispatch(process, prompt, cancellation, onProgress, beforeDispatch);
                return result;
            }
        } catch (IOException unavailable) {
            if (result != null) {
                throw new Failure("Результат не сохранён. Запрос мог использовать лимит Codex; автоматического повтора нет.", "unknown");
            }
            throw failed("Не удалось подготовить изолированный запуск Codex CLI.");
        }
    }

    private Result dispatch(Process process, String prompt, CodexCancellation cancellation,
                            Consumer<String> onProgress, Runnable beforeDispatch) {
        var job = new Execution(process, onProgress);
        var deadline = CodexProcess.DEADLINES.schedule(() -> job.fail(
                "Codex не завершил анализ за пять минут. Результат неизвестен; автоматического повтора нет.", job.state()),
                EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try (var registration = cancellation.onCancel(job::abort)) {
            Thread.ofVirtual().start(() -> CodexProcess.drain(process.getErrorStream()));
            Thread.ofVirtual().start(job::read);
            job.checkFailure();
            onProgress.accept("Запуск Codex");
            if (beforeDispatch != null) {
                beforeDispatch.run();
            }
            job.checkFailure();
            try (var input = process.getOutputStream()) {
                input.write(prompt.getBytes(StandardCharsets.UTF_8));
            } catch (IOException unavailable) {
                job.fail("Не удалось передать контекст Codex.", job.state());
                job.checkFailure();
            }
            return job.await();
        } finally {
            deadline.cancel(false);
            CodexProcess.terminate(process);
            CodexProcess.close(process.getOutputStream());
            CodexProcess.close(process.getInputStream());
            CodexProcess.close(process.getErrorStream());
            try {
                CodexProcess.awaitExit(process);
            } catch (IOException ignored) {
                // The deadline and cancellation already request forced shutdown.
            }
        }
    }

    private static ArrayList<String> featureArguments() {
        var args = new ArrayList<String>();
        for (var name : DISABLED) {
            args.add("--disable");
            args.add(name);
        }
        return args;
    }

    private static List<String> executionArguments(Path schema, String model) {
        var args = new ArrayList<>(List.of("exec", "--ignore-user-config", "--ignore-rules", "--ephemeral",
                "--sandbox", "read-only", "--json", "--output-schema", schema.toString(), "--skip-git-repo-check",
                "--color", "never", "-c", "web_search=\"disabled\"", "-c", "project_doc_max_bytes=0",
                "-c", "memories.use_memories=false", "-c", "memories.generate_memories=false", "-c", "skills.include_instructions=false",
                "--enable", "skip_host_skill_discovery"));
        args.addAll(featureArguments());
        if (model != null && !model.isEmpty()) {
            args.addAll(List.of("--model", model));
        }
        args.add("-");
        return args;
    }

    private static Failure failed(String message) {
        return new Failure(message, "failed");
    }

    static final class Failure extends RuntimeException {
        private final String state;

        Failure(String message, String state) {
            super(message);
            this.state = state;
        }

        String state() {
            return state;
        }
    }

    record Result(String output, String threadId, JsonNode usage) {
    }

    private static final class LoginJob {
        private final Process process;
        private final AtomicBoolean failed = new AtomicBoolean();
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        LoginJob(Process process) {
            this.process = process;
        }

        synchronized String capture(byte[] data, int count) {
            if (output.size() + count > LOGIN_BYTES) {
                failed.set(true);
                return null;
            }
            output.write(data, 0, count);
            var text = TERMINAL_CODES.matcher(output.toString(StandardCharsets.UTF_8)).replaceAll("");
            var code = DEVICE_CODE.matcher(text);
            return DEVICE_LINK.matcher(text).find() && code.find() ? code.group() : null;
        }
    }

    private final class Execution {
        private final Process process;
        private final Consumer<String> onProgress;
        private final AtomicReference<Failure> failure = new AtomicReference<>();
        private final CompletableFuture<Result> result = new CompletableFuture<>();
        private volatile boolean started;
        private boolean completed;
        private String output;
        private String threadId;
        private JsonNode usage;

        Execution(Process process, Consumer<String> onProgress) {
            this.process = process;
            this.onProgress = onProgress;
        }

        String state() {
            return started ? "unknown" : "failed";
        }

        void abort() {
            fail("Выполнение остановлено. Ответ не сохранён; запрос мог использовать лимит Codex. Автоматического повтора нет.", state());
        }

        void fail(String message, String state) {
            failure.compareAndSet(null, new Failure(message, state));
            CodexProcess.terminate(process);
        }

        void checkFailure() {
            var found = failure.get();
            if (found != null) {
                throw found;
            }
        }

        void read() {
            try (var lines = new CodexProcess.Lines(process.getInputStream(), LINE_BYTES, STDIO_BYTES)) {
                for (CodexProcess.Line line; (line = lines.next()) != null; ) {
                    checkFailure();
                    var text = TaskPayload.trim(line.text());
                    if (text.isEmpty()) {
                        continue;
                    }
                    if (!line.complete()) {
                        throw incomplete();
                    }
                    event(json.readTree(text));
                }
                if (process.waitFor() != 0 || !started || !completed || threadId == null || threadId.isEmpty() || output == null || output.isEmpty()) {
                    throw incomplete();
                }
                checkFailure();
                result.complete(new Result(output, threadId, usage));
            } catch (CodexProcess.LimitException oversized) {
                fail("Ответ Codex превышает допустимый размер.", state());
                result.completeExceptionally(failure.get());
            } catch (Failure error) {
                fail(error.getMessage(), error.state());
                result.completeExceptionally(failure.get());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                abort();
                result.completeExceptionally(failure.get());
            } catch (IOException | RuntimeException malformed) {
                fail("Codex вернул неподдерживаемый поток событий.", state());
                result.completeExceptionally(failure.get());
            }
        }

        private Failure incomplete() {
            return new Failure("Codex не вернул завершённый ответ. Автоматического повтора нет.", state());
        }

        private void event(JsonNode event) {
            switch (event.path("type").stringValue("")) {
                case "thread.started" -> threadId = ApiJson.text(event.get("thread_id"), "thread_id", 0, 150, false);
                case "turn.started" -> {
                    started = true;
                    onProgress.accept("Codex анализирует выбранный контекст");
                }
                case "item.started" -> {
                    var item = event.path("item").path("type").stringValue("");
                    if (!"agent_message".equals(item) && !NON_TOOL_ITEMS.contains(item)) {
                        throw failed("Инструменты недоступны для встроенного анализа. Ответ отклонён.");
                    }
                }
                case "item.completed" -> storeItem(event.path("item"));
                case "turn.completed" -> {
                    completed = true;
                    var tokens = event.path("usage");
                    var input = tokens.path("input_tokens");
                    var outputTokens = tokens.path("output_tokens");
                    if (tokenCount(input) && tokenCount(outputTokens)) {
                        usage = mapper.createObjectNode().put("inputTokens", input.longValue()).put("outputTokens", outputTokens.longValue());
                    }
                    onProgress.accept("Проверка и сохранение ответа");
                }
                case "turn.failed" -> throw failed("Codex сообщил об отказе. Проверьте вход и доступный лимит.");
                case "error" -> throw new Failure("Codex сообщил об ошибке. Подробности не копируются из CLI для защиты личных данных.", state());
                default -> { }
            }
        }

        private void storeItem(JsonNode item) {
            var type = item.path("type").stringValue("");
            if ("agent_message".equals(type) && item.path("text").isString()) {
                output = item.path("text").stringValue();
                if (output.getBytes(StandardCharsets.UTF_8).length > OUTPUT_BYTES) {
                    throw new Failure("Ответ Codex больше 64 КБ.", state());
                }
            } else if (!NON_TOOL_ITEMS.contains(type)) {
                throw failed("Неподдерживаемый тип элемента Codex: " + (ITEM_TYPE.matcher(type).matches() ? type : "unknown") + ". Ответ отклонён.");
            }
        }

        private boolean tokenCount(JsonNode value) {
            return value.isNumber() && value.canConvertToExactIntegral() && value.canConvertToLong()
                    && value.longValue() >= 0 && value.longValue() <= MAX_SAFE_INTEGER;
        }

        Result await() {
            try {
                return result.get(EXECUTION_TIMEOUT_SECONDS + 3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                abort();
                throw failure.get();
            } catch (ExecutionException unsuccessful) {
                checkFailure();
                throw incomplete();
            } catch (TimeoutException timedOut) {
                fail("Codex не завершил анализ за пять минут. Результат неизвестен; автоматического повтора нет.", state());
                throw failure.get();
            }
        }
    }
}
