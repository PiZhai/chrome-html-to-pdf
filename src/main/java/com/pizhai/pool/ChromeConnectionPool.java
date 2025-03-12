package com.pizhai.pool;

import com.pizhai.cdp.ChromeDevToolsClient;
import com.pizhai.exception.HtmlToPdfException;

/**
 * Chrome连接池接口
 * 管理Chrome浏览器实例和WebSocket连接
 */
public interface ChromeConnectionPool {

    /**
     * 获取一个Chrome连接
     *
     * @return Chrome开发工具客户端
     * @throws HtmlToPdfException 如果获取连接失败
     */
    ChromeDevToolsClient getConnection() throws HtmlToPdfException;

    /**
     * 归还一个Chrome连接到池中
     *
     * @param client 要归还的客户端
     */
    void releaseConnection(ChromeDevToolsClient client);

    /**
     * 关闭所有连接并销毁连接池
     */
    void shutdown();

    /**
     * 获取连接池统计信息
     *
     * @return 包含连接池状态的字符串
     */
    String getPoolStats();
} 