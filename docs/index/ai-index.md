## 现状基线（2026-11-09 按代码核对）
litisle-ai 仅启动骨架：LitisleAiApplication（唯一源码，空上下文无业务 Bean）+ application.properties（仅 spring.application.name=litisle-ai）
pom.xml：仅 spring-boot-starter + spring-boot-starter-test；未引入 web/feign/mysql/redis/mq/模型 SDK
controller / service / mapper / config / mq 均不存在；端口、DB、Redis、MQ 连接均未配置（端口待确认）

## 已实现契约面（代码在 litisle-api，不在 litisle-ai 模块内）
AiClient（feign）：@PostMapping("/api/feign/ai/chat") Result<AiChatResponseDTO> chat(AiChatRequestDTO)  // 提供方端点待 litisle-ai 实现，当前调用失败走降级
AiClientFallbackFactory：fail-fast 降级；review 捕获后转 SUSPICIOUS 转人工（不存在静默放行）
FeignFallbackAutoConfiguration：注册 AiClient 降级工厂
dto/ai：AiChatRequestDTO / AiChatResponseDTO
网关：/api/ai/** 路由已预留；对话端点未实现（status.md 登记为前置阻塞）

## 设计态锚点（仅设计口径，未在代码中出现前不得引用为既有实现）
包结构/分层约束：AI-Architecture §三（controller/service/provider/mapper/guard/metrics/config 待建）
REST 分组：AI-Architecture §7.1（/ai/xiaoyue/**、/ai/xiaoshen/**、/ai/sessions，落地前须 Api-Architecture 定稿）
MQ：AI 侧当前不生产不消费；判定结论统一走 review 的 moderation.result（AI-Architecture §7.3）
数据表：ai_session / ai_message / ai_usage_daily / ai_token_usage（设计态，DDL 以 Sql-Document §五 为准）
指标：ai_call_total / ai_call_latency / ai_tokens_total / ai_quota_exceeded_total / ai_generation_total / ai_guard_state（AI-Architecture §11.1，均待建）
计量与预算：AI-Architecture §8.3（R2 实施设计）；固定评测集：AI-Architecture §十四（R4 实施设计）

## 文档指针
业务口径：[AI-Business-Guide.md](../Study/AI-Business-Guide.md)（F1–F22、流程、D1–D25 决策台账 §十六、V1–V12 参数定档 §16.3）
架构口径：[AI-Architecture.md](../Architecture/AI-Architecture.md)（v0.1，现状 + 设计态）
状态跟踪：[status.md](../next/status.md)（AI 对话端点未实现 = 实施前置阻塞）
