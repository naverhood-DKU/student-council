package kr.dku.councilflow.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 requestId 를 정해 로그(MDC)와 응답 헤더(X-Request-Id)에 붙인다.
 * 보안 필터보다 먼저 돌아야 401/403 응답에도 requestId 가 들어간다.
 * API 요청은 끝날 때 방법·경로·상태·처리 시간만 한 줄 남긴다(본문은 남기지 않는다).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    private static final String MDC_KEY = "requestId";
    // LB 등이 붙여 준 ID는 안전한 글자로만 이루어져 있을 때만 그대로 쓴다(로그 위조 방지).
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    public static String currentRequestId() {
        return MDC.get(MDC_KEY);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            if (request.getRequestURI().startsWith("/api/")) {
                long tookMs = (System.nanoTime() - startedAt) / 1_000_000;
                log.info("{} {} status={} took={}ms",
                        request.getMethod(), request.getRequestURI(), response.getStatus(), tookMs);
            }
            MDC.remove(MDC_KEY);
        }
    }
}
