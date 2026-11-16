> **归档说明**：新建板块文件（板块十，2026-11-15 归档）——迁自 docs/next/next_update.md「review模块」节板块十（文本机审判定器切换云 API），正文原样搬运（含三任务完成登记与决策口径）。
> **前置脉络**：文本机审判定器演进链 LLM（DeepSeek，迁自板块九前机审 SPI 体系）→ 本板块 阿里云内容安全云 API；用户决策背景：LLM 自建审核成本不可控、中小项目主流为云内容安全 API、LLM 适配器保留不激活（可回切/可组合生产混合方案）。

## 板块十：文本机审判定器切换云 API（中小项目主流方案，2026-11-15 定稿并落地）

#### review模块

> 板块划分依据：Review-Architecture.md / AI-Business-Guide.md §四（机审流程）；主线批次已全部归档，本节承接 2026-11-15 文本机审判定器切换决策（用户拍板：切云 API 主通道，LLM 保留不激活）

##### 板块十：文本机审判定器切换云 API（中小项目主流方案，2026-11-15 定稿）

- [x] 10.1 新增 AliyunTextModerationAdapter 文本适配器（text-provider=aliyun 激活）：调阿里云内容安全文本扫描同步 API；label→三分态/类目映射（politics/porn/abuse/ad→现有 5 类目白名单，无法映射一律 SUSPICIOUS 宁疑不放）；合规凭证（云侧 requestId/bizTypeId）透传 providerTraceId 出证；API 异常/超时→SUSPICIOUS；经 SPI ContentModerationService 实现，编排层零改动
  - 配套：litisle-review pom 增阿里云 green SDK（版本父 POM/dependencyManagement 管）；AK/SK 经 Nacos/环境变量注入禁提交明文；ModerationProperties 增 aliyun 段（endpoint/ak/sk/超时/场景策略）
  - ✅ 2026-11-15：新建 [AliyunTextModerationAdapter.java](../../litisle-review/src/main/java/com/litisle/litislereview/moderation/adapter/AliyunTextModerationAdapter.java)（@RequiredArgsConstructor + @ConditionalOnProperty(havingValue=aliyun)）；SDK 依赖 com.aliyun:green20220302:3.6.10（初选 4.1.5 不存在于中央仓库，查 maven-metadata 改 latest 3.6.10；版本锁父 POM dependencyManagement）；实现要点：Client 懒加载双检锁（AK 未配置不阻断启动，调用期抛异常走兜底转人工，与 LLM 无 key 语义对称）、service 按 scene 映射（MESSAGE→chat_detection/COMMENT→comment_detection/其余→moment_detection）、dataId 复用幂等键 taskId:chunkIndex（缺失 fail-fast）、响应映射按 3.6.10 真实结构（data 为单对象、labels 为 "label:score" 逗号分隔串、getMessage 非 getMsg、Config.setReadTimeout 按 Integer）——解析 labels 取最高分项（多标签最严判定，单项脏数据跳过），白名单 politics/porn/abuse/ad + rejectThreshold 双条件 → REJECT，越界/低分/格式异常/异常超时/响应非 200/AK 缺失 → SUSPICIOUS 六出口宁疑不放，云 requestId 透传 providerTraceId 出证；ModerationProperties 增 Aliyun 段（endpoint/ak/sk/timeoutMs=5000 int/rejectThreshold=90/maxTextChars=400/stepChars=350/overlapChars=50）
- [x] 10.2 配置切换与分片参数对齐：application yml/Nacos `litisle.moderation.text-provider=aliyun`（默认切换）；分片步长/重叠/单片限长按云 API 单条限长重配（llm 的 max-text-chars=5500 口径不适用——云 API 按条计费限长更细，分片机制 splitChunks/TextModerationRequest 复用不改）；ModerationModelQpsGuard 保留（防打爆云 API QPS 计费档位）
  - LLM 资产处置：LlmTextModerationAdapter **保留不删不激活**（SPI 共存切换，回归只改一行配置；未来可做「云 SUSPICIOUS → LLM 二次精判」生产混合方案）；AiChatController/AiChatService 机审端点闲置保留（通用对话契约，生成类不受影响）
  - ✅ 2026-11-15：application.yml `text-provider: aliyun`（注释标明 LLM 回切只改一行）+ 新增 aliyun 配置段（AK/SK 环境变量 ALIYUN_GREEN_AK_ID/SECRET 注入）；分片参数实现「随 textProvider 跟随」：ModerationTextService.splitChunks 按 text-provider 分支取 aliyun 段（400/350/50）或 llm 段（6000/5500/500）——配置切换分片口径自动对齐，llm 段三参数保留并注释「当前不读取、LLM 回切恢复生效」；ModerationModelQpsGuard/QPS 护栏不动
- [x] 10.3 计量与观测口径迁移：review 直调云 API 后不再经 litisle-ai——机审不再产生 ai_token_usage 明细（AI 侧机审合并桶对机审失效，计量/预算体系保留给生成类）；机审成本观测转云账单（阿里云控制台按量）；moderation_total/metrics 的 provider 维度核对（dfa/llm/aliyun 分列统计）
  - 业务语义不变清单（验收口径）：任务幂等分流/缓存查重（四条件版本化）/在途短锁/QPS 护栏/三分态聚合（禁静默截断）/落库留痕 append-only/result 事件回写/REJECT 直落处罚（5-1.1 score=0）/申诉恢复——判定器无关，全部零改动零回归
  - 人工审核触发前提不变（四入口）：①机审 SUSPICIOUS→人工待审（云 label 疑似/接口异常，来源出口减少无 JSON 解析失败）；②举报复审初始即 2-人工待审跳过机审；③DLQ 重试耗尽兜底置人工；④申诉复核维持/撤销；PASS/REJECT 均不进人工原则不变
  - 验收：编译通过 + 运行态验证（云 API 凭证就绪后：送审→云判定三分态落库→凭证出证→申诉链路可用；LLM 配置回切可跑通）——运行态项随实施移入 next_verify.md
  - ✅ 2026-11-15：provider 派生化落地——ModerationTextService 新增 resolveTextProvider()（text-provider=aliyun→ALIYUN 否则 LLM），替换两处硬编码（process 步骤5 缓存 key/短锁 key 用的 provider 变量 + persistAndPublish 留痕 setProvider），缓存/短锁/留痕 provider 分列随配置自动跟随；机审→litisle-ai 链路解除（AiClient 不再被机审调用，ai_token_usage 机审明细自然停止，AI 计量体系保留给生成类）；metrics 维度核对接入现有 moderation provider 口径（dfa/llm/aliyun 分列，无新增指标代码）；编译验证已通过（2026-11-15，mvn -pl litisle-review,litisle-ai -am compile）

## 归档后挂起/预留项去向

| 项 | 去向 | 触发条件 |
| --- | --- | --- |
| 「云 SUSPICIOUS → LLM 二次精判」生产混合方案 | temporarily-04-deferred.md（review 模块） | 云引擎 SUSPICIOUS 比例偏高需语义复核 / 需自定义判定标准（如社区特有「引流广告」规则）时迁回 |
| 运行态验证 RV-V1（云 API 端到端 6 项验收） | next_verify.md（review 模块节） | 阿里云内容安全开通 + AK/SK 注入后执行 |
| LlmTextModerationAdapter / AiChatController-AiChatService 机审端点闲置保留 | 本文件上方条目内已注明（不删不激活） | text-provider 改回 llm 即回切；或混合方案启用 |
