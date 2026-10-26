package com.litisle.litislecontent.service.support;


import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleapi.dto.file.FileOwnerDTO;
import com.litisle.litisleapi.dto.file.FileRefDTO;
import com.litisle.litisleapi.feign.FileClient;
import com.litisle.litislecontent.constant.ContentBizConstant;
import com.litisle.litislecontent.domain.dto.ArticleImageDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文章/草稿插图引用支撑组件（阶段6 方案C content 侧补链公共收口）。
 *
 * 作用：
 * 统一收口 文章/草稿 两条链路的插图归属校验与 file_ref 引用登记/解除，消除双份重复实现：
 *   ① 批量归属校验：file 侧 batchGetOwners 一次往返（收口原逐图 getFileInfo 的 N+1）；
 *   ② 事务内 bindRef（fail-fast）：Feign 失败抛异常随本地事务回滚，无「挂图未登记」中间态；
 *   ③ afterCommit unbindRef（warn 留痕）：编辑重挂差集/软删/草稿清理的补偿性解绑，
 *      失败仅告警不阻断——残留引用仅晚删/不删，保守方向不丢数据（file 侧「仍有引用跳过并告警」兑底）；
 *   ④ 新旧差集计算：编辑重挂/快照滚动清理的弃图判定基准
 *
 * 【四元组精确解绑铁律】unbindRef 必须传完整 (fileId,bizType,bizId,ownerId)，禁按 fileId 删——
 * 同一图可被秒传共享/多文引用，按 fileId 删会悬空他人引用（方案C 设计前提）
 *
 * 【引用维度约定】
 *   文章：ARTICLE_IMG + bizId=articleId（发布/编辑/软删链路）
 *   草稿：DRAFT_IMAGE + bizId=draftId（草稿保存/清理/删除链路），发布归属切换时 unbind 后转文章维度
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleImageRefSupport {

    /** 文件服务 Feign 契约（批量归属查询 + 引用登记/解除） */
    private final FileClient fileClient;

    /**
     * 批量插图归属校验：fileId 归属本人且 bizType=ARTICLE_IMG（fail-fast，防引用他人文件/违规图）
     * <p>file 侧 batchGetOwners 一次往返（任一 fileId 无活跃记录由提供方抛 FILE_NOT_FOUND）；
     * 校验口径 file_meta.bizType=ARTICLE_IMG（上传时落定，草稿/文章共用同一批上传文件）
     *
     * @param images  待校验配图列表
     * @param ownerId 期望归属用户ID
     * @throws BizException ARTICLE_IMG_REFUSED 归属人不符 / bizType 非正文图 / 文件不存在
     */
    public void checkOwnership(List<ArticleImageDTO> images, Long ownerId) {
        // 防御：null/空列表直接放行（无图无校验语义，同时避免 NPE 与无意义 Feign 往返）
        if (images == null || images.isEmpty()) {
            return;
        }
        // 去重后一次批量往返（同一 fileId 多次出现只查一次）
        List<String> fileIds = images.stream()
                .map(ArticleImageDTO::getFileId)
                .distinct()
                .toList();
        List<FileOwnerDTO> owners = fileClient.batchGetOwners(fileIds).getData();
        // 降级/脏数据防御：data 为空或条数不足视为校验不通过（fail-fast，不静默放行）
        if (owners == null || owners.size() < fileIds.size()) {
            throw new BizException(ErrorCode.ARTICLE_IMG_REFUSED);
        }
        // 按 fileId 索引后逐条判定（归属人 + bizType 白名单双条件）
        Map<String, FileOwnerDTO> ownerMap = owners.stream()
                .collect(Collectors.toMap(FileOwnerDTO::getFileId, Function.identity(), (a, b) -> a));
        //逐一校验文件归属，fail-fast
        for (String fileId : fileIds) {
            FileOwnerDTO ownerInfo = ownerMap.get(fileId);
            if (ownerInfo == null
                    || !ownerId.equals(ownerInfo.getOwnerId())
                    || !ContentBizConstant.FILE_BIZ_TYPE_ARTICLE_IMG.equals(ownerInfo.getBizType())) {
                throw new BizException(ErrorCode.ARTICLE_IMG_REFUSED);
            }
        }
    }

    /**
     * 事务内逐图登记文章维度引用（ARTICLE_IMG + bizId=articleId，幂等）
     * <p>必须在本地事务内调用：bindRef 降级 fail-fast，Feign 失败抛异常随事务回滚，
     * 保证无「挂图未登记」中间态（验证点：编辑时 Feign 失败 → article_image 与 file_ref 同生同灭）
     *
     * @param images    配图列表（按 distinct fileId 登记，同文件多位置只登记一次）
     * @param articleId 文章ID（引用 bizId）
     * @param ownerId   引用归属用户ID（文章作者）
     */
    public void bindArticleRefs(List<ArticleImageDTO> images, Long articleId, Long ownerId) {
        bindRefs(distinctFileIds(images), ContentBizConstant.FILE_BIZ_TYPE_ARTICLE_IMG, articleId, ownerId);
    }

    /**
     * 事务内逐图登记草稿维度引用（DRAFT_IMAGE + bizId=draftId，幂等）
     * <p>与文章维度隔离（防 bind/unbind 维度错配）；发布归属切换时由发布链路 unbind 本维度
     *
     * @param images  配图列表（按 distinct fileId 登记）
     * @param draftId 草稿快照ID（引用 bizId）
     * @param ownerId 引用归属用户ID（草稿作者）
     */
    public void bindDraftRefs(List<ArticleImageDTO> images, Long draftId, Long ownerId) {
        bindRefs(distinctFileIds(images), ContentBizConstant.FILE_BIZ_TYPE_DRAFT_IMG, draftId, ownerId);
    }

    /**
     * 封面归属校验（阶段8②）：fileId 归属本人且 bizType=ARTICLE_IMG（封面上传时落定维度，
     * 与正文配图同源），fail-fast 防挂他人文件当封面（checkImageOwnership 只查正文 images 的补口）
     * <p>复用 batchGetOwners 一次往返（单元素批量，语义与 N+1 收口一致）
     *
     * @param coverFileId 封面文件ID（可空=无封面，直接放行）
     * @param ownerId     期望归属用户ID
     * @throws BizException ARTICLE_IMG_REFUSED 归属人不符 / bizType 非正文图 / 文件不存在
     */
    public void checkCoverOwnership(String coverFileId, Long ownerId) {
        // 无封面直接放行（可空字段语义）
        if (coverFileId == null || coverFileId.isEmpty()) {
            return;
        }
        // 单文件走批量契约（单元素列表，复用同一归属判定口径）
        List<FileOwnerDTO> owners = fileClient.batchGetOwners(List.of(coverFileId)).getData();
        FileOwnerDTO ownerInfo = (owners == null || owners.isEmpty()) ? null : owners.get(0);
        if (ownerInfo == null
                || !ownerId.equals(ownerInfo.getOwnerId())
                || !ContentBizConstant.FILE_BIZ_TYPE_ARTICLE_IMG.equals(ownerInfo.getBizType())) {
            throw new BizException(ErrorCode.ARTICLE_IMG_REFUSED);
        }
    }

    /**
     * 事务内单文件登记引用（阶段8②封面专用入口，幂等；bindRef 降级 fail-fast 随事务回滚）
     *
     * @param fileId  封面文件ID
     * @param bizType 引用维度（FILE_BIZ_TYPE_COVER）
     * @param bizId   引用方业务主键（articleId/draftId）
     * @param ownerId 引用归属用户ID
     */
    public void bindSingleRef(String fileId, String bizType, Long bizId, Long ownerId) {
        if (fileId == null || fileId.isEmpty()) {
            return;
        }
        fileClient.bindRef(buildRef(fileId, bizType, bizId, ownerId));
    }

    /**
     * 事务内逐个登记引用（bindRef 幂等：同四元组已存在则跳过，重试安全）
     *
     * @param fileIds 去重后的文件ID列表
     * @param bizType 引用维度（FILE_BIZ_TYPE_* 常量）
     * @param bizId   引用方业务主键（articleId/draftId）
     * @param ownerId 引用归属用户ID
     */
    private void bindRefs(List<String> fileIds, String bizType, Long bizId, Long ownerId) {
        for (String fileId : fileIds) {
            fileClient.bindRef(buildRef(fileId, bizType, bizId, ownerId));
        }
    }






    /**
     * afterCommit 逐个解除引用（幂等，四元组精确定位；失败 warn 留痕不阻断主流程）
     * <p>事务活跃时注册 afterCommit 执行（保证业务变更先落库再解绑）；已处于 afterCommit 上下文
     * （如草稿快照滚动清理回调）时自动降级为直接解绑（防注册失效，见方法体注释）；
     * 补偿性清理不进主事务——解绑失败仅残留引用（晚删/不删，保守方向不丢数据），
     * 若随主事务 fail-fast 反而会让已成立的业务变更回滚；
     * file 侧「仍有引用跳过并告警」兑底，对账任务最终一致
     *
     * @param fileIds 待解绑文件ID列表（自动去重）
     * @param bizType 引用维度（FILE_BIZ_TYPE_* 常量）
     * @param bizId   引用方业务主键（articleId/draftId）
     * @param ownerId 引用归属用户ID（四元组之一，禁缺省）
     */
    public void unbindRefsAfterCommit(List<String> fileIds, String bizType, Long bizId, Long ownerId) {
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }


        ///  这里一堆注释，总结：没懂，先跳过，总之作用就是逐个解除文件引用
//        HashSet → 去重  Linked → 保留插入顺序   即这一步是去重并保留顺序   其实并无保留顺序的必要，只不过是防御编程，没必要打乱
//        生成的是不可变列表（防止后续被误改）
        List<String> distinct = List.copyOf(new LinkedHashSet<>(fileIds));


        //afterCommit 的语义是：“业务变更已落库，再做清理”

       //为什么不能只保留else，去掉if？ 不能去掉，因为去掉之后，在「主事务内」调用时，解绑会在事务提交之前执行，主事务一回滚，文件引用就白删了。
        // 为什么不把解绑和主事务绑定在一起？因为解绑是"补偿清理"，不是"业务核心"。把它塞进主事务，会让主事务变重、变脆、变难回滚。

    //如果主事务回滚了，并且走的是 else 分支，那确实会"误删"——但这在现实中是不会发生的。  主事务回滚时，isSynchronizationActive() 一定是 true，不可能走 else。

        // 事务活跃 → 注册提交后执行；不活跃（已在 afterCommit 回调内，如快照清理）→ 直接解绑，
        // 此阶段注册新同步器不会被执行（Spring 遍历的是注册时已固定的快照列表），降级防静默失效
            // TransactionSynchronizationManager.isSynchronizationActive() : 判断“当前线程里，Spring 事务同步机制是否处于可用状态”。
        if (TransactionSynchronizationManager.isSynchronizationActive()) {

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    unbindRefsNow(distinct, bizType, bizId, ownerId);
                }
            });
        } else {

//            else 覆盖的是：调用方本身就不在事务里，或者调用方已经“出了事务边界”，但业务逻辑上确实需要清理文件引用。
            //else:
//            因为 registerSynchronization 只能在"事务还没提交"时注册，
//            如果已经提交了（比如自己在 afterCommit 里被调用），注册了也不会执行，
//            -----因为 unbindRefsAfterCommit 这个方法，不只在主事务里被调用，它也可能被「afterCommit 回调本身」再调一次。
//            所以 else 直接调 unbindRefsNow()，别注册了，直接干

            unbindRefsNow(distinct, bizType, bizId, ownerId);
        }
    }

    //    unbindRefsNow 只负责"怎么干"，unbindRefsAfterCommit 负责"什么时候干"。

    /**
     * 直接逐个解除引用（不做事务编排，供 afterCommit 上下文复用；失败 warn 留痕不抛出）
     *
     * @param fileIds 去重后的文件ID列表
     * @param bizType 引用维度（FILE_BIZ_TYPE_* 常量）
     * @param bizId   引用方业务主键
     * @param ownerId 引用归属用户ID
     */
    private void unbindRefsNow(List<String> fileIds, String bizType, Long bizId, Long ownerId) {
        for (String fileId : fileIds) {
            try {
                fileClient.unbindRef(buildRef(fileId, bizType, bizId, ownerId));
            } catch (Exception e) {
                // 补偿性解绑失败：warn 留痕不抛出（残留引用由 file 侧对账兑底，不阻断已提交业务）
                log.warn("file_ref 解绑失败（残留引用由对账兑底）：fileId={}, bizType={}, bizId={}, ownerId={}",
                        fileId, bizType, bizId, ownerId, e);
            }
        }
    }

    /**
     * 计算新旧 fileId 差集（旧列表 − 新列表，保序去重）：编辑重挂/快照清理的弃图判定基准
     *
     * @param oldFileIds 旧挂图 fileId 列表
     * @param newFileIds 新挂图 fileId 列表
     * @return 被弃用的 fileId 列表（保序去重；无弃图返回空列表）
     */
    public List<String> diffOldMinusNew(List<String> oldFileIds, List<String> newFileIds) {
        Set<String> newSet = newFileIds == null ? Set.of() : new LinkedHashSet<>(newFileIds);
        List<String> removed = new ArrayList<>();
        for (String oldId : oldFileIds == null ? List.<String>of() : oldFileIds) {
            if (!newSet.contains(oldId) && !removed.contains(oldId)) {
                removed.add(oldId);
            }
        }
        return removed;
    }

    /**
     * 组装引用四元组 DTO（unbind/bind 共用，保证定位口径一致）
     *
     * @param fileId  文件ID
     * @param bizType 引用维度
     * @param bizId   引用方业务主键
     * @param ownerId 引用归属用户ID
     * @return 引用登记/解除 DTO
     */
    private FileRefDTO buildRef(String fileId, String bizType, Long bizId, Long ownerId) {
        FileRefDTO dto = new FileRefDTO();
        dto.setFileId(fileId);
        dto.setBizType(bizType);
        dto.setBizId(bizId);
        dto.setOwnerId(ownerId);
        return dto;
    }

    /**
     * 提取去重后的 fileId 列表（保序）
     *
     * @param images 配图列表
     * @return 去重 fileId 列表（空入参返回空列表）
     */
    private List<String> distinctFileIds(List<ArticleImageDTO> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        return images.stream()
                .map(ArticleImageDTO::getFileId)
                .distinct()
                .toList();
    }
}