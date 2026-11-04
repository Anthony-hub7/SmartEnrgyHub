package com.smartenergy.collector.web;

import com.smartenergy.collector.config.MeasurementProperties;
import com.smartenergy.contracts.common.ApiPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limite la taille du corps de POST /api/measurements (CDC section 5.2 : 10 Ko max).
 * Content-Length present : rejet immediat. Corps chunked (sans Content-Length) : le corps est
 * lu au plus jusqu'a la limite + 1 octet puis rejoue vers la chaine ; au-dela, 413.
 */
public class BodySizeLimitFilter extends OncePerRequestFilter {

    private static final String PROBLEM_TYPE = "https://smartenergy.local/problems/payload-too-large";

    private final long maxBodyBytes;

    public BodySizeLimitFilter(MeasurementProperties props) {
        this.maxBodyBytes = props.maxBodyBytes();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ApiPaths.MEASUREMENTS.equals(request.getRequestURI())
                || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long contentLength = req.getContentLengthLong();
        if (contentLength > maxBodyBytes) {
            writeProblem(res);
            return;
        }
        HttpServletRequest request = req;
        if (contentLength < 0) {
            // Corps chunked : on ne connait pas la taille a l'avance.
            try {
                request = readAndWrap(req);
            } catch (PayloadTooLargeException e) {
                writeProblem(res);
                return;
            }
        }
        chain.doFilter(request, res);
    }

    /** Lit au plus maxBodyBytes+1 octets puis rejoue le corps vers la suite de la chaine. */
    private HttpServletRequest readAndWrap(HttpServletRequest req) throws IOException, PayloadTooLargeException {
        int limit = Math.toIntExact(maxBodyBytes) + 1;
        byte[] body = req.getInputStream().readNBytes(limit);
        if (body.length > maxBodyBytes) {
            throw new PayloadTooLargeException();
        }
        return new HttpServletRequestWrapper(req) {
            @Override
            public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    private final ByteArrayInputStream in = new ByteArrayInputStream(body);

                    @Override
                    public int read() {
                        return in.read();
                    }

                    @Override
                    public boolean isFinished() {
                        return in.available() == 0;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        throw new UnsupportedOperationException("non implante");
                    }
                };
            }

            @Override
            public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }

            @Override
            public int getContentLength() {
                return body.length;
            }

            @Override
            public long getContentLengthLong() {
                return body.length;
            }
        };
    }

    private void writeProblem(HttpServletResponse res) throws IOException {
        String detail = "corps superieur a " + maxBodyBytes + " octets";
        String problem = "{\"type\":\"" + PROBLEM_TYPE
                + "\",\"title\":\"Corps de requete trop volumineux\",\"status\":413"
                + ",\"detail\":\"" + detail + "\",\"code\":\"PAYLOAD_TOO_LARGE\"}";
        res.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(problem);
    }

    private static final class PayloadTooLargeException extends Exception {
        PayloadTooLargeException() {
            super("corps trop volumineux");
        }
    }
}
