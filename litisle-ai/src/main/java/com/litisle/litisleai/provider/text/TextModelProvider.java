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
 *
 * <p>【通俗说明】这是调用 AI 文本模型的"标准插座"：业务代码只管把"系统提示词 + 用户消息"
 * 塞进来，就能拿到模型的回复和 token 用量，完全不关心背后接的是 DeepSeek 还是别家模型。
 * 好处是换供应商不用改业务代码（换个实现类 + 改 yml 即可）。它只管"传话"，至于怎么拼
 * prompt、怎么校验输出、怎么记账算钱，都是调用方（service 层）的事。</p>
 */
public interface TextModelProvider {

    /**
     * 发起一次文本模型调用（同步阻塞）
     *
     * @param request system + user 双段 prompt 入参
     * @return 模型输出与 token 用量；失败抛异常（fail-fast，不返回 null）
     *
     * <p>【通俗说明】把两段话发给模型，等它把回复说完整了再返回——像打电话，不是
     * 留言板。调用失败直接抛异常（绝不返回 null 或空串糊弄），让调用方自己决定
     * 怎么处理失败。</p>
     */
    TextChatResult chat(TextChatRequest request);
}
