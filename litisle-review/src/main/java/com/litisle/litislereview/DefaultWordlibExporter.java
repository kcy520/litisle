package com.litisle.litislereview;

import com.github.houbb.sensitive.word.bs.SensitiveWordBs;
import com.github.houbb.sensitive.word.support.deny.WordDenys;
import com.github.houbb.sensitive.word.support.tag.WordTags;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一次性工具：提取 houbb sensitive-word 内置默认词库（6W+），按类目筛选子集后
 * 生成可直接执行的 seed SQL，供导入 sensitive_word_library 表。
 * 不进 Git / 不入生产代码，用完即删。（但是可以留作给出参考，虽然无用，但是可以不必删除）
 *
 * 1. `E:\NewJavaPro\LitIsle\litisle-review\target\seed-words-article.sql` （`Path.of("target", ...)` 相对于 IDEA 的运行工作目录`litisle-review` 模块根）。
 */
public class DefaultWordlibExporter {

//    ⚠️ 一个预期提醒：由于`WordTags.system()` 标签覆盖率有限，5 类全开的情况下「本次导出」可能仍明显小于总量 6W+（无标签词全被滤掉）。如果你希望 全量导入 ，把`INCLUDE_TAGS` 改为空集合`Set.of()` 即可，无标签词会按 MID 落库。
//      输出结果：内置词库总量=64431，超长/空词跳过=1，本次导出=43691，输出文件=E:\NewJavaPro\LitIsle\target\seed-words-article.sql
    //4w+

    /** 导入目标场景（TableWordLibraryLoader 仅加载文本场景；USERNAME 场景慎全量导入，见类尾注意） */
    private static final String SCENE = "ARTICLE";

    /** 导入人ID（updated_by 审计列，改为你的管理员ID） */
    private static final long UPDATED_BY = 1L;

    /** 类目 → 落表级别映射：1-HIGH 2-MID（对应 WordLevelEnum，业务口径可自行调整） */
    private static final Map<String, Integer> TAG_LEVEL = Map.of(
            "0", 1,  // 政治 → HIGH（命中同步拒绝）
            "1", 1,  // 毒品 → HIGH
            "2", 1,  // 色情 → HIGH
            "3", 2,  // 赌博 → MID（送 LLM 语义复审）
            "4", 2   // 违法 → MID
    );

    /** word 列 varchar(64) 上限 */
    private static final int MAX_WORD_LEN = 64;

    /** 每个类目的导出词数配额：色情/政治等各限 1000 词（词命中多类目时向每个命中类目各扂1个名额） */
    private static final int QUOTA_PER_TAG = 1000;

    /** 只导入这些类目（子集筛选开关：删掉不想要的类目即可），空集合 = 全量 */
    private static final Set<String> INCLUDE_TAGS = Set.of("0", "1", "2", "3", "4");

    public static void main(String[] args) throws IOException {
        // Step1 构建带内置标签的引擎实例（仅用于查标签，不做文本匹配）
        SensitiveWordBs bs = SensitiveWordBs.newInstance()
                .wordTag(WordTags.system())
                .init();

        // Step2 拉取内置默认词库全量词（即 houbb 默认 DFA 的 deny 数据源）
        Collection<String> allWords = WordDenys.defaults().deny();

        // Step3 逐词过滤 + 标签映射级别（LinkedHashMap 去重保序；tagCount 记录各类目已收词数）
        Map<String, Integer> picked = new LinkedHashMap<>();
        Map<String, Integer> tagCount = new LinkedHashMap<>();
        int skippedLen = 0;
        for (String raw : allWords) {
            String word = raw == null ? "" : raw.trim();
            if (word.isEmpty() || word.length() > MAX_WORD_LEN) {
                skippedLen++;
                continue;
            }
            Set<String> tags = bs.tags(word);
            // tags() 对无标签词返回 null（0.27.1：WordTags.system() 仅覆盖部分词库），按空集合兜底
            if (tags == null) {
                tags = Set.of();
            }
            // 只看纳入统计的类目（INCLUDE_TAGS 为空时视为全部 TAG_LEVEL 键）
            Set<String> tracked = INCLUDE_TAGS.isEmpty() ? TAG_LEVEL.keySet()
                    : new java.util.HashSet<>(tags);
            tracked.retainAll(INCLUDE_TAGS);
            if (tracked.isEmpty()) {
                continue;
            }
            // 类目配额过滤：仅保留配额未满的类目；全部命中类目均已满额则丢弃该词
            tracked.removeIf(tag -> tagCount.getOrDefault(tag, 0) >= QUOTA_PER_TAG);
            if (tracked.isEmpty()) {
                continue;
            }
            // 级别取该词剩余命中类目中最严的一档
            Integer level = tracked.stream().map(TAG_LEVEL::get).filter(Objects::nonNull)
                    .min(Integer::compareTo).orElse(2);
            if (picked.putIfAbsent(word, level) == null) {
                // 保留成功：向该词的每个命中类目各扂1个名额
                for (String tag : tracked) {
                    tagCount.merge(tag, 1, Integer::sum);
                }
            }
        }

        // Step4 生成 seed SQL（同文件内完成：词库版本 +1 → 读版本快照 → 批量 insert ignore）
        // insert ignore 借 uk_scene_word 天然去重，重跑不报错；同事务 bump 版本符合「版本 SSOT」纪律
        Path out = Path.of("target", "seed-words-" + SCENE.toLowerCase() + ".sql");
        List<String> sql = new ArrayList<>();
        sql.add("use litisle_review;");
        sql.add("insert into sensitive_word_version(scene, version, updated_by)");
        sql.add("values ('" + SCENE + "', 1, " + UPDATED_BY + ")");
        sql.add("on duplicate key update version = version + 1, updated_by = values(updated_by);");
        sql.add("set @v = (select version from sensitive_word_version where scene='" + SCENE + "');");
        sql.add("");
        // CSV 导出纪律：首字符为 =/+/-/@ 的词前置 ' 防公式注入（SQL 文本场景同样落此纪律）
        List<String> batch = new ArrayList<>();
        for (Map.Entry<String, Integer> e : picked.entrySet()) {
            String word = e.getKey().replaceAll("(['\\\\])", "\\\\$1");
            if ("=+-@".indexOf(word.charAt(0)) >= 0) {
                word = "'" + word;
            }
            batch.add("('" + word + "','" + SCENE + "'," + e.getValue() + ",1,1," + UPDATED_BY + ",@v)");
            if (batch.size() == 500) {
                flush(sql, batch);
            }
        }
        flush(sql, batch);
        // 父目录不存在时自动创建（IDEA 运行工作目录不一定是模块根，相对路径 target/ 可能无父目录）
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, sql, StandardCharsets.UTF_8);

        System.out.println("内置词库总量=" + allWords.size()
                + "，超长/空词跳过=" + skippedLen
                + "，本次导出=" + picked.size()
                + "，各类目收词数=" + tagCount
                + "，输出文件=" + out.toAbsolutePath());
    }

    /**
     * 按 500 行/批拼装 insert ignore 语句（避免单条 SQL 过大超出 max_allowed_packet）
     */
    private static void flush(List<String> sql, List<String> batch) {
        if (batch.isEmpty()) {
            return;
        }
        sql.add("insert ignore into sensitive_word_library(word, scene, level, is_blacklist, status, updated_by, version) values");
        sql.add(String.join(",\n", batch) + ";");
        sql.add("");
        batch.clear();
    }
}