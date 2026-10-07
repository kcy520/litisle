package com.litisle.litisleapi.dfa;

import java.util.List;

/**
 * 词库装载 SPI（DFA 引擎的数据源抽象，各宿主服务提供实现）
 *
 * <p>选型速查：</p>
 * <table border="1">
 *   <tr><th>宿主</th><th>实现方式</th><th>版本源</th></tr>
 *   <tr><td>litisle-review</td><td>查 sensitive_word_library 表（scene+status=1）</td><td>sensitive_word_version（scene 级 SSOT）</td></tr>
 *   <tr><td>content/message</td><td>读 Redis 词库快照（review 广播维护，JSON 序列化）</td><td>同上（快照携带 version）</td></tr>
 * </table>
 *
 * <p>【兜底纪律（§4.4.4，宁严勿松）】实现加载失败/为空时须回退本地持久化快照（上次成功词库）
 * 并告警，绝不静默返回空列表造成 default-allow；仅当回退后仍无可用数据才抛异常（启动期）或
 * 交由 DfaFacade 抛出未初始化异常（运行期）</p>
 *
 * <p>【场景入参】scene 为 DfaScenes.normalize 归一后的文本场景（ARTICLE/COMMENT/USERNAME/
 * MESSAGE），图片场景不会传入（加载侧按 DfaScenes.normalize==null 跳过）</p>
 */
public interface WordLibraryLoader {

    /**
     * 当前词库版本（sensitive_word_version.scene 级；-1 表示无版本记录）
     */
    long loadVersion(String scene);

    /**
     * 装载该场景全部启用词条（黑+白名单，DFA 引擎按 blacklist 分流入 houbb 词库）
     */
    List<WordEntry> loadWords(String scene);
}