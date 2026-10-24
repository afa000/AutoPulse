package dev.autopulse.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CorrelationFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = "autopulse.correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID id = UUID.randomUUID();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader("X-Correlation-Id", id.toString());
        chain.doFilter(request, response);
    }
}
