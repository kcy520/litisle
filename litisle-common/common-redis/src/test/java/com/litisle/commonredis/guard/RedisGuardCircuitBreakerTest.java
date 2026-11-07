package com.litisle.commonredis.guard;

import com.litisle.commonredis.config.CommonRedisAutoConfiguration;
import com.litisle.commonredis.config.RateLimitFallbackProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * RedisGuard 熔断语义等价性测试（纯内存，不依赖 Redis / Spring 容器）
 *
 * 【测试目标】证明「Resilience4j 显式配置」与「自研 RedisGuard 三态语义」等价：
 * 1. 连续 3 次失败即 OPEN（不是 Resilience4j 默认的 100 次 / 失败率 50%）
 * 2. 冷却到期自动 HALF_OPEN，且只放行 1 个探测许可
 * 3. 探测成功回 CLOSED；探测失败重新 OPEN
 * 4. 生产配置值本身正确（防止 permittedNumberOfCallsInHalfOpenState=0 这类错误配置上线）
 *
 * 【为什么用真实 CircuitBreaker 而不是 Mock】
 * 本测试验证的就是「配置 → 状态机行为」的映射关系，Mock 掉熔断器会失去验证意义。
 * 每个用例使用独立的 CircuitBreakerRegistry，实例名带用例后缀，互不干扰。
 *
 * @author litisle
 */
class RedisGuardCircuitBreakerTest {

    /** 生产默认冷却期（秒），与 RateLimitFallbackProperties#cooldownSeconds 默认值一致 */
    private static final long DEFAULT_COOLDOWN_SECONDS = 5L;

    /** 等待半开状态的最长时间（毫秒），仅用于覆盖调度器线程的触发延迟 */
    private static final long AWAIT_TIMEOUT_MILLIS = 5_000L;

