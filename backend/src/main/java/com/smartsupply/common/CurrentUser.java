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
        List<String> roles = roles();
        return roles.isEmpty() ? null : roles.get(0);
    }

    /** 当前用户全部角色（ROLE_* 去前缀）；未登录返回空列表 */
    public static List<String> roles() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !isReal(a)) return List.of();
        return a.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(s -> s.startsWith("ROLE_"))
                .map(s -> s.substring("ROLE_".length()))
                .toList();
    }

    /** 是否具备指定角色（多角色场景不能只看第一个） */
    public static boolean hasRole(String role) {
        return roles().contains(role);
    }

    private static boolean isReal(Authentication a) {
        return a.isAuthenticated() && !"anonymousUser".equals(String.valueOf(a.getName()));
    }
}
