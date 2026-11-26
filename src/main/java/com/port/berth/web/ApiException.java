package com.port.berth.web;

import java.util.List;

/**带业务错误码与详情的 API 异常*/
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private Object details;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException notFound(String what) {
        return new ApiException(404, "NOT_FOUND", what);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }

    public ApiException details(Object d) {
        this.details = d;
        return this;
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }
    public Object getDetails() { return details; }

    /**资源冲突详情（候选原因列表）*/
    public static class ConflictBody {
        public String code;
        public String message;
        public List<?> reasons;
        public ConflictBody(String code, String message, List<?> reasons) {
            this.code = code; this.message = message; this.reasons = reasons;
        }
    }
}
