package com.litisle.litisleapi.moderation;


import com.litisle.litisleapi.constant.ModerationConstant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * content.unpublished 同步失效编排（板块四 4-4.1 / Review-Plan §2.1）——口径实现
 * （键名构造收口契约层 SSOT，Redis 删除操作由宿主注入，契约层零 spring-data-redis 依赖）。
 *
 * ContentUnpublishedInvalidator 负责在内容被下架、删除或转私密时，把 Redis 里所有跟这篇内容相关的审核缓存键一次性删干净，防止攻击者利用旧缓存绕过审核。
 *
 * 【定位】发布方（content）本地 Redis 失效编排器——删除/下架/转私密三路径在发
 * ContentUnpublishedEvent 的同一事务/紧随其后调用本类（缓存失效走本机 Redis 同步删，
 * 不经 MQ——事件只服务 ai/search 等远程消费方，两者职责不可互替）。
 *
 * 【失效矩阵】（三步全做，漏一步即留攻击窗口）
 * 1. last-hash 键（4-3）：防免审判定命中旧 PASS/REJECT 结论；
 * 2. text-result 键（§2.11）：按 TEXT_RESULT_KEY 四段（wordlibVer/fewShotVer/scene/contentHash）
 *    精确删——防同 hash 重发命中旧机审 PASS 直落；
 * 3. img-result 键（§2.11）：配图 sha256 列表发布方回查 file 元数据后拼好成品键名传入
 *    （含 provider/modelVersion 版本段），无配图传空列表。
 *
 * 【攻击面闭环示例】PASS 文章被人工/法务下架→作者删除→重发同内容（新 bizId 同 contentHash）：
 * 缺第 2 步则发布免审判定命中旧 text-result PASS 直落，绕过下架决定。
 *
 * 【失败语义】宿主调用处须 try/catch：仅 error 留痕不阻断业务主流程
 * （下架动作本身已生效，残留键等 TTL 过期兜底；不可因删缓存失败回滚下架事务）。
 *
 * @author litisle
 */
public final class ContentUnpublishedInvalidator {

    private ContentUnpublishedInvalidator() {
    }

    /**
     * Redis 键删除操作抽象（契约层不引 spring-data-redis 依赖，宿主注入实现）：
     * content 侧传 stringRedisTemplate::delete，单测传内存收集器即可。
     */
    @FunctionalInterface
    public interface RedisKeyDeleter {

        /**
         * 删除指定审核缓存键（一次性批量删除，键名由 invalidateAll 统一拼装）
         *
         * @param keys 待删键名集合（非空）
         */
        void delete(Collection<String> keys);
    }

    /**
     * 三路径统一失效编排：last-hash + text-result + img-result 三步全删。
     * 必须在 ContentUnpublishedEvent 发布的同一事务/紧随其后执行。
     *
     * @param wordlibVer    判定时词库版本（与 last-hash/text-result key 口径一致）
     * @param fewShotVer    判定时 few-shot 版本（text-result key 段之一）
     * @param bizType       审核业务类型（1-文章）
     * @param scene         审核场景（ARTICLE）
     * @param bizId         业务 ID
     * @param contentHash   最近一次送审文本指纹（text-result key 段之一；null=从未送审，跳过）
     * @param imgResultKeys 配图 img-result 成品键名列表（含 provider/modelVersion 版本段；
     *                      发布方回查配图 sha256 后拼好传入，无配图传空列表/null）
     * @param deleter       Redis 键删除操作（宿主注入）
     */
    public static void invalidateAll(long wordlibVer, String fewShotVer, int bizType, String scene,
                                     long bizId, String contentHash, List<String> imgResultKeys,
                                     RedisKeyDeleter deleter) {
        // 步骤0：删除操作判空 fail-fast（静默跳过会留下免审/免机审攻击窗口）
        if (deleter == null) {
            throw new IllegalArgumentException("RedisKeyDeleter 为空，禁止静默跳过缓存失效");
        }
        List<String> keys = new ArrayList<>(2 + (imgResultKeys == null ? 0 : imgResultKeys.size()));
        // 步骤1：last-hash 键——防免审判定命中旧 PASS/REJECT 结论（与 LastHashStore 同 SSOT 常量，禁各自拼串）
        keys.add(String.format(ModerationConstant.LAST_HASH_KEY, wordlibVer, bizType, scene, bizId));
        // 步骤2：text-result 键——防同 hash 重发命中旧机审 PASS 直落；null=从未送审无结果缓存
        if (contentHash != null && !contentHash.isEmpty()) {
            keys.add(String.format(ModerationConstant.TEXT_RESULT_KEY,
                    wordlibVer, fewShotVer, scene, contentHash));
        }
        // 步骤3：img-result 成品键——发布方回查配图后拼好传入，此处不重复拼装
        if (imgResultKeys != null && !imgResultKeys.isEmpty()) {
            keys.addAll(imgResultKeys);
        }
        deleter.delete(keys);
    }
}