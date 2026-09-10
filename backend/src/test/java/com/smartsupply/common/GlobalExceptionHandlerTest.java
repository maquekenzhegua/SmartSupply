package com.smartsupply.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {
    @Test void badRequestReturns400() {
        GlobalExceptionHandler h = new GlobalExceptionHandler();
        Result<Void> r = h.badRequest(new IllegalArgumentException("bad sql"));
        assertEquals(400, r.code());
    }
    @Test void internalHidesInProd() {
        String orig = System.getProperty("spring.profiles.active");
        try {
            System.setProperty("spring.profiles.active", "prod");
            GlobalExceptionHandler h = new GlobalExceptionHandler();
            Result<Void> r = h.internal(new RuntimeException("x".repeat(500)));
            assertEquals(500, r.code());
            assertEquals("系统异常，请稍后重试", r.msg());
        } finally {
            if (orig == null) System.clearProperty("spring.profiles.active");
            else System.setProperty("spring.profiles.active", orig);
        }
    }

    /** 实跑回归：未知路径此前被兜底 500 + ERROR 堆栈（"No static resource api/inventory/list"），
     *  监控误报且语义错误；应答 404 */
    @Test void unknownPathReturns404() {
        GlobalExceptionHandler h = new GlobalExceptionHandler();
        Result<Void> r = h.notFound(new org.springframework.web.servlet.resource.NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "api/inventory/list"));
        assertEquals(404, r.code());
        assertEquals("接口不存在", r.msg());
    }
}
