package com.litisle.litislereview.dfa;

import com.litisle.litisleapi.dfa.WordEntry;

import java.util.List;

/**
 * 词库备份存储 SPI（批次1 ①：词库治理基建，纯新增不触碰引擎链路）
 *
 * <p>【角色定位】与既有两套兜底互补：Redis 词库快照 = 热备（仅存最新版，冷启动兜底），
 * 本地文件快照 = 装载兜底（TableWordLibraryLoader 回退用）；本接口实现 = 冷备
 * （按版本号全量保留、可人工恢复）。二期私有化部署上 MinIO 时新增实现类接同一接口，
 * 调用方零代码切换。</p>
 *
 * <p>【激活方式】实现类经 @ConditionalOnProperty(litisle.moderation.wordlib.backup.enabled=true)
 * 注册；未激活时 WordlibBackupTrigger 拿不到实现 Bean，零开销跳过，词库变更链路不受影响。</p>
 *
 * <p>【恢复纪律（红线）】恢复走人工 runbook，禁自动回滚（防旧备份把新词库误回滚）：
 * restore 只读返回备份文本，回灌必须经既有词库导入入口（逐词 validateWord 限长+字符校验链
 * 自动生效），防被污染的备份文件绕过校验直接写库；本接口实现禁止任何直写词表（DB）的行为。</p>
 */
public interface WordlibBackupStore {

    /** 备份文件扩展名（文件名统一口径的一部分） */
    String BACKUP_FILE_SUFFIX = ".csv.gz";

    /**
     * 备份文件名统一口径（{scene}-v{version}.csv.gz）：备份落盘与审计表 backup_file 引用列
     * 共用本方法生成，防两处各自拼串漂移
     *
     * @param scene   词库场景码
     * @param version 词库版本号
     * @return 备份文件名（如 ARTICLE-v12.csv.gz）
     */
    static String backupFileName(String scene, long version) {
        return scene + "-v" + version + BACKUP_FILE_SUFFIX;
    }

    /**
     * 保存一份词库版本冷备（该版本全量启用词条）
     *
     * @param scene   词库场景码（ARTICLE/COMMENT/USERNAME/MESSAGE/BIO）
     * @param version 词库版本号（sensitive_word_version 推进后的新版本）
     * @param words   该版本全量启用词条（调用方从词库装载，保证非空）
     */
    void save(String scene, long version, List<WordEntry> words);

    /**
     * 读取指定版本备份内容（纯文本 CSV 全文，供人工 runbook 经既有导入入口回灌）
     *
     * @param scene   词库场景码
     * @param version 目标备份版本号
     * @return 备份文件解压后的 CSV 全文（表头 word,level,blacklist；首列词内容可直接回导入）
     */
    String restore(String scene, long version);
}
