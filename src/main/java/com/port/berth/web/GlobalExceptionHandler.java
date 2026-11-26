package com.port.berth.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ex.getCode());
        body.put("message", ex.getMessage());
        if (ex.getDetails() != null) body.put("details", ex.getDetails());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "VALIDATION_FAILED");
        body.put("message", "参数校验失败");
        body.put("details", ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).toList());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleIntegrity(DataIntegrityViolationException ex) {
        Throwable root = deepest(ex);
        String msg = root.getMessage() != null ? root.getMessage() : ex.getMessage();
        Map<String, Object> body = new LinkedHashMap<>();

        if (msg != null && msg.contains("RESOURCE_OVERLAP")) {
            body.put("code", "RESOURCE_LOCK_CONFLICT");
            body.put("message", "全套资源原子锁定失败：已有其他船次先锁定了同一资源区间（" + msg + "）");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        if (msg != null && msg.contains("WORK_RECORD_IMMUTABLE")) {
            body.put("code", "WORK_RECORD_IMMUTABLE");
            body.put("message", "已完成的引航/装卸记录不可修改或删除，只能通过重排延续未完成作业");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        log.warn("数据完整性冲突", ex);
        body.put("code", "DATA_INTEGRITY_VIOLATION");
        body.put("message", msg);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex) {
        log.error("未处理异常", ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "INTERNAL_ERROR");
        body.put("message", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        return ResponseEntity.status(500).body(body);
    }

    private Throwable deepest(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur;
    }
}
