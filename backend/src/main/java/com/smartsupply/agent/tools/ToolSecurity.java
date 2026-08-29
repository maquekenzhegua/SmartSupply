package com.smartsupply.agent.tools;

import com.smartsupply.common.CurrentUser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 工具层安全校验：读工具要求可追溯（记录调用者），写工具要求 ADMIN 角色 + 审计；
 * 同时提供请求级工具调用追踪（ThreadLocal），供 /api/agent/chat 返回 tools 字段给前端展示。
 * 注意：ThreadLocal 追踪只在同步调用线程可靠，SSE 流式路径由 done 事件尽力回传。
 */
@Component
public class ToolSecurity {

    private static final Set<String> WRITE_TOOLS = Set.of("createPurchaseOrder", "adjustInventory");

    private static final ThreadLocal<List<String>> TOOL_TRACE = new ThreadLocal<>();

    /** 每次请求开始前调用；endToolTrace 返回同一请求的工具调用记录 */
    public static void beginToolTrace() { TOOL_TRACE.set(Collections.synchronizedList(new ArrayList<>())); }

    public static List<String> endToolTrace() {
        List<String> trace = TOOL_TRACE.get();
        TOOL_TRACE.remove();
        return trace == null ? List.of() : List.copyOf(trace);
    }

    private static void trace(String toolName) {
        List<String> trace = TOOL_TRACE.get();
        if (trace != null && trace.size() < 32) trace.add(toolName + "(" + CurrentUser.username() + ")");
    }

    public void requireRead(String toolName) {
        trace(toolName);
    }

    public void requireAuthenticated(String toolName) {
        if (!CurrentUser.isAuthenticated()) {
            throw new SecurityException("工具 " + toolName + " 需要登录后调用");
        }
        trace(toolName);
    }

    /** 写工具：登录 + ADMIN 角色（JWT role claim -> JwtAuthFilter 写入的 ROLE_ADMIN authority） */
    public void requireSupplierWritePerm(String toolName) {
        requireAuthenticated(toolName);
        if (WRITE_TOOLS.contains(toolName)) {
            String role = CurrentUser.role();
            if (!"ADMIN".equals(role)) {
                throw new SecurityException("工具 " + toolName + " 需要 ADMIN 角色，当前角色: " + role);
            }
        }
    }
}
