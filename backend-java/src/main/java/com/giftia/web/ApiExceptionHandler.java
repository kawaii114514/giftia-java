package com.giftia.web;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> apiFailure(ApiException failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("detail", failure.detail()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> invalidRequest(Exception failure) {
        return ResponseEntity.unprocessableEntity().body(Map.of("detail", "请求参数无效"));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, String>> notFound(Exception failure) {
        return ResponseEntity.status(404).body(Map.of("detail", "接口不存在"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> unsupportedMethod(HttpRequestMethodNotSupportedException failure) {
        return ResponseEntity.status(405).body(Map.of("detail", "请求方法不支持"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> unsupportedMedia(HttpMediaTypeNotSupportedException failure) {
        return ResponseEntity.status(415).body(Map.of("detail", "请求内容类型不支持"));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Map<String, String>> unacceptableMedia(HttpMediaTypeNotAcceptableException failure) {
        return ResponseEntity.status(406).body(Map.of("detail", "响应内容类型不支持"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpectedFailure(Exception failure) {
        // Do not expose model credentials, SQL or filesystem paths to clients.
        log.error("请求处理失败 ({})", failure.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(Map.of("detail", "服务暂时不可用，请稍后重试"));
    }
}
