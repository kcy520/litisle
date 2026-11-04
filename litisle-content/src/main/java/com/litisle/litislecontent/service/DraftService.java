package com.litisle.litislecontent.service;


import com.litisle.litislecontent.domain.dto.DraftSaveDTO;
import com.litisle.litislecontent.domain.vo.DraftCardVO;
import com.litisle.litislecontent.domain.vo.DraftDetailVO;
import com.litisle.litislecontent.domain.vo.DraftSaveVO;

import java.util.List;

/**
 * 草稿域服务（快照幂等保存 + 版本管理），REST 由 DraftController 调用：
 *
 * DraftSaveVO save(DraftSaveDTO, Long userId)       // 保存：归属校验 + baseVersion 乐观锁（落后拒写/同版本幂等跳过）+ 单行覆盖只存最新内容
 * List<DraftCardVO> listMy(Long userId)             // 我的草稿列表
 * DraftDetailVO getDetail(Long id, Long userId)     // 草稿详情（归属校验防横向越权）
 * void delete(Long id, Long userId)                 // 删除草稿（孤儿图片由 file 对账链路兜底）
 *
 * @author litisle
 */
public interface DraftService {

    /**
     * 草稿保存（幂等）：归属校验（author_id 比对防越权）→ baseVersion 与 draft_version 比对
     * （相等→UPSERT 递增；落后→ARTICLE_DRAFT_CONFLICT 刷新；同版本重复提交幂等跳过）
     * → 单行 UPSERT 原地覆盖只存最新内容（无历史版本留存，draft_version 递增仅作乐观锁计数）
     *
     * @param dto   保存入参（新草稿 articleId=null，发布后回填）
     * @param userId 当前登录用户ID
     * @return 保存结果 {draftId, draftVersion}
     */
    DraftSaveVO save(DraftSaveDTO dto, Long userId);

    /**
     * 我的草稿列表（按 author_id 归属过滤）
     *
     * @param userId 当前登录用户ID
     * @return 草稿卡片列表
     */
    List<DraftCardVO> listMy(Long userId);

    /**
     * 草稿详情（含 content_html，编辑器回显用；归属校验防横向越权）
     *
     * @param id     草稿ID
     * @param userId 当前登录用户ID
     * @return 详情 VO
     */
    DraftDetailVO getDetail(Long id, Long userId);

    /**
     * 删除草稿（仅本人；草稿图片 draft_id 归属清理，废弃孤儿图片由 file 对账链路兜底）
     *
     * @param id     草稿ID
     * @param userId 当前登录用户ID
     */
    void delete(Long id, Long userId);
}
