package com.giftia.web;

/** A client-visible failure with the status and detail used by the existing API. */
public class ApiException extends RuntimeException {
    private final int status;

    public ApiException(int status, String detail) {
        super(detail);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public String detail() {
        return getMessage();
    }
}
