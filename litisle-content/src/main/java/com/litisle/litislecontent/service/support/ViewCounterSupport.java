package com.litisle.litislecontent.service.support;

import com.litisle.litislecontent.constant.ContentBizConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 浏览计数支撑组件（阶段8①：PV+去重窗口口径，§8-①）。
 *
 * 作用：
 * 详情命中后的浏览计数单点收口——Redis INCR 累计待刷回增量 + SETNX 去重窗口防刷，
 * 本体计数由 ViewCountFlushJob 定时 GETDEL 合并刷回 article.view_cnt（对齐建表注释
 * 「Redis 异步刷新」设计意图）；Redis 故障 fail-safe（吞异常留 warn，浏览计数不阻断读）。
 *
 * 【口径】PV（进入次数）+ 去重窗口，非纯 UV：
 * 浏览量是热度信号而非独立访客统计，UV 压缩信号失真；匿名场景拿不到 userId
 * （热门接口匿名可访问），UV 需设备指纹归一复杂度陡增。
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ViewCounterSupport {

    /** 字符串 Redis 模板（INCR/SETNX 原子操作载体） */
    private final StringRedisTemplate stringRedisTemplate;

    /** 去重窗口 TTL（秒）：@Value 注入支持配置覆盖，默认对齐常量 24h */
    @org.springframework.beans.factory.annotation.Value("${litisle.content.view.dedup-ttl-seconds:86400}")
    private long dedupTtlSeconds;

    /**
     * redis记录一次浏览（带作者标识，作者本人浏览不计入 PV）。
     *
     * 作用：先判断「浏览者是否为内容作者」，是则直接返回；否则复用原计数链路。
     * 典型调用方：文章详情、章节阅读（11.11-A 自浏览排除）。
     *
     * @param articleId 文章ID
     * @param authorId  内容作者ID（可为 null；null 表示无法判定，按非作者处理）
     * @param viewerId  查看者用户ID（null=匿名，不会与作者ID命中）
     * @param ip        访问者 IP（匿名去重基准之一）
     * @param userAgent 访问者 User-Agent（匿名去重基准之一）
     */
    public void recordView(Long articleId, Long authorId, Long viewerId, String ip, String userAgent) {
        // 11.11-A：作者本人浏览不计 PV，避免污染 article_stat.view_count（推荐流排序统计量之一）
        if (authorId != null && authorId.equals(viewerId)) {
            return;
        }
        //redis记录一次浏览
        recordView(articleId, viewerId, ip, userAgent);
    }

    /**
     * 记录一次浏览（详情命中后调用，fail-safe：Redis 故障仅 warn 不阻断读链路）。
     *
     * 作用：去重窗口判定（SETNX 抢占）→ 抢占成功才 INCR 待刷回增量；
     * 窗口期内同一访问者重复进入不计数（防刷）。
     *
     * @param articleId 文章ID
     * @param viewerId  查看者用户ID（null=匿名，用 ip+UA 哈希归一）
     * @param ip        访问者 IP（匿名去重基准之一）
     * @param userAgent 访问者 User-Agent（匿名去重基准之一）
     */
    public void recordView(Long articleId, Long viewerId, String ip, String userAgent) {
        try {
            // 步骤1：归一访问者标识（登录人直接用 userId；匿名用 ip+UA 哈希，无隐私明文落键）
            String visitor = viewerId != null
                    ? "u" + viewerId
                    : "a" + hashVisitor(ip, userAgent);
            // 步骤2：去重窗口抢占（SETNX+EXPIRE 原子语义：键存在=窗口期内重复进入不计数）
                /// {prefix}:{articleId}:{visitor}
                //登录用户：u + userId
                //匿名用户：a + SHA-256(ip+|`+userAgent) 前32位
            String dedupKey = ContentBizConstant.VIEW_DEDUP_KEY_PREFIX + articleId + ":" + visitor;
            Boolean first = stringRedisTemplate.opsForValue().setIfAbsent(dedupKey, "1",
                    Duration.ofSeconds(dedupTtlSeconds));
            if (!Boolean.TRUE.equals(first)) {
                return;
            }
            // 步骤3：待刷回增量累计（INCR；无 TTL——增量由定时任务 GETDEL 消费，残留键手动可清）
            stringRedisTemplate.opsForValue().increment(
                    ContentBizConstant.VIEW_CNT_PENDING_KEY_PREFIX + articleId);
        } catch (Exception e) {
            // 浏览计数非核心读链路：Redis 故障 fail-safe 吞异常（丢一次计数 vs 详情打不开，取后者）
            log.warn("浏览计数记录失败（fail-safe 不阻断读）：articleId={}, viewerId={}", articleId, viewerId, e);
        }
    }

    /**
     * 匿名访问者哈希归一（ip+UA SHA-256 截断 16 进制）。
     *
     * @param ip        访问者 IP
     * @param userAgent 访问者 User-Agent
     * @return 32 位十六进制哈希（明文 ip/UA 不落键，降隐私面）
     */
    private String hashVisitor(String ip, String userAgent) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(
                    ((ip == null ? "" : ip) + "|" + (userAgent == null ? "" : userAgent))
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            // JVM 必带 SHA-256，理论不可达；防御性退化为拼接串（窗口语义不受影响）
            return String.valueOf(((ip == null ? "" : ip) + (userAgent == null ? "" : userAgent)).hashCode());
        }
    }
}
