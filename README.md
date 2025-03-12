# HTML转PDF工具库

一个使用Java实现的基于Chrome浏览器的HTML转PDF工具库，通过WebSocket连接Chrome DevTools Protocol来实现高质量的HTML到PDF转换。支持连接池、共享资源和多种转换模式，提供极佳的性能和灵活性。

## 功能特点

- 自动查找本地Chrome浏览器，支持指定Chrome路径或自动配置环境
- 使用Chrome DevTools Protocol进行通信，实现高质量PDF生成
- 支持多种输入来源：
    - 本地HTML文件转换为PDF
    - HTML字符串直接转换为PDF（无需创建临时文件）
- 支持多种输出格式：
    - 转换为PDF文件
    - 转换为字节数组（用于内存处理、网络传输等）
- 高级资源管理：
    - 连接池模式，优化Chrome实例资源利用
    - 共享连接池，实现应用级资源共享
    - 支持空闲连接超时自动释放
    - 队列等待机制，防止资源耗尽
- 智能环境自动配置，自动检测和适配不同操作系统环境
- 多种使用模式：
    - 构建器模式，灵活配置各项参数
    - 单例模式，便捷高效
    - 静态方法，一行代码完成转换
- 自定义PDF选项（页面大小、方向、边距等）
- 完整的异常处理机制
- 自动资源管理（实现了AutoCloseable接口）

## 系统要求

- JDK 17 或更高版本
- Maven 3.6 或更高版本
- 已安装的Google Chrome浏览器

## 如何在项目中使用

### 1. 添加Maven依赖

```xml
<dependency>
    <groupId>org.example</groupId>
    <artifactId>html-to-pdf</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. 代码示例

#### 基本用法

```java
// 最简单的使用方式 - 静态方法调用（推荐）
HtmlToPdfConverter.convertStatic("input.html", "output.pdf");

// 或者使用单例模式
HtmlToPdfConverter converter = HtmlToPdfConverter.getInstance();
converter.convert("input.html", "output.pdf");

// 或者完全自定义
try (HtmlToPdfConverter converter = HtmlToPdfConverter.builder().build()) {
    converter.convert("input.html", "output.pdf");
}
```

#### HTML字符串直接转PDF

```java
// HTML字符串转为PDF文件
String htmlContent = "<html><body><h1>Hello World</h1></body></html>";
byte[] pdfData = HtmlToPdfConverter.convertHtmlToBytes(htmlContent);

// 保存到文件
try (java.io.FileOutputStream fos = new java.io.FileOutputStream("output.pdf")) {
    fos.write(pdfData);
}
```

#### 指定Chrome路径

```java
// 方式1：直接在构建器中指定
try (HtmlToPdfConverter converter = HtmlToPdfConverter.builder()
        .chromePath("C:/Program Files/Google/Chrome/Application/chrome.exe")
        .build()) {
    converter.convert("input.html", "output.pdf");
}

// 方式2：全局配置（影响所有单例和静态方法调用）
HtmlToPdfConverter.Config.setChromePath("C:/Program Files/Google/Chrome/Application/chrome.exe");
HtmlToPdfConverter.convertStatic("input.html", "output.pdf");
```

#### 使用配置文件

```java
// 从类路径资源加载配置
HtmlToPdfConverter.Config.loadFromResource("html2pdf.properties");

// 或从文件系统加载
HtmlToPdfConverter.Config.loadFromFile("/path/to/html2pdf.properties");

// 然后使用配置好的转换器
HtmlToPdfConverter.convertStatic("input.html", "output.pdf");
```

配置文件示例 (html2pdf.properties):
```properties
# Chrome路径配置
html2pdf.chrome.path=C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe
# 连接池配置
html2pdf.pool.min-connections=2
html2pdf.pool.max-connections=10
html2pdf.pool.base-port=9222
html2pdf.pool.idle-timeout-seconds=300
```

#### 环境自动配置

```java
// 自动检测并配置Chrome环境
org.example.util.ChromeEnvironment.autoConfig();

// 然后使用配置好的转换器
HtmlToPdfConverter.convertStatic("input.html", "output.pdf");
```

#### 连接池管理

```java
// 配置共享连接池
HtmlToPdfConverter.Config.setMinConnections(2);
HtmlToPdfConverter.Config.setMaxConnections(10);
HtmlToPdfConverter.Config.setIdleTimeout(60, TimeUnit.SECONDS);

// 使用共享连接池
HtmlToPdfConverter.convertStatic("input.html", "output.pdf");

