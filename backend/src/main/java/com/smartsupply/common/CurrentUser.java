package com.smartsupply.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {
    private CurrentUser() {}
    public static String username() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? "system" : String.valueOf(a.getName());
    }
    public static boolean isAuthenticated() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.isAuthenticated() && !"anonymousUser".equals(String.valueOf(a.getName()));
    }
}
