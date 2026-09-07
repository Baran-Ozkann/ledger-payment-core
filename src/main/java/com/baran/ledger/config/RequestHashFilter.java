package com.baran.ledger.config;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.baran.ledger.domain.LedgerError;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Computes the idempotency request hash and hands it to the controller as a request attribute.
 *
 * <p>It has to happen here: the hash covers the body as it arrived, and by the time a controller
 * method runs Jackson has already consumed the stream. The body is read once, hashed, and replayed
 * to the rest of the chain, so binding and the 400 on a malformed body behave exactly as before.
 *
 * <p>The method and the path are hashed with the body. Without them one key would collide across
 * two different endpoints and a transfer could be answered with a funding's stored response.
 *
 * <p>The read is bounded. Hashing needs the whole body in memory, and an unbounded read of an
 * unauthenticated request is a way to spend the heap from outside: a single 400 MB POST against a
 * 256 MB heap produced an OutOfMemoryError before this limit existed. The cap is far above any
 * real request here - the largest this API accepts is a few hundred bytes - so nothing legitimate
 * meets it.
 */
@Component
public class RequestHashFilter extends OncePerRequestFilter {

    public static final String REQUEST_HASH = "ledger.requestHash";

    /** Three orders of magnitude above the largest real request, and still bounded. */
    public static final int MAX_BODY_BYTES = 64 * 1024;

    private final ObjectMapper json = JsonMapper.builder().build();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        CachedBodyRequest cached;
        try {
            cached = new CachedBodyRequest(request);
        } catch (BodyTooLarge tooLarge) {
            reject(response);
            return;
        }
        cached.setAttribute(REQUEST_HASH, hash(cached));
        chain.doFilter(cached, response);
    }

    /**
     * Written here rather than raised for the controller advice, because a filter sits outside the
     * dispatcher and no advice will ever see what it throws. The shape is the same RFC 7807 body
     * the rest of the API answers with, and the code comes from the same enum, so a client matches
     * on one field whichever layer refused it.
     */
    private void reject(HttpServletResponse response) throws IOException {
        LedgerError error = LedgerError.REQUEST_TOO_LARGE;
        response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.writeValueAsString(Map.of(
                "type", "urn:ledger:" + error.code(),
                "title", error.title(),
                "status", HttpStatus.CONTENT_TOO_LARGE.value())));
    }

    private String hash(CachedBodyRequest request) {
        String material = request.getMethod() + '\n' + request.getRequestURI() + '\n' + canonicalize(request.body);
        return HexFormat.of().formatHex(sha256().digest(material.getBytes(StandardCharsets.UTF_8)));
    }

    private String canonicalize(byte[] body) {
        try {
            return json.writeValueAsString(sorted(json.readValue(body, Object.class)));
        } catch (JacksonException notJson) {
            // A body that does not parse is about to be rejected as a bad request anyway. It still
            // needs a stable hash, and its own bytes are as canonical as it gets.
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /**
     * Object keys are sorted so that the same request written in a different order hashes the same.
     * Array order is left alone: in an array the order is part of what was asked for.
     */
    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> object) {
            Map<String, Object> canonical = new TreeMap<>();
            object.forEach((key, member) -> canonical.put(String.valueOf(key), sorted(member)));
            return canonical;
        }
        if (value instanceof List<?> array) {
            return array.stream().map(RequestHashFilter::sorted).toList();
        }
        return value;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every JVM", impossible);
        }
    }

    /** Not a LedgerException: nothing downstream of a filter can translate one. */
    private static final class BodyTooLarge extends IOException {
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            // One byte past the limit is enough to know it was exceeded, and is all that is ever
            // held: readNBytes stops there rather than following the stream wherever it goes.
            byte[] read = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (read.length > MAX_BODY_BYTES) {
                throw new BodyTooLarge();
            }
            this.body = read;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream buffer = new ByteArrayInputStream(body);
            return new ServletInputStream() {

                @Override
                public int read() {
                    return buffer.read();
                }

                @Override
                public boolean isFinished() {
                    return buffer.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("The body is already buffered");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
