package io.github.dmitryaf.controlcenter;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

@Component
final class Input {

    private static final int MAX_BODY = 256 * 1024;
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}|00000000-0000-0000-0000-000000000000|ffffffff-ffff-ffff-ffff-ffffffffffff");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern INSTANT = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]+)?Z");

    private final JsonMapper mapper;
    private final ObjectReader reader;

    Input(JsonMapper mapper) {
        this.mapper = mapper;
        reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    JsonNode read(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY) {
            throw new ApiFailure(413, "Размер запроса превышает 256 КБ.");
        }
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) {
            throw new ApiFailure(413, "Размер запроса превышает 256 КБ.");
        }
        if (bytes.length == 0) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode value = reader.readTree(bytes);
            if (value.isObject()) {
                return value;
            }
        } catch (JacksonException ignored) {
            // Keep parser details out of the response.
        }
        throw new ApiFailure(400, "Некорректный JSON.");
    }

    static String text(JsonNode node, String field, String fallback, int min, int max, boolean trim) {
        if (node == null && fallback != null) {
            return fallback;
        }
        if (node == null || !node.isTextual()) {
            throw invalid(field);
        }
        String value = node.stringValue();
        if (trim) {
            value = trim(value);
        }
        if (value.length() < min || value.length() > max) {
            throw invalid(field);
        }
        return value;
    }

    static String choice(JsonNode input, String field, String fallback, Set<String> allowed) {
        String choice = text(input.get(field), field, fallback, 1, 30, false);
        if (!allowed.contains(choice)) {
            throw invalid(field);
        }
        return choice;
    }

    static String uuid(JsonNode node, String field, boolean nullable) {
        if (nullable && (node == null || node.isNull())) {
            return null;
        }
        String id = text(node, field, null, 36, 36, false);
        if (!UUID.matcher(id).matches()) {
            throw invalid(field);
        }
        return id;
    }

    static String date(JsonNode input, String field, boolean timestamp) {
        JsonNode node = input.get(field);
        if (timestamp && (node == null || node.isNull())) {
            return null;
        }
        String value = text(node, field, null, 10, MAX_BODY, false);
        if (!(timestamp ? INSTANT : DATE).matcher(value).matches()) {
            throw invalid(field);
        }
        try {
            LocalDate.parse(value.substring(0, 10));
            return value;
        } catch (DateTimeParseException invalid) {
            throw invalid(field);
        }
    }

    static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isSpace(value.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isSpace(char character) {
        return switch (character) {
            case '\t', '\n', 0x0b, '\f', '\r', 0x2028, 0x2029, 0xfeff -> true;
            default -> Character.getType(character) == Character.SPACE_SEPARATOR;
        };
    }

    static ApiFailure invalid(String field) {
        return new ApiFailure(400, "Проверьте поля: " + field);
    }
}
