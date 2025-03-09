package com.pizhai.cdp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pizhai.exception.HtmlToPdfException.ConnectionException;
import com.pizhai.exception.HtmlToPdfException.PageNavigationException;
import com.pizhai.exception.HtmlToPdfException.PdfGenerationException;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chrome DevTools Protocol WebSocket客户端
 */
public class ChromeDevToolsClient extends WebSocketClient {

    private static final Logger logger = LoggerFactory.getLogger(ChromeDevToolsClient.class);
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create(); // 使用美化输出的JSON
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;

    private final AtomicInteger requestId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();

    public ChromeDevToolsClient(String webSocketUrl) throws ConnectionException {
        super(URI.create(webSocketUrl));

        try {
            logger.info("正在连接到Chrome DevTools WebSocket: {}", webSocketUrl);
            boolean connected = connectBlocking(10, TimeUnit.SECONDS);
            if (!connected) {
                throw new ConnectionException("连接Chrome DevTools WebSocket失败");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectionException("连接Chrome DevTools WebSocket被中断", e);
        }
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        logger.info("已连接到Chrome DevTools WebSocket");
    }

    @Override
    public void onMessage(String message) {
        // 记录接收到的消息
        logger.info("接收到WebSocket消息: \n{}", formatJson(message));

        JsonObject response = gson.fromJson(message, JsonObject.class);

        // 处理响应
        if (response.has("id")) {
            int id = response.get("id").getAsInt();
            CompletableFuture<JsonObject> future = pendingRequests.remove(id);
            if (future != null) {
                future.complete(response);
            }
        } else {
            // 处理事件（没有id的消息）
            if (response.has("method")) {
                logger.info("接收到Chrome事件: {}", response.get("method").getAsString());
            }
        }
    }

    @Override
    public void send(String text) {
        // 记录发送的消息
        logger.info("发送WebSocket消息: \n{}", formatJson(text));
        super.send(text);
    }

    private String formatJson(String json) {
        try {
            JsonElement jsonElement = gson.fromJson(json, JsonElement.class);
            return gson.toJson(jsonElement);
        } catch (Exception e) {
            // 如果不是有效的JSON，直接返回原始字符串
            return json;
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.info("Chrome DevTools WebSocket连接已关闭: {}，代码: {}, 远程关闭: {}", reason, code, remote);

        // 处理所有未完成的请求
        for (CompletableFuture<JsonObject> future : pendingRequests.values()) {
            future.completeExceptionally(new ConnectionException("WebSocket连接已关闭: " + reason));
        }
        pendingRequests.clear();
    }

    @Override
    public void onError(Exception ex) {
        logger.error("Chrome DevTools WebSocket错误", ex);
    }

    /**
     * 启用Page域
     */
    public void enablePage() throws ConnectionException {
        try {
            logger.info("启用Page域");
            sendCommand("Page.enable");
        } catch (Exception e) {
            throw new ConnectionException("启用Page域失败", e);
        }
    }

    /**
     * 导航到指定URL（包括本地文件路径）
     */
    public void navigateToUrl(String url) throws PageNavigationException {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("url", url);

            logger.info("导航到URL: {}", url);
            JsonObject response = sendCommand("Page.navigate", params);

            if (response.has("error")) {
                // 处理错误对象，可能是嵌套的JsonObject
                JsonElement errorElement = response.get("error");
                String errorMessage;

                if (errorElement.isJsonObject()) {
                    // 如果是对象，尝试获取message字段或整个对象的字符串表示
                    JsonObject errorObj = errorElement.getAsJsonObject();
                    if (errorObj.has("message")) {
                        errorMessage = errorObj.get("message").getAsString();
                    } else {
                        errorMessage = errorObj.toString();
                    }
                } else if (errorElement.isJsonPrimitive()) {
                    // 如果是基本类型，直接获取字符串值
                    errorMessage = errorElement.getAsString();
                } else {
                    // 其他情况，使用toString
                    errorMessage = errorElement.toString();
                }

                logger.error("导航失败: {}", errorMessage);
                throw new PageNavigationException("导航到URL失败: " + errorMessage);
            }

            // 检查导航结果
            if (response.has("result")) {
                JsonObject result = response.getAsJsonObject("result");
                if (result.has("frameId")) {
                    logger.info("导航成功，frameId: {}", result.get("frameId").getAsString());
                }

                // 检查是否有加载错误
                if (result.has("errorText") && !result.get("errorText").getAsString().isEmpty()) {
                    String errorText = result.get("errorText").getAsString();
                    logger.warn("导航可能有问题: {}", errorText);
                }
            }

            // 等待页面加载完成
            waitForPageLoad();

        } catch (PageNavigationException e) {
            throw e;
        } catch (Exception e) {
            logger.error("导航过程中发生异常", e);
            throw new PageNavigationException("导航到URL失败: " + e.getMessage(), e);
        }
    }

    /**
     * 等待页面加载完成
     */
    private void waitForPageLoad() throws Exception {
        // 等待页面加载事件
        sendCommand("Page.enable");

        // 使用DOMContentLoaded事件判断页面是否加载完成
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        // 等待合理的时间以确保页面加载
        logger.info("等待页面加载完成...");
        Thread.sleep(3000); // 增加到3秒
        logger.info("等待页面加载完成结束");
    }

    /**
     * 生成PDF并保存到指定路径
     */
    public void generatePdf(String outputPath, PdfOptions options) throws PdfGenerationException {
        try {
            // 生成PDF数据
            byte[] pdfData = generatePdfAsByteArray(options);

            // 写入文件
            logger.info("保存PDF到文件: {}", outputPath);
            File outputFile = new File(outputPath);
            Files.write(outputFile.toPath(), pdfData);

        } catch (PdfGenerationException e) {
            throw e;
        } catch (Exception e) {
            logger.error("保存PDF文件过程中发生异常", e);
            throw new PdfGenerationException("保存PDF文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成PDF并返回字节数组
     *
     * @param options PDF生成选项
     * @return PDF数据的字节数组
     * @throws PdfGenerationException 如果生成过程中发生错误
     */
    public byte[] generatePdfAsByteArray(PdfOptions options) throws PdfGenerationException {
        try {
            Map<String, Object> params = new HashMap<>();

            // 设置PDF选项
            if (options != null) {
                params.put("landscape", options.isLandscape());
                params.put("printBackground", options.isPrintBackground());
                params.put("scale", options.getScale());
                params.put("paperWidth", options.getPaperWidth());
                params.put("paperHeight", options.getPaperHeight());
                params.put("marginTop", options.getMarginTop());
                params.put("marginBottom", options.getMarginBottom());
                params.put("marginLeft", options.getMarginLeft());
                params.put("marginRight", options.getMarginRight());
                params.put("pageRanges", options.getPageRanges());
                params.put("preferCSSPageSize", options.isPreferCSSPageSize());
            }

            logger.info("正在请求生成PDF数据...");
            // 生成PDF
            JsonObject response = sendCommand("Page.printToPDF", params);

            if (response.has("error")) {
                String errorMsg = response.get("error").toString();
                logger.error("Chrome返回的错误: {}", errorMsg);
                throw new PdfGenerationException("生成PDF失败: " + errorMsg);
            }

            // 检查response的内容
            logger.debug("Chrome响应: {}", response);

            // 从Base64编码获取PDF数据
            String base64Data = response.getAsJsonObject("result").get("data").getAsString();
            byte[] pdfData = Base64.getDecoder().decode(base64Data);

            logger.info("PDF数据生成成功: {} 字节", pdfData.length);
            return pdfData;

        } catch (Exception e) {
            logger.error("生成PDF数据过程中发生异常", e);
            throw new PdfGenerationException("生成PDF数据失败: " + e.getMessage(), e);
        }
    }

    /**
     * 发送命令到Chrome DevTools Protocol
     */
    private JsonObject sendCommand(String method) throws Exception {
        return sendCommand(method, null);
    }

    /**
     * 发送带参数的命令到Chrome DevTools Protocol
     */
    private JsonObject sendCommand(String method, Map<String, Object> params) throws Exception {
        int id = requestId.getAndIncrement();

        // 创建命令对象
        Map<String, Object> command = new HashMap<>();
        command.put("id", id);
        command.put("method", method);

        if (params != null) {
            command.put("params", params);
        } else {
            command.put("params", new HashMap<>());
        }

        // 创建Future对象等待响应
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        // 发送命令
        String commandJson = gson.toJson(command);
        logger.info("发送命令: {}，ID: {}", method, id);
        send(commandJson);

        // 等待响应，设置超时
        try {
            logger.info("等待命令响应: {}，ID: {}", method, id);
            JsonObject response = future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            logger.info("收到命令响应: {}，ID: {}", method, id);
            return response;
        } catch (Exception e) {
            logger.error("命令执行超时或错误: {}，ID: {}", method, id, e);
            pendingRequests.remove(id);
            throw e;
        }
    }

    /**
     * PDF生成选项
     */
    public static class PdfOptions {
        private boolean landscape = false;
        private boolean printBackground = true;
        private double scale = 1.0;
        private double paperWidth = 8.5;
        private double paperHeight = 11.0;
        private double marginTop = 0.4;
        private double marginBottom = 0.4;
        private double marginLeft = 0.4;
        private double marginRight = 0.4;
        private String pageRanges = "";
        private boolean preferCSSPageSize = false;

        public PdfOptions() {
        }

        public boolean isLandscape() {
            return landscape;
        }

        public PdfOptions setLandscape(boolean landscape) {
            this.landscape = landscape;
            return this;
        }

        public boolean isPrintBackground() {
            return printBackground;
        }

        public PdfOptions setPrintBackground(boolean printBackground) {
            this.printBackground = printBackground;
            return this;
        }

        public double getScale() {
            return scale;
        }

        public PdfOptions setScale(double scale) {
            this.scale = scale;
            return this;
        }

        public double getPaperWidth() {
            return paperWidth;
        }

        public PdfOptions setPaperWidth(double paperWidth) {
            this.paperWidth = paperWidth;
            return this;
        }

        public double getPaperHeight() {
            return paperHeight;
        }

        public PdfOptions setPaperHeight(double paperHeight) {
            this.paperHeight = paperHeight;
            return this;
        }

        public double getMarginTop() {
            return marginTop;
        }

        public PdfOptions setMarginTop(double marginTop) {
            this.marginTop = marginTop;
            return this;
        }

        public double getMarginBottom() {
            return marginBottom;
        }

        public PdfOptions setMarginBottom(double marginBottom) {
            this.marginBottom = marginBottom;
            return this;
        }

        public double getMarginLeft() {
            return marginLeft;
        }

        public PdfOptions setMarginLeft(double marginLeft) {
            this.marginLeft = marginLeft;
            return this;
        }

        public double getMarginRight() {
            return marginRight;
        }

        public PdfOptions setMarginRight(double marginRight) {
            this.marginRight = marginRight;
            return this;
        }

        public String getPageRanges() {
            return pageRanges;
        }

        public PdfOptions setPageRanges(String pageRanges) {
            this.pageRanges = pageRanges;
            return this;
        }

        public boolean isPreferCSSPageSize() {
            return preferCSSPageSize;
        }

        public PdfOptions setPreferCSSPageSize(boolean preferCSSPageSize) {
            this.preferCSSPageSize = preferCSSPageSize;
            return this;
        }
    }

    /**
     * 启用Network域，监听网络请求
     */
    public void enableNetwork() throws ConnectionException {
        try {
            logger.info("启用Network域");
            sendCommand("Network.enable");
        } catch (Exception e) {
            throw new ConnectionException("启用Network域失败", e);
        }
    }
} 