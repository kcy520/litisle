package com.litisle.litislecontent.cache;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.dfa.DfaFacade;
import com.litisle.litisleapi.dfa.DfaScenes;
import com.litisle.litisleapi.feign.FileClient;
import com.litisle.litisleapi.moderation.LastHashStore;
import com.litisle.litislecontent.domain.entity.ArticleImage;
import com.litisle.litislecontent.mapper.ArticleImageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * content.unpublished 三路径（删除/转私密/批量转私密）的审核缓存失效编排（板块三 [3.4] / 4-4.1 宿主侧落地）。
 *
 * 【方案 A：SCAN 模式失效】text-result/img-result 缓存键含 review 侧运行时版本段
 * （fewShotVer / provider / modelVersion），content 侧不可感知 → 按业务段通配版本段 SCAN，
 * 精确删除当前业务命中的全部版本键。旧版本键天然不构成攻击窗口：免审判定/缓存查重
 * 只用当前版本拼 key（LastHashExemptChecker 口径），通配仅扩大清理面不扩大放行面。
 *
 * 【失效矩阵（对齐 ContentUnpublishedInvalidator 契约语义）】
 * 1. last-hash：精确删当前 wordlibVer 的 last-hash key（复用 LastHashStore.remove SSOT）；
 * 2. text-result：SCAN review:text-result:*:*:{scene}:{contentHash}（版本段通配）；
 * 3. img-result：回查配图 sha256（FileClient.batchGetSha256），逐图 SCAN review:img-result:*:*:{sha256}。
 *
 * 【失败语义】整体 try/catch fail-safe：失效失败仅 ERROR 留痕不抛出（不阻断下架主流程，
 * 残留键由 TTL 自然过期兜底——缓存键均有 72h±10% TTL）；配图回查 Feign 失败按无图处理。
 *
 * 【调用时机】发 ContentUnpublishedEvent 的同一事务内同步调用（Invalidator 契约：
 * 同一事务/紧随其后），事件经 afterCommit 钩子发送，失效动作在事务内完成。
 *
 * @author litisle
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class UnpublishedCacheCleaner {

    /** SCAN 单次扫描批量（防大 keyspace 一次拉取过多；文章级缓存键量极小，够用） */
    private static final int SCAN_COUNT = 500;

    private final StringRedisTemplate stringRedisTemplate;

    /** DFA 门面（取当前词库版本快照拼 last-hash key，与发布侧免审判定同 key 同源） */
    private final DfaFacade dfaFacade;

    /** last-hash Redis 读写适配（契约层接口，宿主注入实现） */
    private final LastHashStore.RedisAdapter lastHashRedisAdapter;

    private final ArticleImageMapper articleImageMapper;

    private final FileClient fileClient;

    /**
     * 文章下架三键失效编排（last-hash + text-result + img-result）。
     * 失败不抛出（fail-safe 留痕，残留键 TTL 兜底），可安全在事务内同步调用。
     *
     * @param articleId   文章ID（last-hash key 的 bizId 段）
     * @param contentHash 发布送审文本指纹（text-result key 的 contentHash 段，可为空=跳过文本键）
     */
    public void invalidateArticle(Long articleId, String contentHash) {
        // 步骤1：整体 fail-safe——失效失败不阻断下架主流程
        try {
                //查询当前词库版本
            long wordlibVer = dfaFacade.currentVersion(DfaScenes.ARTICLE);

            // 步骤2：last-hash 精确删（SSOT 口径：仅删当前 wordlibVer 的 key，热更后旧键发布判定不会命中，等 TTL 过期）
                //删除 last-hash：content.unpublished
                //value 里存的是 ：`{"hash":"sha256(标题+正文)","verdict":1}` ——即 这篇文章上一次审核通过时的内容指纹 + 结论 ，跳过整条审核链路，直接发布。
                //存的是「终局结论」。对比 text-result：那个键 只存机审结论 （review 消费端调模型前后写的，SUSPICIOUS 非终局禁写），人审不碰它。
                LastHashStore.remove(wordlibVer, ModerationConstant.BIZ_TYPE_ARTICLE,
                    DfaScenes.ARTICLE, articleId, lastHashRedisAdapter);

            // 步骤3：汇总待删键（text-result 版本段通配 SCAN + img-result 逐图 SCAN）
                    //收集到的键里存的是 ： 机审对这段文字的终局结论 （PASS/REJECT，SUSPICIOUS 非终局禁写），TTL 72h。
            List<String> keys = new ArrayList<>();
            if (contentHash != null && !contentHash.isBlank()) {
                scanCollect(String.format(ModerationConstant.TEXT_RESULT_SCAN_PATTERN,
                        DfaScenes.ARTICLE, contentHash), keys);
            }
                    //收集到的键里存的是 ： 识别模型对某张图片的机审结论 （配合 OnnxImageModerationAdapter 的 reject/suspicious 概率判定）。
            for (String sha256 : collectImgSha256(articleId)) {
                scanCollect(String.format(ModerationConstant.IMG_RESULT_SCAN_PATTERN, sha256), keys);
                //question：如果该图形在其他文章中是免审的，但是在这里被删了，会影响其他文章的免审吗？
            }

            // 步骤4：批量删除收集到的缓存键
            if (!keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
            }
            log.info("下架缓存失效完成，articleId={}，contentHash={}，deletedKeys={}",
                    articleId, contentHash, keys.size());
        } catch (Exception e) {
            // 残留键由 TTL 兜底（72h±10%），仅留痕供排查
            log.error("下架缓存失效失败（不阻断主流程，残留键等 TTL 过期），articleId={}", articleId, e);
        }
    }



    /**
     * SCAN 收集匹配键（版本段通配，键名唯一收集防重）—— redis。
     *
     * 在文章下架（unpublished）时，用 Redis 的 SCAN 命令，
     * 按通配模式增量扫描并收集需要失效的缓存 key，然后统一删除，避免一次性阻塞 Redis。
     *
     * @param pattern SCAN 匹配模式（ModerationConstant *_SCAN_PATTERN 格式化）
     * @param keys    收集容器
     */
    private void scanCollect(String pattern, List<String> keys) {
        // 使用 Redis 的 SCAN 命令进行增量迭代查询
        // stringRedisTemplate.scan() 返回 Cursor<String>，底层基于 SCAN，不会一次性加载所有 key
        try (Cursor<String> cursor = stringRedisTemplate.scan(
                // 构建 ScanOptions，指定匹配模式和每次扫描数量
                ScanOptions.scanOptions()
                        .match(pattern)   // 匹配模式，如 "fewShotVer:*"、"user:*"
                        .count(SCAN_COUNT) // 每次迭代建议返回的数量（Redis 仅作为参考）
                        .build()
        )) {

            // 循环遍历游标，直到所有匹配的 key 都被扫描完毕
            while (cursor.hasNext()) {
                // 获取当前扫描到的 key
                String key = cursor.next();
                // 去重判断：避免重复 key 被加入结果集
                // （在生产环境中 Redis 不会重复返回，但防御性编程更安全）
                if (!keys.contains(key)) {
                    keys.add(key);
                }
            }

        }
    // try-with-resources 结束，Cursor 自动关闭，释放与 Redis 的连接资源
    }

    /**
     * 回查文章配图 sha256 列表（img-result 键按 sha256 定位）。
     * Feign 失败/空数据按无图处理（降级不阻断——图片缓存键残留等 TTL 兜底）。
     *
     * @param articleId 文章ID
     * @return 去重后的 sha256 列表（无图/失败返回空列表）
     */
    private List<String> collectImgSha256(Long articleId) {
        List<ArticleImage> images = articleImageMapper.selectList(new LambdaQueryWrapper<ArticleImage>()
                .eq(ArticleImage::getArticleId, articleId));
        if (images.isEmpty()) {
            return List.of();
        }
        List<String> fileIds = images.stream().map(ArticleImage::getFileId).toList();
        Map<String, String> shaMap;
        try {
            shaMap = fileClient.batchGetSha256(fileIds).getData();
        } catch (Exception e) {
            log.warn("配图 sha256 回查失败（图片缓存键降级不清理），articleId={}", articleId, e);
            return List.of();
        }
        if (shaMap == null || shaMap.isEmpty()) {
            return List.of();
        }
        return shaMap.values().stream()
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .toList();
    }
}