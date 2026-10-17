package com.litisle.litislecontent.job;


import com.litisle.litislecontent.mapper.TagMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 标签 use_count 定时对账任务（[6.2] 冗余计数兜底机制）：
 * 挂载/摘除同事务原子增减为主（正确性来源），本任务仅定时校正漂移行
 * （bug/人工修数累积偏差），与 view_cnt 的「异步刷新」机制定位对齐
 *
 * @author litisle
 */
@Component
@EnableScheduling
@Slf4j
@RequiredArgsConstructor
public class TagCountReconcileJob {

    private final TagMapper tagMapper;

    /** 对账开关（默认开；多实例部署时建议只开一台或改用分布式锁） */
    @Value("${litisle.content.tag.reconcile.enabled:true}")
    private boolean enabled;

    /**
     * 定时对账：单条 UPDATE 自联仅修 use_count ≠ 实际挂载数的漂移行，零事务竞争
     */
    @Scheduled(fixedDelayString = "${litisle.content.tag.reconcile.interval-ms:3600000}",
            initialDelayString = "${litisle.content.tag.reconcile.initial-delay-ms:300000}")
    public void reconcile() {
        if (!enabled) {
            return;
        }
        int fixed = tagMapper.reconcileUseCount();
        if (fixed > 0) {
            log.warn("tag use_count 对账校正漂移行数={}", fixed);
        }
    }
}