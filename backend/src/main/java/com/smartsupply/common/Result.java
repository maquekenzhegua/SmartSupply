package com.smartsupply.common;

public record Result<T>(int code, String msg, T data) {
    public static <T> Result<T> ok(T data) { return new Result<>(200, "success", data); }
    public static <T> Result<T> ok() { return new Result<>(200, "success", null); }
    public static <T> Result<T> fail(String msg) { return new Result<>(500, msg, null); }
    public static <T> Result<T> fail(int code, String msg) { return new Result<>(code, msg, null); }
}
