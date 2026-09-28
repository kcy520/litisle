package com.litisle.litislefile.config;

import com.litisle.litislefile.interceptor.FileGlobalQpsInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * file 模块 Web MVC 配置：文件 IO 线程池 + 全站 QPS 拦截器注册
 * 
 * 默认只在file本模块生效
 *
 * 【设计说明】（方案 A：上传/下载线程池隔离）
 * 1. 下载端点 FileController#download 返回 StreamingResponseBody，其「S3 读 + 客户端写」为阻塞 IO，
 *    交由专用线程池 fileIoExecutor 执行，脱离 Tomcat 工作线程——慢客户端不再占满主线程池。
 * 2. configureAsyncSupport 将该池注册为 MVC 异步请求执行器，并放宽默认 30s 超时（大文件下载耗时）。
 * 3. 上传保持同步（multipart + 事务语义，异步化风险高），瓶颈说明见 FileServiceImpl#upload。
 *
 * 线程池参数外置 litisle.file.executor（见 FileProperties.Executor），可由 Nacos 覆盖，运营可调。
 *
 * @author litisle
 */
@Configuration
@RequiredArgsConstructor
public class FileAsyncConfig implements WebMvcConfigurer {

    private final FileProperties fileProperties;
    private final FileGlobalQpsInterceptor fileGlobalQpsInterceptor;

/**
 * 改造前（同步）
 * `客户端 → Tomcat线程 ──阻塞读S3──阻塞写客户端──→ 释放线程   (全程占用一个 Tomcat 线程)`
 *
 * 改造后（异步隔离）
 * `客户端 → Tomcat线程：鉴权+Range+写状态码/头 → 返回StreamingResponseBody → 归还Tomcat线程 ✅
 *                               ↓ (交给 fileIoExecutor)
 *                       file-io 线程：读S3 + 写客户端 → flush → 完成`
 *
 *   只隔离下载
 *  上传保持同步
 */


    /**
     * 文件 IO 线程池：承接下载流式回写等阻塞 IO，避免占用 Tomcat 工作线程
     * <p>拒绝策略 CallerRunsPolicy：池满时回压到调用线程（Tomcat 线程）执行，
     * 形成天然背压，防止任务被静默丢弃导致客户端挂起。</p>
     */
    @Bean("fileIoExecutor")
    public ThreadPoolTaskExecutor fileIoExecutor() {
        // 读取外置参数（yml: litisle.file.executor），可由 Nacos 覆盖，运营可调、无需改代码
        FileProperties.Executor cfg = fileProperties.getExecutor();
        // Spring 封装的线程池（内部委托 ThreadPoolExecutor，并支持优雅关闭）
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：常驻线程，保证日常下载有稳定的处理能力
        executor.setCorePoolSize(cfg.getCorePoolSize());
        // 最大线程数：队列满后临时扩容的上限，用于应对突发下载高峰
        executor.setMaxPoolSize(cfg.getMaxPoolSize());
        // 队列容量：核心线程忙时任务先入队缓冲，避免频繁创建/销毁线程
        executor.setQueueCapacity(cfg.getQueueCapacity());
        // 空闲存活时间（秒）：超出核心数的空闲线程在此时间后被回收
        executor.setKeepAliveSeconds(cfg.getKeepAliveSeconds());
        // 线程名前缀：便于日志/线程栈定位是 file 模块 IO 任务（file-io-1、file-io-2...）
        executor.setThreadNamePrefix("file-io-");
        // 拒绝策略：池与队列均满时回压到调用线程执行（见方法注释，防任务被静默丢弃）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 初始化线程池（Spring 不会自动调用，需手动触发以使参数立即生效）
        executor.initialize();
        return executor;
    }

    /**
     * 注册 MVC 异步支持：StreamingResponseBody 由 fileIoExecutor 执行，并放宽异步超时
     * <p>超时单位毫秒，取 yml 配置 async-timeout-seconds（默认 600s），防大文件下载被 30s 默认值截断。</p>
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // 指定异步请求的执行器为 fileIoExecutor，使 StreamingResponseBody 的阻塞 IO 脱离 Tomcat 工作线程
        configurer.setTaskExecutor(fileIoExecutor());
        // 异步请求超时（秒 → 毫秒）：默认 30s 会截断大文件下载，这里放宽到配置值（默认 600s）
        configurer.setDefaultTimeout(fileProperties.getExecutor().getAsyncTimeoutSeconds() * 1000L);
    }


    /**
     * 注册全站 QPS 拦截器：仅作用于 /api/file/**，与各接口 @RateLimiter(USER) 并存、互不干扰
     * 全站 QPS 的本质是" 服务入口的总量闸门 "，它不区分读写，因为：
     * - 读接口同样消耗资源（Tomcat 线程、Redis 取元数据、SeaweedFS 读流），且 读往往是流量大头 ；
     * - 洪峰通常就来自读接口（列表页刷 URL、缩略图）；若把读排除，闸门等于形同虚设；
     * - 它只在 总量逼近上限 时才触发，正常情况下读接口完全不受影响
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {

    /// —— 线程池被注册到「MVC 异步机制」。
    /// 关键点：`AsyncSupportConfigurer` 设置的执行器 只服务于 Spring MVC 的「异步请求处理」 。它不是一个会被到处使用的通用线程池—— 全项目没有任何其他地方引用`fileIoExecutor` ：
    /// - 没有`@Async("fileIoExecutor")` 注解在 Service 方法上；
    /// - 没有任何类`@Resource` /`@Autowired` 注入它
    ///         - 也没有在任何地方直接调用它（如 FileServiceImpl#upload）
    /// MVC 判断一个请求是否走异步、是否动用这个执行器， 唯一依据是 Controller 方法的返回类型 。
    /// `StreamingResponseBody` 是 Spring MVC 认识的 函数式异步返回值 之一（同类还有`Callable` 、`DeferredResult` 、`WebAsyncTask` ）。只有它存在时，MVC 才会：
    /// 而上传等其他方法返回普通对象， 根本不会触发这条异步分支 ，`fileIoExecutor` 对它们完全不可见。
    /// 两个条件是 且 的关系：线程池虽然注册了，但只要没有方法返回异步类型，它就是闲置的；反过来，只改返回类型而不注册执行器，MVC 会退化用`SimpleAsyncTaskExecutor` （每次新建线程，不可控）。

        // 注册全站 QPS 拦截器，仅拦截文件接口：控制 /api/file/** 的总量上限，与各接口 @RateLimiter(USER) 并存
        registry.addInterceptor(fileGlobalQpsInterceptor).addPathPatterns("/api/file/**");
    }

}
