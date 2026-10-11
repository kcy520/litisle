package com.litisle.litislereview.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litislereview.dfa.WordlibMetrics;
import com.litisle.litislereview.dfa.WordlibVersionService;
import com.litisle.litislereview.domain.dto.WordUpdateDTO;
import com.litisle.litislereview.domain.entity.SensitiveWordLibrary;
import com.litisle.litislereview.domain.entity.SensitiveWordVersion;
import com.litisle.litislereview.domain.vo.SensitiveWordVO;
import com.litisle.litislereview.enums.WordLevelEnum;
import com.litisle.litislereview.enums.WordListTypeEnum;
import com.litisle.litislereview.enums.WordSceneEnum;
import com.litisle.litislereview.enums.WordStatusEnum;
import com.litisle.litislereview.mapper.SensitiveWordLibraryMapper;
import com.litisle.litislereview.mapper.SensitiveWordVersionMapper;
import com.litisle.litislereview.service.WordlibService;
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
            origin.setWord(word.getWord());
            changed = true;
        }
        if (word.getLevel() != null && word.getLevel() != origin.getLevel()) {
            origin.setLevel(word.getLevel());
            changed = true;
        }
        if (word.getIsBlacklist() != null && word.getIsBlacklist() != origin.getIsBlacklist()) {
            origin.setIsBlacklist(word.getIsBlacklist());
            changed = true;
        }
        if (word.getStatus() != null && word.getStatus() != origin.getStatus()) {
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
        // Step4 版本+1（同事务）+ 提交后广播
        wordlibVersionService.bumpAndBroadcast(origin.getScene(), operatorId);
        return true;
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

    /** 按场景+词内容删除敏感词：TODO scene+word 精确匹配定位 → 不存在抛 WORD_NOT_FOUND → 物理删 + version+1 同事务 + Pub */
    @Override
    public void deleteWordByContent(WordSceneEnum scene, String word, Long operatorId) {
        throw new UnsupportedOperationException("deleteWordByContent 待实现");
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
        // 仅允许黑名单词上报误报：
        // 白名单词不会触发拦截，误报计数无意义；
        // 若放行，会污染 wordlib_misreport_total，影响灰度转正判定
        Long count = sensitiveWordLibraryMapper.selectCount(
                new LambdaQueryWrapper<SensitiveWordLibrary>()
                        .eq(SensitiveWordLibrary::getScene, scene)
                        .eq(SensitiveWordLibrary::getWord, word)
                        .eq(SensitiveWordLibrary::getIsBlacklist, WordListTypeEnum.BLACKLIST)
        );

        if (count == null || count == 0) {
            throw new BizException(ErrorCode.WORD_NOT_FOUND);
        }

        //误报上报计数 +1（scene 标签）
        wordlibMetrics.incrementMisreport(scene);

        // 安全处理：防止 CRLF 日志注入
        String safeWord = word.replace("\r", "").replace("\n", "");
        String safeReason = reason == null ? "" : reason.replace("\r", "").replace("\n", "");

        // 词级明细仅落日志，用于排障 & 运营分析
        log.warn(
                "[词库误报上报] scene={}, word={}, reason={}, reporterId={}",
                scene, safeWord, safeReason, reporterId
        );
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
        // Step3 逐词清洗校验：去 BOM → 6 列导出格式拆首列 → 剥包裹引号（与导出 CSV 转义约定互逆）→
        // validateWord fail-fast（宁严勿松，禁止半截导入）→ 文件内去重
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
     * apply 的 {0} 走 #{} 预编译绑定，转义后的关键词本身无注入面；结果强制 LIMIT 安全上限；
     * 出参转 SensitiveWordVO，不回吐 updatedBy/version 审计字段
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
        wrapper.orderByAsc(SensitiveWordLibrary::getId)
                .last("LIMIT " + WORD_LIST_LIMIT);
        return sensitiveWordLibraryMapper.selectList(wrapper).stream()
                .map(this::toWordVo)
                .toList();
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
