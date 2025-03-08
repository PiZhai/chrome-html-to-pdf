# HTML转PDF工具库

一个使用Java实现的基于Chrome浏览器的HTML转PDF工具库，通过WebSocket连接Chrome DevTools Protocol来实现高质量的HTML到PDF转换。

## 功能特点

- 自动查找本地Chrome浏览器，也支持指定Chrome路径
- 使用Chrome DevTools Protocol进行通信
- 支持本地HTML文件转换为PDF
- 可自定义PDF选项（如页面大小、方向、边距等）
- 完整的异常处理机制
- 自动关闭资源（实现了AutoCloseable接口）
- 支持构建器模式，可流式配置
- 提供简洁易用的API

## 系统要求

- JDK 17 或更高版本（可下载源码之后进行降级）
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
// 使用默认设置
try (HtmlToPdfConverter converter = HtmlToPdfConverter.builder().build()) {
    // 转换HTML到PDF
    converter.convert("input.html", "output.pdf");
}
```

#### 指定Chrome路径

```java
try (HtmlToPdfConverter converter = HtmlToPdfConverter.builder()
        .chromePath("C:/Program Files/Google/Chrome/Application/chrome.exe")
        .build()) {
    converter.convert("input.html", "output.pdf");
}
```

#### 自定义PDF选项

```java
try (HtmlToPdfConverter converter = HtmlToPdfConverter.builder().build()) {
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
    converter.convert("input.html", "output.pdf", options);
}
```

#### 异常处理

```java
try (HtmlToPdfConverter converter = HtmlToPdfConverter.builder().build()) {
    converter.convert("input.html", "output.pdf");
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

## 如何构建项目

### Windows
```bash
build.bat
```

### Linux/Mac
```bash
chmod +x build.sh
./build.sh
```

## 架构说明

项目的主要组件包括：

- `HtmlToPdfConverter`: 主类，提供转换API和构建器接口
- `ChromeFinder`: 负责查找Chrome浏览器路径
- `ChromeLauncher`: 负责启动Chrome浏览器并获取WebSocket URL
- `ChromeDevToolsClient`: WebSocket客户端，负责与Chrome DevTools Protocol通信
- `HtmlToPdfException`: 异常类，包含多个子类表示不同类型的错误

## 注意事项

- 确保系统已安装Chrome浏览器
- 如果在服务器环境运行，需要确保Chrome可以在无头模式下运行
- 转换大型或复杂的HTML页面可能需要较长时间
- 本工具库仅支持本地HTML文件转换，不支持直接转换网络URL

## 许可证

MIT 