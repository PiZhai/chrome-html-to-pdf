package com.pizhai.pool;

import com.pizhai.cdp.ChromeDevToolsClient;
import com.pizhai.chrome.ChromeFinder;
import com.pizhai.chrome.ChromeLauncher;
import com.pizhai.exception.HtmlToPdfException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chrome连接池的默认实现
 */
public class DefaultChromeConnectionPool implements ChromeConnectionPool, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(DefaultChromeConnectionPool.class);

    // 配置选项
    private final String chromePath;
    private final int basePort;
    private final int minConnections;
    private final int maxConnections;
    private final long idleTimeout;
    private final TimeUnit idleTimeoutUnit;
    private final long connectionTimeout;
    private final TimeUnit connectionTimeoutUnit;

    // 连接池状态
    private final Queue<PooledConnection> idleConnections = new ConcurrentLinkedQueue<>();
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final ScheduledExecutorService scheduler;
    private volatile boolean shutdownCalled = false;

    // 任务排队相关
    private final BlockingQueue<ConnectionRequest> pendingRequests = new LinkedBlockingQueue<>();
    private final AtomicInteger waitingCount = new AtomicInteger(0);
    private final ExecutorService connectionHandlerService;

    /**
     * 创建一个Chrome连接池
     *
     * @param builder 连接池构建器
     */
    private DefaultChromeConnectionPool(Builder builder) {
        this.chromePath = builder.chromePath;
        this.basePort = builder.basePort;
        this.minConnections = builder.minConnections;
        this.maxConnections = builder.maxConnections;
        this.idleTimeout = builder.idleTimeout;
        this.idleTimeoutUnit = builder.idleTimeoutUnit;
        this.connectionTimeout = builder.connectionTimeout;
        this.connectionTimeoutUnit = builder.connectionTimeoutUnit;

        // 初始化空闲连接检查任务
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.scheduler.scheduleAtFixedRate(
                this::evictIdleConnections,
                idleTimeout,
                idleTimeout,
                idleTimeoutUnit
        );

        // 初始化连接处理线程
        this.connectionHandlerService = Executors.newSingleThreadExecutor();
        this.connectionHandlerService.submit(this::connectionRequestHandler);

        logger.info("Chrome连接池已初始化，最小连接数: {}, 最大连接数: {}, 空闲超时: {} {}",
                minConnections, maxConnections, idleTimeout, idleTimeoutUnit);

        // 预创建最小连接数
        preCreateMinConnections();
    }

    /**
     * 预创建最小数量的连接
     */
    private void preCreateMinConnections() {
        if (minConnections <= 0) {
            return;
        }

        logger.info("预创建 {} 个Chrome连接", minConnections);
        List<ChromeDevToolsClient> clients = new ArrayList<>();

        try {
            for (int i = 0; i < minConnections; i++) {
                int port = basePort + i;
                logger.info("创建Chrome连接 #{} (端口: {})", (i + 1), port);

                // 添加超时控制
                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    Future<ChromeDevToolsClient> future = executor.submit(() -> {
                        return createNewConnection(port);
                    });

                    try {
                        // 每个连接最多等待30秒
                        ChromeDevToolsClient client = future.get(30, TimeUnit.SECONDS);
                        clients.add(client);
                        logger.info("Chrome连接 #{} 创建成功", (i + 1));
                    } catch (TimeoutException e) {
                        logger.error("创建Chrome连接 #{} 超时", (i + 1), e);
                        // 超时后继续尝试下一个连接
                        continue;
                    } catch (ExecutionException e) {
                        // 提取更详细的错误信息，特别是端口冲突错误
                        Throwable cause = e.getCause();
                        if (cause instanceof HtmlToPdfException) {
                            if (cause.getMessage().contains("端口冲突") ||
                                    cause.getMessage().contains("bind() returned an error")) {
                                logger.error("创建Chrome连接 #{} 失败: 端口 {} 冲突，请检查端口占用情况",
                                        (i + 1), port);
                            } else {
                                logger.error("创建Chrome连接 #{} 失败: {}",
                                        (i + 1), cause.getMessage());
                            }
                        } else {
                            logger.error("创建Chrome连接 #{} 失败", (i + 1), e);
                        }
                        // 失败后继续尝试下一个连接
                        continue;
                    } catch (Exception e) {
                        logger.error("创建Chrome连接 #{} 失败", (i + 1), e);
                        // 失败后继续尝试下一个连接
                        continue;
                    }
                } finally {
                    executor.shutdownNow();
                }
            }

            // 创建成功后添加到空闲池
            for (ChromeDevToolsClient client : clients) {
                PooledConnection pooledConnection = new PooledConnection(client, System.currentTimeMillis());
                idleConnections.add(pooledConnection);
                totalConnections.incrementAndGet();
            }

            logger.info("成功预创建 {} 个Chrome连接，实际创建: {}", minConnections, clients.size());
        } catch (Exception e) {
            logger.error("预创建Chrome连接时发生错误", e);
            // 关闭已创建的连接
            for (ChromeDevToolsClient client : clients) {
                try {
                    client.close();
                } catch (Exception closeEx) {
                    logger.error("关闭Chrome连接失败", closeEx);
                }
            }
        }
    }

    /**
     * 处理连接请求的后台任务
     */
    private void connectionRequestHandler() {
        while (!shutdownCalled) {
            try {
                ConnectionRequest request = pendingRequests.poll(100, TimeUnit.MILLISECONDS);
                if (request == null) {
                    continue;
                }

                // 减少等待计数
                waitingCount.decrementAndGet();

                // 尝试获取一个连接
                ChromeDevToolsClient client = acquireConnection();
                if (client != null) {
                    request.getFuture().complete(client);
                } else {
                    // 如果获取失败，再次加入队列
                    pendingRequests.offer(request);
                    waitingCount.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("连接请求处理线程被中断");
                break;
            } catch (Exception e) {
                logger.error("处理连接请求时出错", e);
            }
        }

        logger.info("连接请求处理线程已退出");
    }

    /**
     * 获取一个连接（内部方法）
     */
    private ChromeDevToolsClient acquireConnection() {
        try {
            // 首先尝试从空闲连接中获取
            PooledConnection connection = idleConnections.poll();
            if (connection != null) {
                logger.debug("从连接池获取现有连接");
                activeConnections.incrementAndGet();
                connection.setLastUsed(System.currentTimeMillis());
                return connection.getClient();
            }

            // 如果没有空闲连接，检查是否可以创建新连接
            int current = totalConnections.get();
            if (current >= maxConnections) {
                // 达到最大连接数，无法创建
                logger.debug("已达到最大连接数: {}, 无法创建新连接", maxConnections);
                return null;
            }

            // 创建新连接
            if (totalConnections.incrementAndGet() > maxConnections) {
                totalConnections.decrementAndGet();
                return null;
            }

            logger.debug("创建新的Chrome连接");
            int port = basePort + current;
            ChromeDevToolsClient client = createNewConnection(port);
            activeConnections.incrementAndGet();
            return client;
        } catch (Exception e) {
            totalConnections.decrementAndGet();
            logger.error("获取连接失败", e);
            return null;
        }
    }

    @Override
    public ChromeDevToolsClient getConnection() throws HtmlToPdfException {
        if (shutdownCalled) {
            throw new HtmlToPdfException("连接池已关闭");
        }

        // 创建一个请求
        ConnectionRequest request = new ConnectionRequest();
        CompletableFuture<ChromeDevToolsClient> future = request.getFuture();

        // 先尝试直接获取
        ChromeDevToolsClient client = acquireConnection();
        if (client != null) {
            return client;
        }

        // 如果没有立即可用的连接，加入等待队列
        logger.debug("没有可用连接，加入等待队列");
        pendingRequests.offer(request);
        waitingCount.incrementAndGet();

        try {
            // 使用超时等待以避免无限阻塞
            client = future.get(connectionTimeout, connectionTimeoutUnit);
            if (client != null) {
                return client;
            }
            throw new HtmlToPdfException("获取连接超时");
        } catch (TimeoutException e) {
            // 从队列中移除请求（如果还在队列中）
            pendingRequests.remove(request);
            waitingCount.decrementAndGet();
            throw new HtmlToPdfException("等待连接超时，连接池已满或负载过高", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HtmlToPdfException("等待连接被中断", e);
        } catch (ExecutionException e) {
            throw new HtmlToPdfException("获取连接时出错", e.getCause());
        }
    }

    @Override
    public void releaseConnection(ChromeDevToolsClient client) {
        if (shutdownCalled) {
            closeConnection(client);
            return;
        }

        if (client == null) {
            return;
        }

        logger.debug("归还连接到池中");
        activeConnections.decrementAndGet();

        // 检查是否有等待的请求
        if (!pendingRequests.isEmpty()) {
            // 有等待的请求，直接分配给它们
            ConnectionRequest request = pendingRequests.poll();
            if (request != null) {
                waitingCount.decrementAndGet();
                // 激活连接
                activeConnections.incrementAndGet();
                request.getFuture().complete(client);
                logger.debug("连接直接分配给等待的请求");
                return;
            }
        }

        // 如果没有等待的请求，或者分配失败，归还到池中
        PooledConnection pooledConnection = new PooledConnection(client, System.currentTimeMillis());
        idleConnections.offer(pooledConnection);
    }

    @Override
    public void shutdown() {
        if (shutdownCalled) {
            return;
        }

        shutdownCalled = true;
        logger.info("关闭Chrome连接池");

        // 关闭调度器
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }

        // 关闭连接处理线程
        connectionHandlerService.shutdown();
        try {
            if (!connectionHandlerService.awaitTermination(10, TimeUnit.SECONDS)) {
                connectionHandlerService.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            connectionHandlerService.shutdownNow();
        }

        // 处理所有等待的请求
        for (ConnectionRequest request : pendingRequests) {
            request.getFuture().completeExceptionally(new HtmlToPdfException("连接池已关闭"));
        }
        pendingRequests.clear();
        waitingCount.set(0);

        // 关闭所有空闲连接
        PooledConnection connection;
        List<PooledConnection> connectionsToClose = new ArrayList<>();

        // 首先收集所有空闲连接
        while ((connection = idleConnections.poll()) != null) {
            connectionsToClose.add(connection);
        }

        // 关闭收集到的连接
        for (PooledConnection conn : connectionsToClose) {
            closeConnection(conn.getClient());
            totalConnections.decrementAndGet();
        }

        logger.info("Chrome连接池已关闭，还有 {} 个活跃连接", activeConnections.get());
    }

    @Override
    public String getPoolStats() {
        return String.format("ChromeConnectionPool[total=%d, active=%d, idle=%d, waiting=%d, max=%d, min=%d]",
                totalConnections.get(), activeConnections.get(), idleConnections.size(),
                waitingCount.get(), maxConnections, minConnections);
    }

    /**
     * 检查并移除空闲超时的连接
     */
    private void evictIdleConnections() {
        long now = System.currentTimeMillis();
        long timeout = idleTimeoutUnit.toMillis(idleTimeout);
        int currentTotal = totalConnections.get();

        try {
            // 如果连接数少于最小值，不进行清理
            if (currentTotal <= minConnections) {
                logger.debug("当前连接数 {} 不超过最小连接数 {}, 跳过清理", currentTotal, minConnections);
                return;
            }

            List<PooledConnection> connectionsToEvict = new ArrayList<>();

            // 收集超时连接，但保持最小连接数
            int maxToEvict = currentTotal - minConnections;

            for (PooledConnection connection : idleConnections) {
                if (now - connection.getLastUsed() > timeout && connectionsToEvict.size() < maxToEvict) {
                    connectionsToEvict.add(connection);
                }
            }

            // 移除超时连接
            for (PooledConnection connection : connectionsToEvict) {
                if (idleConnections.remove(connection)) {
                    closeConnection(connection.getClient());
                    totalConnections.decrementAndGet();
                    logger.debug("移除空闲超时的连接, 剩余连接: {}", totalConnections.get());
                }
            }
        } catch (Exception e) {
            logger.error("清理空闲连接时出错", e);
        }
    }

    /**
     * 创建新的Chrome连接
     *
     * @param requestedPort 请求的端口
     * @return 新创建的客户端连接
     * @throws HtmlToPdfException 如果创建连接失败
     */
    private ChromeDevToolsClient createNewConnection(int requestedPort) throws HtmlToPdfException {
        System.out.println("[ConnectionPool] 创建新的Chrome连接 (请求端口: " + requestedPort + ")");
        logger.info("创建新的Chrome连接 (请求端口: {})", requestedPort);

        ChromeLauncher launcher = new ChromeLauncher();
        try {
            // 启动Chrome浏览器，内部会处理端口冲突问题
            launcher.launch(chromePath, requestedPort);

            // 创建Chrome DevTools客户端
            String webSocketUrl = launcher.getWebSocketDebuggerUrl();
            ChromeDevToolsClient client = new ChromeDevToolsClient(webSocketUrl, launcher);

            System.out.println("[ConnectionPool] Chrome连接创建成功");
            logger.info("Chrome连接创建成功");
            return client;
        } catch (HtmlToPdfException e) {
            System.out.println("[ConnectionPool] 创建Chrome连接失败: " + e.getMessage());
            logger.error("创建Chrome连接失败: {}", e.getMessage());
            // 尝试关闭启动器
            try {
                launcher.close();
            } catch (Exception closeEx) {
                logger.error("关闭Chrome启动器失败", closeEx);
            }
            throw e;
        }
    }

    /**
     * 关闭一个连接
     */
    private void closeConnection(ChromeDevToolsClient client) {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                logger.error("关闭Chrome连接时出错", e);
            }
        }
    }

    /**
     * 实现AutoCloseable接口，支持try-with-resources语法
     */
    @Override
    public void close() {
        shutdown();
    }

    /**
     * 连接请求类，用于任务排队
     */
    private static class ConnectionRequest {
        private final CompletableFuture<ChromeDevToolsClient> future = new CompletableFuture<>();
        private final long creationTime = System.currentTimeMillis();

        public CompletableFuture<ChromeDevToolsClient> getFuture() {
            return future;
        }

        public long getCreationTime() {
            return creationTime;
        }
    }

    /**
     * 池化连接，包装ChromeDevToolsClient并记录最后使用时间
     */
    private static class PooledConnection {
        private final ChromeDevToolsClient client;
        private long lastUsed;

        public PooledConnection(ChromeDevToolsClient client, long lastUsed) {
            this.client = client;
            this.lastUsed = lastUsed;
        }

        public ChromeDevToolsClient getClient() {
            return client;
        }

        public long getLastUsed() {
            return lastUsed;
        }

        public void setLastUsed(long lastUsed) {
            this.lastUsed = lastUsed;
        }
    }

    /**
     * 构建器模式创建连接池
     */
    public static class Builder {
        private String chromePath;
        private int basePort = 9222;
        private int minConnections = 2;
        private int maxConnections = 5;
        private long idleTimeout = 5;
        private TimeUnit idleTimeoutUnit = TimeUnit.MINUTES;
        private long connectionTimeout = 30;
        private TimeUnit connectionTimeoutUnit = TimeUnit.SECONDS;

        /**
         * 设置Chrome可执行文件路径
         */
        public Builder chromePath(String chromePath) {
            this.chromePath = chromePath;
            return this;
        }

        /**
         * 设置基础调试端口（每个连接会递增）
         */
        public Builder basePort(int basePort) {
            this.basePort = basePort;
            return this;
        }

        /**
         * 设置最小连接数
         */
        public Builder minConnections(int minConnections) {
            if (minConnections < 0) {
                throw new IllegalArgumentException("最小连接数不能小于0");
            }
            this.minConnections = minConnections;
            return this;
        }

        /**
         * 设置最大连接数
         */
        public Builder maxConnections(int maxConnections) {
            if (maxConnections <= 0) {
                throw new IllegalArgumentException("最大连接数必须大于0");
            }
            this.maxConnections = maxConnections;
            return this;
        }

        /**
         * 设置空闲连接超时
         */
        public Builder idleTimeout(long timeout, TimeUnit unit) {
            if (timeout <= 0) {
                throw new IllegalArgumentException("超时时间必须大于0");
            }
            this.idleTimeout = timeout;
            this.idleTimeoutUnit = unit;
            return this;
        }

        /**
         * 设置获取连接超时时间
         */
        public Builder connectionTimeout(long timeout, TimeUnit unit) {
            if (timeout <= 0) {
                throw new IllegalArgumentException("超时时间必须大于0");
            }
            this.connectionTimeout = timeout;
            this.connectionTimeoutUnit = unit;
            return this;
        }

        /**
         * 构建连接池
         */
        public DefaultChromeConnectionPool build() {
            // 确保最大连接数不小于最小连接数
            if (maxConnections < minConnections) {
                maxConnections = minConnections;
            }
            return new DefaultChromeConnectionPool(this);
        }
    }

    /**
     * 创建构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 确保连接池中的连接数量达到最小连接数
     * 这个方法通常在后台线程中调用，避免阻塞主线程
     */
    public void ensureMinConnections() {
        if (minConnections <= 0 || shutdownCalled) {
            return;
        }

        int current = totalConnections.get();
        int toCreate = minConnections - current;

        if (toCreate <= 0) {
            logger.info("当前连接数 {} 已达到或超过最小连接数 {}", current, minConnections);
            return;
        }

        logger.info("开始逐步创建连接，当前连接数: {}, 目标最小连接数: {}, 需要创建: {}",
                current, minConnections, toCreate);

        List<ChromeDevToolsClient> clients = new ArrayList<>();

        for (int i = 0; i < toCreate; i++) {
            if (shutdownCalled) {
                break;
            }

            try {
                int port = basePort + current + i;
                logger.info("创建补充连接 #{} (端口: {})", (i+1), port);

                // 添加超时控制
                Future<ChromeDevToolsClient> future = Executors.newSingleThreadExecutor().submit(() -> {
                    return createNewConnection(port);
                });

                try {
                    // 每个连接最多等待30秒
                    ChromeDevToolsClient client = future.get(30, TimeUnit.SECONDS);
                    clients.add(client);
                    logger.info("补充连接 #{} 创建成功", (i+1));

                    // 每创建一个连接后添加到空闲池，这样即使后面失败，已创建的也能使用
                    PooledConnection pooledConnection = new PooledConnection(client, System.currentTimeMillis());
                    idleConnections.add(pooledConnection);
                    totalConnections.incrementAndGet();
                } catch (Exception e) {
                    logger.error("创建补充连接 #{} 失败: {}", (i+1), e.getMessage());
                    // 失败后继续尝试下一个，不中断整个过程
                }

                // 每创建一个连接后稍微暂停，避免系统资源过度消耗
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (Exception e) {
                logger.error("补充连接创建过程中出错", e);
            }
        }

        logger.info("连接补充完成，当前连接总数: {}, 空闲连接数: {}",
                totalConnections.get(), idleConnections.size());
    }
} 