package com.litisle.litislereview.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litislereview.domain.dto.AppealDecideDTO;
import com.litisle.litislereview.domain.dto.AppealSubmitDTO;
import com.litisle.litislereview.domain.dto.ReportSubmitDTO;
import com.litisle.litislereview.domain.dto.TaskDecideDTO;
import com.litisle.litislereview.domain.entity.Report;
import com.litisle.litislereview.domain.entity.SensitiveWordLibrary;
import com.litisle.litislereview.dfa.WordlibMetrics;
import com.litisle.litislereview.dfa.WordlibVersionService;
import com.litisle.litislereview.domain.entity.SensitiveWordVersion;
import com.litisle.litislereview.domain.vo.PunishLogVO;
import com.litisle.litislereview.domain.vo.ReportVO;
import com.litisle.litislereview.domain.vo.ReviewTaskVO;
import com.litisle.litislereview.enums.WordLevelEnum;
import com.litisle.litislereview.enums.WordListTypeEnum;
import com.litisle.litislereview.enums.WordSceneEnum;
import com.litisle.litislereview.enums.WordStatusEnum;
import com.litisle.litislereview.mapper.AppealMapper;
import com.litisle.litislereview.mapper.AuthorApplyMapper;
import com.litisle.litislereview.mapper.ModerationRecordMapper;
import com.litisle.litislereview.mapper.PunishLogMapper;
import com.litisle.litislereview.mapper.ReportEvidenceMapper;
import com.litisle.litislereview.mapper.ReportMapper;
import com.litisle.litislereview.mapper.ReviewTaskMapper;
import com.litisle.litislereview.mapper.SensitiveWordLibraryMapper;
import com.litisle.litislereview.mapper.SensitiveWordVersionMapper;
import com.litisle.litislereview.mapper.ViolationScoreMapper;
import com.litisle.litislereview.service.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ReviewService 实现（骨架版：逐方法 TODO 占位，按分组顺序落地）
 *
 * 落地顺序建议（依赖递进）：
 * 1. 举报链路：submitReport → withdrawReport（ReportMapper CAS + ReportEvidence 复合键）
 * 2. 人审链路：listQueue → getTask → claimTask（CAS 70004）→ decideTask（终态 CAS + 联动）
 * 3. 申诉链路：submitAppeal（双层防重）→ judgeAppeal（ADMIN 回避 + confirm 双签）
 * 4. 处罚查询：listMyPunish / getActivePunishes（PunishLog status=1）
 * 5. 词库管理：addWord/updateWord/deleteWord/importWords/exportWords/listWords
 *    （写操作与 SensitiveWordVersion version+1 同事务 + Redis Pub 广播）
 * 6. 内部契约：getInternalReport（未来 ReviewClient Provider 委托）
 *
 * 【注意】机审执行（DFA/LLM/图片模型）与 MQ 消费不在本类，放 listener + ModerationService
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewServiceImpl implements ReviewService {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReportMapper reportMapper;
    private final ReportEvidenceMapper reportEvidenceMapper;
    private final AppealMapper appealMapper;
    private final PunishLogMapper punishLogMapper;
    private final ViolationScoreMapper violationScoreMapper;
    private final ModerationRecordMapper moderationRecordMapper;
    private final SensitiveWordLibraryMapper sensitiveWordLibraryMapper;
    private final SensitiveWordVersionMapper sensitiveWordVersionMapper;
    private final WordlibVersionService wordlibVersionService;
    private final WordlibMetrics wordlibMetrics;
    private final AuthorApplyMapper authorApplyMapper;

    /** 词长度上限（与实体 @Size 对齐，DFA 构建前拦截，§2.12） */
    private static final int WORD_MAX_LENGTH = 64;

    /** 词合法字符：禁止空白与控制字符（防 DFA 分词失真/日志注入） */
    private static final Pattern WORD_PATTERN = Pattern.compile("[^\\s\\p{Cntrl}]+");

    /** 导入文件大小上限 2MB（导入限类型+大小，§2.12） */
    private static final long IMPORT_MAX_BYTES = 2L * 1024 * 1024;

    /** 词库列表安全上限（配置类小表，防漏加条件全量拉取） */
    private static final long WORD_LIST_LIMIT = 1000L;

    /** CSV 公式注入前缀字符：以这些字符开头的单元格前置 '（防审核员本地打开即执行公式，§2.12） */
    private static final String CSV_FORMULA_PREFIXES = "=+-@";

    /** 提交举报：TODO Step1 防自举报 → Step2 活跃去重 → Step3 落 report+证据 → Step4 DFA 初审分流 */
    @Override
    public ReportVO submitReport(ReportSubmitDTO dto, Long reporterId) {
        throw new UnsupportedOperationException("submitReport 待实现");
    }

    /** 撤回举报：TODO CAS where reporter_id=? and status<3 置 4-已撤回；affected=0 抛错 */
    @Override
    public void withdrawReport(Long reportId, Long reporterId) {
        throw new UnsupportedOperationException("withdrawReport 待实现");
    }

    /** 人审队列：TODO REVIEWER/ADMIN 校验 + layer2_risk 降序 + 认领配额过滤 */
    @Override
    public List<ReviewTaskVO> listQueue(Long operatorId) {
        throw new UnsupportedOperationException("listQueue 待实现");
    }

    /** 任务详情：TODO REVIEWER/ADMIN 校验 + 证据图经 FileClient 批量取 URL */
    @Override
    public ReviewTaskVO getTask(Long taskId, Long operatorId) {
        throw new UnsupportedOperationException("getTask 待实现");
    }

    /** 认领任务：TODO CAS where id=? and status=2 and assignee is null；affected=0 抛 70004 */
    @Override
    public void claimTask(Long taskId, Long reviewerId) {
        throw new UnsupportedOperationException("claimTask 待实现");
    }

    /** 裁决任务：TODO 终态 CAS + REJECT 联动（下架事件/处罚落行/违规分累加/举报回写）+ 高风险 confirm 双签 */
    @Override
    public void decideTask(Long taskId, TaskDecideDTO dto, Long reviewerId) {
        throw new UnsupportedOperationException("decideTask 待实现");
    }

    /** 提交申诉：TODO 归属校验 → 双层防重（关联列级+内容级）→ 落 appeal（bizType/bizId 解析固化） */
    @Override
    public void submitAppeal(AppealSubmitDTO dto, Long appellantId) {
        throw new UnsupportedOperationException("submitAppeal 待实现");
    }

    /** 裁决申诉：TODO ADMIN+原裁决人回避+confirm 双签；撤销联动 punish_log 撤销/违规分重算/举报批量回写 */
    @Override
    public void judgeAppeal(Long appealId, AppealDecideDTO dto, Long judgeId) {
        throw new UnsupportedOperationException("judgeAppeal 待实现");
    }

    /** 我的处罚流水：TODO 仅本人；status 全量展示，reason UGC 回显由前端 sanitize */
    @Override
    public List<PunishLogVO> listMyPunish(Long userId) {
        throw new UnsupportedOperationException("listMyPunish 待实现");
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
        // Step4 版本+1（同事务），事务提交后写 Redis 快照 + Pub 广播各实例重建 DFA
        wordlibVersionService.bumpAndBroadcast(scene, operatorId);
    }

    /** 修改敏感词（含启停/黑白切换）：同 addWord 事务+Pub 约束；scene 不允许变更（变更需双场景 bump，一期不支持） */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateWord(SensitiveWordLibrary word, Long operatorId) {
        // Step1 定位原行（物理删小表，无逻辑删）
        SensitiveWordLibrary origin = sensitiveWordLibraryMapper.selectById(word.getId());
        if (origin == null) {
            throw new BizException(ErrorCode.WORD_NOT_FOUND);
        }
        // Step2 按传入字段增量更新（scene 强制沿用原行，防止跨场景迁移绕过版本一致性）
        if (word.getWord() != null) {
            validateWord(word.getWord());
            Long count = sensitiveWordLibraryMapper.selectCount(new LambdaQueryWrapper<SensitiveWordLibrary>()
                    .eq(SensitiveWordLibrary::getScene, origin.getScene())
                    .eq(SensitiveWordLibrary::getWord, word.getWord())
                    .ne(SensitiveWordLibrary::getId, word.getId()));
            if (count != null && count > 0) {
                throw new BizException(ErrorCode.WORD_DUPLICATED);
            }
            origin.setWord(word.getWord());
        }
        if (word.getLevel() != null) {
            origin.setLevel(word.getLevel());
        }
        if (word.getIsBlacklist() != null) {
            origin.setIsBlacklist(word.getIsBlacklist());
        }
        if (word.getStatus() != null) {
            origin.setStatus(word.getStatus());
        }
        origin.setUpdatedBy(operatorId);
        sensitiveWordLibraryMapper.updateById(origin);
        // Step3 版本+1（同事务）+ 提交后广播
        wordlibVersionService.bumpAndBroadcast(origin.getScene(), operatorId);
    }

    /** 删除敏感词：物理删（配置类小表可全量重建；updated_by 审计走操作日志）；同事务 version+1 + Pub */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteWord(Long wordId, Long operatorId) {
        SensitiveWordLibrary origin = sensitiveWordLibraryMapper.selectById(wordId);
        if (origin == null) {
            throw new BizException(ErrorCode.WORD_NOT_FOUND);
        }
        sensitiveWordLibraryMapper.deleteById(wordId);
        wordlibVersionService.bumpAndBroadcast(origin.getScene(), operatorId);
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
     * 词库误报上报（§2.5）：仅校验词存在（防胡乱上报刷计数）+ Counter 埋点 + warn 日志；
     * 不落库——Counter 是灰度转正唯一数据源（仅 scene 标签），词级明细靠日志排障；
     * 运营判定闭环（降级 HIGH→MID / 按 scene 加白）复用 updateWord
     */
    @Override
    public void reportMisreport(String word, WordSceneEnum scene, String reason, Long reporterId) {
        Long count = sensitiveWordLibraryMapper.selectCount(new LambdaQueryWrapper<SensitiveWordLibrary>()
                .eq(SensitiveWordLibrary::getScene, scene)
                .eq(SensitiveWordLibrary::getWord, word));
        if (count == null || count == 0) {
            throw new BizException(ErrorCode.WORD_NOT_FOUND);
        }
        wordlibMetrics.incrementMisreport(scene);
        // UGC 落日志前剥离 \r\n（防 CRLF 伪造日志行，项目纪律）
        String safeWord = word.replace("\r", "").replace("\n", "");
        String safeReason = reason == null ? "" : reason.replace("\r", "").replace("\n", "");
        log.warn("[词库误报上报] scene={}, word={}, reason={}, reporterId={}", scene, safeWord, safeReason, reporterId);
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
        if (!filename.endsWith(".csv") && !filename.endsWith(".txt")) {
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
        // Step3 逐词清洗校验：去 BOM/包裹引号（与导出 CSV 转义约定互逆）→ validateWord fail-fast（宁严勿松，禁止半截导入）→ 文件内去重
        Set<String> validated = new LinkedHashSet<>();
        for (String raw : rawLines) {
            String word = raw == null ? "" : raw.strip();
            // UTF-8 BOM 兼容：仅文件首行词头会附着 \uFEFF
            if (word.startsWith("\uFEFF")) {
                word = word.substring(1);
            }
            if (word.isEmpty()) {
                continue;
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
        // Step5 逐词落库（冲突词静默跳过计数；行级 version 审计快照与 addWord 同口径）
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
                    .setVersion(selectSceneVersion(scene)));
        }
        log.info("词库导入完成：scene={}, 新增词={}, 冲突跳过={}", scene, validated.size() - skipped, skipped);
        // Step6 有净新增才推进版本：version+1 与本导入同事务，提交后统一写 Redis 快照 + Pub 广播
        if (validated.size() > skipped) {
            wordlibVersionService.bumpAndBroadcast(scene, operatorId);
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
        StringBuilder csv = new StringBuilder("word,scene,level,is_blacklist,status,create_time\n");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (SensitiveWordLibrary row : rows) {
            csv.append(escapeCsvCell(row.getWord())).append(',')
                    .append(escapeCsvCell(row.getScene() == null ? "" : row.getScene().name())).append(',')
                    .append(escapeCsvCell(row.getLevel() == null ? "" : row.getLevel().name())).append(',')
                    .append(escapeCsvCell(row.getIsBlacklist() == null ? "" : row.getIsBlacklist().name())).append(',')
                    .append(escapeCsvCell(row.getStatus() == null ? "" : row.getStatus().name())).append(',')
                    .append(escapeCsvCell(row.getCreateTime() == null ? "" : row.getCreateTime().format(fmt)))
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
     * apply 的 {0} 走 #{} 预编译绑定，转义后的关键词本身无注入面；结果强制 LIMIT 安全上限
     */
    @Override
    public List<SensitiveWordLibrary> listWords(WordSceneEnum scene, String keyword, WordLevelEnum level,
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
        wrapper.orderByAsc(SensitiveWordLibrary::getId)
                .last("LIMIT " + WORD_LIST_LIMIT);
        return sensitiveWordLibraryMapper.selectList(wrapper);
    }

    /** 内部：按举报ID查原始行（未来 ReviewClient Provider 委托，网关不路由） */
    @Override
    public Report getInternalReport(Long reportId) {
        throw new UnsupportedOperationException("getInternalReport 待实现");
    }

    /** 内部：查用户当前处罚态（仅 status=1 生效行，登录判定/发帖前拦截用） */
    @Override
    public List<PunishLogVO> getActivePunishes(Long userId) {
        throw new UnsupportedOperationException("getActivePunishes 待实现");
    }
}
