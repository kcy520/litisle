package com.litisle.litislereview.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litislereview.dfa.WordlibAuditContext;
import com.litisle.litislereview.dfa.WordlibMetrics;
import com.litisle.litislereview.dfa.WordlibVersionService;
import com.litisle.litislereview.domain.dto.WordMisreportStatDTO;
import com.litisle.litislereview.domain.dto.WordUpdateDTO;
import com.litisle.litislereview.domain.entity.SensitiveWordLibrary;
import com.litisle.litislereview.domain.entity.SensitiveWordVersion;
import com.litisle.litislereview.domain.entity.WordlibHitStat;
import com.litisle.litislereview.domain.entity.WordlibMisreport;
import com.litisle.litislereview.domain.vo.SensitiveWordVO;
import com.litisle.litislereview.enums.WordLevelEnum;
import com.litisle.litislereview.enums.WordListTypeEnum;
import com.litisle.litislereview.enums.WordSceneEnum;
import com.litisle.litislereview.enums.WordStatusEnum;
import com.litisle.litislereview.enums.WordSuggestionMarkEnum;
import com.litisle.litislereview.enums.WordlibOpTypeEnum;
import com.litisle.litislereview.mapper.SensitiveWordLibraryMapper;
import com.litisle.litislereview.mapper.SensitiveWordVersionMapper;
import com.litisle.litislereview.mapper.WordlibHitStatMapper;
import com.litisle.litislereview.mapper.WordlibMisreportMapper;
import com.litisle.litislereview.moderation.config.ModerationProperties;
import com.litisle.litislereview.service.WordlibService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 词库管理服务实现（本服务方法已全部落地）
 *
 * 落地约束：写操作与 SensitiveWordVersion version+1 同事务，事务提交后写 Redis 快照 + Pub 广播
 * 各实例重建 DFA；机审执行（DFA 匹配本身）不在本类，放 listener + ModerationService
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WordlibServiceImpl implements WordlibService {

    private final SensitiveWordLibraryMapper sensitiveWordLibraryMapper;
    private final SensitiveWordVersionMapper sensitiveWordVersionMapper;
    private final WordlibVersionService wordlibVersionService;
    private final WordlibMetrics wordlibMetrics;
    private final WordlibMisreportMapper wordlibMisreportMapper;
    /** 词级命中统计 Mapper（§5.4 治理建议清单：按当前页词批量取命中观察量） */
    private final WordlibHitStatMapper wordlibHitStatMapper;
    /** 机审可调参数（wordlib.gray-misreport-rate / min-hit-sample：建议标记口径阈值） */
    private final ModerationProperties moderationProperties;

    /** 词长度上限（与实体 @Size 对齐，DFA 构建前拦截，§2.12） */
    private static final int WORD_MAX_LENGTH = 64;

    /** 误报说明落库上限（与 wordlib_misreport.reason varchar(200) 对齐，超长截断防报错中断上报） */
    private static final int MISREPORT_REASON_MAX_LENGTH = 200;

    /** 词合法字符：禁止空白与控制字符（防 DFA 分词失真/日志注入） */
    private static final Pattern WORD_PATTERN = Pattern.compile("[^\\s\\p{Cntrl}]+");

    /** 导入文件大小上限 2MB（导入限类型+大小，§2.12） */
    private static final long IMPORT_MAX_BYTES = 2L * 1024 * 1024;

    /** 词库列表安全上限（配置类小表，防漏加条件全量拉取） */
    private static final long WORD_LIST_LIMIT = 1000L;

    /** CSV 公式注入前缀字符：以这些字符开头的单元格前置 '（防审核员本地打开即执行公式，§2.12） */
    private static final String CSV_FORMULA_PREFIXES = "=+-@";

    // ---------- 词库导入/导出常量（集中管理防多处硬编码漂移） ----------

    /** 导入文件扩展名白名单（.csv/.txt，与导入校验保持一处定义） */
    private static final String IMPORT_SUFFIX_CSV = ".csv";
    private static final String IMPORT_SUFFIX_TXT = ".txt";

    /** UTF-8 BOM 字符（导入文件首行词头可能附着，解析前剥离） */
    private static final String UTF8_BOM = "\uFEFF";

    /** 导出 CSV 表头（6 列：word,scene,level,is_blacklist,status,create_time，与导入首列解析约定互逆） */
    private static final String EXPORT_CSV_HEADER = "word,scene,level,is_blacklist,status,create_time\n";

    /** 导出 CSV 时间列格式（DateTimeFormatter 线程安全，静态复用） */
    private static final DateTimeFormatter EXPORT_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ---------- 变更审计摘要拼接常量（批次1 ②，detail 列口径集中一处） ----------

    /** 审计摘要字段名（detail 列内使用，与词表字段语义对齐） */
    private static final String AUDIT_FIELD_WORD = "word";
    private static final String AUDIT_FIELD_LEVEL = "level";
    private static final String AUDIT_FIELD_BLACKLIST = "blacklist";
    private static final String AUDIT_FIELD_STATUS = "status";

    /** 多条变更摘要间的连接符（UPDATE 多字段变更拼接） */
    private static final String AUDIT_NOTE_SEPARATOR = "; ";
    /** 属性快照口径键值分隔符（ADD/DELETE：field=value） */
    private static final String AUDIT_ATTR_EQ = "=";
    /** 变更口径标签分隔符与箭头（UPDATE：field: old→new） */
    private static final String AUDIT_CHANGE_LABEL = ": ";
    private static final String AUDIT_ARROW = "→";

    /**
     * 生成审计属性快照片段：field=value（ADD/DELETE 口径，记录变更后的词属性）
     *
     * @param field 审计字段名（AUDIT_FIELD_* 常量）
     * @param value 属性值（枚举 name 或词内容）
     * @return 形如 level=MID 的摘要片段
     */
    private String auditAttr(String field, String value) {
        return field + AUDIT_ATTR_EQ + value;
    }

    /**
     * 生成审计变更片段：field: old→new（UPDATE 口径，记录字段变更前后值）
     *
     * @param field  审计字段名（AUDIT_FIELD_* 常量）
     * @param oldVal 变更前值
     * @param newVal 变更后值
     * @return 形如 level: MID→HIGH 的摘要片段
     */
    private String auditChange(String field, String oldVal, String newVal) {
        return field + AUDIT_CHANGE_LABEL + oldVal + AUDIT_ARROW + newVal;
    }

    /** 新增敏感词：词长度+字符校验 → 查重 → 落词表与 version+1 同事务 → 提交后写快照 + Pub 广播 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addWord(String word, WordSceneEnum scene, WordLevelEnum level,
                        WordListTypeEnum isBlacklist, Long operatorId) {
        // Step0 灰度纪律：新增词未显式指定级别时默认落 MID（只标记不拦截），误报率达标后再人工升 HIGH（§2.5）
        if (level == null) {
            level = WordLevelEnum.MID;
        }
        // Step1 词合法性校验（长度 + 字符，DFA 构建前拦截）
        validateWord(word);
        // Step2 同场景查重（配置类小表，selectCount 足够）
        Long count = sensitiveWordLibraryMapper.selectCount(new LambdaQueryWrapper<SensitiveWordLibrary>()
                .eq(SensitiveWordLibrary::getScene, scene)
                .eq(SensitiveWordLibrary::getWord, word));
        if (count != null && count > 0) {
            throw new BizException(ErrorCode.WORD_DUPLICATED);
        }
        // Step3 落词表（行级 version 仅审计快照，非版本源）
        SensitiveWordLibrary row = new SensitiveWordLibrary()
                .setWord(word)
                .setScene(scene)
                .setLevel(level)
                .setIsBlacklist(isBlacklist)
                .setStatus(WordStatusEnum.ENABLED)
                .setUpdatedBy(operatorId)
                .setVersion(selectSceneVersion(scene));
        sensitiveWordLibraryMapper.insert(row);
        // Step4 版本+1（同事务）+ 同事务插入变更审计（ADD 逐词审计），提交后写快照 + Pub 广播
        wordlibVersionService.bumpAndBroadcast(scene, operatorId, new WordlibAuditContext(
                WordlibOpTypeEnum.ADD, word,
                auditAttr(AUDIT_FIELD_LEVEL, level.name()) + AUDIT_NOTE_SEPARATOR
                        + auditAttr(AUDIT_FIELD_BLACKLIST, isBlacklist.name()), null));
    }

    /**
     * 修改敏感词（含启停/黑白切换）：同 addWord 事务+Pub 约束；scene 不允许变更（变更需双场景 bump，一期不支持）。
     * 入参 WordUpdateDTO 仅开放可变更字段，updatedBy/version/createTime 等服务端字段由本方法固化，防请求体越权改写。
     * 词 id 无对应行抛 WORD_NOT_FOUND；所有入参字段与原行完全一致时判定为未修改：
     * 不写库、不推进版本、不广播（避免无意义 DFA 全实例重建），返回 false 由 Controller 回执"未修改"
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateWord(WordUpdateDTO word, Long operatorId) {
        // Step1 定位原行（物理删小表，无逻辑删）；id 缺失或无对应行均返回 70006
        SensitiveWordLibrary origin = sensitiveWordLibraryMapper.selectById(word.getId());
        if (origin == null) {
            throw new BizException(ErrorCode.WORD_NOT_FOUND);
        }
        boolean changed = false;
        // 变更摘要收集（审计表 detail 列用）：逐字段记录 变更前→变更后，支撑误报灰度转正等治理动作闭环追溯
        List<String> changeNotes = new ArrayList<>();
        // Step2 按传入字段增量更新，仅在值真变化时置 changed（scene 强制沿用原行，防跨场景迁移绕过版本一致性）
        if (word.getWord() != null && !word.getWord().equals(origin.getWord())) {
            validateWord(word.getWord());
            Long count = sensitiveWordLibraryMapper.selectCount(new LambdaQueryWrapper<SensitiveWordLibrary>()
                    .eq(SensitiveWordLibrary::getScene, origin.getScene())
                    .eq(SensitiveWordLibrary::getWord, word.getWord())
                    .ne(SensitiveWordLibrary::getId, word.getId()));
            if (count != null && count > 0) {
                throw new BizException(ErrorCode.WORD_DUPLICATED);
            }
            changeNotes.add(auditChange(AUDIT_FIELD_WORD, origin.getWord(), word.getWord()));
            origin.setWord(word.getWord());
            changed = true;
        }
        if (word.getLevel() != null && word.getLevel() != origin.getLevel()) {
            changeNotes.add(auditChange(AUDIT_FIELD_LEVEL, origin.getLevel().name(), word.getLevel().name()));
            origin.setLevel(word.getLevel());
            changed = true;
        }
        if (word.getIsBlacklist() != null && word.getIsBlacklist() != origin.getIsBlacklist()) {
            changeNotes.add(auditChange(AUDIT_FIELD_BLACKLIST, origin.getIsBlacklist().name(), word.getIsBlacklist().name()));
            origin.setIsBlacklist(word.getIsBlacklist());
            changed = true;
        }
        if (word.getStatus() != null && word.getStatus() != origin.getStatus()) {
            changeNotes.add(auditChange(AUDIT_FIELD_STATUS, origin.getStatus().name(), word.getStatus().name()));
            origin.setStatus(word.getStatus());
            changed = true;
        }
        // Step3 无有效变更：跳过写库与版本推进，直接回执未修改
        if (!changed) {
            log.info("敏感词未修改（内容前后一致）：id={}, operatorId={}", word.getId(), operatorId);
            return false;
        }
        origin.setUpdatedBy(operatorId);
        sensitiveWordLibraryMapper.updateById(origin);
        // Step4 版本+1（同事务）+ 同事务插入变更审计（UPDATE 逐词审计，detail=变更摘要）+ 提交后广播
        wordlibVersionService.bumpAndBroadcast(origin.getScene(), operatorId, new WordlibAuditContext(
                WordlibOpTypeEnum.UPDATE, origin.getWord(), String.join(AUDIT_NOTE_SEPARATOR, changeNotes), null));
        return true;
    }

    /** 删除敏感词：物理删（配置类小表可全量重建；updated_by 审计走操作日志）；同事务 version+1 + Pub */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteWord(Long wordId, Long operatorId) {
        // 1. 查询
        SensitiveWordLibrary origin = sensitiveWordLibraryMapper.selectById(wordId);
        //2.存在性校验
        if (origin == null) {
            throw new BizException(ErrorCode.WORD_NOT_FOUND);
        }
        // 3. 物理删除
        sensitiveWordLibraryMapper.deleteById(wordId);
        // 4. 版本+1 + 同事务插入变更审计（DELETE 逐词审计，detail=删除时原词属性）+ 广播
        wordlibVersionService.bumpAndBroadcast(origin.getScene(), operatorId, new WordlibAuditContext(
                WordlibOpTypeEnum.DELETE, origin.getWord(),
                auditAttr(AUDIT_FIELD_LEVEL, origin.getLevel().name()) + AUDIT_NOTE_SEPARATOR
                        + auditAttr(AUDIT_FIELD_BLACKLIST, origin.getIsBlacklist().name()), null));
    }

    /** 按场景+词内容删除敏感词：物理删；词表删除与 version+1、变更审计同事务（下方 @Transactional）*/
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteWordByContent(WordSceneEnum scene, String word, Long operatorId) {
        // 1. 查询（scene+word 命中 uk_scene_word 唯一键至多一行，selectOne 无需 limit 兜底）
        SensitiveWordLibrary origin = sensitiveWordLibraryMapper.selectOne(
                Wrappers.<SensitiveWordLibrary>lambdaQuery()
                        .eq(SensitiveWordLibrary::getScene, scene)
                        .eq(SensitiveWordLibrary::getWord, word)
        );
        //2.存在性校验
        if (origin == null) {
            throw new BizException(ErrorCode.WORD_NOT_FOUND);
        }
        // 3. 物理删除
        sensitiveWordLibraryMapper.deleteById(origin.getId());
        // 4. 版本+1 + 同事务插入变更审计（DELETE 逐词审计）+ 广播；
        //    补 @Transactional 使词表删除/版本推进/审计三步同事务（原先删除在事务外，违背审计同事务红线）
        wordlibVersionService.bumpAndBroadcast(origin.getScene(), operatorId, new WordlibAuditContext(
                WordlibOpTypeEnum.DELETE, origin.getWord(),
                auditAttr(AUDIT_FIELD_LEVEL, origin.getLevel().name()) + AUDIT_NOTE_SEPARATOR
                        + auditAttr(AUDIT_FIELD_BLACKLIST, origin.getIsBlacklist().name()), null));
    }

    /**
     * 词合法性校验：非空 + 长度≤64 + 禁空白/控制字符（DFA 构建前拦截，§2.12）
     */
    private void validateWord(String word) {
        if (word == null || word.isBlank() || word.length() > WORD_MAX_LENGTH
                || !WORD_PATTERN.matcher(word).matches()) {
            throw new BizException(ErrorCode.WORD_INVALID);
        }
    }

    /**
     * 当前 scene 版本（无行返回 0，仅作行级审计快照填充；版本源推进在 WordlibVersionService）
     */
    private Long selectSceneVersion(WordSceneEnum scene) {
        SensitiveWordVersion row = sensitiveWordVersionMapper.selectOne(
                new LambdaQueryWrapper<SensitiveWordVersion>()
                        .eq(SensitiveWordVersion::getScene, scene));
        return row == null || row.getVersion() == null ? 0L : row.getVersion();
    }

    /**
     * 词库误报上报（§2.5）：校验仅黑名单词可报（防胡乱上报刷计数）→ Counter 场景级埋点
     * + 词级明细落库（wordlib_misreport，同 reporter 同词去重累加）+ warn 日志；
     * 落库失败仅告警不阻断上报（误报明细是治理辅助数据，存储故障不回滚用户反馈动作）；
     * 转正裁决闭环：按 scene+word 聚合本表 report_count 算单词误报率（Counter 只管场景级趋势）
     */
    @Override
    public void reportMisreport(String word, WordSceneEnum scene, String reason, Long reporterId) {
        // 仅允许黑名单词上报误报：
        // 白名单词不会触发拦截，误报计数无意义；
        // 若放行，会污染 wordlib_misreport_total，影响灰度转正判定
        // selectOne 替代 selectCount：需取词级别做快照列（uk_scene_word 唯一键保证至多一行）
        SensitiveWordLibrary wordRow = sensitiveWordLibraryMapper.selectOne(
                new LambdaQueryWrapper<SensitiveWordLibrary>()
                        .eq(SensitiveWordLibrary::getScene, scene)
                        .eq(SensitiveWordLibrary::getWord, word)
                        .eq(SensitiveWordLibrary::getIsBlacklist, WordListTypeEnum.BLACKLIST)
        );

        if (wordRow == null) {
            throw new BizException(ErrorCode.WORD_NOT_FOUND);
        }

        //误报上报计数 +1（scene 标签，场景级趋势告警源）
        wordlibMetrics.incrementMisreport(scene);

        // 词级明细落库（wordlib_misreport）：失败仅 error 不抛——明细为治理辅助数据，
        // 存储故障不应阻断用户反馈动作；Counter 已计数，明细以 warn 日志兜底排障
        try {
            saveMisreportRecord(word, scene, wordRow.getLevel(), reason, reporterId);
        } catch (Exception e) {
            log.error("[词库误报上报] 明细落库失败（Counter 已计数，明细以日志为准）scene={}, word={}, reporterId={}",
                    scene, word, reporterId, e);
        }

        // 安全处理：防止 CRLF 日志注入
        String safeWord = word.replace("\r", "").replace("\n", "");
        String safeReason = reason == null ? "" : reason.replace("\r", "").replace("\n", "");

        // 词级明细同步落日志（落库失败时的兜底排障通道 & 运营分析）
        log.warn(
                "[词库误报上报] scene={}, word={}, reason={}, reporterId={}",
                scene, safeWord, safeReason, reporterId
        );
    }

    /**
     * 误报明细落库（upsert）：按 reporter_id+scene+word 唯一键去重——
     * 已有行 SQL 侧原子累加 report_count 并刷新 last_report_time（防读-改-写竞态与刷行数）；
     * 无行则首插（report_count=1，level 快照固化上报时词级别），并发首插撞唯一键降级为累加
     */
    private void saveMisreportRecord(String word, WordSceneEnum scene, WordLevelEnum level,
                                     String reason, Long reporterId) {
        // 先试累加：影响行数 >0 说明已有上报行，直接结束
        int updated = wordlibMisreportMapper.incrementReportCount(reporterId, scene.getCode(), word);
        if (updated > 0) {
            return;
        }
        // 首次上报：插入明细行
        WordlibMisreport record = new WordlibMisreport()
                .setWord(word)
                .setScene(scene)
                .setLevelSnapshot(level)
                .setReason(cleanMisreportReason(reason))
                .setReporterId(reporterId);
        try {
            wordlibMisreportMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发兜底：同 reporter 同词并发首插撞唯一键，降级为累加（丢一次自增无碍，下一轮上报补齐）
            wordlibMisreportMapper.incrementReportCount(reporterId, scene.getCode(), word);
        }
    }

    /**
     * 误报说明清洗落库：剥 \r\n（防 CRLF 注入管理后台展示）+ 超 200 字符截断
     * （与 wordlib_misreport.reason 列宽对齐，宁存截断不报错中断上报）
     */
    private String cleanMisreportReason(String reason) {
        if (reason == null) {
            return null;
        }
        String cleaned = reason.replace("\r", "").replace("\n", " ");
        return cleaned.length() > MISREPORT_REASON_MAX_LENGTH
                ? cleaned.substring(0, MISREPORT_REASON_MAX_LENGTH)
                : cleaned;
    }


    /**
     * 词库导入（§2.12 安全口径）：CSV 单列 UTF-8（扩展名白名单 .csv/.txt，≤2MB）；
     * 纯文本按行解析、不使用任何 XML/Excel 解析器 → 无外部实体攻击面（防 XXE 的设计性实现）；
     * 逐词 validateWord（长度≤64 + 禁空白/控制字符，DFA 构建前拦截）+ 文件内去重 + DB 冲突跳过；
     * 净新增才 version+1 一次（同事务，提交后写快照 + Pub 广播）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void importWords(MultipartFile file, WordSceneEnum scene, WordLevelEnum level,
                            WordListTypeEnum isBlacklist, Long operatorId) {
        // Step0 灰度纪律：与 addWord 同口径，级别未显式指定默认 MID 观察
        if (level == null) {
            level = WordLevelEnum.MID;
        }
        // Step1 文件基础校验：非空 + 扩展名白名单 + 大小上限（导入限类型+大小）
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.WORD_INVALID);
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!filename.endsWith(IMPORT_SUFFIX_CSV) && !filename.endsWith(IMPORT_SUFFIX_TXT)) {
            throw new BizException(ErrorCode.FILE_TYPE_NOT_ALLOWED);
        }
        if (file.getSize() > IMPORT_MAX_BYTES) {
            throw new BizException(ErrorCode.FILE_SIZE_EXCEEDED);
        }
        // Step2 纯文本按行读取（UTF-8）：CSV 单列契约，整行即一词；不走 XML 解析（XXE 攻击面不存在）
        List<String> rawLines;
        try {
            rawLines = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))
                    .lines().toList();
        } catch (IOException e) {
            throw new BizException(ErrorCode.WORD_IMPORT_FAILED);
        }
        // Step3 逐词清洗校验：去 BOM → 6 列导出格式拆首列 → 剥包裹引号（与导出 CSV 转义约定互逆）→
        // validateWord fail-fast（宁严勿松，禁止半截导入）→ 文件内去重
        Set<String> validated = new LinkedHashSet<>();
        for (String raw : rawLines) {
            String word = raw == null ? "" : raw.strip();
            // UTF-8 BOM 兼容：仅文件首行词头会附着
            if (word.startsWith(UTF8_BOM)) {
                word = word.substring(1);
            }
            if (word.isEmpty()) {
                continue;
            }
            // 兼容导出的 6 列 CSV（word,scene,...）：整行含逗号时仅取首列，
            // 防止 scene/level 等列被拼进词里静默入库污染词库（WORD_PATTERN 允许逗号，必须显式拆列）
            if (word.contains(",")) {
                if (word.startsWith("\"")) {
                    // 带引号包裹的首列：定位真正的结束引号（"" 为转义引号），取引号内内容并还原内部引号
                    int i = 1;
                    while (i < word.length()) {
                        if (word.charAt(i) == '"') {
                            if (i + 1 < word.length() && word.charAt(i + 1) == '"') {
                                i += 2;
                                continue;
                            }
                            break;
                        }
                        i++;
                    }
                    word = word.substring(1, Math.min(i, word.length())).replace("\"\"", "\"");
                } else {
                    // 无引号包裹：首列即第一个逗号前的内容
                    word = word.substring(0, word.indexOf(','));
                }
            }
            // 带包裹引号的词先剥离再校验（"" 转义还原为 "，与导出约定互逆）
            if (word.length() >= 2 && word.charAt(0) == '"' && word.charAt(word.length() - 1) == '"') {
                word = word.substring(1, word.length() - 1).replace("\"\"", "\"");
            }
            validateWord(word);
            validated.add(word);
        }
        if (validated.isEmpty()) {
            throw new BizException(ErrorCode.WORD_INVALID);
        }
        // Step4 DB 冲突检测：一次 .in 批量查同场景已存在词（避免逐词 N 次查询的 N+1 问题）
        Set<String> existingWords = sensitiveWordLibraryMapper.selectList(
                        new LambdaQueryWrapper<SensitiveWordLibrary>()
                                .select(SensitiveWordLibrary::getWord)
                                .eq(SensitiveWordLibrary::getScene, scene)
                                .in(SensitiveWordLibrary::getWord, validated))
                .stream().map(SensitiveWordLibrary::getWord).collect(Collectors.toSet());
        // Step5 逐词落库（冲突词静默跳过计数；行级 version 审计快照循环外取一次，避免逐词 N+1 查版本表）
        Long rowVersion = selectSceneVersion(scene);
        int skipped = 0;
        for (String word : validated) {
            if (existingWords.contains(word)) {
                skipped++;
                continue;
            }
            sensitiveWordLibraryMapper.insert(new SensitiveWordLibrary()
                    .setWord(word)
                    .setScene(scene)
                    .setLevel(level)
                    .setIsBlacklist(isBlacklist)
                    .setStatus(WordStatusEnum.ENABLED)
                    .setUpdatedBy(operatorId)
                    .setVersion(rowVersion));
        }
        log.info("词库导入完成：scene={}, 新增词={}, 冲突跳过={}", scene, validated.size() - skipped, skipped);
        // Step6 有净新增才推进版本：version+1 与本导入同事务 + 同事务插入聚合审计 1 条
        // （BATCH_IMPORT：word_count=净新增数，word 列空——词清单在冷备文件里不重复落库），
        // 提交后统一写 Redis 快照 + Pub 广播
        if (validated.size() > skipped) {
            wordlibVersionService.bumpAndBroadcast(scene, operatorId, new WordlibAuditContext(
                    WordlibOpTypeEnum.BATCH_IMPORT, null, null, validated.size() - skipped));
        }
    }

    /**
     * 词库导出（§2.12）：返回 CSV 文本（表头 6 列），id 升序保证回放顺序稳定；
     * 公式注入防护必须在服务端生成 CSV 时落地——JSON 回吐再由前端拼 CSV 防不住注入；
     * UTF-8 BOM 与下载响应头由 Controller 负责；operatorId 仅作导出行为审计日志（词库属敏感配置数据）
     */
    @Override
    public String exportWords(WordSceneEnum scene, Long operatorId) {
        // Step1 按 scene 全量导出（配置类小表，id 升序保证导入回放顺序稳定）
        List<SensitiveWordLibrary> rows = sensitiveWordLibraryMapper.selectList(
                new LambdaQueryWrapper<SensitiveWordLibrary>()
                        .eq(SensitiveWordLibrary::getScene, scene)
                        .orderByAsc(SensitiveWordLibrary::getId));
        // Step2 生成 CSV：每个单元格先做公式注入防护，再做标准 CSV 转义（逗号/引号/换行）
        StringBuilder csv = new StringBuilder(EXPORT_CSV_HEADER);
        for (SensitiveWordLibrary row : rows) {
            csv.append(escapeCsvCell(row.getWord())).append(',')
                    .append(escapeCsvCell(row.getScene() == null ? "" : row.getScene().name())).append(',')
                    .append(escapeCsvCell(row.getLevel() == null ? "" : row.getLevel().name())).append(',')
                    .append(escapeCsvCell(row.getIsBlacklist() == null ? "" : row.getIsBlacklist().name())).append(',')
                    .append(escapeCsvCell(row.getStatus() == null ? "" : row.getStatus().name())).append(',')
                    .append(escapeCsvCell(row.getCreateTime() == null ? "" : row.getCreateTime().format(EXPORT_DATE_FORMATTER)))
                    .append('\n');
        }
        log.info("词库导出：scene={}, rowCount={}, operatorId={}", scene, rows.size(), operatorId);
        return csv.toString();
    }

    /**
     * CSV 单元格双重转义：① 以 =/+/-/@ 开头前置 '（防公式注入，§2.12，审核员本地 Excel 打开即执行）；
     * ② 含逗号/引号/换行时按 CSV 标准双引号包裹并将内部引号翻倍（防解析歧义；
     * 与 importWords 的包裹引号剥离约定互逆，导出文件首列可直接回导入）
     */
    private String escapeCsvCell(String cell) {
        if (cell == null || cell.isEmpty()) {
            return "";
        }
        String safe = cell;
        if (CSV_FORMULA_PREFIXES.indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return '"' + safe.replace("\"", "\"\"") + '"';
        }
        return safe;
    }

    /**
     * 词库列表：ADMIN 多条件筛选（scene/keyword/级别/黑白）；keyword 模糊查询对 \\ % _ 三字符
     * 前置 \\ 转义 + ESCAPE '\\' 声明转义符，防 %/_ 通配放大全表扫（§2.12）；
     * apply 的 {0} 走 #{} 预编译绑定，转义后的关键词本身无注入面；结果强制 LIMIT 安全上限；
     * 出参转 SensitiveWordVO，不回吐 updatedBy/version 审计字段
     *
     * 词库管理后台的列表查询接口，负责按多条件筛选敏感词、做安全的模糊搜索、限制返回条数，并给结果附加治理建议证据。
     *
     * ADMIN 后台查询敏感词列表，支持按场景/关键词/级别/黑白名单四种条件组合筛选，
     * 关键词模糊搜索防通配符注入和全表扫描，结果强制 LIMIT 上限，返回前批量附加治理建议标记。
     *
     */
    @Override
    public List<SensitiveWordVO> listWords(WordSceneEnum scene, String keyword, WordLevelEnum level,
                                           WordListTypeEnum isBlacklist, Long operatorId) {
        LambdaQueryWrapper<SensitiveWordLibrary> wrapper = new LambdaQueryWrapper<SensitiveWordLibrary>()
                .eq(scene != null, SensitiveWordLibrary::getScene, scene)
                .eq(level != null, SensitiveWordLibrary::getLevel, level)
                .eq(isBlacklist != null, SensitiveWordLibrary::getIsBlacklist, isBlacklist);
        // keyword 模糊查询：\\ % _ 前置 \\ 转义（防通配符放大全表扫），ESCAPE 声明 \\ 为转义符
        if (keyword != null && !keyword.isBlank()) {
            String escaped = keyword.trim()
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
            wrapper.apply("word LIKE CONCAT('%', {0}, '%') ESCAPE '\\\\'", escaped);
        }
        // LIMIT 安全上限用 Page 替代 .last("LIMIT")：分页拦截器标准方式，无裸 SQL 字符串；
        // searchCount=false 跳过 COUNT；上限数值取自常量 WORD_LIST_LIMIT（分页拦截器 maxLimit=500 兜底防配错）
        wrapper.orderByAsc(SensitiveWordLibrary::getId);
        List<SensitiveWordVO> vos = sensitiveWordLibraryMapper
                .selectList(Page.of(1, WORD_LIST_LIMIT, false), wrapper).stream()
                .map(this::toWordVo)
                .toList();
        // §5.4 治理建议清单：为当前页词批量附加「建议标记 + 证据字段」（两次 IN 查询 + 内存计算，禁逐词查询）
        attachSuggestionEvidence(vos, scene);
        return vos;
    }

    /**
     * 为当前页词附加治理建议标记与证据字段（§5.4 词库治理建议清单）
     *
     *
     * 对当前页的敏感词列表，批量查出每个词的"命中观察量"和"误报证据"，
     * 算出误报率，再按规则打上治理建议标记（降级 / 转正确认 / 转正）。标记只是证据，不自动执行任何动作。
     *
     * ┌────────────────────────────────────────────────────────────────────────────┐
     * │ ① 提取当前页词集合 → ② 批量查命中观察量 → ③ 批量查误报聚合 → ④ 逐词计算证据+标记   │
     * └────────────────────────────────────────────────────────────────────────────┘
     *
     * <p>数据源两表，均按当前页词批量 IN 查询（禁逐词/全表扫）：
     * wordlib_hit_stat（命中观察量，LambdaQueryWrapper 带场景过滤）+
     * wordlib_misreport 聚合（误报分子/去重人数）；scene 筛选为空时两查询不带场景条件，
     * 按 (scene, word) 键在内存侧匹配，防同词跨场景证据串行</p>
     *
     * <p>标记三类口径（仅黑名单且启用中的词参与，见 WordSuggestionMarkEnum）：
     * HIGH+误报>0→建议降级（绝对量为主）；HIGH+样本≥min-hit-sample 且误报率&lt;阈值→转正确认；
     * MID+样本≥min-hit-sample→建议转正。标记只是证据，禁自动动作</p>
     *
     * @param vos   当前页词 VO 列表（原位补齐证据字段）
     * @param scene 列表筛选场景（null=跨场景，证据按 (scene,word) 匹配）
     */
    private void attachSuggestionEvidence(List<SensitiveWordVO> vos, WordSceneEnum scene) {
        if (vos.isEmpty()) {
            return;
        }
        // 当前页词集合（distinct 去重防同词多行重复进 IN 条件；上限 WORD_LIST_LIMIT，IN 规模可控）
        List<String> words = vos.stream().map(SensitiveWordVO::getWord).distinct().toList();
        // 命中观察量：scene 筛选非空时下推 DB 过滤；空则跨场景取回，内存按 key 匹配
        Map<String, Integer> hitMap = wordlibHitStatMapper.selectList(new LambdaQueryWrapper<WordlibHitStat>()
                        .in(WordlibHitStat::getWord, words)
                        .eq(scene != null, WordlibHitStat::getScene, scene)).stream()
                .collect(Collectors.toMap(
                        r -> statKey(r.getScene().getCode(), r.getWord()),
                        WordlibHitStat::getHitCount,
                        Integer::sum));
        // 误报证据：聚合查询（sum(report_count)+count(*) 去重人数），scene 传 null 即跨场景聚合
        String sceneCode = scene == null ? null : scene.getCode();
        Map<String, WordMisreportStatDTO> misreportMap = wordlibMisreportMapper
                .aggregateByWords(sceneCode, words).stream()
                .collect(Collectors.toMap(dto -> statKey(dto.getScene(), dto.getWord()), Function.identity()));
        // 建议标记口径阈值（外置可调：改 Nacos 即时生效）
        int minHitSample = moderationProperties.getWordlib().getMinHitSample();
        double rateThreshold = moderationProperties.getWordlib().getGrayMisreportRate();
        for (SensitiveWordVO vo : vos) {
            String key = statKey(vo.getScene().getCode(), vo.getWord());
            int hitCount = hitMap.getOrDefault(key, 0);
            WordMisreportStatDTO misreport = misreportMap.get(key);
            int reportCount = misreport == null ? 0 : misreport.getReportTotal();
            int reporterCount = misreport == null ? 0 : misreport.getReporterCount();
            // 参考误报率：仅命中与误报均 >0 时才有意义（hit=0 或 report=0 置 null，防 0/0 与误导性 0.0）
            Double rate = (hitCount > 0 && reportCount > 0)
                    ? round4((double) reportCount / hitCount) : null;
            vo.setHitCount(hitCount)
                    .setReportCount(reportCount)
                    .setReporterCount(reporterCount)
                    .setMisreportRate(rate)
                    .setSuggestionMark(computeSuggestionMark(vo.getLevel(), vo.getIsBlacklist(),
                            vo.getStatus(), hitCount, reportCount, rate, minHitSample, rateThreshold));
        }
        //直接原地修改 vos 里的每个 SensitiveWordVO 对象，不返回任何值。
    }

    /**
     * 计算单词建议标记（三类口径见 WordSuggestionMarkEnum；白名单/禁用词/证据不足均返回 null 不标记）
     *
     * @param level         词级别
     * @param isBlacklist   名单类型
     * @param status        启停状态
     * @param hitCount      命中观察量（异步机审口径）
     * @param reportCount   累计误报上报次数
     * @param rate          参考误报率（可能 null=无误报或无命中）
     * @param minHitSample  最小命中样本量阈值
     * @param rateThreshold 灰度误报率阈值
     * @return 建议标记；null=无建议
     */
    private WordSuggestionMarkEnum computeSuggestionMark(WordLevelEnum level, WordListTypeEnum isBlacklist,
                                                         WordStatusEnum status, int hitCount, int reportCount,
                                                         Double rate, int minHitSample, double rateThreshold) {
        // 白名单不参与拦截（无命中/误报语义），禁用词已下线观察——均无治理动作意义
        if (isBlacklist != WordListTypeEnum.BLACKLIST || status != WordStatusEnum.ENABLED) {
            return null;
        }
        // HIGH：误报绝对量 >0 即建议降级（分母缺失比率虚高，绝对量为主）；
        // 否则样本足够且无误报劣化（rate=null 表示零误报）→ 转正确认
        if (level == WordLevelEnum.HIGH) {
            if (reportCount > 0) {
                return WordSuggestionMarkEnum.SUGGEST_DEMOTE;
            }
            if (hitCount >= minHitSample && (rate == null || rate < rateThreshold)) {
                return WordSuggestionMarkEnum.PROMOTE_CONFIRM;
            }
            return null;
        }
        // MID：异步命中观察量达标 → 进入转正裁决视野（误报证据列同行展示供运营甄别）
        if (level == WordLevelEnum.MID && hitCount >= minHitSample) {
            return WordSuggestionMarkEnum.SUGGEST_PROMOTE;
        }
        return null;
    }

    /**
     * 证据匹配 key：场景 code + '\n' + 词（'\n' 为控制字符、词校验禁止出现，可安全作分隔符防拼接歧义）
     *
     * @param sceneCode 场景 code
     * @param word      词内容
     * @return 匹配 key
     */
    private String statKey(String sceneCode, String word) {
        return sceneCode + "\n" + word;
    }

    /**
     * 误报率保留 4 位小数（HALF_UP，证据展示口径固定精度）
     *
     * @param value 原始比率
     * @return 固定 4 位小数的比率
     */
    private double round4(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 实体 → 列表出参 VO：仅保留运营可视字段，不回吐 updatedBy/version 审计字段
     */
    private SensitiveWordVO toWordVo(SensitiveWordLibrary row) {
        return new SensitiveWordVO()
                .setId(row.getId())
                .setWord(row.getWord())
                .setScene(row.getScene())
                .setLevel(row.getLevel())
                .setIsBlacklist(row.getIsBlacklist())
                .setStatus(row.getStatus())
                .setCreateTime(row.getCreateTime())
                .setUpdateTime(row.getUpdateTime());
    }
}
