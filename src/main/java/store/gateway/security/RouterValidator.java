package store.gateway.security;

import java.util.List;
import java.util.function.Predicate;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

@Component
public class RouterValidator {

    // rotas que NÃO exigem cookie: "MÉTODO /path" ou "MÉTODO /prefixo/**"
    private static final List<String> OPEN_API_ENDPOINTS = List.of(
        "POST /auth/register",
        "POST /auth/login",
        "GET /auth/logout",
        "GET /auth/health-check",
        "GET /accounts/health-check",
        "GET /products/**"          // vitrine pública (inclui /products/health-check)
    );

    // true = a rota precisa de autenticação
    public final Predicate<ServerHttpRequest> isSecured = request ->
        OPEN_API_ENDPOINTS.stream().noneMatch(endpoint -> matches(endpoint, request));

    private static boolean matches(String endpoint, ServerHttpRequest request) {
        // sem o replaceAll do hands-on: ele apagava o "-" e quebrava /health-check
        final String[] parts = endpoint.split(" ");
        final String method = parts[0];
        final String path = parts[1];

        if (!"ANY".equalsIgnoreCase(method) && !request.getMethod().name().equalsIgnoreCase(method)) {
            return false;
        }

        final String requestPath = request.getURI().getPath();
        if (path.endsWith("/**")) {
            // "/products/**" casa "/products" e "/products/...", mas não "/productsX"
            final String prefix = path.substring(0, path.length() - "/**".length());
            return requestPath.equals(prefix) || requestPath.startsWith(prefix + "/");
        }
        return requestPath.equals(path);
    }

}
