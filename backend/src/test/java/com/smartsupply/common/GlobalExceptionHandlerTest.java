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
}
