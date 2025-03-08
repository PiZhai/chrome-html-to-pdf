#!/bin/bash
# 构建HTML转PDF库

echo "清理旧的构建文件..."
mvn clean

echo "编译并打包..."
mvn package

echo "安装到本地Maven仓库..."
mvn install

echo "构建完成" 