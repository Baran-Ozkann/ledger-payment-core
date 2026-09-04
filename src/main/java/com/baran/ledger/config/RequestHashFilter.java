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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
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
 */
@Component
public class RequestHashFilter extends OncePerRequestFilter {

    public static final String REQUEST_HASH = "ledger.requestHash";

    private final ObjectMapper json = JsonMapper.builder().build();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        CachedBodyRequest cached = new CachedBodyRequest(request);
        cached.setAttribute(REQUEST_HASH, hash(cached));
        chain.doFilter(cached, response);
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

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
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
