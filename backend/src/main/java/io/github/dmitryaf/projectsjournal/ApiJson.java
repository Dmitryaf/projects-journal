package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
final class ApiJson {

    private static final int MAX_BODY_BYTES = 256 * 1024;
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private final JsonMapper mapper;
    private final ObjectReader reader;

    ApiJson(JsonMapper mapper) {
        this.mapper = mapper;
        reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    JsonNode read(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw new TaskError(413, "Размер запроса превышает 256 КБ.");
        }
        var bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new TaskError(413, "Размер запроса превышает 256 КБ.");
        }
        if (bytes.length == 0) {
            return mapper.createObjectNode();
        }
        try {
            var parsed = reader.readTree(bytes);
            if (parsed.isObject() || parsed.isArray()) {
                return parsed;
            }
        } catch (JacksonException invalid) {
            // Keep parser diagnostics out of the HTTP response.
        }
        throw new TaskError(400, "Некорректный JSON.");
    }

    ObjectNode query(MultiValueMap<String, String> parameters, String unlinkedProject) {
        var query = mapper.createObjectNode();
        for (var parameter : parameters.entrySet()) {
            var name = parameter.getKey();
            var values = parameter.getValue();
            if (values.size() != 1) {
                throw invalid(name);
            }
            var value = values.getFirst();
            if (name.equals("projectId") && value.equals(unlinkedProject)) {
                query.putNull(name);
            } else {
                query.put(name, value);
            }
        }
        return query;
    }

    static BigDecimal numberText(String raw, String field) {
        var value = TaskPayload.trim(raw);
        if (value.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            if (value.startsWith("0") && value.length() > 2) {
                int radix = switch (Character.toLowerCase(value.charAt(1))) {
                    case 'b' -> 2;
                    case 'o' -> 8;
                    case 'x' -> 16;
                    default -> 0;
                };
                if (radix != 0 && Character.digit(value.charAt(2), radix) >= 0) {
                    return new BigDecimal(new BigInteger(value.substring(2), radix));
                }
            }
            return new BigDecimal(value);
        } catch (NumberFormatException invalid) {
            throw invalid(field);
        }
    }

    static void requireObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalid("");
        }
    }

    static String text(JsonNode value, String field, int min, int max, boolean trim) {
        if (value == null || !value.isString()) {
            throw invalid(field);
        }
        var text = trim ? TaskPayload.trim(value.stringValue()) : value.stringValue();
        if (text.length() < min || text.length() > max) {
            throw invalid(field);
        }
        return text;
    }

    static long integer(JsonNode value, String field, long min, long max) {
        if (value == null || !value.isNumber() || !value.canConvertToExactIntegral()
                || !value.canConvertToLong() || value.longValue() < min || value.longValue() > max) {
            throw invalid(field);
        }
        return value.longValue();
    }

    static long revision(JsonNode value, String field) {
        return integer(value, field, 0, MAX_SAFE_INTEGER);
    }

    static TaskError invalid(String field) {
        return new TaskError(400, "Проверьте поля: " + field);
    }
}
