package com.pizhai;

import com.pizhai.cdp.ChromeDevToolsClient;
import com.pizhai.chrome.ChromeFinder;
import com.pizhai.chrome.ChromeLauncher;
import com.pizhai.exception.HtmlToPdfException;
import com.pizhai.pool.ChromeConnectionPool;
import com.pizhai.pool.DefaultChromeConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * HTML转PDF转换器
 * 使用Chrome DevTools Protocol通过WebSocket连接将HTML文件转换为PDF
 *
 * <p>示例用法:</p>
 * <pre>
 * // 使用默认设置
 * try (HtmlToPdfConverter converter = HtmlToPdfConverter.builder().build()) {
 *     converter.convert("input.html", "output.pdf");
 * }
 *
 * // 使用自定义设置
 * try (HtmlToPdfConverter converter = HtmlToPdfConverter.builder()
 *         .chromePath("C:/path/to/chrome.exe")
 *         .debuggingPort(9223)
 *         .build()) {
 *
 *     PdfOptions options = PdfOptions.builder()
 *         .landscape(true)
 *         .printBackground(true)
 *         .scale(1.2)
 *         .build();
 *
 *     converter.convert("input.html", "output.pdf", options);
 * }
 * </pre>
 */
public class HtmlToPdfConverter implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(HtmlToPdfConverter.class);
    private static final int DEFAULT_DEBUGGING_PORT = 9222;

    // 单例模式
    private final ChromeLauncher chromeLauncher;
    private ChromeDevToolsClient devToolsClient;
    private final String chromePath;
    private final int debuggingPort;

    // 连接池模式
    private final ChromeConnectionPool connectionPool;
    private final boolean usePool;

    /**
     * 私有构造函数，通过构建器创建实例
     */
    private HtmlToPdfConverter(Builder builder) {
        this.chromePath = builder.chromePath;
        this.debuggingPort = builder.debuggingPort;

        // 根据配置决定使用单例模式还是连接池模式
        if (builder.useConnectionPool) {
            this.usePool = true;
            this.connectionPool = builder.connectionPool != null ?
                    builder.connectionPool :
                    DefaultChromeConnectionPool.builder()
                            .chromePath(chromePath)
                            .basePort(debuggingPort)
                            .maxConnections(builder.poolMaxConnections)
                            .idleTimeout(builder.poolIdleTimeout, builder.poolIdleTimeoutUnit)
                            .build();
            this.chromeLauncher = null;
            this.devToolsClient = null;
        } else {
            this.usePool = false;
            this.connectionPool = null;
            this.chromeLauncher = new ChromeLauncher();
            initialize();
        }
    }

    /**
     * 初始化Chrome浏览器和WebSocket连接（单例模式）
     */
    private void initialize() throws HtmlToPdfException {
        try {
            // 查找Chrome浏览器路径
            String resolvedChromePath = chromePath != null ?
                    chromePath : ChromeFinder.findChrome(null);
            logger.info("使用Chrome浏览器: {}", resolvedChromePath);

            // 启动Chrome浏览器
            chromeLauncher.launch(resolvedChromePath, debuggingPort);

            // 连接WebSocket
            String wsUrl = chromeLauncher.getWebSocketDebuggerUrl();
            this.devToolsClient = new ChromeDevToolsClient(wsUrl);

            // 启用Page域
            devToolsClient.enablePage();

        } catch (Exception e) {
            close();
            logger.error("初始化Chrome失败", e);
            throw new HtmlToPdfException.ConnectionException("连接Chrome DevTools失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取一个ChromeDevToolsClient连接（从连接池或单例）
     */
    private ChromeDevToolsClient getClient() throws HtmlToPdfException {
        if (usePool) {
            return connectionPool.getConnection();
        } else {
            return devToolsClient;
        }
    }

    /**
     * 释放一个ChromeDevToolsClient连接（归还到连接池或不操作）
     */
    private void releaseClient(ChromeDevToolsClient client) {
        if (usePool && client != null) {
            connectionPool.releaseConnection(client);
        }
    }

    /**
     * 将HTML文件转换为PDF
     *
     * @param htmlFilePath  HTML文件路径
     * @param outputPdfPath 输出PDF文件路径
     * @throws HtmlToPdfException 如果转换过程中发生错误
     */
    public void convert(String htmlFilePath, String outputPdfPath) throws HtmlToPdfException {
        convert(htmlFilePath, outputPdfPath, new PdfOptions.Builder().build());
    }

    /**
     * 将HTML文件转换为PDF
     *
     * @param htmlFilePath  HTML文件路径
     * @param outputPdfPath 输出PDF文件路径
     * @param options       PDF生成选项
     * @throws HtmlToPdfException 如果转换过程中发生错误
     */
    public void convert(String htmlFilePath, String outputPdfPath, PdfOptions options) throws HtmlToPdfException {
        File htmlFile = new File(htmlFilePath);
        if (!htmlFile.exists() || !htmlFile.isFile()) {
            logger.error("HTML文件不存在: {}", htmlFilePath);
            throw new HtmlToPdfException("HTML文件不存在: " + htmlFilePath);
        }

        ChromeDevToolsClient client = null;
        try {
            client = getClient();

            // 构建文件URL，确保正确格式化
            String fileUrl = formatFileUrl(htmlFile);
            logger.info("加载HTML文件: {}", fileUrl);

            // 检查输出路径的父目录是否存在
            File outputFile = new File(outputPdfPath);
            File parentDir = outputFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                logger.info("创建输出目录: {}", parentDir.getAbsolutePath());
                if (!parentDir.mkdirs()) {
                    logger.warn("无法创建输出目录: {}", parentDir.getAbsolutePath());
                }
            }

            // 导航到HTML文件
            client.navigateToUrl(fileUrl);

            // 生成PDF
            logger.info("生成PDF文件: {}", outputPdfPath);
            client.generatePdf(outputPdfPath, convertToCdpOptions(options));

            logger.info("PDF生成成功: {}", outputPdfPath);
        } catch (Exception e) {
            logger.error("HTML转PDF失败", e);
            throw new HtmlToPdfException("HTML转PDF失败: " + e.getMessage(), e);
        } finally {
            releaseClient(client);
        }
    }

    /**
     * 将HTML文件转换为PDF字节数组
     *
     * @param htmlFilePath HTML文件路径
     * @return PDF文件的字节数组
     * @throws HtmlToPdfException 如果转换过程中发生错误
     */
    public byte[] convertToByteArray(String htmlFilePath) throws HtmlToPdfException {
        return convertToByteArray(htmlFilePath, new PdfOptions.Builder().build());
    }

    /**
     * 将HTML文件转换为PDF字节数组
     *
     * @param htmlFilePath HTML文件路径
     * @param options      PDF生成选项
     * @return PDF文件的字节数组
     * @throws HtmlToPdfException 如果转换过程中发生错误
     */
    public byte[] convertToByteArray(String htmlFilePath, PdfOptions options) throws HtmlToPdfException {
        File htmlFile = new File(htmlFilePath);
        if (!htmlFile.exists() || !htmlFile.isFile()) {
            logger.error("HTML文件不存在: {}", htmlFilePath);
            throw new HtmlToPdfException("HTML文件不存在: " + htmlFilePath);
        }

        ChromeDevToolsClient client = null;
        try {
            client = getClient();

            // 构建文件URL，确保正确格式化
            String fileUrl = formatFileUrl(htmlFile);
            logger.info("加载HTML文件: {}", fileUrl);

            // 导航到HTML文件
            client.navigateToUrl(fileUrl);

            // 生成PDF
            logger.info("生成PDF数据");
            byte[] pdfData = client.generatePdfAsByteArray(convertToCdpOptions(options));

            logger.info("PDF数据生成成功: {} 字节", pdfData.length);
            return pdfData;
        } catch (Exception e) {
            logger.error("HTML转PDF字节数组失败", e);
            throw new HtmlToPdfException("HTML转PDF字节数组失败: " + e.getMessage(), e);
        } finally {
            releaseClient(client);
        }
    }

    /**
     * 将PdfOptions转换为ChromeDevToolsClient.PdfOptions
     */
    private ChromeDevToolsClient.PdfOptions convertToCdpOptions(PdfOptions options) {
        ChromeDevToolsClient.PdfOptions cdpOptions = new ChromeDevToolsClient.PdfOptions();
        cdpOptions.setLandscape(options.isLandscape())
                .setPrintBackground(options.isPrintBackground())
                .setScale(options.getScale())
                .setPaperWidth(options.getPaperWidth())
                .setPaperHeight(options.getPaperHeight())
                .setMarginTop(options.getMarginTop())
                .setMarginBottom(options.getMarginBottom())
                .setMarginLeft(options.getMarginLeft())
                .setMarginRight(options.getMarginRight())
                .setPageRanges(options.getPageRanges())
                .setPreferCSSPageSize(options.isPreferCSSPageSize());
        return cdpOptions;
    }

    /**
     * 格式化本地文件URL，确保正确处理不同操作系统的路径格式
     *
     * @param file HTML文件
     * @return 格式化的文件URL
     */
    private String formatFileUrl(File file) {
        try {
            // 标准方式获取URL
            String fileUrl = file.toURI().toURL().toString();

            // 处理Windows路径，确保格式正确
            if (File.separatorChar == '\\') {
                // 确保URL中使用正确的斜杠
                if (!fileUrl.startsWith("file:///")) {
                    fileUrl = fileUrl.replace("file:/", "file:///");
                }
            }

            return fileUrl;
        } catch (Exception e) {
            logger.warn("格式化文件URL时出错，使用绝对路径: {}", e.getMessage());
            // 备用方式
            return "file:///" + file.getAbsolutePath().replace('\\', '/');
        }
    }

    /**
     * 关闭资源
     */
    @Override
    public void close() {
        if (usePool) {
            // 关闭连接池
            if (connectionPool instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) connectionPool).close();
                } catch (Exception e) {
                    logger.error("关闭连接池失败", e);
                }
            }
        } else {
            // 关闭WebSocket连接
            if (devToolsClient != null) {
                try {
                    devToolsClient.close();
                } catch (Exception e) {
                    logger.error("关闭WebSocket连接失败", e);
                }
                devToolsClient = null;
            }

            // 关闭Chrome进程
            if (chromeLauncher != null) {
                chromeLauncher.close();
            }
        }
    }

    /**
     * 创建构建器
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 获取默认实例（使用共享连接池）
     *
     * @return 默认的HtmlToPdfConverter实例
     */
    public static HtmlToPdfConverter getInstance() {
        return SingletonHolder.INSTANCE;
    }

    /**
     * 单例持有者（延迟加载）
     */
    private static class SingletonHolder {
        static {
            // 尝试自动配置Chrome环境
            try {
                // 尝试加载配置文件
                try {
                    Config.loadFromResource("html2pdf.properties");
                    logger.info("已从配置文件加载Chrome配置");
                } catch (Exception e) {
                    logger.debug("未找到配置文件，尝试自动检测环境");

                    try {
                        Class<?> envClass = Class.forName("com.pizhai.util.ChromeEnvironment");
                        java.lang.reflect.Method autoConfigMethod = envClass.getMethod("autoConfig");
                        autoConfigMethod.invoke(null);
                    } catch (Exception ex) {
                        logger.debug("自动环境配置不可用，将使用默认设置");
                    }
                }
            } catch (Exception e) {
                logger.debug("配置加载过程中出现异常，将使用默认设置: {}", e.getMessage());
            }
        }

        private static final HtmlToPdfConverter INSTANCE = builder()
                .useSharedConnectionPool()
                .build();
    }

    /**
     * 静态方法：使用共享连接池将HTML转换为PDF
     *
     * @param htmlFilePath HTML文件路径
     * @param outputPdfPath 输出PDF文件路径
     * @throws HtmlToPdfException 如果转换过程中发生错误
     */
    public static void convertStatic(String htmlFilePath, String outputPdfPath) throws HtmlToPdfException {
        getInstance().convert(htmlFilePath, outputPdfPath);
    }

    /**
     * 静态方法：使用共享连接池将HTML转换为PDF
     *
     * @param htmlFilePath HTML文件路径
     * @param outputPdfPath 输出PDF文件路径
     * @param options PDF生成选项
     * @throws HtmlToPdfException 如果转换过程中发生错误
     */
    public static void convertStatic(String htmlFilePath, String outputPdfPath, PdfOptions options) throws HtmlToPdfException {
        getInstance().convert(htmlFilePath, outputPdfPath, options);
    }

    /**
     * 静态方法：使用共享连接池将HTML转换为PDF字节数组
     *
     * @param htmlFilePath HTML文件路径
     * @return PDF文件的字节数组
     * @throws HtmlToPdfException 如果转换过程中发生错误
     */
    public static byte[] convertToBytes(String htmlFilePath) throws HtmlToPdfException {
        return getInstance().convertToByteArray(htmlFilePath);
    }

    /**
     * 静态方法：使用共享连接池将HTML转换为PDF字节数组
     *
     * @param htmlFilePath HTML文件路径
     * @param options PDF生成选项
     * @return PDF文件的字节数组
     * @throws HtmlToPdfException 如果转换过程中发生错误
     */
    public static byte[] convertToBytes(String htmlFilePath, PdfOptions options) throws HtmlToPdfException {
        return getInstance().convertToByteArray(htmlFilePath, options);
    }

    /**
     * 将HTML字符串转换为PDF字节数组
     *
     * @param htmlContent HTML内容字符串
     * @return PDF文件的字节数组
     * @throws HtmlToPdfException 如果转换过程中发生错误
     */
    public byte[] convertHtmlStringToByteArray(String htmlContent) throws HtmlToPdfException {
        return convertHtmlStringToByteArray(htmlContent, new PdfOptions.Builder().build());
    }

    /**
     * 将HTML字符串转换为PDF字节数组
     *
     * @param htmlContent HTML内容字符串
     * @param options     PDF生成选项
     * @return PDF文件的字节数组
     * @throws HtmlToPdfException 如果转换过程中发生错误
     */
    public byte[] convertHtmlStringToByteArray(String htmlContent, PdfOptions options) throws HtmlToPdfException {
        if (htmlContent == null || htmlContent.trim().isEmpty()) {
            throw new HtmlToPdfException("HTML内容不能为空");
        }

        File tempFile = null;
        try {
            // 创建临时HTML文件
            tempFile = File.createTempFile("html2pdf_", ".html");

            // 写入HTML内容
            try (java.io.FileWriter writer = new java.io.FileWriter(tempFile)) {
                writer.write(htmlContent);
            }

            logger.debug("已将HTML内容写入临时文件: {}", tempFile.getAbsolutePath());

            // 转换为PDF字节数组
            return convertToByteArray(tempFile.getAbsolutePath(), options);

        } catch (Exception e) {
            logger.error("HTML字符串转PDF字节数组失败", e);
            throw new HtmlToPdfException("HTML字符串转PDF字节数组失败: " + e.getMessage(), e);
        } finally {
            // 删除临时文件
            if (tempFile != null && tempFile.exists()) {
                boolean deleted = tempFile.delete();
                if (!deleted) {
                    logger.warn("无法删除临时HTML文件: {}", tempFile.getAbsolutePath());
                    // 在JVM退出时尝试删除
                    tempFile.deleteOnExit();
                }
            }
        }
    }

    /**
     * 静态方法：使用共享连接池将HTML字符串转换为PDF字节数组
     *
     * @param htmlContent HTML内容字符串
     * @return PDF文件的字节数组
     * @throws HtmlToPdfException 如果转换过程中发生错误
     */
    public static byte[] convertHtmlToBytes(String htmlContent) throws HtmlToPdfException {
        return getInstance().convertHtmlStringToByteArray(htmlContent);
    }

    /**
     * 静态方法：使用共享连接池将HTML字符串转换为PDF字节数组
     *
     * @param htmlContent HTML内容字符串
     * @param options     PDF生成选项
     * @return PDF文件的字节数组
     * @throws HtmlToPdfException 如果转换过程中发生错误
     */
    public static byte[] convertHtmlToBytes(String htmlContent, PdfOptions options) throws HtmlToPdfException {
        return getInstance().convertHtmlStringToByteArray(htmlContent, options);
    }

    /**
     * HtmlToPdfConverter的构建器
     */
    public static class Builder {
        private String chromePath;
        private int debuggingPort = DEFAULT_DEBUGGING_PORT;

        // 连接池配置
        private boolean useConnectionPool = false;
        private ChromeConnectionPool connectionPool;
        private int poolMaxConnections = 5;
        private long poolIdleTimeout = 5;
        private java.util.concurrent.TimeUnit poolIdleTimeoutUnit = java.util.concurrent.TimeUnit.MINUTES;

        /**
         * 设置Chrome可执行文件路径
         *
         * @param chromePath Chrome可执行文件的路径
         * @return 构建器实例
         */
        public Builder chromePath(String chromePath) {
            this.chromePath = chromePath;
            return this;
        }

        /**
         * 设置远程调试端口
         *
         * @param debuggingPort 远程调试端口
         * @return 构建器实例
         */
        public Builder debuggingPort(int debuggingPort) {
            this.debuggingPort = debuggingPort;
            return this;
        }

        /**
         * 启用连接池模式
         *
         * @param usePool 是否使用连接池
         * @return 构建器实例
         */
        public Builder useConnectionPool(boolean usePool) {
            this.useConnectionPool = usePool;
            return this;
        }

        /**
         * 设置现有的连接池
         *
         * @param pool 连接池实例
         * @return 构建器实例
         */
        public Builder connectionPool(ChromeConnectionPool pool) {
            this.connectionPool = pool;
            return this;
        }

        /**
         * 设置连接池最大连接数
         *
         * @param maxConnections 最大连接数
         * @return 构建器实例
         */
        public Builder poolMaxConnections(int maxConnections) {
            this.poolMaxConnections = maxConnections;
            return this;
        }

        /**
         * 设置连接池空闲超时
         *
         * @param timeout 超时时间
         * @param unit   时间单位
         * @return 构建器实例
         */
        public Builder poolIdleTimeout(long timeout, java.util.concurrent.TimeUnit unit) {
            this.poolIdleTimeout = timeout;
            this.poolIdleTimeoutUnit = unit;
            return this;
        }

        /**
         * 使用共享连接池
         *
         * @return 构建器实例
         */
        public Builder useSharedConnectionPool() {
            this.useConnectionPool = true;
            this.connectionPool = com.pizhai.pool.SharedConnectionPool.getInstance();
            return this;
        }

        /**
         * 构建HtmlToPdfConverter实例
         *
         * @return 新的HtmlToPdfConverter实例
         * @throws HtmlToPdfException 如果初始化失败
         */
        public HtmlToPdfConverter build() throws HtmlToPdfException {
            return new HtmlToPdfConverter(this);
        }
    }

    /**
     * PDF生成选项
     */
    public static class PdfOptions {
        private final boolean landscape;
        private final boolean printBackground;
        private final double scale;
        private final double paperWidth;
        private final double paperHeight;
        private final double marginTop;
        private final double marginBottom;
        private final double marginLeft;
        private final double marginRight;
        private final String pageRanges;
        private final boolean preferCSSPageSize;

        private PdfOptions(Builder builder) {
            this.landscape = builder.landscape;
            this.printBackground = builder.printBackground;
            this.scale = builder.scale;
            this.paperWidth = builder.paperWidth;
            this.paperHeight = builder.paperHeight;
            this.marginTop = builder.marginTop;
            this.marginBottom = builder.marginBottom;
            this.marginLeft = builder.marginLeft;
            this.marginRight = builder.marginRight;
            this.pageRanges = builder.pageRanges;
            this.preferCSSPageSize = builder.preferCSSPageSize;
        }

        public boolean isLandscape() {
            return landscape;
        }

        public boolean isPrintBackground() {
            return printBackground;
        }

        public double getScale() {
            return scale;
        }

        public double getPaperWidth() {
            return paperWidth;
        }

        public double getPaperHeight() {
            return paperHeight;
        }

        public double getMarginTop() {
            return marginTop;
        }

        public double getMarginBottom() {
            return marginBottom;
        }

        public double getMarginLeft() {
            return marginLeft;
        }

        public double getMarginRight() {
            return marginRight;
        }

        public String getPageRanges() {
            return pageRanges;
        }

        public boolean isPreferCSSPageSize() {
            return preferCSSPageSize;
        }

        /**
         * 创建PDF选项构建器
         *
         * @return 新的构建器实例
         */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * PdfOptions的构建器
         */
        public static class Builder {
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

            /**
             * 设置页面方向为横向
             *
             * @param landscape true为横向，false为纵向
             * @return 构建器实例
             */
            public Builder landscape(boolean landscape) {
                this.landscape = landscape;
                return this;
            }

            /**
             * 设置是否打印背景
             *
             * @param printBackground true表示打印背景，false表示不打印
             * @return 构建器实例
             */
            public Builder printBackground(boolean printBackground) {
                this.printBackground = printBackground;
                return this;
            }

            /**
             * 设置缩放比例
             *
             * @param scale 缩放比例，默认1.0
             * @return 构建器实例
             */
            public Builder scale(double scale) {
                this.scale = scale;
                return this;
            }

            /**
             * 设置纸张宽度（英寸）
             *
             * @param paperWidth 纸张宽度，默认8.5
             * @return 构建器实例
             */
            public Builder paperWidth(double paperWidth) {
                this.paperWidth = paperWidth;
                return this;
            }

            /**
             * 设置纸张高度（英寸）
             *
             * @param paperHeight 纸张高度，默认11.0
             * @return 构建器实例
             */
            public Builder paperHeight(double paperHeight) {
                this.paperHeight = paperHeight;
                return this;
            }

            /**
             * 设置上边距（英寸）
             *
             * @param marginTop 上边距，默认0.4
             * @return 构建器实例
             */
            public Builder marginTop(double marginTop) {
                this.marginTop = marginTop;
                return this;
            }

            /**
             * 设置下边距（英寸）
             *
             * @param marginBottom 下边距，默认0.4
             * @return 构建器实例
             */
            public Builder marginBottom(double marginBottom) {
                this.marginBottom = marginBottom;
                return this;
            }

            /**
             * 设置左边距（英寸）
             *
             * @param marginLeft 左边距，默认0.4
             * @return 构建器实例
             */
            public Builder marginLeft(double marginLeft) {
                this.marginLeft = marginLeft;
                return this;
            }

            /**
             * 设置右边距（英寸）
             *
             * @param marginRight 右边距，默认0.4
             * @return 构建器实例
             */
            public Builder marginRight(double marginRight) {
                this.marginRight = marginRight;
                return this;
            }

            /**
             * 设置页面范围，例如"1-5, 8, 11-13"
             *
             * @param pageRanges 页面范围
             * @return 构建器实例
             */
            public Builder pageRanges(String pageRanges) {
                this.pageRanges = pageRanges;
                return this;
            }

            /**
             * 设置是否优先使用CSS页面大小
             *
             * @param preferCSSPageSize 是否优先使用CSS页面大小
             * @return 构建器实例
             */
            public Builder preferCSSPageSize(boolean preferCSSPageSize) {
                this.preferCSSPageSize = preferCSSPageSize;
                return this;
            }

            /**
             * 构建PdfOptions实例
             *
             * @return 新的PdfOptions实例
             */
            public PdfOptions build() {
                return new PdfOptions(this);
            }
        }
    }

    /**
     * 配置类 - 用于配置单例转换器和共享连接池
     */
    public static class Config {

        /**
         * 设置Chrome浏览器路径
         * 必须在获取单例实例前调用
         *
         * @param path Chrome可执行文件的路径
         */
        public static void setChromePath(String path) {
            // 配置共享连接池的Chrome路径
            com.pizhai.pool.SharedConnectionPool.Config.setChromePath(path);
        }

        /**
         * 设置最小连接数
         *
         * @param count 最小连接数
         */
        public static void setMinConnections(int count) {
            com.pizhai.pool.SharedConnectionPool.Config.setMinConnections(count);
        }

        /**
         * 设置最大连接数
         *
         * @param count 最大连接数
         */
        public static void setMaxConnections(int count) {
            com.pizhai.pool.SharedConnectionPool.Config.setMaxConnections(count);
        }

        /**
         * 设置空闲连接超时
         *
         * @param timeout 超时时间
         * @param unit 时间单位
         */
        public static void setIdleTimeout(long timeout, java.util.concurrent.TimeUnit unit) {
            com.pizhai.pool.SharedConnectionPool.Config.setIdleTimeout(timeout, unit);
        }

        /**
         * 设置基础调试端口
         *
         * @param port 基础端口号
         */
        public static void setBasePort(int port) {
            com.pizhai.pool.SharedConnectionPool.Config.setBasePort(port);
        }

        /**
         * 从配置文件加载配置
         * 支持的配置项:
         * - html2pdf.chrome.path: Chrome浏览器路径
         * - html2pdf.pool.min-connections: 最小连接数
         * - html2pdf.pool.max-connections: 最大连接数
         * - html2pdf.pool.base-port: 基础调试端口
         * - html2pdf.pool.idle-timeout-seconds: 空闲超时(秒)
         *
         * @param configPath 配置文件路径(properties格式)
         */
        public static void loadFromFile(String configPath) {
            try {
                java.util.Properties props = new java.util.Properties();
                try (java.io.FileInputStream fis = new java.io.FileInputStream(configPath)) {
                    props.load(fis);
                }

                loadFromProperties(props);

                logger.info("已从配置文件加载配置: {}", configPath);
            } catch (Exception e) {
                logger.error("加载配置文件失败: {}", e.getMessage());
                throw new RuntimeException("加载配置文件失败", e);
            }
        }

        /**
         * 从资源文件加载配置
         *
         * @param resourcePath 资源文件路径(相对于classpath的路径)
         */
        public static void loadFromResource(String resourcePath) {
            try {
                java.util.Properties props = new java.util.Properties();
                try (java.io.InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
                    if (is == null) {
                        throw new RuntimeException("找不到资源文件: " + resourcePath);
                    }
                    props.load(is);
                }

                loadFromProperties(props);

                logger.info("已从资源文件加载配置: {}", resourcePath);
            } catch (Exception e) {
                logger.error("加载资源文件配置失败: {}", e.getMessage());
                throw new RuntimeException("加载资源文件配置失败", e);
            }
        }

        /**
         * 从Properties对象加载配置
         *
         * @param props Properties对象
         */
        public static void loadFromProperties(java.util.Properties props) {
            // 获取Chrome路径
            String chromePath = props.getProperty("html2pdf.chrome.path");
            if (chromePath != null && !chromePath.trim().isEmpty()) {
                setChromePath(chromePath.trim());
            }

            // 获取最小连接数
            String minConnStr = props.getProperty("html2pdf.pool.min-connections");
            if (minConnStr != null && !minConnStr.trim().isEmpty()) {
                try {
                    int minConn = Integer.parseInt(minConnStr.trim());
                    setMinConnections(minConn);
                } catch (NumberFormatException e) {
                    logger.warn("解析最小连接数失败: {}", minConnStr);
                }
            }

            // 获取最大连接数
            String maxConnStr = props.getProperty("html2pdf.pool.max-connections");
            if (maxConnStr != null && !maxConnStr.trim().isEmpty()) {
                try {
                    int maxConn = Integer.parseInt(maxConnStr.trim());
                    setMaxConnections(maxConn);
                } catch (NumberFormatException e) {
                    logger.warn("解析最大连接数失败: {}", maxConnStr);
                }
            }

            // 获取基础端口
            String basePortStr = props.getProperty("html2pdf.pool.base-port");
            if (basePortStr != null && !basePortStr.trim().isEmpty()) {
                try {
                    int basePort = Integer.parseInt(basePortStr.trim());
                    setBasePort(basePort);
                } catch (NumberFormatException e) {
                    logger.warn("解析基础端口失败: {}", basePortStr);
                }
            }

            // 获取空闲超时
            String idleTimeoutStr = props.getProperty("html2pdf.pool.idle-timeout-seconds");
            if (idleTimeoutStr != null && !idleTimeoutStr.trim().isEmpty()) {
                try {
                    long idleTimeout = Long.parseLong(idleTimeoutStr.trim());
                    setIdleTimeout(idleTimeout, java.util.concurrent.TimeUnit.SECONDS);
                } catch (NumberFormatException e) {
                    logger.warn("解析空闲超时失败: {}", idleTimeoutStr);
                }
            }
        }
    }
}