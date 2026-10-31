package com.litisle.litislereview.dfa;

import com.litisle.litislereview.domain.entity.WordlibHitStat;
import com.litisle.litislereview.enums.WordSceneEnum;
import com.litisle.litislereview.mapper.WordlibHitStatMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 词级命中统计记录器（wordlib_hit_stat §5.4，词库治理建议清单数据源）
 *
 * <p>接线点：异步机审建任务处（ModerationTextService 任务 CAS 流转成功后），按任务快照 hitWords
 * 逐词累加命中量。HIGH/MID 命中均统计（MID 转正看命中观察量、HIGH 降级看误报绝对量）</p>
 *
 * <p>口径（已知局限，清单标注）：仅在 review 进程内异步机审处累加——所有送审内容都会建任务过
 * review，数据完整；同步闸门 HIGH 拦截发生在宿主本地，不建任务不经过此处，分母缺失为已知局限</p>
 *
 * <p>可靠性约束：统计属运营证据非审核主链路——单词落库失败仅 error 继续，整体方法不抛异常，
 * 绝不影响审核事务；与 wordlib_misreport 同套 upsert 模板（先累加→无行首插→撞唯一键降级累加）</p>
 *
 * <p>快照格式局限：hitWords 为逗号拼接 CSV（与 ModerationRecord 留痕格式一致），而词校验允许词内
 * 含逗号——此类词快照后无法还原边界，可能被拆成两个"伪词"分别统计；属既有快照格式局限，
 * 证据量级不受影响，一期不处理</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WordlibHitStatRecorder {

    /** hitWords 快照 CSV 分隔符（与任务创建处 String.join(",", ...) 口径一致） */
    private static final String HIT_WORDS_SEPARATOR = ",";

    private final WordlibHitStatMapper wordlibHitStatMapper;

    /**
     * 按任务快照累加词级命中统计（解析 CSV → 场景归一 → 去重 → 逐词 upsert）
     *
     * <p>整体不抛异常：统计失败绝不阻断审核主链路（宁少统计不误伤审核）</p>
     *
     * @param sceneCode    词库场景 code（调用方经 requireWordScene 归一映射后的值，如 ARTICLE）
     * @param hitWordsCsv  任务快照 hitWords（逗号拼接；null/空白=本次无命中词，直接跳过）
     */
    public void recordFromSnapshot(String sceneCode, String hitWordsCsv) {
        // 无命中词快照：正常情况（未触发词库），静默跳过
        if (!StringUtils.hasText(hitWordsCsv) || !StringUtils.hasText(sceneCode)) {
            return;
        }
        // 场景 code 归一为枚举（入库走 @EnumValue 映射）；未知场景属脏数据，warn 后放弃本次统计
        WordSceneEnum scene = resolveScene(sceneCode);
        if (scene == null) {
            log.warn("词级命中统计场景 code 非法，放弃本次统计，scene={}", sceneCode);
            return;
        }
        // 解析 CSV + 去重：同任务同词只计一次（快照内重复词来自 DFA 多次命中同一词，按次累加会虚高）
        Set<String> words = Arrays.stream(hitWordsCsv.split(HIT_WORDS_SEPARATOR))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // 逐词 upsert：单词失败 error 继续下一词，不中断本批
        for (String word : words) {
            try {
                upsertOne(scene, word);
            } catch (Exception e) {
                log.error("词级命中统计累加失败（不阻断审核主链路），scene={}, word={}", sceneCode, word, e);
            }
        }
    }

    /**
     * 单词 upsert：先原子累加 → 无既有行（影响 0 行）时首插 → 并发撞唯一键降级为再累加
     *
     * <p>照抄 WordlibServiceImpl.saveMisreportRecord 模板，保证并发首插窗口下的最终一致</p>
     *
     * @param scene 词库场景枚举
     * @param word  命中词（词库规范词）
     */
    private void upsertOne(WordSceneEnum scene, String word) {
        // 第一步：原子累加（SQL 侧 hit_count+1，无读-改-写竞态）；影响 0 行 = 无既有统计行
        int affected = wordlibHitStatMapper.incrementHitCount(scene.getCode(), word);
        if (affected > 0) {
            return;
        }
        // 第二步：首插（hit_count=1；first/last_hit_time 由 DB 默认值 current_timestamp 兜底）
        WordlibHitStat row = new WordlibHitStat()
                .setScene(scene)
                .setWord(word)
                .setHitCount(1);
        try {
            wordlibHitStatMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 第三步：并发首插撞 uk_scene_word 唯一键 → 行已被他实例建出，降级为再累加
            wordlibHitStatMapper.incrementHitCount(scene.getCode(), word);
        }
    }

    /**
     * 场景 code 反查枚举（WordSceneEnum 未提供静态 fromCode，此处线性查找——枚举仅 5 值开销可忽略）
     *
     * @param sceneCode 场景 code（WordSceneEnum 入库值）
     * @return 匹配的枚举；无匹配返回 null（调用方 warn 后放弃）
     */
    private WordSceneEnum resolveScene(String sceneCode) {
        for (WordSceneEnum e : WordSceneEnum.values()) {
            if (e.getCode().equals(sceneCode)) {
                return e;
            }
        }
        return null;
    }
}
