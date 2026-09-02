package com.smartsupply.agent.tools;

import com.smartsupply.common.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 工具层安全校验：读工具要求登录且可追溯（记录调用者），写工具要求 ADMIN 角色 + 审计；
 * 同时提供请求级工具调用追踪（ThreadLocal），供 /api/agent/chat 返回 tools 字段给前端展示。
 * 注意：ThreadLocal 追踪只在同步调用线程可靠，SSE 流式路径由 done 事件尽力回传。
 * demo 降级开关：smartsupply.agent.tool.allow-anonymous-read（prod 置 false，匿名读也拒绝）。
 */
@Component
public class ToolSecurity {

    /** 与实际 @Tool 写工具保持对齐；REST 层的 adjustInventory 不走本类 */
    private static final Set<String> WRITE_TOOLS = Set.of("createPurchaseOrder");

    private static final ThreadLocal<List<String>> TOOL_TRACE = new ThreadLocal<>();

    private final boolean allowAnonymousRead;

    public ToolSecurity(@Value("${smartsupply.agent.tool.allow-anonymous-read:true}") boolean allowAnonymousRead) {
        this.allowAnonymousRead = allowAnonymousRead;
    }

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

    /** 读工具：默认要求登录（无 Key 演示可在配置降级为匿名可读）；无论降级与否都记录调用者供审计 */
    public void requireRead(String toolName) {
        if (!allowAnonymousRead && !CurrentUser.isAuthenticated()) {
            throw new SecurityException("工具 " + toolName + " 需要登录后调用");
        }
        trace(toolName);
    }

    public void requireAuthenticated(String toolName) {
        if (!CurrentUser.isAuthenticated()) {
            throw new SecurityException("工具 " + toolName + " 需要登录后调用");
        }
        trace(toolName);
    }

    /** 写工具：登录 + ADMIN 角色（多角色取全集判断，不只看第一个） */
    public void requireSupplierWritePerm(String toolName) {
        requireAuthenticated(toolName);
        if (WRITE_TOOLS.contains(toolName)) {
            if (!CurrentUser.hasRole("ADMIN")) {
                throw new SecurityException("工具 " + toolName + " 需要 ADMIN 角色，当前角色: " + CurrentUser.roles());
            }
        }
    }
}
