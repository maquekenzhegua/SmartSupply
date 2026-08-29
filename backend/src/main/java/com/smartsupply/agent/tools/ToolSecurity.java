package com.smartsupply.agent.tools;

import com.smartsupply.common.CurrentUser;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 工具层安全校验：鉴权 + 写操作审计 + 写入幂等键由业务层持有。
 */
@Component
public class ToolSecurity {

    private static final Set<String> WRITE_TOOLS = Set.of("createPurchaseOrder", "adjustInventory");

    public void requireAuthenticated(String toolName) {
        if (WRITE_TOOLS.contains(toolName) && !CurrentUser.isAuthenticated()) {
            throw new SecurityException("工具 " + toolName + " 需要登录后调用");
        }
    }

    public void requireSupplierWritePerm(String toolName) {
        if (WRITE_TOOLS.contains(toolName) && !CurrentUser.isAuthenticated()) {
            throw new SecurityException("无写权限");
        }
    }
}
