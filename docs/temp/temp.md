# 临时任务清单汇总

## 目录及文档说明

- 目录及文档说明
- 归档记录（已完成批次索引）
- 待办部分

### 文档说明:

- 本文档为临时任务清单（区别于 `docs/next/next_update.md` 正式待办），收纳临时立项、批次化改造类任务，与正式待办并行使用
- 临时清单为独立闭环：任务内容与落地摘要直接记录在本清单条目内，**不引用 status.md 细案**
- **不拆分「代码部分/验证部分」**：模块直接挂在「待办部分」下，代码任务与验证任务（V{n}）统一按 模块 → 批次/任务 组织
- 每阶段/批次完成后勾选 [x] 并补 ✅ 落地摘要；清单全部完成后按「归档与迁移」规则处理，文件是否删除/保留待用户确认（遵循文件删除需确认纪律）

### 文档模版

** temp.md 模版: **

```
# {清单主题}（临时任务清单）

> 方案：{关键方案决策，如：方案A（xx）+ xx 校验}
> 原则：{推进原则，如：xx 链路先行，xx 待确认后跟进；每阶段完成后勾选 [x]}

## 目录及文档说明

- 目录及文档说明
- 待办部分

### 文档说明:

- 临时清单不引用 status.md，任务细案直接写在条目内
- 不拆分代码/验证部分：验证项（V{n}）直接挂在对应模块下，与批次任务并列

--------------------------

## 待办部分

#### {模块名}模块

> 批次划分依据：{排查/设计文档来源}；推进顺序：批次一 → 二 → ...（有依赖的批次注明前置）

##### 批次{n}：{批次名称}

###### {批次n}.{m} {任务短标题}

- [ ] {任务内容一句话 + 关键口径/铁律 + 涉及的表/事件/常量}
  - ✅ {yyyy-MM-dd}：{落地摘要：核心类/方法 + 关键分支决策}；跨批次遗留：{遗留项（所属批次）}；编译验证已通过（{yyyy-MM-dd}）

------------

（每个批次之间用 ------------ 分隔线隔开）

##### V{n} {验证主题}

- [ ] {验证内容}（依赖{前置批次/模块}）
```

### 归档与迁移（批次完成后的处理规则）

**相关文档职责**：

| 文档 | 职责 |
| --- | --- |
| `docs/temp/temp.md`（本文档） | 仅收纳临时立项的活跃任务，按 模块 → 批次/任务（含验证项 V{n}）组织 |
| `docs/archive/` | 已完成批次的实现记录归档：按模块分子目录追加对应板块文件（新模块新建归档子目录 + 同名索引页），如 content-09-temp-checklist.md |
| `docs/next/temporarily/` | 挂起/预留项转移：代码类挂起进 temporarily-01-code.md，验证类挂起进 temporarily-02-verify.md，预留/暂不实现进 temporarily-04-deferred.md，每条必须附触发条件 |

**批次完成后的迁移动作（按顺序执行）**：

1. **实现记录归档**：将已完成批次的条目（含口径修正、注意事项）原样搬运至 `docs/archive/` 对应模块板块文件，不压缩关键细节。
2. **本文档回填**：已完成批次从本文档「待办部分」删除，并在顶部「归档记录」补一行批次索引（日期 + 归档去向 + 挂起项去向），保持本文档只留活跃任务。
3. **挂起项转移**：无法落地的事项移入 `docs/next/temporarily/` 对应文件并写明触发条件，供宿主就绪时迁回。
4. **预留项登记**：明确「暂不实现/暂不做」的事项（含触发条件与决策原因），移入 temporarily-04-deferred.md。

> 纪律：迁移时条目内容原样搬运；归档文档与本文档/temporarily 清单之间用相对路径互链，保证可回溯。

--------------------------


--------------------------

## 待办部分


#### search模块

> **search 模块内容已迁出（2026-11-03，用户确认）**：批次A/B/C 定版记录、批次D（D1 骨架摘要 + D2~D6 待办）与 V1~V3 验证项已原样迁入 [next_update.md](../next/next_update.md) search模块（重组为批次一~八 + V1~V3，user_idx 消费链路暂缓项迁入该文档「暂不实现汇总」）；本清单不再保留 search 条目，后续 search 活跃任务统一在 next_update.md 维护，勿在此重复登记。
> **（2026-11-04 补）**：搜索链路企业级优化为独立临时立项（性能补强 + 可靠性补强，非 next_update.md 既定批次一~八范围），仍登记于本清单，见下方「search模块（搜索链路企业级优化）」批次一~三。

------------

#### search模块（搜索链路企业级优化，临时立项）

> 批次划分依据：2026-11-04 搜索链路代码评审（三处代码点：ContentInternalServiceImpl 建索引回查与标签联想端点、UserFeignProviderController 注册事件发布）；方案选型贴合企业主流：thin event（事件只带定位键）+ 回查范式保留不动，联想走「最小输入长度 + Redis 短缓存 + 匿名限流」组合（标签入 ES completion 仅作触发式升级，不提前双写），索引同步补版本比较防乱序覆盖；推进顺序：批次一 → 二 → 三（相互独立，可并行）。

