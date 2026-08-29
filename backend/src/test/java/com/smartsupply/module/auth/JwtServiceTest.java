package com.smartsupply.module.auth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {
    private JwtService svc() {
        JwtService s = new JwtService("01234567890123456789012345678901-test-secret-!!", 12);
        s.init();
        return s;
    }
    @Test void generateAndParseRoundTrip() {
        JwtService svc = svc();
        String token = svc.generate("alice", "ADMIN");
        assertNotNull(token);
        var claims = svc.parse(token);
        assertEquals("alice", claims.getSubject());
        assertEquals("ADMIN", claims.get("role", String.class));
    }
    @Test void tamperedTokenFails() {
        JwtService svc = svc();
        String token = svc.generate("bob", "ADMIN");
        String tampered = token.substring(0, token.length() - 4) + "aaaa";
        assertThrows(Exception.class, () -> svc.parse(tampered));
    }
    @Test void shortSecretRejected() {
        JwtService weak = new JwtService("short", 12);
        assertThrows(IllegalStateException.class, weak::init);
    }
}