    /**
     * 用例1：连续 3 次失败 → OPEN，且打开后不再放行 Redis 路径
     */
    @Test
    @DisplayName("连续 3 次失败即打开熔断，打开后 acquire 返回 LOCAL")
    void opensAfterThreeConsecutiveFailures() {
        // 冷却期设 60 秒，避免用例执行期间自动转半开干扰断言
        RateLimitFallbackProperties properties = properties(60L);
        CircuitBreaker circuitBreaker = circuitBreaker(properties, "consecutiveFailures");
        RedisGuard guard = new RedisGuard(circuitBreaker, properties);

        // 前两次失败仍在 CLOSED（窗口最小调用数 3，未达阈值不打开）
        for (int i = 1; i <= 3; i++) {
            assertEquals(RedisGuard.Path.REDIS, guard.acquire(), "第 " + i + " 次调用前应为 CLOSED");
            guard.onFailure(false, new IllegalStateException("redis down " + i));
            if (i < 3) {
                assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState(), "第 " + i + " 次失败后仍应 CLOSED");
            }
        }
        // 第 3 次失败后应打开熔断
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        // OPEN 态下 acquire 直接走本地兜底，不再申请 Redis 调用
        assertEquals(RedisGuard.Path.LOCAL, guard.acquire());
    }

    /**
     * 用例2：冷却到期自动转 HALF_OPEN，且本轮只放行 1 个探测许可
     */
    @Test
    @DisplayName("冷却到期自动半开，仅第一个请求拿到 PROBE，其余走 LOCAL")
    void halfOpenAllowsSingleProbe() throws InterruptedException {
        // 冷却期 1 秒：测试内可真实等待到期
        RateLimitFallbackProperties properties = properties(1L);
        CircuitBreaker circuitBreaker = circuitBreaker(properties, "halfOpenProbe");
        RedisGuard guard = new RedisGuard(circuitBreaker, properties);

        openByThreeFailures(guard, circuitBreaker);
        // 冷却到期后由 Resilience4j 调度器自动 OPEN → HALF_OPEN
        awaitState(circuitBreaker, CircuitBreaker.State.HALF_OPEN);

        // 第一个请求拿到唯一的半开许可 → PROBE；后续请求许可被拒 → LOCAL
        assertEquals(RedisGuard.Path.PROBE, guard.acquire());
        assertEquals(RedisGuard.Path.LOCAL, guard.acquire());
        assertEquals(RedisGuard.Path.LOCAL, guard.acquire());
        // 断言期间状态仍为 HALF_OPEN（未被提前打回 CLOSED）
        assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());
    }

    /**
     * 用例3：半开探测成功回 CLOSED；再次半开探测失败重新 OPEN
     */
    @Test
    @DisplayName("探测成功恢复 CLOSED，探测失败重新 OPEN")
    void probeSuccessClosesAndProbeFailureReopens() throws InterruptedException {
        RateLimitFallbackProperties properties = properties(1L);
        CircuitBreaker circuitBreaker = circuitBreaker(properties, "probeRecovery");
        RedisGuard guard = new RedisGuard(circuitBreaker, properties);

        // ---- 第一轮：打开 → 半开 → 探测成功 → CLOSED ----
        openByThreeFailures(guard, circuitBreaker);
        awaitState(circuitBreaker, CircuitBreaker.State.HALF_OPEN);
        assertEquals(RedisGuard.Path.PROBE, guard.acquire());
        guard.onSuccess(true);
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        // 恢复后正常请求重新走 Redis
        assertEquals(RedisGuard.Path.REDIS, guard.acquire());
        guard.onSuccess(false);

        // ---- 第二轮：再次连续 3 次失败 → 打开 → 半开 → 探测失败 → 重新 OPEN ----
        openByThreeFailures(guard, circuitBreaker);
        awaitState(circuitBreaker, CircuitBreaker.State.HALF_OPEN);
        assertEquals(RedisGuard.Path.PROBE, guard.acquire());
        guard.onFailure(true, new IllegalStateException("probe failed"));
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
    }

    /**
     * 用例4：复位路径（未申请许可的 onSuccess/onFailure）不得绕过半开探测
     *
     * 对应原自研实现里 LoginRetryLimiter#resetFailure 的「无许可复位」调用：
     * 旧实现借 onSuccess 把 OPEN 直接打回 CLOSED，会跳过半开探测；
     * 迁移后必须只允许在 CLOSED 下清计数，OPEN / HALF_OPEN 下一律忽略。
     */
    @Test
    @DisplayName("无许可复位在 OPEN/HALF_OPEN 下不得把熔断打回 CLOSED，也不得吃掉探测许可")
    void resetPathDoesNotBypassHalfOpenProbe() throws InterruptedException {
        RateLimitFallbackProperties properties = properties(1L);
        CircuitBreaker circuitBreaker = circuitBreaker(properties, "resetPath");
        RedisGuard guard = new RedisGuard(circuitBreaker, properties);

        // ---- OPEN 态：复位型成功上报必须被忽略 ----
        openByThreeFailures(guard, circuitBreaker);
        guard.onSuccess(false);
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState(), "复位路径不得把 OPEN 打回 CLOSED");

        // ---- HALF_OPEN 态：复位型成功/失败上报都必须被忽略 ----
        awaitState(circuitBreaker, CircuitBreaker.State.HALF_OPEN);
        guard.onSuccess(false);
        guard.onFailure(false, new IllegalStateException("reset failed"));
        assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreaker.getState(), "复位路径不得绕过半开探测");
        // 探测许可未被复位路径消耗，仍可正常拿到 PROBE
        assertEquals(RedisGuard.Path.PROBE, guard.acquire());
    }

    /**
     * 用例5：生产配置必须显式等价，不能落回 Resilience4j 默认值，也不能把半开许可配成 0
     */
    @Test
    @DisplayName("生产熔断配置显式等价：失败率 100 / 窗口 3 / 最小调用 3 / 半开许可 1 / 自动转半开")
    void productionConfigIsExplicitlyEquivalent() {
        CircuitBreakerConfig config = CommonRedisAutoConfiguration.createCircuitBreakerConfig(properties(DEFAULT_COOLDOWN_SECONDS));

        // 三项一起对齐才等价「连续 3 次失败即开」
        assertEquals(100F, config.getFailureRateThreshold());
        assertEquals(3, config.getSlidingWindowSize());
        assertEquals(3, config.getMinimumNumberOfCalls());
        // 半开许可必须 >= 1，否则降级后永远无法恢复（0 会让探测请求全部被拒）
        assertEquals(1, config.getPermittedNumberOfCallsInHalfOpenState());
        assertTrue(config.isAutomaticTransitionFromOpenToHalfOpenEnabled());
        // 冷却期（waitDurationInOpenState）映射不直接断言字段（2.x 该配置以 IntervalFunction 形式暴露，
        // 无同名 getter），由用例2「1 秒冷却后自动半开」以真实行为覆盖
    }

    /**
     * 构造降级配置
     *
     * @param cooldownSeconds 熔断 OPEN 冷却期（秒）
     * @return 启用本地兜底的配置对象
     */
    private static RateLimitFallbackProperties properties(long cooldownSeconds) {
        RateLimitFallbackProperties properties = new RateLimitFallbackProperties();
        properties.setEnabled(true);
        properties.setCooldownSeconds(cooldownSeconds);
        properties.setInstanceCount(1);
        return properties;
    }

    /**
     * 按生产配置创建独立熔断器
     *
     * @param properties 降级配置（提供冷却期）
     * @param instanceName 实例名（用例间隔离，避免共享状态）
     * @return 熔断器实例
     */
    private static CircuitBreaker circuitBreaker(RateLimitFallbackProperties properties, String instanceName) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(
                CommonRedisAutoConfiguration.createCircuitBreakerConfig(properties));
        return registry.circuitBreaker("redisGuard-" + instanceName);
    }

    /**
     * 通过 3 次失败把熔断器打开，并断言过程中路径判定正确
     *
     * @param guard         待测适配层
     * @param circuitBreaker 熔断器（用于断言最终状态）
     */
    private static void openByThreeFailures(RedisGuard guard, CircuitBreaker circuitBreaker) {
        for (int i = 1; i <= 3; i++) {
            assertEquals(RedisGuard.Path.REDIS, guard.acquire());
            guard.onFailure(false, new IllegalStateException("redis down " + i));
        }
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
    }

    /**
     * 轮询等待熔断器进入指定状态（覆盖自动转半开的调度延迟）
     *
     * @param circuitBreaker 熔断器
     * @param expected       期望状态
     */
    private static void awaitState(CircuitBreaker circuitBreaker, CircuitBreaker.State expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (circuitBreaker.getState() == expected) {
                return;
            }
            Thread.sleep(20L);
        }
        fail("等待熔断器进入 " + expected + " 超时，当前状态=" + circuitBreaker.getState());
    }
}
