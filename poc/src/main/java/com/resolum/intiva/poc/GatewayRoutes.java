package com.resolum.intiva.poc;

import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.function.*;

@Configuration
@Profile("gateway")
class GatewayRoutes {
    @Bean
    RouterFunction<ServerResponse> routes(@Value("${intiva.agent}") String agent,
            @Value("${intiva.communications}") String communications, @Value("${intiva.legacy}") String legacy) {
        return route("agent").route(RequestPredicates.path("/api/v1/monthly-plans")
                .or(RequestPredicates.path("/api/v1/monthly-plans/**"))
                .or(RequestPredicates.path("/api/v1/category-suggestions"))
                .or(RequestPredicates.path("/api/v1/planning-goals"))
                .or(RequestPredicates.path("/api/v1/financial-insights")), http(agent)).build()
            .and(route("plan-notifications").route(RequestPredicates.path("/api/v1/plan-notifications"),
                    http(communications)).build())
            .and(route("legacy").route(RequestPredicates.path("/api/v1/**"), http(legacy)).build())
            .and(RouterFunctions.route(RequestPredicates.GET("/health"), req -> ServerResponse.ok().body("ok")));
    }
}