##### 批次一：搜索联想性能优化（推荐组合 L0 + L1 + L5）

###### 1.1 联想最小输入长度门控（L0）

- [ ] SuggestServiceImpl#suggest 增加最小输入长度校验：q 长度小于阈值直接返回空列表（当前仅校验空串与 >64 超长）；联想为高频击键场景，最小长度可在最前端砍掉大量单字符穿透请求，成本最低收益最大
  - 涉及：litisle-search SuggestServiceImpl；长度阈值走 SearchBizConstant 常量（禁硬编码），风格对齐 content 侧 TAG_SUGGEST_KEYWORD_MAX_LENGTH

###### 1.2 标签联想 Redis 短缓存（L1，推荐主方案）

- [ ] TagServiceImpl#searchByPrefix 增加 Redis 短缓存：命中 TTL 5~10min 带 ±10% 抖动（防同批 key 同时过期雪崩），空结果短 TTL 60s（防缓存穿透）；写法对齐同文件 hotTags 既有缓存范式（CacheConstant 新增前缀常量 + RedisUtil）
  - 涉及：litisle-content TagServiceImpl、commonredis CacheConstant
  - 口径：标签池变更（新建/封禁/删除）不主动失效缓存，靠短 TTL 自然过期收敛（联想场景允许分钟级陈旧）；search 侧 SuggestServiceImpl#collectTags 暂不加缓存（Feign 结果已随 content 侧缓存受益，避免双层缓存口径复杂化）

###### 1.3 /search/suggest 匿名限流（L5）

- [ ] SearchController#suggest 补 @RateLimiter：IP 维度（建议 20 次/60s，阈值常量化），对齐 content TagController /tags 的 USER 维度限流风格；suggest 为匿名接口且无 IP 限流，可被脚本刷穿直连 MySQL（经 L1 缓存后 DB 压力已大幅缓解，限流为兜底防线）
  - 涉及：litisle-search SearchController

###### 1.4 标签入 ES completion（L3，挂起不实施）

- [ ] 挂起项（登记触发条件，当前不做）：将 content 标签池同步至 ES completion 字段，suggest 一次 ES 请求拿齐书名/作者/标签三源并消掉 Feign 跨服务调用（大厂主流终态）。触发条件：标签总数 > 5万，或 suggest QPS > 200，或联想 P99 > 100ms；触发后迁回实施（涉及标签双写一致性，需先专项设计同步机制）

------------

##### 批次二：索引同步可靠性补强（thin event 范式的企业级补强）

###### 2.1 索引写入版本比较（防乱序覆盖）

- [ ] ArticleIndexServiceImpl 写入前增加版本比较：回查 DTO 已带 publishTime（=上游 updateTime），写入前读 ES 现有文档 updateTime，小于等于现值则跳过写入；消除「发布/更新事件乱序到达 → 旧快照覆盖新快照」的最终一致窗口（现状为按 articleId 无条件 upsert 整篇覆盖）
  - 涉及：litisle-search ArticleIndexServiceImpl（article_idx 优先落地，book_idx/user_idx 同口径跟进）
  - 备选方案（暂不采用）：同一 articleId 事件固定队列 + 单消费者串行消费——约束更强但牺牲吞吐，且多队列扩容时失效

###### 2.2 索引批量回查端点（挂起不实施）

- [ ] 挂起项（登记触发条件，当前不做）：ContentClient 新增 getArticleIndexDocs(List<Long>) 批量回查，将消费端 N 次逐条 RPC 压成 1 次（当前一次消息一次回查）。触发条件：索引同步消息量级上来（如日均消费 > 1万条）后实施

------------

##### 批次三：user 事件发送时机纪律（登记，不改代码）

###### 3.1 注册/资料更新事件发送时机纪律

- [x] 纪律登记（本条即为登记动作本身，不产生代码改动）：UserFeignProviderController#createUser 当前无 @Transactional，「先落库后发事件」成立，现有实现正确；若将来为 createUser 加事务，必须改为 afterCommit 发送（参考 litisle-content BookServiceImpl#publishBookChangedAfterCommit 写法），否则出现「事件已发、事务回滚」的脏事件导致 user_idx 脏数据
  - ✅ 2026-11-04：已登记入清单；触发时机（createUser 引入事务时）由 code review 按本条执行
  - 涉及：litisle-user UserFeignProviderController

------------

##### V1 联想链路优化验证

- [ ] 验证批次一 L0/L1/L5 生效：同前缀重复请求命中 Redis 缓存（命中率日志）、空结果短 TTL 防穿透生效、超限 IP 请求被限流、TTL 抖动生效（依赖批次一）

