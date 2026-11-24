package io.github.dmitryaf.controlcenter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Component
class LocalRequestFilter extends OncePerRequestFilter {

    private final Set<String> hosts;
    private final Set<String> origins;
    private final JsonMapper mapper;

    LocalRequestFilter(@Value("${server.port}") int port, JsonMapper mapper) {
        hosts = Set.of("127.0.0.1:" + port, "localhost:" + port);
        origins = Set.of("http://127.0.0.1:" + port, "http://localhost:" + port);
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var host = request.getHeader("Host");
        if (host == null || !hosts.contains(host)) {
            reject(response, "Допустим только локальный адрес приложения.");
            return;
        }
        var origin = request.getHeader("Origin");
        if (origin != null && !origin.isEmpty() && !origins.contains(origin)) {
            reject(response, "Запрос с другого сайта запрещён.");
            return;
        }
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (request.getServletPath().startsWith("/api")) {
            response.setHeader("Cache-Control", "no-store");
            var method = request.getMethod();
            if (!method.equals("GET") && !method.equals("HEAD")
                    && !("1".equals(request.getHeader("X-Control-Center")) && isJson(request.getContentType()))) {
                reject(response, "Для изменения нужен локальный JSON-запрос.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            var type = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(type);
        } catch (InvalidMediaTypeException invalid) {
            return false;
        }
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(403);
        response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), Map.of("error", message));
    }
}
