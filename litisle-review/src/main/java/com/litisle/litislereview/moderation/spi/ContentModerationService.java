package com.litisle.litislereview.moderation.spi;


/**
 * 内容审核 SPI 门面（机审域统一收口，业务代码只依赖本接口）
 *
 * <p>职责边界：仅承载「异步语义层机审」（文本 LLM / 图片模型）；同步关键词闸门不走此接口，
 * 直接用 DFA 门面（毫秒级、本地执行）。适配器按 litisle.moderation.text-provider /
 * image-provider 配置经 @ConditionalOnProperty 激活，环境切换只改配置不改代码</p>
 *
 * <p>使用场景示例（MQ 异步消费端）：</p>
 * <pre>{@code
 * // 文本：ModerationTextConsumer 定位任务后回查正文，逐片送审
 * ModerationResult r = contentModerationService.moderateText(chunk);
 * switch (r.verdict()) { case PASS -> ...; case REJECT -> ...; case SUSPICIOUS -> ...; }
 * // 图片：ModerationImageConsumer 经 FileClient 内部契约按 fileId 取字节流后送审
 * ModerationResult img = contentModerationService.moderateImage(bytes);
 * }</pre>
 *
 * <p>实现选型速查：</p>
 * <table border="1">
 *   <tr><th>provider 配置值</th><th>适配器</th><th>覆盖范围</th></tr>
 *   <tr><td>llm</td><td>LlmTextModerationAdapter</td><td>文本语义层（防注入 + JSON 输出）</td></tr>
 *   <tr><td>onnx</td><td>OnnxImageModerationAdapter</td><td>图片鉴黄（开发期，仅 NSFW 类目）</td></tr>
 *   <tr><td>aliyun</td><td>AliyunImageModerationAdapter</td><td>图片全类目 + 合规凭证（生产期，二期引入）</td></tr>
 * </table>
 *
 * <p>扩展纪律（§2.9）：视频/音频一期显式不支持——业务出现视频/音频 UGC 前，须先新增
 * moderateVideo 等方法与对应适配器，禁止「无对应方法即默认通过」的隐式放行；
 * 多模态（图文组合）同样经本门面扩展，OCR/送审文本一律按不可信数据包裹</p>
 */
public interface ContentModerationService {

//    yml 配置 (litisle.moderation.text-provider / image-provider)
//        ↓ 启动期决定
//    @ConditionalOnProperty 条件装配 → 激活对应适配器 Bean（0/1/2个）
//            ↓
//    注入点：ModerationTextService / ModerationImageService 按 @Qualifier 精确取用
//        ↓ 运行期
//    Consumer 消费事件 → Service 调 contentModerationService.moderateText/moderateImage
//        ↓
//    兜底：ModerationProviderStartupChecker 启动自检 fail-fast

    /**
     * 文本审核（MQ 异步消费端调用）
     * <p>入参为单片待审文本：标题与正文拼接后一并送审（hash 含标题但送审漏标题时，
     * 违规语义藏标题可躲过语义层）；超长正文按段落分片/字符窗口硬切由消费端负责，
     * 本方法收到的已是切分后单片；实现须防 Prompt 注入（分隔符包裹 + system 声明
     * 数据非指令 + 强制 JSON 输出，解析失败按 SUSPICIOUS 宁疑不放）</p>
     *
     * @param content 待审文本（单片）
     * @return 三分态判定结果
     */
    ModerationResult moderateText(String content);

    /**
     * 图片审核（AVATAR/COVER 上传即审与 ARTICLE_IMG 预审/复审共用）
     * <p>入参为图片字节流：消费端按 fileId 经 FileClient 内部契约端点取图后传入，
     * 严禁携带用户提供的 URL（SSRF 纪律：机审取图唯一入口是内部契约）；云 API 适配器
     * 优先传字节流而非内网 URL</p>
     *
     * @param imageBytes 图片字节流
     * @return 三分态判定结果
     */
    ModerationResult moderateImage(byte[] imageBytes);
}