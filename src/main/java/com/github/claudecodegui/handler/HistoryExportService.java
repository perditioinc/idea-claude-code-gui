package com.github.claudecodegui.handler;

import com.github.claudecodegui.provider.claude.ClaudeHistoryReader;
import com.github.claudecodegui.provider.codex.CodexHistoryReader;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * Service for exporting session data.
 */
class HistoryExportService {

    private static final Logger LOG = Logger.getInstance(HistoryExportService.class);

    private final HandlerContext context;

    HistoryExportService(HandlerContext context) {
        this.context = context;
    }

    /**
     * Export session data.
     * Reads all messages of the session and returns them to the frontend.
     */
    void handleExportSession(String content, String currentProvider) {
        CompletableFuture.runAsync(() -> {
            LOG.info("[HistoryHandler] ========== 开始导出会话 ==========");

            try {
                // Parse JSON from frontend to extract sessionId and title
                com.google.gson.JsonObject exportRequest = new com.google.gson.Gson().fromJson(content, com.google.gson.JsonObject.class);
                String sessionId = exportRequest.get("sessionId").getAsString();
                String title = exportRequest.get("title").getAsString();

                String projectPath = context.getProject().getBasePath();
                LOG.info("[HistoryHandler] SessionId: " + sessionId);
                LOG.info("[HistoryHandler] Title: " + title);
                LOG.info("[HistoryHandler] ProjectPath: " + projectPath);
                LOG.info("[HistoryHandler] CurrentProvider: " + currentProvider);

                // Choose a different reader based on the provider
                String messagesJson;
                if ("codex".equals(currentProvider)) {
                    LOG.info("[HistoryHandler] 使用 CodexHistoryReader 读取 Codex 会话消息");
                    CodexHistoryReader codexReader = new CodexHistoryReader();
                    messagesJson = codexReader.getSessionMessagesAsJson(sessionId);
                } else {
                    LOG.info("[HistoryHandler] 使用 ClaudeHistoryReader 读取 Claude 会话消息");
                    ClaudeHistoryReader historyReader = new ClaudeHistoryReader();
                    messagesJson = historyReader.getSessionMessagesAsJson(projectPath, sessionId);
                }

                // Wrap messages into an object containing sessionId and title
                com.google.gson.JsonObject exportData = new com.google.gson.JsonObject();
                exportData.addProperty("sessionId", sessionId);
                exportData.addProperty("title", title);
                exportData.add("messages", com.google.gson.JsonParser.parseString(messagesJson));

                String wrappedJson = new com.google.gson.Gson().toJson(exportData);

                LOG.info("[HistoryHandler] 读取到会话消息，准备注入到前端");

                String escapedJson = context.escapeJs(wrappedJson);

                ApplicationManager.getApplication().invokeLater(() -> {
                    String jsCode = "console.log('[Backend->Frontend] Starting to inject export data');" +
                                            "if (window.onExportSessionData) { " +
                                            "  try { " +
                                            "    var jsonStr = '" + escapedJson + "'; " +
                                            "    window.onExportSessionData(jsonStr); " +
                                            "    console.log('[Backend->Frontend] Export data injected successfully'); " +
                                            "  } catch(e) { " +
                                            "    console.error('[Backend->Frontend] Failed to inject export data:', e); " +
                                            "  } " +
                                            "} else { " +
                                            "  console.error('[Backend->Frontend] onExportSessionData not available!'); " +
                                            "}";

                    context.executeJavaScriptOnEDT(jsCode);
                });

                LOG.info("[HistoryHandler] ========== 导出会话完成 ==========");

            } catch (Exception e) {
                LOG.error("[HistoryHandler] 导出会话失败: " + e.getMessage(), e);

                ApplicationManager.getApplication().invokeLater(() -> {
                    String jsCode = "if (window.addToast) { " +
                                            "  window.addToast('导出失败: " + context.escapeJs(e.getMessage() != null ? e.getMessage() : "未知错误") + "', 'error'); " +
                                            "}";
                    context.executeJavaScriptOnEDT(jsCode);
                });
            }
        });
    }
}
