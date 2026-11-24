package io.github.dmitryaf.projectsjournal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
final class OpenAiResponses {

    static final int MAX_INPUT_BYTES = 80_000;
    static final int MAX_OUTPUT_TOKENS = 3_000;
    static final int TIMEOUT_SECONDS = 60;
    static final int MAX_RESPONSE_BYTES = 160_000;
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/responses");
    private static final Duration TIMEOUT = Duration.ofSeconds(TIMEOUT_SECONDS);
    private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9_.:-]{1,100}");

    private final JsonMapper mapper;
    private final AnalysisPrivacy privacy;
    private final ObjectReader reader;
    private final Config config;
    private final Transport transport;

    @Autowired
    OpenAiResponses(JsonMapper mapper) {
        this(mapper, Config.environment(), OpenAiResponses::send);
    }

    OpenAiResponses(JsonMapper mapper, Config config, Transport transport) {
        this.mapper = mapper;
        this.privacy = new AnalysisPrivacy(mapper);
        this.reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.config = config;
        this.transport = transport;
    }

    record Config(String key, String model) {
        static Config environment() {
            return new Config(TaskPayload.trim(System.getenv().getOrDefault("OPENAI_API_KEY", "")),
                    TaskPayload.trim(System.getenv().getOrDefault("CONTROL_CENTER_OPENAI_MODEL", "")));
        }

        @Override
        public String toString() {
            return "OpenAI configuration";
        }
    }

    @FunctionalInterface
    interface Transport {
        TransportResponse send(HttpRequest request) throws IOException, InterruptedException;
    }

    record TransportResponse(int statusCode, byte[] body) {
    }

    record Result(JsonNode report, String rawOutput, JsonNode usage, String responseId) {
    }

    static final class Failure extends TaskError {
        private final String outcome;
        private final JsonNode usage;
        private final String responseId;

        Failure(String message, String outcome) {
            this(message, outcome, null, null);
        }

        Failure(String message, String outcome, JsonNode usage, String responseId) {
            super(502, message);
            this.outcome = outcome;
            this.usage = usage;
            this.responseId = responseId;
        }

        String outcome() {
            return outcome;
        }

        JsonNode usage() {
            return usage;
        }

        String responseId() {
            return responseId;
        }
    }

    ObjectNode status() {
        return mapper.createObjectNode().put("ready", ready()).put("model", model())
                .put("maxInputBytes", MAX_INPUT_BYTES).put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                .put("timeoutSeconds", TIMEOUT_SECONDS);
    }

    boolean ready() {
        return config.key() != null && !config.key().isEmpty() && model() != null;
    }

    String model() {
        var model = config.model();
        return model != null && MODEL_NAME.matcher(model).matches() ? model : null;
    }

    Result request(JsonNode payload, String instructions, String schemaName, JsonNode schema,
                   UnaryOperator<JsonNode> validator) {
        TransportResponse response;
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            var body = mapper.createObjectNode().put("model", config.model()).put("store", false)
                    .put("max_output_tokens", MAX_OUTPUT_TOKENS).put("instructions", instructions);
            body.putArray("input").addObject().put("role", "user").put("content", privacy.json(payload));
            body.putObject("text").putObject("format").put("type", "json_schema").put("name", schemaName)
                    .put("strict", true).set("schema", schema);
            var request = HttpRequest.newBuilder(ENDPOINT).timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + config.key()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(privacy.json(body), StandardCharsets.UTF_8)).build();
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            response = Objects.requireNonNull(transport.send(request));
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw missingResponse();
        } catch (UnreadableBody unreadable) {
            throw invalidResponse(null);
        } catch (IOException | RuntimeException unavailable) {
            throw missingResponse();
        }
        if (!ok(response.statusCode())) {
            var message = switch (response.statusCode()) {
                case 401, 403 -> "OpenAI отклонил доступ. Проверьте серверный API-ключ и права модели.";
                case 429 -> "OpenAI сообщил об ограничении запросов или баланса.";
                default -> "OpenAI не выполнил запрос.";
            };
            throw new Failure(message + " Автоматического повтора нет.", "failed");
        }
        if (response.body() == null || response.statusCode() == 204 || response.statusCode() == 205) {
            throw new Failure("OpenAI вернул пустой ответ.", "unknown");
        }
        Envelope received = null;
        try {
            if (response.body().length > MAX_RESPONSE_BYTES) {
                throw invalidResponse(null);
            }
            received = envelope(reader.readTree(new String(response.body(), StandardCharsets.UTF_8)));
            if (received.refused()) {
                throw new Failure("Модель отказалась анализировать эти данные. Предложения не сохранены.",
                        "failed", received.usage(), received.id());
            }
            if (!"completed".equals(received.status())) {
                throw new Failure("Ответ модели не завершён. Предложения не сохранены; автоматического повтора нет.",
                        "failed", received.usage(), received.id());
            }
            var report = reader.readTree(received.raw());
            if (report == null || report.isMissingNode()) {
                throw invalidResponse(received);
            }
            return new Result(Objects.requireNonNull(validator.apply(report)), received.raw(), received.usage(), received.id());
        } catch (Failure error) {
            throw error;
        } catch (RuntimeException malformed) {
            throw invalidResponse(received);
        }
    }

    private Envelope envelope(JsonNode body) {
        ApiJson.requireObject(body);
        var id = body.has("id") ? ApiJson.text(body.get("id"), "id", 0, 200, false) : null;
        var status = ApiJson.text(body.get("status"), "status", 0, Integer.MAX_VALUE, false);
        var output = body.path("output");
        if (!output.isArray()) {
            throw ApiJson.invalid("output");
        }
        JsonNode usage = null;
        if (body.hasNonNull("usage")) {
            var tokens = body.get("usage");
            ApiJson.requireObject(tokens);
            usage = mapper.createObjectNode()
                    .put("inputTokens", ApiJson.integer(tokens.get("input_tokens"), "input_tokens", 0, MAX_SAFE_INTEGER))
                    .put("outputTokens", ApiJson.integer(tokens.get("output_tokens"), "output_tokens", 0, MAX_SAFE_INTEGER));
        }
        boolean refused = false;
        var raw = new StringBuilder();
        for (var item : output) {
            ApiJson.requireObject(item);
            boolean message = "message".equals(ApiJson.text(item.get("type"), "type", 0, Integer.MAX_VALUE, false));
            if (!item.has("content")) {
                continue;
            }
            var content = item.get("content");
            if (!content.isArray()) {
                throw ApiJson.invalid("content");
            }
            for (var part : content) {
                ApiJson.requireObject(part);
                var type = ApiJson.text(part.get("type"), "type", 0, Integer.MAX_VALUE, false);
                var text = part.has("text") ? ApiJson.text(part.get("text"), "text", 0, Integer.MAX_VALUE, false) : "";
                if (message && "refusal".equals(type)) {
                    refused = true;
                }
                if (message && "output_text".equals(type)) {
                    raw.append(text);
                }
            }
        }
        return new Envelope(id, status, usage, refused, raw.toString());
    }

    private record Envelope(String id, String status, JsonNode usage, boolean refused, String raw) {
    }

    private static Failure invalidResponse(Envelope envelope) {
        return new Failure("OpenAI вернул ответ неподходящего формата. Предложения не сохранены; автоматического повтора нет.",
                envelope == null ? "unknown" : "failed", envelope == null ? null : envelope.usage(),
                envelope == null ? null : envelope.id());
    }

    private static Failure missingResponse() {
        return new Failure("Ответ OpenAI не получен. Запрос мог быть оплачен; автоматического повтора нет.", "unknown");
    }

    private static boolean ok(int status) {
        return status >= 200 && status < 300;
    }

    private static TransportResponse send(HttpRequest request) throws IOException, InterruptedException {
        var knownStatus = new AtomicInteger();
        var ongoing = ClientHolder.CLIENT.sendAsync(request, response -> {
            knownStatus.set(response.statusCode());
            return new BoundedBody(ok(response.statusCode()));
        });
        try {
            // Wait for the full bounded body under the same deadline as the headers.
            var response = ongoing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return transportResponse(response.statusCode(), response.body());
        } catch (InterruptedException cancelled) {
            ongoing.cancel(true);
            throw cancelled;
        } catch (TimeoutException | ExecutionException failure) {
            ongoing.cancel(true);
            var status = knownStatus.get();
            if (status != 0) {
                if (!ok(status)) {
                    return transportResponse(status, new byte[0]);
                }
                throw new UnreadableBody();
            }
            if (failure instanceof TimeoutException) {
                throw new HttpTimeoutException("Responses timeout");
            }
            throw new IOException("Responses delivery failed");
        }
    }

    private static TransportResponse transportResponse(int status, byte[] body) throws IOException {
        return switch (status) {
            case 301, 302, 303, 307, 308 -> throw new IOException("Redirect rejected");
            default -> new TransportResponse(status, body);
        };
    }

    private static final class ClientHolder {
        private static final HttpClient CLIENT = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static final class UnreadableBody extends IOException {
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final boolean read;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        BoundedBody(boolean read) {
            this.read = read;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (!read) {
                result.complete(new byte[0]);
                subscription.cancel();
                return;
            }
            this.subscription = Objects.requireNonNull(subscription);
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> data) {
            for (var buffer : data) {
                var remaining = buffer.remaining();
                if (remaining > MAX_RESPONSE_BYTES - output.size()) {
                    result.completeExceptionally(new UnreadableBody());
                    subscription.cancel();
                    return;
                }
                var bytes = new byte[remaining];
                buffer.get(bytes);
                output.writeBytes(bytes);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(new UnreadableBody());
        }

        @Override
        public void onComplete() {
            result.complete(output.toByteArray());
        }
    }
}
