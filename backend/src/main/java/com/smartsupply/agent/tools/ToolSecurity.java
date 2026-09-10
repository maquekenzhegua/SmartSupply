package com.smartsupply.agent.tools;

import com.smartsupply.common.CurrentUser;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 工具层安全校验：读工具要求登录且可追溯（记录调用者），写工具要求 ADMIN 角色 + 审计；
 * 同时提供请求级工具调用追踪，供 /api/agent/chat 返回 tools 字段给前端展示。
 * 追踪双通道：优先 ToolContext（调用方经 .toolContext(Map.of("toolTrace", list)) 注入，
 * 跨线程可靠——SSE 流式的工具执行发生在 reactor 线程，ThreadLocal 会静默丢失，
 * 实跑实锤 done.tools=[] 而回复里有真实工具数据）；无 context 时回退 ThreadLocal
 * （仅同步调用线程可靠）。匿名读开关：smartsupply.agent.tool.allow-anonymous-read——
 * 代码默认 false（fail-closed），演示环境由 application.yml 显式开 true；
 * 未配置该属性的环境（如新 profile）一律要求登录。
 */
@Component
public class ToolSecurity {

    /** 与实际 @Tool 写工具保持对齐；REST 层的 adjustInventory 不走本类 */
    private static final Set<String> WRITE_TOOLS = Set.of("createPurchaseOrder");

    private static final ThreadLocal<List<String>> TOOL_TRACE = new ThreadLocal<>();

    private final boolean allowAnonymousRead;

    public ToolSecurity(@Value("${smartsupply.agent.tool.allow-anonymous-read:false}") boolean allowAnonymousRead) {
        this.allowAnonymousRead = allowAnonymousRead;
    }

    /** 每次请求开始前调用；endToolTrace 返回同一请求的工具调用记录（ThreadLocal 兼容路径） */
    public static void beginToolTrace() { TOOL_TRACE.set(Collections.synchronizedList(new ArrayList<>())); }

    public static List<String> endToolTrace() {
        List<String> trace = TOOL_TRACE.get();
        TOOL_TRACE.remove();
        return trace == null ? List.of() : List.copyOf(trace);
    }

    /** 无 context 时回退 ThreadLocal；有 context 时记入调用方提供的共享列表（跨线程可靠） */
    private static void trace(String toolName, ToolContext ctx) {
        List<String> shared = resolveTrace(ctx);
        if (shared != null) {
            if (shared.size() < 32) shared.add(toolName + "(" + CurrentUser.username() + ")");
            return;
        }
        List<String> trace = TOOL_TRACE.get();
        if (trace != null && trace.size() < 32) trace.add(toolName + "(" + CurrentUser.username() + ")");
    }

    @SuppressWarnings("unchecked")
    static List<String> resolveTrace(ToolContext ctx) {
        if (ctx == null || ctx.getContext() == null) return null;
        Object t = ctx.getContext().get("toolTrace");
        return t instanceof List ? (List<String>) t : null;
    }

    /** 读工具：默认要求登录（无 Key 演示可在配置降级为匿名可读）；无论降级与否都记录调用者供审计 */
    public void requireRead(String toolName) {
        if (!allowAnonymousRead && !CurrentUser.isAuthenticated()) {
            throw new SecurityException("工具 " + toolName + " 需要登录后调用");
        }
        trace(toolName, null);
    }

    /** ToolContext 版：@Tool 方法把注入的 ToolContext 原样传入，追踪跨线程可靠 */
    public void requireRead(String toolName, ToolContext ctx) {
        if (!allowAnonymousRead && !CurrentUser.isAuthenticated()) {
            throw new SecurityException("工具 " + toolName + " 需要登录后调用");
        }
        trace(toolName, ctx);
    }

    public void requireAuthenticated(String toolName) {
        if (!CurrentUser.isAuthenticated()) {
            throw new SecurityException("工具 " + toolName + " 需要登录后调用");
        }
        trace(toolName, null);
    }

    public void requireAuthenticated(String toolName, ToolContext ctx) {
        if (!CurrentUser.isAuthenticated()) {
            throw new SecurityException("工具 " + toolName + " 需要登录后调用");
        }
        trace(toolName, ctx);
    }

    /** 写工具：登录 + ADMIN 角色（多角色取全集判断，不只看第一个） */
    public void requireSupplierWritePerm(String toolName) {
        requireAuthenticated(toolName, null);
        if (WRITE_TOOLS.contains(toolName)) {
            if (!CurrentUser.hasRole("ADMIN")) {
                throw new SecurityException("工具 " + toolName + " 需要 ADMIN 角色，当前角色: " + CurrentUser.roles());
            }
        }
    }

    public void requireSupplierWritePerm(String toolName, ToolContext ctx) {
        requireAuthenticated(toolName, ctx);
        if (WRITE_TOOLS.contains(toolName)) {
            if (!CurrentUser.hasRole("ADMIN")) {
                throw new SecurityException("工具 " + toolName + " 需要 ADMIN 角色，当前角色: " + CurrentUser.roles());
            }
        }
    }
}
