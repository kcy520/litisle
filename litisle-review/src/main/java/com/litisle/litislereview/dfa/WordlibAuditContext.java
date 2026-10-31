package com.litisle.litislereview.dfa;

import com.litisle.litislereview.enums.WordlibOpTypeEnum;

/**
 * 词库变更审计上下文（批次1 ②）：由各词表变更入口组装，随 bumpAndBroadcast 传入，
 * 在版本推进同一事务内落审计表（wordlib_change_audit，红线：审计不同事务=不可信）
 *
 * @param opType    操作类型：ADD/UPDATE/DELETE/BATCH_IMPORT
 * @param word      变更词（单条变更逐词审计；批量导入传 null——词清单在备份文件里不重复落库）
 * @param detail    变更摘要（如 level: MID→HIGH；批量导入传 null）
 * @param wordCount 批量导入净新增词数（仅 BATCH_IMPORT 传，其余 null）
 */
public record WordlibAuditContext(
        WordlibOpTypeEnum opType,
        String word,
        String detail,
        Integer wordCount) {
}
