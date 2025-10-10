//package com.zzw.chatserver.config;
//
//import com.corundumstudio.socketio.SocketIOServer;
//import com.corundumstudio.socketio.Transport;
//import com.corundumstudio.socketio.annotation.SpringAnnotationScanner;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//
//@Configuration
//public class SocketIOConfig {
//    @Value("${socketio.port}")
//    private Integer port;
//
//    @Value("${socketio.workCount}")
//    private int workCount;
//
//    @Value("${socketio.allowCustomRequests}")
//    private boolean allowCustomRequests;
//
//    @Value("${socketio.upgradeTimeout}")
//    private int upgradeTimeout;
//
//    @Value("${socketio.pingTimeout}")
//    private int pingTimeout;
//
//    @Value("${socketio.pingInterval}")
//    private int pingInterval;
//
//    @Value("${socketio.maxFramePayloadLength}")
//    private int maxFramePayloadLength;
//
//    @Value("${socketio.maxHttpContentLength}")
//    private int maxHttpContentLength;
//
//    // 动态线程池核心参数
//    @Value("${socketio.threadPool.coreMultiplier:2}")
//    private int coreMultiplier; // CPU核心数乘数
//
//    @Value("${socketio.threadPool.maxConnections:10000}")
//    private int maxConnections; // 预估最大连接数
//
//    @Bean("socketIOServer")
//    public SocketIOServer socketIOServer() {
//        com.corundumstudio.socketio.Configuration config = new com.corundumstudio.socketio.Configuration();
//        // 动态设置工作线程数（CPU核心数*2）
//        int coreCount = Runtime.getRuntime().availableProcessors();
//        // 计算动态线程数：CPU核心数*乘数 与 配置文件workCount 取最小值，同时保证不小于核心数
//        int dynamicWorkerCount = Math.min(
//                Math.max(coreCount * coreMultiplier, coreCount),
//                workCount
//        );
//
//        // 基于最大连接数的线程数修正（每1000连接至少1个线程）
//        int connectionBasedThreads = (int) Math.ceil(maxConnections / 1000.0);
//        dynamicWorkerCount = Math.max(dynamicWorkerCount, connectionBasedThreads);
//        config.setWorkerThreads(dynamicWorkerCount);
//
//
//        config.setPort(port);
//        com.corundumstudio.socketio.SocketConfig socketConfig = new com.corundumstudio.socketio.SocketConfig();
//        socketConfig.setReuseAddress(true);
//        config.setSocketConfig(socketConfig);
//        config.setAllowCustomRequests(allowCustomRequests);
//        config.setUpgradeTimeout(upgradeTimeout);
//        config.setPingTimeout(pingTimeout);
//        config.setPingInterval(pingInterval);
//        config.setMaxHttpContentLength(maxHttpContentLength);
//        config.setMaxFramePayloadLength(maxFramePayloadLength);
//        config.setTransports(Transport.WEBSOCKET);//指定传输协议为WebSocket
//        return new SocketIOServer(config);
//    }
//
//    /**
//     * 开启SocketIOServer注解支持，比如 @OnConnect、@OnEvent
//     */
//    @Bean
//    public SpringAnnotationScanner springAnnotationScanner(SocketIOServer socketServer) {
//        return new SpringAnnotationScanner(socketServer);
//    }
//}

package com.zzw.chatserver.config;

import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.SocketConfig;
import com.corundumstudio.socketio.Transport;
import com.corundumstudio.socketio.annotation.SpringAnnotationScanner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class SocketIOConfig {
    @Value("${socketio.port}")
    private Integer port;

    @Value("${socketio.workCount}")
    private int workCount;

    @Value("${socketio.allowCustomRequests}")
    private boolean allowCustomRequests;

    @Value("${socketio.upgradeTimeout}")
    private int upgradeTimeout;

    @Value("${socketio.pingTimeout}")
    private int pingTimeout;

    @Value("${socketio.pingInterval}")
    private int pingInterval;

    @Value("${socketio.maxFramePayloadLength}")
    private int maxFramePayloadLength;

    @Value("${socketio.maxHttpContentLength}")
    private int maxHttpContentLength;

    // 动态线程池参数
    @Value("${socketio.threadPool.maxConnections:10000}")
    private int maxConnections;

    @Bean("socketIOServer")
    public SocketIOServer socketIOServer() {
        com.corundumstudio.socketio.Configuration config = new com.corundumstudio.socketio.Configuration();

        // 动态线程池优化
        int coreCount = Runtime.getRuntime().availableProcessors();
        // 计算规则：确保线程数合理（既不超额也不不足）
        int dynamicWorkerCount = Math.min(
                Math.max(coreCount * 2, (int) Math.ceil(maxConnections / 1000.0)),
                workCount
        );
        config.setWorkerThreads(dynamicWorkerCount);

        // 连接性能优化
        SocketConfig socketConfig = new SocketConfig();
        socketConfig.setReuseAddress(true); // 地址复用，避免端口占用问题
        socketConfig.setTcpNoDelay(true);   // 禁用Nagle算法，降低实时通信延迟
        socketConfig.setSoLinger(0);        // 连接关闭时立即释放资源
        config.setSocketConfig(socketConfig);

        // 基础配置
        config.setPort(port);
        config.setAllowCustomRequests(allowCustomRequests);
        config.setUpgradeTimeout(upgradeTimeout);
        config.setPingTimeout(pingTimeout);
        config.setPingInterval(pingInterval);
        config.setMaxFramePayloadLength(maxFramePayloadLength);
        config.setMaxHttpContentLength(maxHttpContentLength);
        // 传输协议：仅启用WebSocket
        config.setTransports(Transport.WEBSOCKET);

        return new SocketIOServer(config);
    }

    /**
     * 开启SocketIO注解支持（@OnConnect、@OnEvent等）
     */
    @Bean
    public SpringAnnotationScanner springAnnotationScanner(SocketIOServer socketServer) {
        return new SpringAnnotationScanner(socketServer);
    }

    /**
     * Socket事件异步处理线程池（专门处理连接/断开的非核心流程）
     */
    @Bean(name = "socketAsyncExecutor")
    public Executor socketAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int coreCount = Runtime.getRuntime().availableProcessors();
        executor.setCorePoolSize(coreCount); // 核心线程数=CPU核心数
        executor.setMaxPoolSize(coreCount * 2); // 最大线程数=CPU核心数*2
        executor.setQueueCapacity(1000); // 队列容量（缓冲任务）
        executor.setThreadNamePrefix("socket-async-"); // 线程名前缀（便于排查）
        // 拒绝策略：任务满时由调用线程执行（避免任务丢失）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}


