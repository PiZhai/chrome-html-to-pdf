package com.pizhai.pool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 全局共享的Chrome连接池
 * 单例模式，支持所有HtmlToPdfConverter实例共享
 */
public class SharedConnectionPool {

    private static final Logger logger = LoggerFactory.getLogger(SharedConnectionPool.class);

    // 单例实例，使用AtomicReference保证线程安全
    private static final AtomicReference<DefaultChromeConnectionPool> INSTANCE = new AtomicReference<>();

    // 默认配置
    private static final int DEFAULT_MIN_CONNECTIONS = 2;
    private static final int DEFAULT_MAX_CONNECTIONS = 10;
    private static final long DEFAULT_IDLE_TIMEOUT = 5;
    private static final TimeUnit DEFAULT_IDLE_TIMEOUT_UNIT = TimeUnit.MINUTES;
    private static final long DEFAULT_CONNECTION_TIMEOUT = 30;
    private static final TimeUnit DEFAULT_CONNECTION_TIMEOUT_UNIT = TimeUnit.SECONDS;

    // 配置选项
    private static String chromePath;
    private static int basePort = 9222;
    private static int minConnections = DEFAULT_MIN_CONNECTIONS;
    private static int maxConnections = DEFAULT_MAX_CONNECTIONS;
    private static long idleTimeout = DEFAULT_IDLE_TIMEOUT;
    private static TimeUnit idleTimeoutUnit = DEFAULT_IDLE_TIMEOUT_UNIT;
    private static long connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
    private static TimeUnit connectionTimeoutUnit = DEFAULT_CONNECTION_TIMEOUT_UNIT;

    // 防止实例化
    private SharedConnectionPool() {
    }

    /**
     * 获取共享连接池实例
     * 懒加载，首次调用时创建
     *
     * @return 共享的连接池实例
     */
    public static ChromeConnectionPool getInstance() {
        if (INSTANCE.get() == null) {
            synchronized (SharedConnectionPool.class) {
                if (INSTANCE.get() == null) {
                    System.out.println("[SharedPool] 准备创建共享连接池: chromePath=" + chromePath +
                            ", basePort=" + basePort + ", minConnections=" + minConnections +
                            ", maxConnections=" + maxConnections);
                    logger.info("准备创建共享连接池: chromePath={}, basePort={}, minConnections={}, maxConnections={}",
                            chromePath, basePort, minConnections, maxConnections);

                    // 临时保存原始配置
                    int originalMinConnections = minConnections;
                    // 首次创建时完全禁用预创建连接，避免超时
                    minConnections = 0;
                    System.out.println("[SharedPool] 首次创建时禁用预创建连接(设置minConnections=0)以加快初始化速度");
                    logger.info("首次创建时禁用预创建连接(设置minConnections=0)以加快初始化速度");

                    // 使用Future带超时的方式创建连接池
                    ExecutorService executor = null;
                    try {
                        // 创建新的连接池
                        logger.info("开始创建连接池...");
                        executor = Executors.newSingleThreadExecutor();
                        Future<DefaultChromeConnectionPool> future = executor.submit(() -> {
                            return DefaultChromeConnectionPool.builder()
                                    .chromePath(chromePath)
                                    .basePort(basePort)
                                    .minConnections(minConnections)  // 此时为0
                                    .maxConnections(maxConnections)
                                    .idleTimeout(idleTimeout, idleTimeoutUnit)
                                    .connectionTimeout(connectionTimeout, connectionTimeoutUnit)
                                    .build();
                        });

                        // 等待连接池创建完成，最多等待120秒（增加超时时间）
                        DefaultChromeConnectionPool pool = future.get();

                        logger.info("连接池创建成功");

                        INSTANCE.set(pool);

                        // 恢复原始配置值，以便后续扩展连接
                        minConnections = originalMinConnections;

                        // 添加JVM关闭钩子，确保连接池正确关闭
                        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                            logger.info("JVM关闭钩子: 关闭共享连接池");
                            shutdown();
                        }));

                        logger.info("共享连接池初始化完成: minConnections={}, maxConnections={}",
                                minConnections, maxConnections);

                        // 连接池创建成功后，可以考虑在后台线程中逐步创建到minConnections个连接
                        if (minConnections > 0) {
                            new Thread(() -> {
                                logger.info("后台线程开始逐步创建连接到设定的最小连接数: {}", minConnections);
                                try {
                                    // 等待一段时间后再创建，避免刚启动时资源紧张
                                    Thread.sleep(5000);
                                    pool.ensureMinConnections();
                                } catch (Exception e) {
                                    logger.error("后台创建最小连接数失败", e);
                                }
                            }).start();
                        }
                    } catch (Exception e) {
                        logger.error("创建连接池失败", e);
                        throw new RuntimeException("创建连接池失败", e);
                    } finally {
                        // 关闭临时创建的线程池，避免线程泄漏
                        if (executor != null) {
                            executor.shutdown();
                        }
                    }
                }
            }
        }

        return INSTANCE.get();
    }

    /**
     * 关闭共享连接池
     */
    public static void shutdown() {
        DefaultChromeConnectionPool pool = INSTANCE.getAndSet(null);
        if (pool != null) {
            try {
                pool.shutdown();
                logger.info("共享连接池已关闭");
            } catch (Exception e) {
                logger.error("关闭共享连接池时出错", e);
            }
        }
    }

    /**
     * 检查连接池是否已初始化
     */
    public static boolean isInitialized() {
        return INSTANCE.get() != null;
    }

    /**
     * 获取连接池状态信息
     */
    public static String getPoolStats() {
        DefaultChromeConnectionPool pool = INSTANCE.get();
        return pool != null ? pool.getPoolStats() : "ConnectionPool[uninitialized]";
    }

    /**
     * 配置类，用于在创建共享连接池前设置参数
     */
    public static class Config {

        /**
         * 设置Chrome路径
         */
        public static void setChromePath(String path) {
            checkNotInitialized();
            chromePath = path;
        }

        /**
         * 设置基础端口
         */
        public static void setBasePort(int port) {
            checkNotInitialized();
            basePort = port;
        }

        /**
         * 设置最小连接数
         */
        public static void setMinConnections(int count) {
            checkNotInitialized();
            minConnections = count;
        }

        /**
         * 设置最大连接数
         */
        public static void setMaxConnections(int count) {
            checkNotInitialized();
            maxConnections = count;
        }

        /**
         * 设置空闲超时
         */
        public static void setIdleTimeout(long timeout, TimeUnit unit) {
            checkNotInitialized();
            idleTimeout = timeout;
            idleTimeoutUnit = unit;
        }

        /**
         * 设置连接获取超时
         */
        public static void setConnectionTimeout(long timeout, TimeUnit unit) {
            checkNotInitialized();
            connectionTimeout = timeout;
            connectionTimeoutUnit = unit;
        }

        /**
         * 检查连接池是否已初始化，如果已初始化则抛出异常
         */
        private static void checkNotInitialized() {
            if (isInitialized()) {
                throw new IllegalStateException("连接池已初始化，无法更改配置");
            }
        }
    }
} 