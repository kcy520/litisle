package com.litisle.litisleapi.dfa;

/**
 * 词库词条（WordLibraryLoader 装载产物，来自 sensitive_word_library 表）
 *
 * <p>level 为 int code：1-HIGH（同步拦截 70001）/ 2-MID（只标记 layer1_hit=2，兼新增词灰度位）；
 * SSOT 是 litisle-review 的 WordLevelEnum，契约层传 code（改名须同步）。
 * blacklist=true 进引擎黑名单（deny，命中即证据），false 进白名单（allow，优先级高于黑名单）</p>
 */
public record WordEntry(String word, int level, boolean blacklist) {
}