// 或者创建自定义连接池
try (DefaultChromeConnectionPool pool = DefaultChromeConnectionPool.builder()
        .minConnections(2)
        .maxConnections(5)
        .idleTimeout(30, TimeUnit.SECONDS)
        .build()) {
    
    try (HtmlToPdfConverter converter = HtmlToPdfConverter.builder()
            .connectionPool(pool)
            .useConnectionPool(true)
            .build()) {
        converter.convert("input.html", "output.pdf");
    }
}
```

#### 自定义PDF选项

```java
// 创建PDF选项
HtmlToPdfConverter.PdfOptions options = HtmlToPdfConverter.PdfOptions.builder()
    .landscape(true)                // 横向布局
    .printBackground(true)          // 打印背景
    .scale(1.2)                     // 缩放比例
    .paperWidth(11.0)               // 纸张宽度（英寸）
    .paperHeight(8.5)               // 纸张高度（英寸）
    .marginTop(0.5)                 // 上边距（英寸）
    .marginBottom(0.5)              // 下边距（英寸）
    .marginLeft(0.5)                // 左边距（英寸）
    .marginRight(0.5)               // 右边距（英寸）
    .pageRanges("1-5,8,11-13")      // 页面范围
    .build();
    
// 转换HTML到PDF，使用自定义选项
HtmlToPdfConverter.convertStatic("input.html", "output.pdf", options);
```

#### 异常处理

```java
try {
    HtmlToPdfConverter.convertStatic("input.html", "output.pdf");
} catch (HtmlToPdfException.ChromeNotFoundException e) {
    // 找不到Chrome浏览器
    System.err.println("找不到Chrome: " + e.getMessage());
} catch (HtmlToPdfException.ConnectionException e) {
    // WebSocket连接失败
    System.err.println("连接失败: " + e.getMessage());
} catch (HtmlToPdfException.PageNavigationException e) {
    // 页面导航错误
    System.err.println("导航失败: " + e.getMessage());
} catch (HtmlToPdfException.PdfGenerationException e) {
    // PDF生成错误
    System.err.println("PDF生成失败: " + e.getMessage());
} catch (HtmlToPdfException e) {
    // 其他转换错误
    System.err.println("转换失败: " + e.getMessage());
}
```

#### 多线程环境使用

```java
// 在应用启动时配置共享连接池
HtmlToPdfConverter.Config.setMinConnections(2);
HtmlToPdfConverter.Config.setMaxConnections(10);

// 多线程环境中使用
ExecutorService executor = Executors.newFixedThreadPool(5);
for (int i = 0; i < 10; i++) {
    final int taskId = i;
    executor.submit(() -> {
        try {
            String output = "output_" + taskId + ".pdf";
            // 使用单例实例或静态方法都可以，它们共享同一个连接池
            HtmlToPdfConverter.convertStatic("input.html", output);
            System.out.println("转换任务 #" + taskId + " 完成");
        } catch (Exception e) {
            e.printStackTrace();
        }
    });
}
```

## 如何构建项目

### Windows
```bash
# 跳过javadoc检查
build-nojdoc.bat

# 包含javadoc检查
build.bat
```

### Linux/Mac
```bash
chmod +x build.sh
./build.sh
```

## 架构说明

项目的主要组件包括：

- `HtmlToPdfConverter`: 主类，提供转换API、构建器接口和静态方法
- `ChromeFinder`: 负责查找Chrome浏览器路径
- `ChromeLauncher`: 负责启动Chrome浏览器并获取WebSocket URL
- `ChromeDevToolsClient`: WebSocket客户端，负责与Chrome DevTools Protocol通信
- `ChromeConnectionPool`: 连接池接口，定义连接池行为
- `DefaultChromeConnectionPool`: 连接池实现，管理Chrome连接资源
- `SharedConnectionPool`: 共享连接池，提供全局单例访问
- `ChromeEnvironment`: 环境工具类，自动检测和配置环境
- `HtmlToPdfException`: 异常类，包含多个子类表示不同类型的错误

## 高级用法

### 1. 配置优先级

Chrome路径的获取优先级如下：
1. 通过代码直接指定（`setChromePath`方法）
2. 从配置文件加载（`loadFromResource`/`loadFromFile`方法）
3. 环境变量`CHROME_PATH`
4. Java系统属性`chrome.path`
5. 自动环境检测和常见安装路径搜索

### 2. 性能优化

- 使用共享连接池可以显著提高性能，特别是在需要多次转换的场景
- 适当设置最小连接数和最大连接数可以平衡资源使用和性能
- 在批量转换时，推荐使用静态转换方法，自动利用共享连接池

### 3. 资源管理

库会在以下情况自动释放资源：
- 使用try-with-resources语句块时
- JVM关闭时（通过关闭钩子）
- 连接空闲超时时

## 注意事项

- 确保系统已安装Chrome浏览器
- 如果在服务器环境运行，需要确保Chrome可以在无头模式下运行
- 转换大型或复杂的HTML页面可能需要较长时间
- 连接池初始化可能需要一定时间，特别是第一次使用时

## 许可证

MIT 