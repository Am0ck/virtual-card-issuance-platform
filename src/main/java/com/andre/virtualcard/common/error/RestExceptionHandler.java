package com.andre.virtualcard.common.error;

import com.andre.virtualcard.card.CardNotFoundException;
import com.andre.virtualcard.idempotency.IdempotencyConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class RestExceptionHandler {

    private final ProblemDetailFactory problemDetailFactory;

    public RestExceptionHandler(ProblemDetailFactory problemDetailFactory) {
        this.problemDetailFactory = problemDetailFactory;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationFailure(MethodArgumentNotValidException e, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_REQUEST,
                "Request validation failed", request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(MissingRequestHeaderException e, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_REQUEST,
                "Required header '" + e.getHeaderName() + "' is missing", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_REQUEST,
                "Malformed parameter value", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleMessageNotReadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_REQUEST,
                "Malformed request body", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleInvalidArgument(IllegalArgumentException e, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_REQUEST, e.getMessage(), request);
    }

    @ExceptionHandler(CardNotFoundException.class)
    public ProblemDetail handleCardNotFound(CardNotFoundException e, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, ApiErrorCode.CARD_NOT_FOUND, e.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(IdempotencyConflictException e, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, ApiErrorCode.IDEMPOTENCY_CONFLICT, e.getMessage(), request);
    }

    @ExceptionHandler(CardMutationDeclinedException.class)
    public ProblemDetail handleBusinessDecline(CardMutationDeclinedException e, HttpServletRequest request) {
        return switch (e.getReason()) {
            case INSUFFICIENT_FUNDS ->
                    problem(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.INSUFFICIENT_FUNDS, e.getMessage(), request);
            case CARD_BLOCKED ->
                    problem(HttpStatus.CONFLICT, ApiErrorCode.CARD_BLOCKED, e.getMessage(), request);
            case CARD_CLOSED ->
                    problem(HttpStatus.CONFLICT, ApiErrorCode.CARD_CLOSED, e.getMessage(), request);
        };
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException e, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, ApiErrorCode.INVALID_REQUEST,
                "No API endpoint matches this request path", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                  HttpServletRequest request) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, ApiErrorCode.METHOD_NOT_ALLOWED,
                "HTTP method is not supported for this endpoint", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ProblemDetail handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e,
                                                     HttpServletRequest request) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ApiErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "Request Content-Type is not supported", request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleBrokenInvariant(IllegalStateException e, HttpServletRequest request) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR,
                "An internal consistency error occurred", request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred", request);
    }

    private ProblemDetail problem(HttpStatus status, ApiErrorCode code, String detail, HttpServletRequest request) {
        return problemDetailFactory.create(status, code, detail, request);
    }
}
