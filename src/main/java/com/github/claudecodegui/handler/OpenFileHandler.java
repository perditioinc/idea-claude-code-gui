package com.github.claudecodegui.handler;

import com.github.claudecodegui.util.EditorFileUtils;
import com.github.claudecodegui.util.PathUtils;
import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * Handles opening files in the editor and opening URLs in the browser.
 */
class OpenFileHandler {

    private static final Logger LOG = Logger.getInstance(OpenFileHandler.class);

    private final HandlerContext context;

    OpenFileHandler(HandlerContext context) {
        this.context = context;
    }

    /**
     * Open a file in the editor.
     * Supports file paths with line numbers: file.txt:100 or file.txt:100-200.
     */
    void handleOpenFile(String filePath) {
        LOG.info("Open file request: " + filePath);

        // First process file path parsing on a regular thread (no VFS operations involved)
        CompletableFuture.runAsync(() -> {
            try {
                // Parse file path and line number
                final String[] parsedPath = {filePath};
                final int[] parsedLineNumber = {-1};
                final int[] parsedEndLineNumber = {-1};

                // Detect and extract line number (format: file.txt:100 or file.txt:100-200)
                int colonIndex = filePath.lastIndexOf(':');
                if (colonIndex > 0) {
                    String afterColon = filePath.substring(colonIndex + 1);
                    // Check if after the colon is a line number (may include a range, e.g. 100-200)
                    if (afterColon.matches("\\d+(-\\d+)?")) {
                        parsedPath[0] = filePath.substring(0, colonIndex);
                        int dashIndex = afterColon.indexOf('-');
                        String startLineStr = dashIndex > 0 ? afterColon.substring(0, dashIndex) : afterColon;
                        String endLineStr = dashIndex > 0 ? afterColon.substring(dashIndex + 1) : null;
                        try {
                            parsedLineNumber[0] = Integer.parseInt(startLineStr);
                            if (endLineStr != null && !endLineStr.isBlank()) {
                                parsedEndLineNumber[0] = Integer.parseInt(endLineStr);
                                LOG.info("Detected line range: " + parsedLineNumber[0] + "-" + parsedEndLineNumber[0]);
                            } else {
                                LOG.info("Detected line number: " + parsedLineNumber[0]);
                            }
                        } catch (NumberFormatException e) {
                            LOG.warn("Failed to parse line number: " + afterColon);
                        }
                    }
                }

                final String actualPath = parsedPath[0];
                final int lineNumber = parsedLineNumber[0];
                final int endLineNumber = parsedEndLineNumber[0];

                File file = new File(actualPath);
                if (!file.exists() && PlatformUtils.isWindows()) {
                    String convertedPath = PathUtils.convertMsysToWindowsPath(actualPath);
                    if (!convertedPath.equals(actualPath)) {
                        LOG.info("Detected MSYS2 path, converted to Windows path: " + convertedPath);
                        file = new File(convertedPath);
                    }
                }

                // If file does not exist and is a relative path, try resolving relative to the project root
                if (!file.exists() && !file.isAbsolute() && context.getProject().getBasePath() != null) {
                    File projectFile = new File(context.getProject().getBasePath(), actualPath);
                    LOG.info("Trying to resolve relative to project root: " + projectFile.getAbsolutePath());
                    if (projectFile.exists()) {
                        file = projectFile;
                    }
                }

                if (!file.exists()) {
                    LOG.warn("File not found: " + actualPath);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        context.callJavaScript("addErrorMessage", context.escapeJs("Cannot open file: file does not exist (" + actualPath + ")"));
                    }, ModalityState.nonModal());
                    return;
                }

                final File finalFile = file;

                // Use utility method to asynchronously refresh and find the file
                EditorFileUtils.refreshAndFindFileAsync(finalFile, virtualFile -> {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (context.getProject().isDisposed() || !virtualFile.isValid()) {
                            return;
                        }

                        Editor editor;
                        if (lineNumber > 0) {
                            OpenFileDescriptor descriptor = new OpenFileDescriptor(context.getProject(), virtualFile);
                            editor = FileEditorManager.getInstance(context.getProject()).openTextEditor(descriptor, true);

                            if (editor != null) {
                                int lineCount = editor.getDocument().getLineCount();
                                if (lineCount > 0) {
                                    int zeroBasedLine = Math.min(Math.max(0, lineNumber - 1), lineCount - 1);
                                    int startOffset = editor.getDocument().getLineStartOffset(zeroBasedLine);
                                    editor.getCaretModel().moveToOffset(startOffset);

                                    if (endLineNumber >= lineNumber) {
                                        int zeroBasedEndLine = Math.min(endLineNumber - 1, lineCount - 1);
                                        int endOffset = editor.getDocument().getLineEndOffset(zeroBasedEndLine);
                                        editor.getSelectionModel().setSelection(startOffset, endOffset);
                                        LOG.info("Navigated and selected line range: " + lineNumber + "-" + endLineNumber);
                                    } else {
                                        if (endLineNumber > 0) {
                                            LOG.warn("Invalid line range: " + lineNumber + "-" + endLineNumber);
                                        }
                                        editor.getSelectionModel().removeSelection();
                                        LOG.info("Navigated to line " + lineNumber);
                                    }

                                    editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
                                } else {
                                    LOG.warn("File is empty, cannot navigate to line " + lineNumber);
                                }
                            } else {
                                LOG.warn("Cannot open text editor: " + virtualFile.getPath());
                                FileEditorManager.getInstance(context.getProject()).openFile(virtualFile, true);
                            }
                        } else {
                            FileEditorManager.getInstance(context.getProject()).openFile(virtualFile, true);
                        }

                        LOG.info("Successfully opened file: " + filePath);
                    }, ModalityState.nonModal());
                }, () -> {
                    // Failure callback
                    LOG.error("Failed to get VirtualFile: " + filePath);
                    context.callJavaScript("addErrorMessage", context.escapeJs("Cannot open file: " + filePath));
                });
            } catch (Exception e) {
                LOG.error("Failed to open file: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Open the browser.
     */
    void handleOpenBrowser(String url) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                BrowserUtil.browse(url);
            } catch (Exception e) {
                LOG.error("Cannot open browser: " + e.getMessage(), e);
            }
        });
    }
}
