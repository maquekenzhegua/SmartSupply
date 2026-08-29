package com.smartsupply.module.auth;

import com.smartsupply.common.Result;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtService jwtService;
    private final PasswordEncoder encoder;

    public AuthController(JwtService jwtService, PasswordEncoder encoder) {
        this.jwtService = jwtService;
        this.encoder = encoder;
    }

    @PostMapping("/login")
    public Result<Map<String, String>> login(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "");
        String password = body.getOrDefault("password", "");
        // Demo：admin / admin123 直通；生产请查 sys_user 表并用 encoder.matches 校验
        if ("admin".equals(username) && "admin123".equals(password)) {
            String token = jwtService.generate(username, "ADMIN");
            return Result.ok(Map.of("token", token, "username", username));
        }
        return Result.fail(401, "用户名或密码错误（演示账号 admin / admin123）");
    }

    @GetMapping("/me")
    public Result<Map<String, String>> me(@RequestHeader(value="Authorization", required=false) String auth) {
        return Result.ok(Map.of("username", "admin", "role", "ADMIN"));
    }
}
