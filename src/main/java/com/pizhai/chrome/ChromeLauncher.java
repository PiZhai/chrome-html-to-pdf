package com.pizhai.chrome;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pizhai.exception.HtmlToPdfException.ConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chrome浏览器启动管理类
 */
public class ChromeLauncher {
    
    private static final Logger logger = LoggerFactory.getLogger(ChromeLauncher.class);
    private static final Pattern WS_URL_PATTERN = Pattern.compile("DevTools listening on (ws://[^\\s]+)");
    private Process chromeProcess;
    private String webSocketDebuggerUrl;
    private static final Gson gson = new Gson();
    
    /**
     * 启动Chrome浏览器的调试模式
     *
     * @param chromePath Chrome可执行文件路径
     * @param remoteDebuggingPort 远程调试端口
     * @throws ConnectionException 如果启动Chrome失败
     */
    public void launch(String chromePath, int remoteDebuggingPort) throws ConnectionException {
        try {
            List<String> command = new ArrayList<>();
            command.add(chromePath);
            command.add("--headless");  // 无头模式
            command.add("--disable-gpu");
            command.add("--no-sandbox");
            command.add("--disable-web-security");
            command.add("--allow-file-access-from-files"); // 允许文件访问
            command.add("--disable-extensions"); // 禁用扩展
            command.add("--disable-popup-blocking"); // 禁用弹窗阻止
            command.add("--disable-translate"); // 禁用翻译
            command.add("--remote-debugging-port=" + remoteDebuggingPort);
            command.add("about:blank");  // 打开空白页
            
            logger.debug("启动Chrome命令: {}", String.join(" ", command));
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            
            chromeProcess = processBuilder.start();
            
            // 读取Chrome输出，确认启动成功
            boolean debuggerStarted = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(chromeProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("Chrome输出: {}", line); // 以调试级别记录Chrome的输出
                    Matcher matcher = WS_URL_PATTERN.matcher(line);
                    if (matcher.find()) {
                        debuggerStarted = true;
                        break;
                    }
                }
            }
            
            if (!debuggerStarted) {
                throw new ConnectionException("无法确认Chrome调试器启动");
            }
            
            // 等待Chrome完全启动
            Thread.sleep(1000);
            
            // 获取页面级别的WebSocket URL
            webSocketDebuggerUrl = getPageWebSocketUrl(remoteDebuggingPort);
            
            if (webSocketDebuggerUrl == null) {
                throw new ConnectionException("无法获取Chrome页面的WebSocket调试URL");
            }
            
            logger.info("使用页面WebSocket URL: {}", webSocketDebuggerUrl);
            
        } catch (IOException e) {
            throw new ConnectionException("启动Chrome失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectionException("启动Chrome过程被中断", e);
        }
    }
    
    /**
     * 获取页面级别的WebSocket URL
     * 
     * @param debuggingPort 调试端口
     * @return 页面级别的WebSocket URL
     * @throws IOException 如果HTTP请求失败
     */
    private String getPageWebSocketUrl(int debuggingPort) throws IOException {
        // 首先获取标签页列表
        URL url = new URL("http://localhost:" + debuggingPort + "/json/list");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        
        logger.debug("请求标签页列表: {}", url);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            
            // 解析JSON响应
            JsonArray tabs = gson.fromJson(response.toString(), JsonArray.class);
            logger.debug("获取到{}个标签页", tabs.size());
            
            // 寻找第一个可用的页面
            for (JsonElement tab : tabs) {
                JsonObject tabObj = tab.getAsJsonObject();
                
                // 检查是否是页面类型
                if (tabObj.has("type") && tabObj.get("type").getAsString().equals("page")) {
                    // 如果有页面，返回其WebSocket URL
                    if (tabObj.has("webSocketDebuggerUrl")) {
                        String wsUrl = tabObj.get("webSocketDebuggerUrl").getAsString();
                        logger.debug("找到页面WebSocket URL: {}", wsUrl);
                        return wsUrl;
                    }
                }
            }
            
            logger.debug("没有找到现有页面，创建新标签页");
            // 如果没有找到页面，创建一个新页面
            return createNewTab(debuggingPort);
        }
    }
    
    /**
     * 创建一个新标签页并返回其WebSocket URL
     */
    private String createNewTab(int debuggingPort) throws IOException {
        URL url = new URL("http://localhost:" + debuggingPort + "/json/new");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        
        logger.debug("创建新标签页: {}", url);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            
            JsonObject newTab = gson.fromJson(response.toString(), JsonObject.class);
            if (newTab.has("webSocketDebuggerUrl")) {
                String wsUrl = newTab.get("webSocketDebuggerUrl").getAsString();
                logger.debug("创建的新标签页WebSocket URL: {}", wsUrl);
                return wsUrl;
            }
        }
        
        return null;
    }
    
    /**
     * 获取WebSocket调试URL
     */
    public String getWebSocketDebuggerUrl() {
        return webSocketDebuggerUrl;
    }
    
    /**
     * 关闭Chrome进程
     */
    public void close() {
        if (chromeProcess != null && chromeProcess.isAlive()) {
            logger.debug("关闭Chrome进程");
            chromeProcess.destroy();
            try {
                // 等待进程结束
                if (!chromeProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    logger.warn("Chrome进程未在5秒内关闭，强制终止");
                    chromeProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("等待Chrome关闭时被中断，强制终止进程", e);
                chromeProcess.destroyForcibly();
            }
        }
    }
} 