package com.github.claudecodegui.handler;

import com.github.claudecodegui.cache.SessionIndexCache;
import com.github.claudecodegui.cache.SessionIndexManager;
import com.github.claudecodegui.util.PathUtils;
import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service for deleting session history files and related data.
 */
class HistoryDeleteService {

    private static final Logger LOG = Logger.getInstance(HistoryDeleteService.class);

    private final HandlerContext context;
    private final NodeJsServiceCaller nodeJsServiceCaller;
    private final HistoryLoadService historyLoadService;

    HistoryDeleteService(HandlerContext context, NodeJsServiceCaller nodeJsServiceCaller, HistoryLoadService historyLoadService) {
        this.context = context;
        this.nodeJsServiceCaller = nodeJsServiceCaller;
        this.historyLoadService = historyLoadService;
    }

    /**
     * Delete session history files.
     * Deletes the .jsonl file for the specified sessionId and related agent-xxx.jsonl files.
     */
    void handleDeleteSession(String sessionId, String currentProvider) {
        CompletableFuture.runAsync(() -> {
            try {
                LOG.info("[HistoryHandler] ========== 开始删除会话 ==========");
                LOG.info("[HistoryHandler] SessionId: " + sessionId);
                LOG.info("[HistoryHandler] CurrentProvider: " + currentProvider);

                String homeDir = PlatformUtils.getHomeDirectory();
                Path sessionDir;
                boolean mainDeleted = false;
                int agentFilesDeleted = 0;

                // Determine session directory based on provider
                if ("codex".equals(currentProvider)) {
                    // Codex sessions: stored in ~/.codex/sessions/
                    sessionDir = Paths.get(homeDir, ".codex", "sessions");
                    LOG.info("[HistoryHandler] 使用 Codex 会话目录: " + sessionDir);

                    if (!Files.exists(sessionDir)) {
                        LOG.error("[HistoryHandler] Codex 会话目录不存在: " + sessionDir);
                        return;
                    }

                    // Find and delete Codex session files (may be in subdirectories)
                    try (Stream<Path> paths = Files.walk(sessionDir)) {
                        List<Path> sessionFiles = paths
                                                          .filter(Files::isRegularFile)
                                                          .filter(path -> path.getFileName().toString().startsWith(sessionId))
                                                          .filter(path -> path.toString().endsWith(".jsonl"))
                                                          .collect(Collectors.toList());

                        for (Path sessionFile : sessionFiles) {
                            try {
                                Files.delete(sessionFile);
                                LOG.info("[HistoryHandler] 已删除 Codex 会话文件: " + sessionFile);
                                mainDeleted = true;
                            } catch (Exception e) {
                                LOG.error("[HistoryHandler] 删除 Codex 会话文件失败: " + sessionFile + " - " + e.getMessage(), e);
                            }
                        }
                    }

                } else {
                    // Claude sessions: stored in ~/.claude/projects/{projectPath}/
                    String projectPath = context.getProject().getBasePath();
                    LOG.info("[HistoryHandler] ProjectPath: " + projectPath);

                    Path claudeDir = Paths.get(homeDir, ".claude");
                    Path projectsDir = claudeDir.resolve("projects");

                    // Sanitize project path (consistent with ClaudeHistoryReader)
                    String sanitizedPath = PathUtils.sanitizePath(projectPath);
                    sessionDir = projectsDir.resolve(sanitizedPath);

                    LOG.info("[HistoryHandler] 使用 Claude 会话目录: " + sessionDir);

                    if (!Files.exists(sessionDir)) {
                        LOG.error("[HistoryHandler] Claude 项目目录不存在: " + sessionDir);
                        return;
                    }

                    // Delete the main session file
                    Path mainSessionFile = sessionDir.resolve(sessionId + ".jsonl");

                    if (Files.exists(mainSessionFile)) {
                        Files.delete(mainSessionFile);
                        LOG.info("[HistoryHandler] 已删除主会话文件: " + mainSessionFile.getFileName());
                        mainDeleted = true;
                    } else {
                        LOG.warn("[HistoryHandler] 主会话文件不存在: " + mainSessionFile.getFileName());
                    }

                    // Delete related agent files
                    try (Stream<Path> stream = Files.list(sessionDir)) {
                        List<Path> agentFiles = stream
                                                        .filter(path -> {
                                                            String filename = path.getFileName().toString();
                                                            if (!filename.startsWith("agent-") || !filename.endsWith(".jsonl")) {
                                                                return false;
                                                            }
                                                            return isAgentFileRelatedToSession(path, sessionId);
                                                        })
                                                        .collect(Collectors.toList());

                        for (Path agentFile : agentFiles) {
                            try {
                                Files.delete(agentFile);
                                LOG.info("[HistoryHandler] 已删除关联 agent 文件: " + agentFile.getFileName());
                                agentFilesDeleted++;
                            } catch (Exception e) {
                                LOG.error("[HistoryHandler] 删除 agent 文件失败: " + agentFile.getFileName() + " - " + e.getMessage(), e);
                            }
                        }
                    }
                }

                LOG.info("[HistoryHandler] ========== 删除会话完成 ==========");
                LOG.info("[HistoryHandler] 主会话文件: " + (mainDeleted ? "已删除" : "未找到"));
                LOG.info("[HistoryHandler] Agent 文件: 删除了 " + agentFilesDeleted + " 个");

                // Clean up related favorite and title data
                if (mainDeleted) {
                    try {
                        LOG.info("[HistoryHandler] 开始清理会话关联数据...");

                        // Clean up favorite data
                        nodeJsServiceCaller.callNodeJsFavoritesService("removeFavorite", sessionId);
                        LOG.info("[HistoryHandler] 已清理收藏数据");

                        // Clean up title data
                        nodeJsServiceCaller.callNodeJsDeleteTitle(sessionId);
                        LOG.info("[HistoryHandler] 已清理标题数据");

                    } catch (Exception e) {
                        LOG.warn("[HistoryHandler] 清理关联数据失败（不影响会话删除）: " + e.getMessage());
                    }
                }

                // Clear cache to ensure deleted sessions are not returned on next load
                try {
                    String projectPath = context.getProject().getBasePath();
                    LOG.info("[HistoryHandler] 清理会话缓存...");

                    if ("codex".equals(currentProvider)) {
                        SessionIndexCache.getInstance().clearAllCodexCache();
                        SessionIndexManager.getInstance().clearAllCodexIndex();
                        LOG.info("[HistoryHandler] 已清理所有 Codex 缓存和索引");
                    } else {
                        SessionIndexCache.getInstance().clearProject(projectPath);
                        SessionIndexManager.getInstance().clearProjectIndex("claude", projectPath);
                        LOG.info("[HistoryHandler] 已清理 Claude 项目缓存和索引");
                    }

                } catch (Exception e) {
                    LOG.warn("[HistoryHandler] 清理缓存失败（不影响会话删除）: " + e.getMessage());
                }

                // After deletion, reload history data and push to frontend
                LOG.info("[HistoryHandler] 重新加载历史数据...");
                historyLoadService.handleLoadHistoryData(currentProvider);

            } catch (Exception e) {
                LOG.error("[HistoryHandler] 删除会话失败: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Check if an agent file belongs to the specified session.
     */
    private boolean isAgentFileRelatedToSession(Path agentFilePath, String sessionId) {
        try (BufferedReader reader = Files.newBufferedReader(agentFilePath, StandardCharsets.UTF_8)) {
            String line;
            int lineCount = 0;
            // Only read the first 20 lines for performance
            while ((line = reader.readLine()) != null && lineCount < 20) {
                if (line.contains("\"sessionId\":\"" + sessionId + "\"") ||
                            line.contains("\"parentSessionId\":\"" + sessionId + "\"")) {
                    LOG.debug("[HistoryHandler] Agent文件 " + agentFilePath.getFileName() + " 属于会话 " + sessionId);
                    return true;
                }
                lineCount++;
            }
            LOG.debug("[HistoryHandler] Agent文件 " + agentFilePath.getFileName() + " 不属于会话 " + sessionId);
            return false;
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] 无法读取agent文件 " + agentFilePath.getFileName() + ": " + e.getMessage());
            return false;
        }
    }
}
