package com.pizhai;

import com.pizhai.exception.HtmlToPdfException;
import com.pizhai.pool.DefaultChromeConnectionPool;
import com.pizhai.pool.SharedConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        // 检查日志配置是否有效
        checkLoggingConfig();

        // 获取HTML文件路径
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

        // 示例1: 单例模式 - 不使用连接池
//        singleInstanceExample(htmlFilePath, outputPdfPath, chromePath);

        // 示例2: 连接池模式 - 默认配置
//        connectionPoolExample(htmlFilePath, outputPdfPath, chromePath);

        // 示例3: 连接池模式 - 自定义配置
//        customPoolExample(htmlFilePath, outputPdfPath, chromePath);

        // 示例4: 使用共享连接池和单例模式
//        sharedPoolExample(htmlFilePath, outputPdfPath, chromePath);

        // 示例5: 使用环境自动配置
        environmentAutoConfigExample(htmlFilePath, outputPdfPath);
    }

    /**
     * 检查日志配置是否正确加载
     */
    private static void checkLoggingConfig() {
        // 输出一条测试日志消息
        logger.info("====== 程序开始 ======");
        logger.debug("测试DEBUG级别日志");

        // 输出SLF4J绑定信息
        try {
            Class<?> loggerFactoryClass = org.slf4j.LoggerFactory.getILoggerFactory().getClass();
            logger.info("SLF4J绑定: {}", loggerFactoryClass.getName());

            // 如果没有看到此消息，说明日志配置有问题
            System.out.println("=====================================");
            System.out.println("如果您能看到此消息但看不到上面的日志消息，");
            System.out.println("则说明SLF4J配置有问题，请检查pom.xml和日志配置文件");
            System.out.println("=====================================");
        } catch (Exception e) {
            System.out.println("SLF4J未正确绑定: " + e.getMessage());
        }
    }

    /**
     * 单例模式示例（每次转换都创建新的Chrome实例）
     */
    private static void singleInstanceExample(String htmlFilePath, String outputPdfPath, String chromePath) {
        logger.info("=== 示例1: 单例模式 ===");

        long startTime = System.currentTimeMillis();

        try (HtmlToPdfConverter converter = HtmlToPdfConverter.builder()
                .chromePath(chromePath)
                .build()) {

            converter.convert(htmlFilePath, outputPdfPath);
            logger.info("转换成功: {}", outputPdfPath);

            // 测试转换为字节数组
            byte[] pdfData = converter.convertToByteArray(htmlFilePath);
            logger.info("PDF字节数组大小: {} 字节", pdfData.length);

        } catch (Exception e) {
            handleException(e);
        }

        long endTime = System.currentTimeMillis();
        logger.info("单例模式耗时: {} 毫秒", (endTime - startTime));
    }

    /**
     * 连接池模式示例（使用默认配置）
     */
    private static void connectionPoolExample(String htmlFilePath, String outputPdfPath, String chromePath) {
        logger.info("=== 示例2: 连接池模式（默认配置）===");

        long startTime = System.currentTimeMillis();

        try (HtmlToPdfConverter converter = HtmlToPdfConverter.builder()
                .chromePath(chromePath)
                .useConnectionPool(true) // 启用连接池
                .build()) {

            // 进行多次转换，测试连接池复用效果
            for (int i = 0; i < 3; i++) {
                String output = outputPdfPath.replace(".pdf", "_pool_" + i + ".pdf");
                converter.convert(htmlFilePath, output);
                logger.info("转换 #{} 成功: {}", i + 1, output);
            }

        } catch (Exception e) {
            handleException(e);
        }

        long endTime = System.currentTimeMillis();
        logger.info("连接池模式（默认配置）耗时: {} 毫秒", (endTime - startTime));
    }

    /**
     * 连接池模式示例（自定义配置）
     */
    private static void customPoolExample(String htmlFilePath, String outputPdfPath, String chromePath) {
        logger.info("=== 示例3: 连接池模式（自定义配置）===");

        long startTime = System.currentTimeMillis();

        // 自定义连接池
        try (DefaultChromeConnectionPool pool = DefaultChromeConnectionPool.builder()
                .chromePath(chromePath)
                .minConnections(2)       // 最小保持2个连接
                .maxConnections(5)       // 最多5个连接
                .idleTimeout(30, TimeUnit.SECONDS) // 30秒空闲超时
                .connectionTimeout(10, TimeUnit.SECONDS) // 10秒连接获取超时
                .build()) {

            // 使用自定义连接池创建转换器
            try (HtmlToPdfConverter converter = HtmlToPdfConverter.builder()
                    .connectionPool(pool) // 使用自定义连接池
                    .useConnectionPool(true)
                    .build()) {

                logger.info("初始连接池状态: {}", pool.getPoolStats());

                // 创建多个线程同时进行转换，测试排队功能
                int numTasks = 10; // 创建10个任务，超过最大连接数
                CountDownLatch latch = new CountDownLatch(numTasks);
                List<Thread> threads = new ArrayList<>();

                for (int i = 0; i < numTasks; i++) {
                    final int taskId = i;
                    Thread thread = new Thread(() -> {
                        try {
                            String output = outputPdfPath.replace(".pdf", "_custom_" + taskId + ".pdf");

                            // 使用不同的PDF选项
                            HtmlToPdfConverter.PdfOptions options = HtmlToPdfConverter.PdfOptions.builder()
                                    .landscape(taskId % 2 == 0) // 偶数使用横向
                                    .printBackground(true)
                                    .scale(1.0)
                                    .build();

                            logger.info("任务 #{} 开始执行", taskId + 1);
                            converter.convert(htmlFilePath, output, options);
                            logger.info("任务 #{} 成功: {} ({}向)", taskId + 1, output, taskId % 2 == 0 ? "横" : "纵");
                        } catch (Exception e) {
                            logger.error("任务 #{} 失败: {}", taskId + 1, e.getMessage());
                        } finally {
                            latch.countDown();
                        }
                    });
                    threads.add(thread);
                }

                // 启动所有线程
                for (Thread thread : threads) {
                    thread.start();
                    Thread.sleep(200); // 稍微错开启动时间，便于观察
                }

                // 每隔一秒输出连接池状态
                Thread monitorThread = new Thread(() -> {
                    try {
                        while (!latch.await(1, TimeUnit.SECONDS)) {
                            logger.info("连接池状态: {}", pool.getPoolStats());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                monitorThread.setDaemon(true);
                monitorThread.start();

                // 等待所有任务完成
                latch.await();

                logger.info("所有任务完成，最终连接池状态: {}", pool.getPoolStats());

            } catch (Exception e) {
                handleException(e);
            }

        } catch (Exception e) {
            handleException(e);
        }

        long endTime = System.currentTimeMillis();
        logger.info("连接池模式（自定义配置）耗时: {} 毫秒", (endTime - startTime));
    }

    /**
     * 使用共享连接池和单例模式示例
     */
    private static void sharedPoolExample(String htmlFilePath, String outputPdfPath, String chromePath) {
        logger.info("=== 示例4: 共享连接池和单例模式 ===");

        // 方式1: 直接配置
        // 配置共享连接池
        HtmlToPdfConverter.Config.setChromePath(chromePath);
        HtmlToPdfConverter.Config.setMinConnections(2);
        HtmlToPdfConverter.Config.setMaxConnections(5);
        HtmlToPdfConverter.Config.setIdleTimeout(60, TimeUnit.SECONDS);

        // 方式2: 从配置文件加载
        // HtmlToPdfConverter.Config.loadFromResource("html2pdf.properties");

        long startTime = System.currentTimeMillis();

        // 方式1：使用单例模式获取实例
        try {
            // 获取单例实例
            HtmlToPdfConverter converter = HtmlToPdfConverter.getInstance();

            // 使用单例转换
            String output1 = outputPdfPath.replace(".pdf", "_shared_1.pdf");
            converter.convert(htmlFilePath, output1);
            logger.info("单例转换成功: {}", output1);

            // 在不同的地方再次获取单例（实际是同一个实例）
            HtmlToPdfConverter sameConverter = HtmlToPdfConverter.getInstance();
            String output2 = outputPdfPath.replace(".pdf", "_shared_2.pdf");
            sameConverter.convert(htmlFilePath, output2);
            logger.info("单例是否相同: {}", converter == sameConverter);

            // 输出共享连接池状态
            logger.info("共享连接池状态: {}", SharedConnectionPool.getPoolStats());

        } catch (Exception e) {
            handleException(e);
        }

        // 方式2：使用静态方法直接转换（使用相同的共享连接池）
        try {
            // 直接使用静态方法
            String output3 = outputPdfPath.replace(".pdf", "_static_1.pdf");
            HtmlToPdfConverter.convertStatic(htmlFilePath, output3);
            logger.info("静态方法转换成功: {}", output3);

            // 使用自定义选项
            String output4 = outputPdfPath.replace(".pdf", "_static_2.pdf");
            HtmlToPdfConverter.PdfOptions options = HtmlToPdfConverter.PdfOptions.builder()
                    .landscape(true)
                    .printBackground(true)
                    .build();
            HtmlToPdfConverter.convertStatic(htmlFilePath, output4, options);
            logger.info("静态方法转换成功(带选项): {}", output4);

            // 转换为字节数组
            byte[] pdfData = HtmlToPdfConverter.convertToBytes(htmlFilePath);
            logger.info("静态方法转换为字节数组成功: {} 字节", pdfData.length);

            // 输出共享连接池状态
            logger.info("共享连接池状态: {}", SharedConnectionPool.getPoolStats());

        } catch (Exception e) {
            handleException(e);
        }

        // 方式3：多线程环境下使用
        try {
            int numTasks = 5;
            CountDownLatch latch = new CountDownLatch(numTasks);

            for (int i = 0; i < numTasks; i++) {
                final int taskId = i;
                new Thread(() -> {
                    try {
                        String output = outputPdfPath.replace(".pdf", "_shared_thread_" + taskId + ".pdf");
                        logger.info("线程 #{} 开始转换", taskId);

                        // 使用单例实例或静态方法都可以
                        if (taskId % 2 == 0) {
                            // 使用单例实例
                            HtmlToPdfConverter.getInstance().convert(htmlFilePath, output);
                        } else {
                            // 使用静态方法
                            HtmlToPdfConverter.convertStatic(htmlFilePath, output);
                        }

                        logger.info("线程 #{} 转换完成: {}", taskId, output);
                    } catch (Exception e) {
                        logger.error("线程 #{} 转换失败: {}", taskId, e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            // 输出共享连接池状态
            while (!latch.await(1, TimeUnit.SECONDS)) {
                logger.info("共享连接池状态: {}", SharedConnectionPool.getPoolStats());
            }

            logger.info("所有线程转换完成，最终连接池状态: {}", SharedConnectionPool.getPoolStats());

        } catch (Exception e) {
            handleException(e);
        }

        long endTime = System.currentTimeMillis();
        logger.info("共享连接池和单例模式耗时: {} 毫秒", (endTime - startTime));
    }

    /**
     * 使用环境自动配置示例
     */
    private static void environmentAutoConfigExample(String htmlFilePath, String outputPdfPath) {
        logger.info("=== 示例5: 环境自动配置（延迟加载版）===");

        long startTime = System.currentTimeMillis();

        try {
            // 配置共享连接池参数 - 关键修改：初始连接数设为0，避免启动时创建连接
            HtmlToPdfConverter.Config.setMinConnections(1);  // 初始0个连接
            HtmlToPdfConverter.Config.setMaxConnections(5);  // 最大5个连接

            // 设置连接超时，防止无限等待
            HtmlToPdfConverter.Config.setIdleTimeout(30, java.util.concurrent.TimeUnit.SECONDS);
            // 设置连接池获取连接的超时时间（使用共享连接池的内部方法）
            com.pizhai.pool.SharedConnectionPool.Config.setConnectionTimeout(30, java.util.concurrent.TimeUnit.SECONDS);

            // 自动配置Chrome环境
            com.pizhai.util.ChromeEnvironment.autoConfig();

            // 使用Chrome环境检查验证
            boolean envOk = com.pizhai.util.ChromeEnvironment.checkEnvironment();
            logger.info("Chrome环境检查: {}", envOk ? "通过" : "失败");

            if (!envOk) {
                logger.warn("Chrome环境检查失败，但仍将尝试进行转换");
            }

            // 转换PDF - 延迟加载方式
            logger.info("开始PDF转换...");
            String outputFile = outputPdfPath.replace(".pdf", "_auto.pdf");
            HtmlToPdfConverter.convertStatic(htmlFilePath, outputFile);

            logger.info("转换成功: {}", outputFile);

            // 连接池现在已创建，可以进行额外操作
            logger.info("共享连接池状态: {}", com.pizhai.pool.SharedConnectionPool.getPoolStats());

        } catch (Exception e) {
            handleException(e);
        }

        long endTime = System.currentTimeMillis();
        logger.info("环境自动配置模式耗时: {} 毫秒", (endTime - startTime));
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