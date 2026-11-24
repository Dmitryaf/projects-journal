package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;

@Configuration(proxyBeanMethods = false)
final class ClientResources implements WebMvcConfigurer {

    private final Path developmentDirectory;

    ClientResources(@Value("${CONTROL_CENTER_CLIENT_DIR:}") String configuredDirectory) {
        developmentDirectory = configuredDirectory.isBlank() ? null : Path.of(configuredDirectory).toAbsolutePath().normalize();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Resource location = developmentDirectory == null
                ? new ClassPathResource("static/") : new FileSystemResource(developmentDirectory + "/");
        registry.addResourceHandler("/**").addResourceLocations(location)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(false).addResolver(new ClientResolver(developmentDirectory));
    }

    private static final class ClientResolver extends PathResourceResolver {

        private final Path developmentDirectory;

        private ClientResolver(Path developmentDirectory) {
            this.developmentDirectory = developmentDirectory;
        }

        @Override
        protected Resource resolveResourceInternal(HttpServletRequest request, String requestPath,
                                                   List<? extends Resource> locations, ResourceResolverChain chain) {
            Resource resource = super.resolveResourceInternal(request, requestPath, locations, chain);
            if (resource != null || request == null || !spaRoute(requestPath, request.getMethod())) {
                return resource;
            }
            return super.resolveResourceInternal(request, "index.html", locations, chain);
        }

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource resource = super.getResource(resourcePath, location);
            if (resource == null || developmentDirectory == null) {
                return resource;
            }
            // Resolve links and Windows junctions before handing the file to Spring.
            Path canonical = resource.getFile().toPath().toRealPath();
            return canonical.startsWith(developmentDirectory.toRealPath()) ? new FileSystemResource(canonical) : null;
        }

        private static boolean spaRoute(String path, String method) {
            if (!(method.equals("GET") || method.equals("HEAD"))) {
                return false;
            }
            String route = path.startsWith("/") ? path.substring(1) : path;
            if (route.equals("api") || route.startsWith("api/") || route.equals("assets") || route.startsWith("assets/")) {
                return false;
            }
            String trimmed = route.endsWith("/") ? route.substring(0, route.length() - 1) : route;
            return trimmed.indexOf('.', trimmed.lastIndexOf('/') + 1) < 0;
        }
    }
}
