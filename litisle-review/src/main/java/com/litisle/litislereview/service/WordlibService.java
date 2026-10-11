package com.litisle.litislereview.service;

import com.litisle.litislereview.domain.dto.WordUpdateDTO;
import com.litisle.litislereview.domain.vo.SensitiveWordVO;
import com.litisle.litislereview.enums.WordLevelEnum;
import com.litisle.litislereview.enums.WordListTypeEnum;
import com.litisle.litislereview.enums.WordSceneEnum;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 词库管理服务（对应实体：sensitive_word_library + sensitive_word_version）
 *
 * 落地约束：写操作与 SensitiveWordVersion version+1 同事务 + Redis Pub 广播全实例重建 DFA（§2.5/§2.12）
 *
 * 【注入】SensitiveWordLibraryMapper、SensitiveWordVersionMapper、WordlibVersionService、WordlibMetrics
 */
public interface WordlibService {

    /**
     * 新增敏感词：词合法性校验（长度≤64+禁空白/控制字符）→ 同场景查重 → 落词表与 version+1 同事务 →
     * 提交后写快照 + Pub 广播；level 不传默认 MID（灰度观察，§2.5）
     */
    void addWord(String word, WordSceneEnum scene, WordLevelEnum level,
                 WordListTypeEnum isBlacklist, Long operatorId);

    /**
     * 修改敏感词（含启停/黑白切换）：ADMIN；同上事务 + Pub 约束；
     * 入参 WordUpdateDTO 仅开放可变更字段（word/level/isBlacklist/status），scene 沿用原行，
     * updatedBy/version/createTime 等服务端字段由服务端固化，防请求体越权改写；
     * 返回是否有有效变更：修改前后全字段一致（无有效变更）时返回 false，
     * 不写库、不推进版本、不广播（避免无意义版本空转触发全实例 DFA 重建）
     */
    boolean updateWord(WordUpdateDTO dto, Long operatorId);

    /**
     * 删除敏感词（按 id 物理删）：ADMIN；同上事务 + Pub 约束
     */
    void deleteWord(Long wordId, Long operatorId);

    /**
     * 按场景+词内容删除敏感词（物理删）：ADMIN；scene+word 精确匹配定位唯一行（同场景词查重唯一），
     * 词不存在抛 WORD_NOT_FOUND；同上事务 + Pub 约束
     */
    void deleteWordByContent(WordSceneEnum scene, String word, Long operatorId);

    /**
     * 词库误报上报（§2.5）：HIGH 同步拒发的内容未发布无法走 appeal，独立入口回流；
     * 仅校验黑名单词存在（白/灰名单词不参与 DFA 拦截，无"误报"可言，上报即污染灰度转正唯一数据源）
     * + Counter 埋点（wordlib_misreport_total{scene}）+ warn 日志，不落库
     */
    void reportMisreport(String word, WordSceneEnum scene, String reason, Long reporterId);

    /**
     * 词库导入：ADMIN；限类型大小、解析不启外部实体（防 XXE）、逐词长度/字符校验；
     * 单词冲突跳过并计数，整体成功后 version+1 一次
     */
    void importWords(MultipartFile file, WordSceneEnum scene, WordLevelEnum level,
                     WordListTypeEnum isBlacklist, Long operatorId);

    /**
     * 词库导出：ADMIN；返回 CSV 文本（表头 6 列：word,scene,level,is_blacklist,status,create_time）；
     * 对 =/+/-/@ 开头的单元格前置 '（防公式注入，审核员本地打开即执行，§2.12）；
     * 含逗号/引号/换行的单元格按 CSV 标准双引号包裹转义；UTF-8 BOM 与下载头由 Controller 负责
     */
    String exportWords(WordSceneEnum scene, Long operatorId);

    /**
     * 词库列表：ADMIN；支持 scene/keyword/级别/黑白筛选，keyword 模糊查询对 %/_/\ 转义
     * （ESCAPE '\\' 声明转义符），防通配符放大全表扫（§2.12）；
     * 出参 SensitiveWordVO 不回吐 updatedBy/version 等服务端审计字段
     */
    List<SensitiveWordVO> listWords(WordSceneEnum scene, String keyword, WordLevelEnum level,
                                    WordListTypeEnum isBlacklist, Long operatorId);
}
