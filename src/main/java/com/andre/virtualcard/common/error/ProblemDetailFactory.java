package com.andre.virtualcard.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

@Component
public class ProblemDetailFactory {

    private static final String URI_URN_PREFIX = "urn:problem:";

    public ProblemDetail create(HttpStatus status, ApiErrorCode code, String detail,
                                HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(java.net.URI.create(URI_URN_PREFIX + code.typeSlug()));
        problem.setTitle(code.getTitle());
        problem.setInstance(java.net.URI.create(request.getRequestURI()));
        problem.setProperty("code", code.name());
        problem.setProperty("requestId", MDC.get("requestId"));
        return problem;
    }
}
