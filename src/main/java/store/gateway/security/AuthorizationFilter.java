package store.gateway.security;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class AuthorizationFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(AuthorizationFilter.class);

    public static final String AUTH_COOKIE_TOKEN = "__store_jwt_token";
    public static final String HEADER_ID_ACCOUNT = "id-account";
    public static final String HEADER_ID_ROLE = "id-role";

    // Roda DEPOIS dos default-filters (ordem 1..n; o RemoveRequestHeader apagaria
    // os headers que este filtro injeta) e ANTES do roteamento
    // (RouteToRequestUrlFilter = 10000, NettyRoutingFilter = Integer.MAX_VALUE).
    public static final int ORDER = 1000;

    private static final ParameterizedTypeReference<Map<String, String>> MAP_TYPE =
        new ParameterizedTypeReference<>() {};

    private final RouterValidator routerValidator;
    private final WebClient webClient;
    private final String solveUrl;

    public AuthorizationFilter(
        RouterValidator routerValidator,
        @Value("${store.gateway.auth.solve-url:http://auth:8080/auth/solve}") String solveUrl
    ) {
        this.routerValidator = routerValidator;
        this.solveUrl = solveUrl;
        // criado uma vez (o hands-on criava um WebClient a cada requisição)
        this.webClient = WebClient.builder().build();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        final ServerHttpRequest request = exchange.getRequest();

        if (!routerValidator.isSecured.test(request)) {
            logger.debug("rota aberta: {} {}", request.getMethod(), request.getURI().getPath());
            return chain.filter(exchange);
        }

        final HttpCookie cookie = request.getCookies().getFirst(AUTH_COOKIE_TOKEN);
        if (cookie == null || cookie.getValue().isBlank()) {
            logger.debug("rota protegida sem cookie: {} {}", request.getMethod(), request.getURI().getPath());
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing token"));
        }

        return solve(cookie.getValue().trim())
            .flatMap(solved -> chain.filter(authorize(exchange, solved)));
    }

    // POST /auth/solve {"token": jwt} -> {"idAccount": "...", "role": "..."}
    private Mono<Map<String, String>> solve(final String jwt) {
        return webClient.post()
            .uri(solveUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("token", jwt))
            .retrieve()
            // token inválido/expirado/adulterado: o cliente precisa logar de novo
            .onStatus(HttpStatusCode::is4xxClientError, response -> Mono.error(
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token")))
            // erro no auth: não é culpa do token, tente mais tarde
            .onStatus(HttpStatusCode::is5xxServerError, response -> Mono.error(
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Auth service unavailable")))
            .bodyToMono(MAP_TYPE)
            // auth fora do ar (conexão recusada, DNS, timeout): 503 em vez de 500
            .onErrorMap(WebClientRequestException.class, e ->
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Auth service unavailable", e))
            // 200 sem body ou sem idAccount: trata como token inválido
            .filter(map -> map.get("idAccount") != null && !map.get("idAccount").isBlank())
            .switchIfEmpty(Mono.error(
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token")));
    }

    // repassa a identidade resolvida a partir do token (nunca a enviada pelo cliente)
    private ServerWebExchange authorize(ServerWebExchange exchange, Map<String, String> solved) {
        final ServerHttpRequest.Builder builder = exchange.getRequest().mutate()
            .header(HEADER_ID_ACCOUNT, solved.get("idAccount"));

        final String role = solved.get("role");
        if (role != null && !role.isBlank()) {
            builder.header(HEADER_ID_ROLE, role);
        }

        logger.debug("autorizado: id-account={} id-role={}", solved.get("idAccount"), role);
        return exchange.mutate().request(builder.build()).build();
    }

}
