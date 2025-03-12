package com.pizhai.util;

import com.pizhai.HtmlToPdfConverter;
import com.pizhai.chrome.ChromeFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Chrome环境检测和自动配置工具
 * 用于启动时自动检测和配置Chrome环境
 */
public class ChromeEnvironment {

    private static final Logger logger = LoggerFactory.getLogger(ChromeEnvironment.class);

    private static final String ENV_CHROME_PATH = "CHROME_PATH";
    private static final String PROP_CHROME_PATH = "chrome.path";

    private ChromeEnvironment() {
        // 工具类，防止实例化
    }

    /**
     * 自动检测并配置Chrome环境
     * 按以下顺序查找Chrome路径:
     * 1. 环境变量 CHROME_PATH
     * 2. 系统属性 chrome.path
     * 3. 自动搜索默认位置
     *
     * @return 是否成功配置
     */
    public static boolean autoConfig() {
        try {
            String chromePath = findChromePath();
            if (chromePath != null) {
                HtmlToPdfConverter.Config.setChromePath(chromePath);
                logger.info("自动配置Chrome路径: {}", chromePath);
                return true;
            }
            logger.warn("未能自动配置Chrome路径，将使用默认搜索机制");
            return false;
        } catch (Exception e) {
            logger.error("自动配置Chrome环境失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 查找Chrome路径
     */
    private static String findChromePath() {
        // 1. 尝试从环境变量获取
        String chromePath = System.getenv(ENV_CHROME_PATH);
        if (isValidChromePath(chromePath)) {
            logger.info("从环境变量获取Chrome路径: {}", chromePath);
            return chromePath;
        }

        // 2. 尝试从系统属性获取
        chromePath = System.getProperty(PROP_CHROME_PATH);
        if (isValidChromePath(chromePath)) {
            logger.info("从系统属性获取Chrome路径: {}", chromePath);
            return chromePath;
        }

        // 3. 尝试自动搜索
        try {
            chromePath = ChromeFinder.findChrome(null);
            if (isValidChromePath(chromePath)) {
                logger.info("自动搜索到Chrome路径: {}", chromePath);
                return chromePath;
            }
        } catch (Exception e) {
            logger.warn("自动搜索Chrome路径失败: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 验证Chrome路径是否有效
     */
    private static boolean isValidChromePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return false;
        }

        File file = new File(path);
        return file.exists() && file.isFile() && file.canExecute();
    }

    /**
     * 检查环境是否支持HTML转PDF
     * 主要检查Chrome浏览器是否可用
     *
     * @return 是否支持
     */
    public static boolean checkEnvironment() {
        try {
            // 获取配置的Chrome路径或自动搜索
            String chromePath = findChromePath();

            if (chromePath == null) {
                logger.warn("无法找到Chrome浏览器");
                return false;
            }

            File chromeFile = new File(chromePath);
            if (!chromeFile.exists()) {
                logger.warn("Chrome浏览器文件不存在: {}", chromePath);
                return false;
            }

            if (!chromeFile.canExecute()) {
                logger.warn("Chrome浏览器文件没有执行权限: {}", chromePath);
                return false;
            }

            logger.info("环境检查通过，Chrome浏览器可用: {}", chromePath);
            return true;
        } catch (Exception e) {
            logger.error("环境检查失败: {}", e.getMessage());
            return false;
        }
    }
} 