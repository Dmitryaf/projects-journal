package io.github.dmitryaf.projectsjournal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

public final class AnalysisStubApplication {

    public static void main(String[] args) {
        new SpringApplication(JournalApplication.class, StubConfiguration.class)
                .run("--server.address=127.0.0.1", "--server.port=" + System.getenv("PORT"));
    }

    @Configuration(proxyBeanMethods = false)
    static class StubConfiguration {
        @Bean
        StubTransport stubTransport(JsonMapper mapper) {
            return new StubTransport(mapper);
        }

        @Bean
        @Primary
        OpenAiResponses stubProvider(JsonMapper mapper, StubTransport transport) {
            return new OpenAiResponses(mapper, new OpenAiResponses.Config("fixture-only", "fixture-model"), transport);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "journal.test.codex", havingValue = "true")
    static class CodexConfiguration {
        @Bean
        @Primary
        CodexReaderTransport fixtureReader(JsonMapper mapper) {
            return new CodexReaderTransport(mapper, System.getenv("JOURNAL_TEST_CODEX_NODE"), fixturePrefix(),
                    fixtureEnvironment());
        }

        @Bean
        @Primary
        CodexExecutor fixtureExecutor(JsonMapper mapper, SqliteStore store) {
            return new CodexExecutor(mapper, store.path().getParent(), System.getenv("JOURNAL_TEST_CODEX_NODE"),
                    fixturePrefix(), fixtureEnvironment());
        }

        private static List<String> fixturePrefix() {
            return List.of(System.getenv("JOURNAL_TEST_CODEX_SCRIPT"), System.getenv("JOURNAL_TEST_CODEX_CONFIG"));
        }

        private static Map<String, String> fixtureEnvironment() {
            var home = Path.of(System.getenv("JOURNAL_TEST_CODEX_CONFIG")).getParent().resolve("fixture-personal-home");
            var result = new HashMap<>(System.getenv());
            result.put("CODEX_HOME", home.toString());
            result.put("HOME", home.toString());
            result.put("USERPROFILE", home.toString());
            result.put("TEST_PRIVATE_TOKEN", "fixture-value-not-forwarded");
            return result;
        }
    }

    @RestController
    static class StubStatus {
        private final StubTransport transport;

        StubStatus(StubTransport transport) {
            this.transport = transport;
        }

        @GetMapping("/api/test/openai/calls")
        Map<String, Integer> calls() {
            return Map.of("calls", transport.calls.get());
        }
    }

    @RestController
    @ConditionalOnProperty(name = "journal.test.schedules", havingValue = "true")
    static class ScheduleTick {
        private final AnalysisScheduler scheduler;

        ScheduleTick(AnalysisScheduler scheduler) {
            this.scheduler = scheduler;
        }

        @PostMapping("/api/test/schedules/tick")
        Map<String, Boolean> tick() {
            scheduler.tick();
            return Map.of("ok", true);
        }
    }

    private static final class StubTransport implements OpenAiResponses.Transport {
        private final JsonMapper mapper;
        private final AtomicInteger calls = new AtomicInteger();

        StubTransport(JsonMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public OpenAiResponses.TransportResponse send(HttpRequest request) throws IOException, InterruptedException {
            int call = calls.incrementAndGet();
            var body = mapper.readTree(body(request));
            var format = body.path("text").path("format");
            if (!request.uri().equals(URI.create("https://api.openai.com/v1/responses")) || !request.method().equals("POST")
                    || !request.headers().firstValue("Authorization").orElse("").equals("Bearer fixture-only")
                    || !body.path("model").asString().equals("fixture-model") || !body.path("store").isBoolean()
                    || body.path("store").booleanValue() || body.has("tools") || !format.path("strict").booleanValue()
                    || !format.path("schema").isObject()) {
                throw new AssertionError("Unexpected OpenAI Responses request");
            }
            var payload = body.path("input").path(0).path("content").stringValue();
            if (payload.contains("stub-disconnect")) {
                throw new IOException("Simulated connection failure");
            }
            if (payload.contains("stub-hold")) {
                Thread.sleep(15_000);
            }
            var root = mapper.createObjectNode().put("id", "fixture-response-" + call).put("status", "completed");
            root.putObject("usage").put("input_tokens", 42).put("output_tokens", 7);
            var content = root.putArray("output").addObject().put("type", "message").putArray("content");
            if (payload.contains("stub-refusal")) {
                content.addObject().put("type", "refusal").put("refusal", "Synthetic refusal");
            } else {
                var report = mapper.createObjectNode();
                switch (format.path("name").asString()) {
                    case "period_review" -> {
                        report.put("narrative", "Synthetic period report");
                        var findings = report.putArray("findings");
                        if (payload.contains("stub-foreign-source")) {
                            findings.addObject().put("statement", "Unapproved source").putArray("sources").add("missing-source");
                        }
                        report.putArray("uncertainties");
                    }
                    case "project_analysis" -> report.put("summary", "Synthetic legacy report");
                    default -> throw new AssertionError("Unexpected response schema");
                }
                var proposals = report.putArray("proposals");
                if (payload.contains("<b>Наблюдение как текст</b>")) {
                    var packet = mapper.readTree(payload);
                    var reviewSource = "";
                    for (var source : packet.path("sources")) {
                        if (source.path("kind").asString().equals("review_text")) {
                            reviewSource = source.path("id").asString();
                            break;
                        }
                    }
                    if (reviewSource.isBlank()) {
                        throw new AssertionError("Missing review source");
                    }
                    report.put("narrative", "Нужно проверить гипотезу.");
                    for (var title : List.of("Исходное AI предложение", "Вторая AI идея")) {
                        var proposal = proposals.addObject().put("kind", "idea").put("title", title)
                                .put("description", "Проверить гипотезу").put("reason", "Есть наблюдение")
                                .put("uncertainty", "Нет независимого свидетельства").put("cheapCheck", "Один пример");
                        proposal.putArray("projectIds");
                        proposal.putArray("sources").add(reviewSource);
                    }
                }
                content.addObject().put("type", "output_text").put("text", mapper.writeValueAsString(report));
            }
            return new OpenAiResponses.TransportResponse(200, mapper.writeValueAsBytes(root));
        }

        private static byte[] body(HttpRequest request) throws IOException, InterruptedException {
            var bytes = new ByteArrayOutputStream();
            var received = new CompletableFuture<byte[]>();
            request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(ByteBuffer chunk) {
                    var data = new byte[chunk.remaining()];
                    chunk.get(data);
                    bytes.writeBytes(data);
                }

                @Override
                public void onError(Throwable failure) {
                    received.completeExceptionally(failure);
                }

                @Override
                public void onComplete() {
                    received.complete(bytes.toByteArray());
                }
            });
            try {
                return received.get();
            } catch (ExecutionException failure) {
                throw new IOException(failure.getCause());
            }
        }
    }
}
