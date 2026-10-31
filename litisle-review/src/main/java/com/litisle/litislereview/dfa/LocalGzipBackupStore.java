package com.litisle.litislereview.dfa;

import com.litisle.litisleapi.dfa.WordEntry;
import com.litisle.litislereview.moderation.config.ModerationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import java.io.InputStream;

/**
 * 词库冷备本地实现：gzip 压缩 CSV 落盘（批次1 ①，{@link WordlibBackupStore} 一期实现）
 *
 * <p>【文件布局】{backup-dir}/{scene}/{scene}-v{version}.csv.gz——词库纯文本 gz 压缩率高
 * （约 1/5~1/10），全版本保留磁盘无压力；retention-versions 控制每 scene 保留最近 N 版，
 * 每次落盘后自动清理更旧版本文件（防磁盘无限增长）。</p>
 *
 * <p>【写入安全】临时文件 + 原子 move（与 TableWordLibraryLoader 快照同纪律）：半途崩溃最多
 * 留 .tmp 残留，绝不留截断备份文件；⚠️ 备份目录必须是挂载持久卷（容器本地盘随实例销毁会丢备份）。</p>
 *
 * <p>【CSV 契约】表头 word,level,blacklist，逐词一行，UTF-8；首列词内容与既有导入/导出契约对齐，
 * 可直接走词库导入入口回灌（validateWord 限长+字符校验链自动生效）。与导出文件防公式注入不同：
 * 备份文件不进 Excel、只服务回灌演练，词内容零失真优先（不前置 ' 转义，=/-/@ 开头词原样保留）。</p>
 *
 * <p>【激活】litisle.moderation.wordlib.backup.enabled=true 时注册（默认关=纯新增不改变现网行为）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "litisle.moderation.wordlib.backup", name = "enabled", havingValue = "true")
public class LocalGzipBackupStore implements WordlibBackupStore {

    /** CSV 表头常量（首列 word 与导入入口契约对齐：首列词内容可直接回导入） */
    private static final String CSV_HEADER = "word,level,blacklist";

    private final ModerationProperties properties;

    /**
     * 保存该版本全量词条为 gz 压缩 CSV：写临时文件 → 原子 move 成正式备份文件 → 清理过期版本
     *
     * 落盘失败抛异常交由 WordlibBackupTrigger 统一告警（备份失败仅告警不阻断词库变更）
     */
    @Override
    public void save(String scene, long version, List<WordEntry> words) {
        // 防御：空词库备份无意义（恢复出空词库会破坏「绝不空词库放行」纪律），直接拒绝
        if (words == null || words.isEmpty()) {
            throw new IllegalArgumentException("空词库不备份：scene=" + scene + ", version=" + version);
        }
        Path file = backupFile(scene, version);
        Path dir = file.getParent();
        // 临时文件引用提到 try 外：异常时 catch 可 best-effort 删除，防 .tmp 残留累积
        Path tmp = null;
        try {
            // Step1 确保备份目录存在（{dir}/{scene}/，首次落盘时目录可能尚未创建）
            Files.createDirectories(dir);
            // Step2 临时文件与正式备份同目录（保证原子 move 同文件系统）
            tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
            writeGzipCsv(tmp, words);
            // Step3 原子替换（半途崩溃最多留 .tmp，绝不留截断备份）；FS 不支持原子时降级普通 move
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("词库冷备已落盘：scene={}, version={}, 词数={}, file={}", scene, version, words.size(), file);
        } catch (IOException e) {
            // 进程内异常兜底：即时删除半成品临时文件（best-effort，删除失败仅告警）
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception cleanupEx) {
                    log.warn("词库备份临时文件清理失败：{}", tmp, cleanupEx);
                }
            }
            throw new UncheckedIOException("词库冷备落盘失败：scene=" + scene + ", version=" + version, e);
        }
        // Step4 清理过期版本（超出 retention-versions 的最旧备份），失败不影响本次备份结果
        pruneOutdatedVersions(scene);
    }

    /**
     * 读取指定版本备份全文：解压返回 CSV 文本
     *
     * 红线：本方法只读，禁止自动回滚——回灌必须由人工把返回文本经既有词库导入入口执行
     * （validateWord 限长+字符校验链自动生效），防被污染的备份文件绕过校验直写词库
     */
    @Override
    public String restore(String scene, long version) {
        Path file = backupFile(scene, version);
        if (!Files.exists(file)) {
            throw new IllegalStateException("词库备份文件不存在：scene=" + scene + ", version=" + version + ", file=" + file);
        }
        try (InputStream in = Files.newInputStream(file);
             GZIPInputStream gz = new GZIPInputStream(in)) {
            return new String(gz.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("词库备份文件读取失败（文件可能损坏）：scene=" + scene + ", version=" + version, e);
        }
    }

    /**
     * 将词条列表写为 gz 压缩 CSV（表头 + 逐词一行）
     */
    private void writeGzipCsv(Path target, List<WordEntry> words) throws IOException {
        try (OutputStream out = new GZIPOutputStream(
                Files.newOutputStream(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            writer.write(CSV_HEADER);
            writer.newLine();
            // 逐词一行：word 列按 CSV 标准转义（含逗号/引号时双引号包裹+内部引号翻倍，
            // 与导入入口的首列解析约定互逆）；level/blacklist 为枚举码无特殊字符直写
            for (WordEntry word : words) {
                writer.write(escapeCsvCell(word.word()));
                writer.write(',');
                writer.write(String.valueOf(word.level()));
                writer.write(',');
                writer.write(String.valueOf(word.blacklist()));
                writer.newLine();
            }
        }
    }

    /**
     * CSV 单元格转义（仅备份回灌口径）：含逗号/引号/换行时双引号包裹并将内部引号翻倍；
     * 不做导出文件的公式注入前缀转义（备份文件不进 Excel，词内容零失真优先）
     */
    private String escapeCsvCell(String cell) {
        if (cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r")) {
            return '"' + cell.replace("\"", "\"\"") + '"';
        }
        return cell;
    }


    /**
     * 过期版本清理：按 retention-versions 保留最近 N 版，删除更旧版本文件；
     * 按 scene 维度，只保留最近 N 个版本的 gzip CSV 备份文件，超出部分按版本号从旧到新逐个删除，防止磁盘无限增长。
     * 文件名版本号解析失败的文件一律不动（防误删人工放置的文件），单文件删除失败仅告警下轮重试
     */
    private void pruneOutdatedVersions(String scene) {
        
        //Step 1：读取并校验 retention 配置
        // 防御：非正数视为不清理（保留全版本）
        int retention = properties.getWordlib().getBackup().getRetentionVersions();
        if (retention <= 0) {
            return;
        }
        
        //Step 2：定位 scene 备份目录
        Path sceneDir = backupDir(scene);
        if (!Files.isDirectory(sceneDir)) {
            return;
        }

        // Step 3：扫描目录，解析合法版本号
        // 解析该 scene 目录下全部 {scene}-v{N}.csv.gz 的版本号
        String prefix = scene + "-v";
        List<Long> versions = new ArrayList<>();
        try (var stream = Files.list(sceneDir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                if (name.startsWith(prefix) && name.endsWith(BACKUP_FILE_SUFFIX)) {
                    try {
                        versions.add(Long.parseLong(name.substring(prefix.length(),
                                name.length() - BACKUP_FILE_SUFFIX.length())));
                    } catch (NumberFormatException ignored) {
                        // 版本号解析失败不纳入清理范围（防误删非本组件命名的文件）
                    }
                }
            });
        } catch (IOException e) {
            log.warn("词库备份目录扫描失败（跳过本轮过期清理）：scene={}, dir={}", scene, sceneDir, e);
            return;
        }

        //Step 4：按版本号排序并删除“过期版本”
        // 新版本在前排序，retention 位之后即为过期版本逐个删除
        versions.sort(Comparator.reverseOrder());
        for (int i = retention; i < versions.size(); i++) {
            try {
                Files.deleteIfExists(backupFile(scene, versions.get(i)));
                log.info("词库冷备过期清理：scene={}, version={}（保留最近 {} 版）", scene, versions.get(i), retention);
            } catch (IOException e) {
                log.warn("词库冷备过期文件清理失败（下轮备份重试）：scene={}, version={}", scene, versions.get(i), e);
            }
        }
    }

    /**
     * 备份根目录下该 scene 的子目录（{backup-dir}/{scene}/）
     */
    private Path backupDir(String scene) {
        return Path.of(properties.getWordlib().getBackup().getDir(), scene);
    }

    /**
     * 该 scene 指定版本的备份文件路径（{backup-dir}/{scene}/{scene}-v{version}.csv.gz，
     * 文件名走 WordlibBackupStore.backupFileName 统一口径）
     */
    private Path backupFile(String scene, long version) {
        return backupDir(scene).resolve(WordlibBackupStore.backupFileName(scene, version));
    }
}
