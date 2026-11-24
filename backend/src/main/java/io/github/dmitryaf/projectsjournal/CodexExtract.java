package io.github.dmitryaf.projectsjournal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class CodexExtract {

    private static final String GAP = " [… сокращено …] ";
    private static final Pattern ACKNOWLEDGEMENT = Pattern.compile("^(коммить|пуш|продолжай|согласен|давай дальше)[.!]?$");
    private static final Pattern INJECTED_CONTEXT = Pattern.compile("<environment_context>|# agents\\.md|^<in-app-browser-context>");
    private static final String FORMAT = "Формат ответа Codex не поддерживается. Диалоги не подключены; остальные источники доступны.";
    static final String SUMMARY = "Автоматическая выборка: два последних содержательных сообщения пользователя и ответ на последнее "
            + "из них, если он завершён. Порядок хронологический; поздняя поправка может отменять раннее предложение. "
            + "Это выдержки, не смысловой итог: выполнение работы и окончательность решений не подтверждены. "
            + "Выборка неполная, без команд и вывода инструментов.";

    private CodexExtract() {
    }

    record ThreadRow(String id, String cwd, String name, long updatedAt) {
        ObjectNode dto(JsonMapper mapper) {
            String title = name == null ? "" : TaskPayload.trim(name);
            return mapper.createObjectNode().put("id", id).put("name", title.isEmpty() ? "Диалог " + id : title)
                    .put("updatedAt", updatedAt);
        }
    }

    record Page(JsonNode data, String cursor) {
    }

    record Capture(ArrayNode excerpts, String sourceHash, boolean partial, int undated, int omitted) {
    }

    @FunctionalInterface
    interface Reader {
        JsonNode request(String method, JsonNode parameters);
    }

    static Page page(JsonNode input, int limit) {
        requireObject(input);
        var data = input.path("data");
        if (!data.isArray() || data.size() > limit) {
            throw unsupported();
        }
        return new Page(data, nullableString(input.get("nextCursor"), 4096));
    }

    static ThreadRow thread(JsonNode value) {
        requireObject(value);
        JsonNode at = value.get("updatedAt");
        if (at == null || !at.isNumber() || !at.canConvertToExactIntegral() || !at.canConvertToLong()) {
            throw unsupported();
        }
        return new ThreadRow(string(value.get("id"), 1, 100), string(value.get("cwd"), 0, 4000),
                nullableString(value.get("name"), 1000), at.longValue());
    }

    static Capture read(JsonMapper mapper, AnalysisPrivacy privacy, Reader rpc, ThreadRow thread,
                       JsonNode review) {
        var sample = new Sample(mapper, rpc, thread.id());
        String cursor = null;
        for (int pageNumber = 0; pageNumber < 5; pageNumber++) {
            var turnPage = page(rpc.request("thread/turns/list", mapper.createObjectNode().put("threadId", thread.id())
                    .put("limit", 10).put("cursor", cursor).put("sortDirection", "desc").put("itemsView", "notLoaded")), 10);
            for (var value : turnPage.data()) {
                sample.readTurn(value, review);
                if (sample.itemRequests > 8 || sample.excerpts.size() == 40) {
                    break;
                }
            }
            cursor = turnPage.cursor();
            if (sample.partial || sample.excerpts.size() == 40 || cursor == null || cursor.isEmpty()) {
                break;
            }
            if (pageNumber == 4) {
                sample.partial = true;
            }
        }
        return new Capture(sample.excerpts, privacy.digest(sample.fingerprints), sample.partial, sample.undated, sample.omitted);
    }

    static List<ObjectNode> select(JsonNode preview) {
        var ordered = new ArrayList<ObjectNode>();
        preview.path("excerpts").forEach(item -> ordered.add(item.asObject()));
        ordered.sort(Comparator.comparing((ObjectNode item) -> item.path("at").asString()).reversed());
        var seen = new HashSet<String>();
        var candidates = ordered.stream().filter(item -> {
            String normalized = TaskPayload.normalizedTitle(item.path("text").asString());
            return !normalized.isEmpty() && !ACKNOWLEDGEMENT.matcher(normalized).matches()
                    && !INJECTED_CONTEXT.matcher(normalized).find() && seen.add(normalized);
        }).toList();
        var user = candidates.stream().filter(item -> "user".equals(item.path("role").asString())).limit(2).toList();
        if (user.isEmpty()) {
            return List.of();
        }
        var selected = new ArrayList<>(user);
        String newestTurn = user.getFirst().path("turnId").asString();
        candidates.reversed().stream().filter(item -> "assistant".equals(item.path("role").asString())
                && "completed".equals(item.path("turnStatus").asString()) && newestTurn.equals(item.path("turnId").asString()))
                .findFirst().ifPresent(selected::add);
        selected.sort(Comparator.comparing((ObjectNode item) -> item.path("at").asString())
                .thenComparing(item -> "assistant".equals(item.path("role").asString())));
        return selected.stream().map(item -> {
            String original = item.path("text").asString();
            String text = clip(original, 500);
            return item.deepCopy().put("text", text).put("partial", item.path("partial").booleanValue() || !text.equals(original));
        }).toList();
    }

    static String clip(String text, int maximum) {
        if (utf8(text) <= maximum) {
            return text;
        }
        int half = (maximum - utf8(GAP)) / 2;
        int start = 0;
        int end = text.length();
        int size = 0;
        while (start < text.length()) {
            int point = text.codePointAt(start);
            size += utf8(point);
            if (size > half) {
                break;
            }
            start += Character.charCount(point);
        }
        size = 0;
        while (end > start) {
            int point = text.codePointBefore(end);
            size += utf8(point);
            if (size > half) {
                break;
            }
            end -= Character.charCount(point);
        }
        return text.substring(0, start) + GAP + text.substring(end);
    }

    private static int utf8(String value) {
        return value.codePoints().map(CodexExtract::utf8).sum();
    }

    private static int utf8(int codePoint) {
        return codePoint < 128 ? 1 : codePoint < 2048 ? 2 : codePoint < 65536 ? 3 : 4;
    }

    private static void requireObject(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw unsupported();
        }
    }

    private static String nullableString(JsonNode value, int maximum) {
        return value == null || value.isNull() ? null : string(value, 0, maximum);
    }

    private static String string(JsonNode value, int minimum, int maximum) {
        if (value != null && value.isString()) {
            String text = value.stringValue();
            if (text.length() >= minimum && text.length() <= maximum) {
                return text;
            }
        }
        throw unsupported();
    }

    private static TaskError unsupported() {
        return new TaskError(503, FORMAT);
    }

    private static final class Sample {
        private final JsonMapper mapper;
        private final Reader rpc;
        private final ArrayNode excerpts;
        private final ArrayNode fingerprints;
        private final String thread;
        private final HashSet<String> turns = new HashSet<>();
        private final HashSet<String> items = new HashSet<>();
        private int itemRequests;
        private int undated;
        private int omitted;
        private boolean partial;

        Sample(JsonMapper mapper, Reader rpc, String thread) {
            this.mapper = mapper;
            this.rpc = rpc;
            this.thread = thread;
            this.excerpts = mapper.createArrayNode();
            this.fingerprints = mapper.createArrayNode();
        }

        void readTurn(JsonNode value, JsonNode review) {
            requireObject(value);
            String turn = string(value.get("id"), 0, 100);
            String status = string(value.get("status"), 0, 40);
            if (!turns.add(turn)) {
                throw new TaskError(503, "Codex повторил страницу истории. Чтение остановлено.");
            }
            JsonNode began = value.get("startedAt");
            if (began == null || began.isNull()) {
                undated++;
                return;
            }
            if (!began.isNumber() || !Double.isFinite(began.doubleValue()) || Math.abs(began.doubleValue()) > 8.64e12) {
                throw unsupported();
            }
            String at = JournalPeriods.utc(Instant.ofEpochMilli((long) (began.doubleValue() * 1000)));
            if (at.compareTo(review.path("startAt").asString()) < 0 || at.compareTo(review.path("nextStartAt").asString()) >= 0) {
                return;
            }
            if (++itemRequests > 8) {
                partial = true;
                return;
            }
            var page = page(rpc.request("thread/items/list", mapper.createObjectNode().put("threadId", thread)
                    .put("turnId", turn).put("limit", 50).put("sortDirection", "asc")), 50);
            if (page.cursor() != null && !page.cursor().isEmpty()) {
                partial = true;
            }
            for (JsonNode entry : page.data()) {
                if (!readItem(entry, turn, status, at)) {
                    break;
                }
            }
        }

        private boolean readItem(JsonNode entry, String turn, String status, String at) {
            requireObject(entry);
            if (!turn.equals(string(entry.get("turnId"), 0, 100))) {
                throw new TaskError(503, "Ответ Codex не соответствует выбранному turn.");
            }
            var timestamp = entry.get("startedAtMs");
            if (timestamp != null && !timestamp.isNull() && !timestamp.isNumber()) {
                throw unsupported();
            }
            var item = entry.get("item");
            requireObject(item);
            String role;
            String text;
            switch (item.path("type").asString("")) {
                case "userMessage" -> {
                    role = "user";
                    text = userText(item.path("content"));
                }
                case "agentMessage" -> {
                    if ("commentary".equals(item.path("phase").asString(""))) {
                        omitted++;
                        return true;
                    }
                    role = "assistant";
                    text = string(item.get("text"), 0, Integer.MAX_VALUE);
                }
                default -> {
                    omitted++;
                    return true;
                }
            }
            if (TaskPayload.trim(text).isEmpty()) {
                return true;
            }
            if (excerpts.size() == 40) {
                partial = true;
                return false;
            }
            String id = string(item.get("id"), 1, 100);
            String composite = turn + ":" + id;
            if (!items.add(composite)) {
                throw new TaskError(503, "Codex повторил элемент истории.");
            }
            fingerprints.addArray().add(turn).add(id).add(role).add(status).add(at).add(AnalysisPrivacy.digestText(text));
            String visible = clip(text, 1000);
            excerpts.addObject().put("id", composite).put("turnId", turn).put("itemId", id).put("role", role)
                    .put("timeBasis", "turn_start").put("turnStatus", status).put("at", at).put("text", visible)
                    .put("partial", !visible.equals(text));
            return true;
        }

        private String userText(JsonNode content) {
            if (!content.isArray()) {
                throw unsupported();
            }
            var text = new ArrayList<String>();
            for (JsonNode part : content) {
                requireObject(part);
                if (Objects.equals("text", part.path("type").asString(null))) {
                    text.add(string(part.get("text"), 0, Integer.MAX_VALUE));
                } else {
                    omitted++;
                }
            }
            return String.join("\n", text);
        }
    }
}
