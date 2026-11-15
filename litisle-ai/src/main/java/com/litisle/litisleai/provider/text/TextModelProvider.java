package com.litisle.litisleai.provider.text;

/**
 * 文本模型通道端口（provider 层是唯一接触模型 SDK/HTTP 的层，controller/service 不直连模型）
 *
 * <p>【职责边界】本接口只定义「system + user 双段 prompt → 文本回复 + token 用量」的最小通道面；
 * prompt 模板组织、结构校验、预算与计量全部由调用方（service）承担。</p>
 *
 * <p>【失败语义】调用失败 fail-fast 上抛（SDK 异常原样透出），不静默吞错、不默认放行；
 * 失败留痕（计量 status=FAIL）由调用方在 catch 中收口。</p>
 *
 * <p>【日志纪律】实现类只记标识与必要片段（字符数/token 数/耗时），禁止打印 prompt 正文全文。</p>
 */
public interface TextModelProvider {

    /**
     * 发起一次文本模型调用（同步阻塞）
     *
     * @param request system + user 双段 prompt 入参
     * @return 模型输出与 token 用量；失败抛异常（fail-fast，不返回 null）
     */
    TextChatResult chat(TextChatRequest request);
}
