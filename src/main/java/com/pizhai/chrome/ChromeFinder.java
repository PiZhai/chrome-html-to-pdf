package com.pizhai.chrome;

import com.pizhai.exception.HtmlToPdfException.ChromeNotFoundException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Chrome浏览器查找工具类
 */
public class ChromeFinder {

    private static final List<String> WINDOWS_CHROME_PATHS = Arrays.asList(
            "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
            "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
            System.getProperty("user.home") + "\\AppData\\Local\\Google\\Chrome\\Application\\chrome.exe"
    );

    private static final List<String> MAC_CHROME_PATHS = Arrays.asList(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            System.getProperty("user.home") + "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
    );

    private static final List<String> LINUX_CHROME_PATHS = Arrays.asList(
            "/usr/bin/google-chrome",
            "/usr/bin/google-chrome-stable",
            "/usr/bin/chromium-browser",
            "/usr/bin/chromium"
    );

    /**
     * 获取Chrome可执行文件路径
     * @param userSpecifiedPath 用户指定的Chrome路径，可为null
     * @return Chrome可执行文件的路径
     * @throws ChromeNotFoundException 如果无法找到Chrome
     */
    public static String findChrome(String userSpecifiedPath) throws ChromeNotFoundException {
        // 1. 如果用户指定了路径，优先使用
        if (userSpecifiedPath != null && !userSpecifiedPath.trim().isEmpty()) {
            File chromeFile = new File(userSpecifiedPath);
            if (chromeFile.exists() && chromeFile.canExecute()) {
                return userSpecifiedPath;
            } else {
                throw new ChromeNotFoundException("指定的Chrome路径无效: " + userSpecifiedPath);
            }
        }

        // 2. 根据操作系统查找默认位置
        String os = System.getProperty("os.name").toLowerCase();
        List<String> possiblePaths = new ArrayList<>();

        if (os.contains("win")) {
            possiblePaths.addAll(WINDOWS_CHROME_PATHS);
        } else if (os.contains("mac")) {
            possiblePaths.addAll(MAC_CHROME_PATHS);
        } else if (os.contains("linux")) {
            possiblePaths.addAll(LINUX_CHROME_PATHS);
        }

        for (String path : possiblePaths) {
            File chromeFile = new File(path);
            if (chromeFile.exists() && chromeFile.canExecute()) {
                return path;
            }
        }

        // 3. 尝试通过命令查找
        try {
            String chromePath = findChromeUsingCommand();
            if (chromePath != null) {
                return chromePath;
            }
        } catch (Exception e) {
            // 忽略命令查找错误，继续尝试其他方法
        }

        throw new ChromeNotFoundException("无法找到Chrome浏览器。请使用参数指定Chrome路径。");
    }

    /**
     * 通过系统命令查找Chrome
     */
    private static String findChromeUsingCommand() throws IOException, InterruptedException {
        String os = System.getProperty("os.name").toLowerCase();
        ProcessBuilder builder;

        if (os.contains("win")) {
            builder = new ProcessBuilder("where", "chrome.exe");
        } else {
            builder = new ProcessBuilder("which", "google-chrome");
        }

        Process process = builder.start();
        int exitCode = process.waitFor();

        if (exitCode == 0) {
            java.util.Scanner s = new java.util.Scanner(process.getInputStream()).useDelimiter("\\A");
            String result = s.hasNext() ? s.next().trim() : "";
            if (!result.isEmpty() && new File(result).exists()) {
                return result;
            }
        }

        return null;
    }
} 