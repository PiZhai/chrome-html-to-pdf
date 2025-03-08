package com.pizhai;

import com.pizhai.exception.HtmlToPdfException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * HTML转PDF工具示例
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    static {
        // 配置SLF4J Simple日志级别
        java.util.Properties properties = new java.util.Properties();
        properties.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");
        properties.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        properties.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss.SSS");
        properties.setProperty("org.slf4j.simpleLogger.showThreadName", "true");
        properties.setProperty("org.slf4j.simpleLogger.showLogName", "true");
        properties.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");
        
        // 设置各包的日志级别
        properties.setProperty("org.slf4j.simpleLogger.log.org.example.cdp", "info");
        properties.setProperty("org.slf4j.simpleLogger.log.org.example.chrome", "info");
        properties.setProperty("org.slf4j.simpleLogger.log.org.java_websocket", "warn");

        // 应用配置
        for (String name : properties.stringPropertyNames()) {
            System.setProperty(name, properties.getProperty(name));
        }
    }

    public static void main(String[] args) {
        // 解析命令行参数
        String htmlFilePath = getHtmlFilePath(args);
        String outputPdfPath = getOutputPdfPath(args, htmlFilePath);
        String chromePath = args.length > 2 ? args[2] : null;

        logger.info("HTML文件: {}", htmlFilePath);
        logger.info("输出PDF: {}", outputPdfPath);
        logger.info("Chrome路径: {}", chromePath != null ? chromePath : "自动检测");

        // 检查HTML文件是否存在
        File htmlFile = new File(htmlFilePath);
        if (!htmlFile.exists() || !htmlFile.isFile()) {
            logger.error("HTML文件不存在: {}", htmlFilePath);
            return;
        }

        // 示例1: 使用构建器创建转换器并使用默认PDF选项
        try (HtmlToPdfConverter converter = HtmlToPdfConverter.builder()
                .chromePath(chromePath)
                .build()) {

            converter.convert(htmlFilePath, outputPdfPath);
            logger.info("转换成功 (默认选项): {}", outputPdfPath);

        } catch (Exception e) {
            handleException(e);
        }

        // 示例2: 使用自定义PDF选项
        String customOutputPath = outputPdfPath.replace(".pdf", "_landscape.pdf");
        try (HtmlToPdfConverter converter = HtmlToPdfConverter.builder()
                .chromePath(chromePath)
                .build()) {

            HtmlToPdfConverter.PdfOptions options = HtmlToPdfConverter.PdfOptions.builder()
                    .landscape(true)
                    .printBackground(true)
                    .scale(1.0)
                    .marginTop(0.5)
                    .marginBottom(0.5)
                    .marginLeft(0.5)
                    .marginRight(0.5)
                    .build();

            converter.convert(htmlFilePath, customOutputPath, options);
            logger.info("转换成功 (自定义选项): {}", customOutputPath);

        } catch (Exception e) {
            handleException(e);
        }
    }

    /**
     * 处理异常
     */
    private static void handleException(Exception e) {
        if (e instanceof HtmlToPdfException.ChromeNotFoundException) {
            logger.error("找不到Chrome浏览器: {}", e.getMessage());
        } else if (e instanceof HtmlToPdfException.ConnectionException) {
            logger.error("连接Chrome失败: {}", e.getMessage());
        } else if (e instanceof HtmlToPdfException.PageNavigationException) {
            logger.error("页面导航失败: {}", e.getMessage());
        } else if (e instanceof HtmlToPdfException.PdfGenerationException) {
            logger.error("PDF生成失败: {}", e.getMessage());
        } else if (e instanceof HtmlToPdfException) {
            logger.error("HTML转PDF失败: {}", e.getMessage());
        } else {
            logger.error("未知错误: {}", e.getMessage());
        }
        logger.error("异常详情", e);
    }

    /**
     * 获取HTML文件路径
     */
    private static String getHtmlFilePath(String[] args) {
        // 优先使用命令行参数
        if (args.length > 0 && args[0] != null && !args[0].isEmpty()) {
            return args[0];
        }

        // 否则使用测试HTML文件
        String testHtmlPath = "src/main/resources/test.html";
        File testFile = new File(testHtmlPath);

        if (testFile.exists() && testFile.isFile()) {
            logger.debug("使用测试HTML文件: {}", testFile.getAbsolutePath());
            return testFile.getAbsolutePath();
        }

        // 最后使用临时路径（可能不存在）
        return System.getProperty("java.io.tmpdir") + File.separator + "example.html";
    }

    /**
     * 获取输出PDF路径
     */
    private static String getOutputPdfPath(String[] args, String htmlFilePath) {
        // 优先使用命令行参数
        if (args.length > 1 && args[1] != null && !args[1].isEmpty()) {
            return args[1];
        }

        // 否则基于HTML文件路径生成
        File htmlFile = new File(htmlFilePath);
        String fileName = htmlFile.getName();
        String baseName = fileName.contains(".") ?
                fileName.substring(0, fileName.lastIndexOf('.')) : fileName;

        return new File(htmlFile.getParent(), baseName + ".pdf").getAbsolutePath();
    }
}