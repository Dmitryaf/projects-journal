package io.github.dmitryaf.projectsjournal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

@Component
final class AnalysisPrivacy {

    private static final Pattern SENSITIVE = Pattern.compile(
            "(?:[A-Za-z]:[\\\\/]|\\\\\\\\[^\\s\\\\]+\\\\|/(?:home|Users|etc|var|opt|tmp|mnt)/"
                    + "|(?:\\.local|\\.codex|\\.ai-rules)[\\\\/]|(?<![A-Za-z0-9_])(?:sk-|gh[pousr]_)[A-Za-z0-9_-]{15,}"
                    + "|BEGIN [A-Z ]*PRIVATE KEY|(?<![A-Za-z0-9_])(?:password|api[_ -]?key|token)\\s*[:=]\\s*\\S+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern LONE_SURROGATE = Pattern.compile("[\\uD800-\\uDFFF]");

    private final ObjectReader reader;
    private final ObjectWriter writer;

    AnalysisPrivacy(JsonMapper mapper) {
        reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        writer = mapper.writer().without(JsonWriteFeature.WRITE_HEX_UPPER_CASE);
    }

    String json(JsonNode value) {
        var encoded = writer.writeValueAsString(value);
        return LONE_SURROGATE.matcher(encoded).replaceAll(match ->
                Matcher.quoteReplacement("\\u%04x".formatted((int) match.group().charAt(0))));
    }

    String digest(JsonNode value) {
        return digestText(value.isString() ? value.stringValue() : json(value));
    }

    int bytes(JsonNode value) {
        return json(value).getBytes(StandardCharsets.UTF_8).length;
    }

    static String digestText(String value) {
        try {
            var text = LONE_SURROGATE.matcher(value).replaceAll("\uFFFD");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 недоступен", missing);
        }
    }

    static boolean sensitiveText(String text) {
        return SENSITIVE.matcher(text).find();
    }

    boolean containsSensitive(JsonNode value) {
        var pending = new ArrayDeque<JsonNode>();
        pending.push(value);
        while (!pending.isEmpty()) {
            var node = pending.pop();
            if (node.isString()) {
                var current = node.stringValue();
                var trimmed = TaskPayload.trim(current);
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    try {
                        var nested = reader.readTree(trimmed);
                        if (nested.isContainer()) {
                            pending.push(nested);
                            continue;
                        }
                    } catch (JacksonException invalid) {
                        // A source may contain ordinary prose starting with punctuation.
                    }
                }
                if (sensitiveText(current)) {
                    return true;
                }
            } else if (node.isObject()) {
                for (var property : node.properties()) {
                    if (sensitiveText(property.getKey())) {
                        return true;
                    }
                    pending.push(property.getValue());
                }
            } else if (node.isArray()) {
                node.forEach(pending::push);
            }
        }
        return false;
    }
}
