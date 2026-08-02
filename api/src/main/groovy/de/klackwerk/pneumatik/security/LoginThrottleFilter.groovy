package de.klackwerk.pneumatik.security

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Backs off repeated failed logins.
 *
 * The login endpoint is necessarily public, so without this an attacker can
 * try passwords as fast as the network allows. Failures are counted per
 * client address and per username: the address stops one host walking a
 * dictionary, the username stops a distributed attempt against one account.
 * A successful login clears both counters.
 *
 * State is in memory, which is the right scope for a single-container
 * deployment — a restart forgives outstanding lockouts, and that is a better
 * trade than a shared store for a tool that runs as one process.
 */
@Slf4j
@CompileStatic
class LoginThrottleFilter implements Filter {

    /** bodies larger than this are not parsed for a username — only the address is counted */
    private static final int MAX_PARSED_BODY_BYTES = 8 * 1024
    /** prune bookkeeping once the map grows past this many keys */
    private static final int PRUNE_THRESHOLD = 10_000

    int maxAttempts = 5
    long windowMinutes = 15
    long lockMinutes = 15
    /**
     * Whether X-Forwarded-For may name the client. Only enable behind a
     * proxy that overwrites the header — otherwise a client picks its own
     * throttling identity by sending one.
     */
    boolean trustForwardedFor = false

    private final ConcurrentHashMap<String, Attempts> attempts = new ConcurrentHashMap<>()

    @CompileStatic
    private static class Attempts {
        int failures
        long windowStart
        long lockedUntil
    }

    @Override
    void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        HttpServletRequest request = req as HttpServletRequest
        HttpServletResponse response = res as HttpServletResponse

        if (!'POST'.equalsIgnoreCase(request.method)) {
            chain.doFilter(req, res)
            return
        }

        // the credentials are JSON in the body, which the authentication
        // filter downstream still needs — so it is buffered, not consumed
        boolean parseable = request.contentLength in 1..MAX_PARSED_BODY_BYTES
        HttpServletRequest forwarded = parseable ? new CachedBodyRequest(request) : request
        String username = parseable ? readUsername(forwarded as CachedBodyRequest) : null

        String addressKey = 'address:' + clientAddress(request)
        String userKey = username ? 'user:' + username.toLowerCase() : null

        long now = System.currentTimeMillis()
        long lockedFor = Math.max(remainingLock(addressKey, now), userKey ? remainingLock(userKey, now) : 0L)
        if (lockedFor > 0) {
            rejectThrottled(response, lockedFor)
            return
        }

        chain.doFilter(forwarded, response)

        if (response.status == HttpServletResponse.SC_UNAUTHORIZED) {
            recordFailure(addressKey, now)
            if (userKey) {
                recordFailure(userKey, now)
            }
            log.warn "LOGINTHROTTLE - Failed login for '${username ?: 'unknown'}' from ${clientAddress(request)}"
        } else if (response.status < 400) {
            attempts.remove(addressKey)
            if (userKey) {
                attempts.remove(userKey)
            }
        }
    }

    /** Milliseconds left on a lock, or 0 when the key is not locked. */
    private long remainingLock(String key, long now) {
        Attempts current = attempts.get(key)
        if (current == null) {
            return 0L
        }
        return current.lockedUntil > now ? current.lockedUntil - now : 0L
    }

    private void recordFailure(String key, long now) {
        long window = TimeUnit.MINUTES.toMillis(windowMinutes)
        attempts.compute(key) { String ignored, Attempts existing ->
            Attempts current = existing ?: new Attempts(windowStart: now)
            if (now - current.windowStart > window) {
                current.windowStart = now
                current.failures = 0
            }
            current.failures++
            if (current.failures >= maxAttempts) {
                current.lockedUntil = now + TimeUnit.MINUTES.toMillis(lockMinutes)
                current.failures = 0
                current.windowStart = now
            }
            return current
        }
        if (attempts.size() > PRUNE_THRESHOLD) {
            prune(now, window)
        }
    }

    /** Drops keys that are neither locked nor inside their failure window. */
    private void prune(long now, long window) {
        attempts.entrySet().removeIf { Map.Entry<String, Attempts> entry ->
            Attempts current = entry.value
            return current.lockedUntil < now && now - current.windowStart > window
        }
    }

    private void rejectThrottled(HttpServletResponse response, long lockedForMillis) {
        long retryAfterSeconds = Math.max(1L, TimeUnit.MILLISECONDS.toSeconds(lockedForMillis))
        response.status = 429
        response.setHeader('Retry-After', retryAfterSeconds as String)
        response.contentType = 'application/json;charset=UTF-8'
        response.writer.write(JsonOutput.toJson([
                error: [
                        code   : 'too_many_requests',
                        message: "Too many failed sign-in attempts. Try again in ${retryAfterSeconds} seconds.",
                ],
        ]))
    }

    private String clientAddress(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwarded = request.getHeader('X-Forwarded-For')
            if (forwarded) {
                return forwarded.split(',')[0].trim()
            }
        }
        return request.remoteAddr ?: 'unknown'
    }

    private static String readUsername(CachedBodyRequest request) {
        try {
            Object parsed = new JsonSlurper().parse(request.body, StandardCharsets.UTF_8.name())
            if (parsed instanceof Map) {
                Object username = (parsed as Map).get('username')
                return username ? username.toString() : null
            }
        } catch (Exception ignored) {
            // unparseable body — the address counter still applies
        }
        return null
    }

    /**
     * Buffers the request body so it can be read for the username here and
     * again by the authentication filter downstream.
     */
    @CompileStatic
    private static class CachedBodyRequest extends HttpServletRequestWrapper {

        final byte[] body

        CachedBodyRequest(HttpServletRequest request) {
            super(request)
            this.body = request.inputStream.bytes
        }

        @Override
        ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body)
            return new ServletInputStream() {
                @Override
                int read() { return source.read() }

                @Override
                boolean isFinished() { return source.available() == 0 }

                @Override
                boolean isReady() { return true }

                @Override
                void setReadListener(ReadListener listener) { }
            }
        }

        @Override
        BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(inputStream, characterEncoding ?: 'UTF-8'))
        }
    }
}
