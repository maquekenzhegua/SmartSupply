package com.smartsupply.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

public final class CurrentUser {
    private CurrentUser() {}

    public static String username() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null || !isReal(a) ? "system" : String.valueOf(a.getName());
    }

    public static boolean isAuthenticated() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && isReal(a);
    }

    /** JWT role claim -> JwtAuthFilter 写入的 ROLE_* authority，去前缀返回（如 ADMIN）；未登录返回 null */
    public static String role() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !isReal(a)) return null;
        List<String> roles = a.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(s -> s.startsWith("ROLE_"))
                .map(s -> s.substring("ROLE_".length()))
                .toList();
        return roles.isEmpty() ? null : roles.get(0);
    }

    private static boolean isReal(Authentication a) {
        return a.isAuthenticated() && !"anonymousUser".equals(String.valueOf(a.getName()));
    }
}
