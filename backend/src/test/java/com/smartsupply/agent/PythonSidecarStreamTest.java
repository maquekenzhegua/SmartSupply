package com.smartsupply.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** PythonSidecarService.streamReason 对边车 SSE 的消费契约（不依赖真实边车）。 */
class PythonSidecarStreamTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    private static List<Map<String, String>> msgs() {
        return List.of(Map.of("role", "user", "content", "which SKUs are low"));
    }

    private void sse(String body) {
        server.createContext("/api/reason/stream", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
    }

    @Test
    void consumesEventStreamAndForwardsCallbacks() {
        sse("""
                event: plan
                data: {"event":"plan","calls":[{"tool":"list_low_stock","args":{}}],"provider":"mock"}

                event: tool
                data: {"event":"tool","tool":"list_low_stock","args":{},"ok":true}

                event: reply
                data: {"event":"reply","text":"2 SKUs below safety stock"}

                event: done
                data: {"event":"done","degraded":false,"degrade_reason":"","iters":2,"tools":["list_low_stock"]}

                """);
        PythonSidecarService svc = new PythonSidecarService(true, baseUrl, 5000, 60000, "");
        List<String> seen = new ArrayList<>();
        StringBuilder replyFromCb = new StringBuilder();
        PythonSidecarService.SidecarResult r = svc.streamReason(msgs(), "general", "s1", "Bearer t", (name, payload) -> {
            seen.add(name);
            if ("reply".equals(name)) replyFromCb.append(payload.get("text"));
        });
        assertNotNull(r, "正常 SSE 应返回结果而非 null");
        assertEquals("2 SKUs below safety stock", r.reply());
        assertFalse(r.degraded());
        assertEquals(2, r.iters());
        assertEquals(1, r.toolResults().size());
        assertEquals("list_low_stock", r.toolResults().get(0).get("tool"));
        assertEquals(List.of("plan", "tool", "reply"), seen);
        assertEquals("2 SKUs below safety stock", replyFromCb.toString());
        assertEquals("plan", ((Map<?, ?>) r.trace().get(0)).get("node"));
    }

    @Test
    void degradedDoneIsPropagated() {
        sse("""
                event: done
                data: {"event":"done","degraded":true,"degrade_reason":"all_tool_calls_failed","iters":1,"tools":[]}

                """);
        PythonSidecarService svc = new PythonSidecarService(true, baseUrl, 5000, 60000, "");
        PythonSidecarService.SidecarResult r = svc.streamReason(msgs(), "general", "s", "Bearer t", (n, p) -> {});
        assertNotNull(r);
        assertTrue(r.degraded());
        assertEquals("all_tool_calls_failed", r.degradeReason());
    }

    @Test
    void connectionRefusedReturnsNullForFallback() {
        // 端口未监听：连接失败 → null，调用方回落 java-direct
        PythonSidecarService svc = new PythonSidecarService(true, "http://127.0.0.1:1", 1000, 60000, "");
        PythonSidecarService.SidecarResult r = svc.streamReason(msgs(), "general", "s", "Bearer t", (n, p) -> {});
        assertNull(r);
    }

    @Test
    void badRequestBecomesDegradedResult() throws Exception {
        server.createContext("/api/reason/stream", exchange -> {
            byte[] bytes = "{\"error\":\"bad\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        PythonSidecarService svc = new PythonSidecarService(true, baseUrl, 5000, 60000, "");
        PythonSidecarService.SidecarResult r = svc.streamReason(msgs(), "general", "s", "Bearer t", (n, p) -> {});
        assertNotNull(r, "4xx 应作为 degraded 结果上报，而非 null（不触发回落）");
        assertTrue(r.degraded());
        assertTrue(r.degradeReason().startsWith("sidecar_bad_request"));
    }
}
