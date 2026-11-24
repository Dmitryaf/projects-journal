package io.github.dmitryaf.projectsjournal;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class TaskPayload {

    private static final String SPACE = "[\\p{Zs}\\t\\n\\x0B\\f\\r\\u2028\\u2029\\uFEFF]";
    private static final Pattern RUN_OF_SPACE = Pattern.compile(SPACE + "+");
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
                    + "|00000000-0000-0000-0000-000000000000|ffffffff-ffff-ffff-ffff-ffffffffffff");
    private static final Pattern UTC_DATE_TIME = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]+)?Z");
    private static final Set<String> PATCH_FIELDS = Set.of(
            "title", "projectId", "projectIds", "description", "expectedResult", "acceptance", "links",
            "result", "priority", "completedAt", "cancellation");
    private static final Set<String> PRIORITIES = Set.of("low", "normal", "high");

    private final JsonMapper mapper;

    TaskPayload(JsonMapper mapper) {
        this.mapper = mapper;
    }

    ObjectNode readData(JsonNode input) {
        if (input == null || !input.isObject()) {
            throw invalid("");
        }
        // The schema's field order is part of persisted request hashes.
        ObjectNode data = mapper.createObjectNode();
        data.put("title", string(input.get("title"), "title", 1, 240, null));
        data.put("projectId", uuid(input.get("projectId"), "projectId", true));
        if (input.has("projectIds")) {
            JsonNode ids = array(input.get("projectIds"), "projectIds", 30);
            ArrayNode normalized = data.putArray("projectIds");
            for (int index = 0; index < ids.size(); index++) {
                normalized.add(uuid(ids.get(index), "projectIds." + index, false));
            }
        }
        for (String field : new String[]{"description", "expectedResult", "acceptance"}) {
            data.put(field, string(input.get(field), field, 0, 10_000, ""));
        }
        ArrayNode links = data.putArray("links");
        if (input.has("links")) {
            JsonNode values = array(input.get("links"), "links", 50);
            for (int index = 0; index < values.size(); index++) {
                links.add(string(values.get(index), "links." + index, 1, 2_000, null));
            }
        }
        data.set("result", input.hasNonNull("result") ? parseResult(input.get("result")) : mapper.nullNode());
        JsonNode priority = input.get("priority");
        String priorityValue = priority == null ? "normal" : priority.asString(null);
        if (priorityValue == null || !PRIORITIES.contains(priorityValue)) {
            throw invalid("priority");
        }
        data.put("priority", priorityValue);
        data.put("completedAt", timestamp(input.get("completedAt"), "completedAt", true));
        data.set("cancellation", cancellation(input.get("cancellation")));
        return data;
    }

    static void checkWriteInput(JsonNode input) {
        if (input == null || !input.isObject()) {
            throw invalid("");
        }
        if (input.has("state")) {
            throw new TaskError(400, "Поле state больше не поддерживается. Используйте completedAt для завершения; "
                    + "отмена при закрытии проекта хранится отдельно в cancellation.");
        }
    }

    static void checkPatch(JsonNode input) {
        checkWriteInput(input);
        if (!PATCH_FIELDS.containsAll(input.propertyNames())) {
            throw invalid("change");
        }
        for (String field : new String[]{"completedAt", "cancellation"}) {
            if (input.hasNonNull(field)) {
                throw invalid("change." + field);
            }
        }
    }

    static String requestKey(JsonNode input) {
        return string(input.get("requestKey"), "requestKey", 1, 200, null, true);
    }

    static boolean allowDuplicate(JsonNode input) {
        JsonNode value = input.get("allowDuplicate");
        if (value == null) {
            return false;
        }
        if (!value.isBoolean()) {
            throw invalid("allowDuplicate");
        }
        return value.booleanValue();
    }

    static Long revision(JsonNode input) {
        JsonNode value = input.get("revision");
        if (value == null) {
            return null;
        }
        if (value.isNumber()) {
            try {
                long revision = value.decimalValue().longValueExact();
                if (revision > 0 && revision <= 9_007_199_254_740_991L) {
                    return revision;
                }
            } catch (ArithmeticException | NumberFormatException ignored) {
                // Revisions are positive safe integers in the existing JSON contract.
            }
        }
        throw invalid("revision");
    }

    static String normalizedTitle(String title) {
        return RUN_OF_SPACE.matcher(trim(title)).replaceAll(" ").toLowerCase(Locale.forLanguageTag("ru"));
    }

    static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isSpace(char character) {
        return switch (character) {
            case '\t', '\n', 0x0b, '\f', '\r', 0x2028, 0x2029, 0xfeff -> true;
            default -> Character.getType(character) == Character.SPACE_SEPARATOR;
        };
    }

    ObjectNode parseResult(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw invalid("result");
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("summary", string(value.get("summary"), "result.summary", 1, 10_000, null));
        for (String field : new String[]{"verified", "unverified", "remaining"}) {
            result.put(field, string(value.get(field), "result." + field, 0, 10_000, ""));
        }
        return result;
    }

    private JsonNode cancellation(JsonNode value) {
        if (value == null || value.isNull()) {
            return mapper.nullNode();
        }
        if (!value.isObject()) {
            throw invalid("cancellation");
        }
        return mapper.createObjectNode()
                .put("at", timestamp(value.get("at"), "cancellation.at", false))
                .put("projectEventId", uuid(value.get("projectEventId"), "cancellation.projectEventId", false));
    }

    static String uuid(JsonNode value, String field, boolean nullable) {
        if (nullable && (value == null || value.isNull())) {
            return null;
        }
        if (value != null && value.isString() && UUID.matcher(value.asString()).matches()) {
            return value.asString();
        }
        throw invalid(field);
    }

    private static String timestamp(JsonNode value, String field, boolean nullable) {
        if (nullable && (value == null || value.isNull())) {
            return null;
        }
        if (value != null && value.isString()) {
            String text = value.asString();
            if (UTC_DATE_TIME.matcher(text).matches()) {
                try {
                    LocalDate.parse(text.substring(0, 10));
                    return text;
                } catch (DateTimeParseException ignored) {
                    // Shape validation does not rule out impossible calendar dates.
                }
            }
        }
        throw invalid(field);
    }

    private static JsonNode array(JsonNode value, String field, int maximum) {
        if (!value.isArray() || value.size() > maximum) {
            throw invalid(field);
        }
        return value;
    }

    private static String string(JsonNode value, String field, int minimum, int maximum, String fallback) {
        return string(value, field, minimum, maximum, fallback, fallback != null);
    }

    private static String string(JsonNode value, String field, int minimum, int maximum,
                                 String fallback, boolean optional) {
        if (optional && value == null) {
            return fallback;
        }
        if (value != null && value.isString()) {
            String text = trim(value.asString());
            if (text.length() >= minimum && text.length() <= maximum) {
                return text;
            }
        }
        throw invalid(field);
    }

    private static TaskError invalid(String field) {
        return new TaskError(400, "Проверьте поля: " + field);
    }
}
