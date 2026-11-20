package io.groundedrag.security;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-caller token bucket on the endpoints that cost money (chat and MCP tool calls = LLM tokens,
 * upload = embedding calls). Keyed by the authenticated caller, never by IP: behind a load balancer
 * every user shares an IP, and one tenant must not exhaust another's budget.
 *
 * <p>In-process; with several replicas the effective limit is per replica. Move the buckets to
 * Redis (e.g. Bucket4j) for a global limit.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    static final class Bucket {
        private final double capacity;
        private final double refillPerNano;
        private double tokens;
        private long last;

        Bucket(double capacity, double refillPerNano, long now) {
            this.capacity = capacity;
            this.refillPerNano = refillPerNano;
            this.tokens = capacity;
            this.last = now;
        }

        /** Returns 0 if a token was taken, otherwise nanoseconds until one is available. */
        synchronized long tryTake(long now) {
            tokens = Math.min(capacity, tokens + (now - last) * refillPerNano);
            last = now;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return (long) Math.ceil((1 - tokens) / refillPerNano);
        }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int burst;
    private final double refillPerNano;
    private final LongSupplier clock;

    public RateLimitFilter(int requestsPerMinute, int burst) {
        this(requestsPerMinute, burst, System::nanoTime);
    }

    RateLimitFilter(int requestsPerMinute, int burst, LongSupplier clock) {
        this.burst = Math.max(1, burst);
        this.refillPerNano = requestsPerMinute / 60e9;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean chat = path.startsWith("/api/v1/chat");
        // MCP tool calls run retrieval and generation too
        boolean mcp = HttpMethod.POST.matches(request.getMethod()) && path.equals("/mcp");
        boolean upload = HttpMethod.POST.matches(request.getMethod()) && path.matches("/api/v1/spaces/[^/]+/documents");
        return !(chat || upload || mcp);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Caller caller)) {
            chain.doFilter(request, response); // unauthenticated: the authorization rules reject it
            return;
        }
        long now = clock.getAsLong();
        long waitNanos = buckets.computeIfAbsent(caller.name(), k -> new Bucket(burst, refillPerNano, now)).tryTake(now);
        if (waitNanos == 0) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = Math.max(1, (long) Math.ceil(waitNanos / 1e9));
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"detail\":\"Rate limit exceeded; retry after " + retryAfter + " s\"}");
    }
}
