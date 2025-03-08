@echo off
REM 构建HTML转PDF库

echo 清理旧的构建文件...
call mvn clean

echo 编译并打包...
call mvn package

echo 安装到本地Maven仓库...
call mvn install

echo 构建完成 