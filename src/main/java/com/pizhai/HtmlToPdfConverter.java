package com.pizhai;

import com.pizhai.cdp.ChromeDevToolsClient;
import com.pizhai.chrome.ChromeFinder;
import com.pizhai.chrome.ChromeLauncher;
import com.pizhai.exception.HtmlToPdfException;
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

    private final ChromeLauncher chromeLauncher;
    private ChromeDevToolsClient devToolsClient;
    private final String chromePath;
    private final int debuggingPort;

    /**
     * 私有构造函数，通过构建器创建实例
     */
    private HtmlToPdfConverter(Builder builder) {
        this.chromePath = builder.chromePath;
        this.debuggingPort = builder.debuggingPort;
        this.chromeLauncher = new ChromeLauncher();

        initialize();
    }

    /**
     * 初始化Chrome浏览器和WebSocket连接
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

        try {
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
            devToolsClient.navigateToUrl(fileUrl);

            // 生成PDF
            logger.info("生成PDF文件: {}", outputPdfPath);
            devToolsClient.generatePdf(outputPdfPath, convertToCdpOptions(options));

            logger.info("PDF生成成功: {}", outputPdfPath);
        } catch (Exception e) {
            logger.error("HTML转PDF失败", e); // 记录完整堆栈跟踪
            throw new HtmlToPdfException("HTML转PDF失败: " + e.getMessage(), e);
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
     * @param options PDF生成选项
     * @return PDF文件的字节数组
     * @throws HtmlToPdfException 如果转换过程中发生错误
     */
    public byte[] convertToByteArray(String htmlFilePath, PdfOptions options) throws HtmlToPdfException {
        File htmlFile = new File(htmlFilePath);
        if (!htmlFile.exists() || !htmlFile.isFile()) {
            logger.error("HTML文件不存在: {}", htmlFilePath);
            throw new HtmlToPdfException("HTML文件不存在: " + htmlFilePath);
        }

        try {
            // 构建文件URL，确保正确格式化
            String fileUrl = formatFileUrl(htmlFile);
            logger.info("加载HTML文件: {}", fileUrl);

            // 导航到HTML文件
            devToolsClient.navigateToUrl(fileUrl);

            // 生成PDF
            logger.info("生成PDF数据");
            byte[] pdfData = devToolsClient.generatePdfAsByteArray(convertToCdpOptions(options));

            logger.info("PDF数据生成成功: {} 字节", pdfData.length);
            return pdfData;
        } catch (Exception e) {
            logger.error("HTML转PDF字节数组失败", e);
            throw new HtmlToPdfException("HTML转PDF字节数组失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将PdfOptions转换为ChromeDevToolsClient.PdfOptions
     */
    private ChromeDevToolsClient.PdfOptions convertToCdpOptions(PdfOptions options) {
        ChromeDevToolsClient.PdfOptions cdpOptions = new ChromeDevToolsClient.PdfOptions();
        return cdpOptions.setLandscape(options.isLandscape())
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
            if (File.separatorChar == '\\' && !fileUrl.startsWith("file:///")) {
                fileUrl = fileUrl.replace("file:/", "file:///");
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

    /**
     * 创建构建器
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * HtmlToPdfConverter的构建器
     */
    public static class Builder {
        private String chromePath;
        private int debuggingPort = DEFAULT_DEBUGGING_PORT;

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
             * 设置是否优先使用CSS页面尺寸
             *
             * @param preferCSSPageSize 是否优先使用CSS页面尺寸
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
} 