# 贡献指南

感谢您考虑为CHROME-HTML-TO-PDF项目做出贡献！以下是一些指导原则，帮助您参与项目开发。

## 开发环境设置

1. 克隆仓库
2. 确保您的系统中安装了JDK 17或更高版本
3. 确保您的系统中安装了Chrome浏览器
4. 使用Maven构建项目：`mvn clean install`

## 提交Pull Request

1. Fork本仓库
2. 创建您的特性分支：`git checkout -b feature/amazing-feature`
3. 提交您的更改：`git commit -m 'Add some amazing feature'`
4. 推送到分支：`git push origin feature/amazing-feature`
5. 提交Pull Request

## 代码风格

- 遵循Java代码规范
- 使用4个空格缩进
- 为所有公共API添加JavaDoc注释
- 使用SLF4J进行日志记录，避免使用System.out或System.err

## 测试

- 为新功能添加单元测试
- 确保所有测试通过：`mvn test`

## 报告Bug

如果您发现了Bug，请创建一个Issue，并包含以下信息：

- 问题描述
- 复现步骤
- 预期行为
- 实际行为
- 环境信息（操作系统、Java版本、Chrome版本等）

## 功能请求

如果您有新功能的想法，请创建一个Issue，描述您的想法和使用场景。

感谢您的贡献！ 