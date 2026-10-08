# ADR-0002: Servlet Gateway with Virtual Threads

- **Status:** Accepted
- **Date:** 2026-09-10
- **Phase:** 1a Step 3

## Context

PayFlow needs a single public reverse proxy in front of `merchant-service`
(and later payment, ledger, webhook services). Spring Cloud Gateway 5.x ships
two runtimes:

| | WebFlux | WebMVC |
| --- | --- | --- |
| Artifact | `spring-cloud-starter-gateway-server-webflux` | `spring-cloud-starter-gateway-server-webmvc` |
| Server | Netty event loop | Tomcat (servlet) |
| Programming model | Reactive (`Mono`/`Flux`, Reactor Context) | Blocking (virtual threads) |
| Shared `common-web` | Cannot reuse servlet filters | Reuses `CorrelationIdFilter` |
| `/actuator/gateway/routes` | Present | Absent in this variant |

The original plan favoured WebFlux on the thread-pool argument: Tomcat's ~200
platform threads collapse under slow downstreams. That argument is from before
Java 21 virtual threads. PayFlow is already on Java 21 and Spring Boot 4.0.7.

## Decision

Use **Spring Cloud Gateway Server WebMVC** with:

```yaml
spring.threads.virtual.enabled: true
```

Routes are YAML under `spring.cloud.gateway.server.webmvc.routes`. Authentication
and rate limiting stay out of this step.

## Why not WebFlux now

1. The gateway has no domain model, no database, and three-to-four filters. A
   later migration is a 1–2 day module rewrite, not a 3–6 month service rewrite.
2. `common-web` is servlet-based. WebFlux would duplicate correlation handling
   in Reactor Context and make MDC a special case.
3. Steps 4 and 5 teach API-key verification and token-bucket rate limiting.
   Stacking a reactive programming model on top of that hides whether a bug is
   security logic or an accidental `.block()` on an event loop.
4. Published Gateway 5 / Boot 4 comparisons put WebFlux ~1.7× ahead of WebMVC
   *with* virtual threads, and only above roughly 500 concurrent connections.
   Local and early production traffic is nowhere near that.

WebFlux remains the right tool for SSE, WebSockets, and genuine backpressure.
None of those are in the PayFlow gateway's request/response path.

## Alternatives considered

**WebFlux / Netty (original recommendation).** Rejected for this step. Revisit
if we measure >500 concurrent connections per instance, add streaming proxying,
or need a WebFlux-only filter.

**Kong / Envoy at the edge.** What large payment companies actually run. Out of
scope for a Java learning platform; migrating *from* Spring Cloud Gateway to
Kong later is cheaper than starting there.

**WebMVC without virtual threads.** The one configuration that is genuinely
worse than both alternatives. Not acceptable.

## Consequences

**Positive**

- One mental model across `merchant-service` and `edge-gateway`.
- Correlation IDs work via ThreadLocal/MDC without a Reactor bridge.
- Debugger stack traces are ordinary servlet traces.

**Negative / accepted**

- Peak throughput is lower than WebFlux under high concurrency (~1.7× in one
  4-CPU benchmark after the Boot 4.0 RestClient virtual-thread fix).
- MVC lacks `/actuator/gateway/routes`. Routes live in `application.yml`.
- MVC documentation and filter catalogue are thinner. Rate limiting in Step 5
  uses Bucket4j (or a custom Redis Lua script), not the WebFlux
  `RequestRateLimiter`.
- Default-deny YAML routing is defense-in-depth only: it hides `/internal/**`
  from traffic that *goes through the gateway*. Production must also bind
  internal endpoints to a private network or port.

## Revisit if

1. A load test exceeds ~500 concurrent connections per gateway instance.
2. We proxy SSE or WebSockets.
3. We need a WebFlux-only filter that has no MVC equivalent.
4. We want the reactive model as a deliberate portfolio exercise, with a
   before/after benchmark.
