

## 当前执行锚点

- **前置阻塞**：litisle-ai chat 端点（`POST /api/feign/ai/chat`）未实现——3-1 联调前必须落地（见 Temporarily_not_implemented.md 预留项）
- **推进状态**：板块五/六/七已全量落地并归档；板块三/四挂起项随 content/social 开发回接

> 维护纪律：后续新落地项直接写入归档文档对应板块，本页仅维护指针与锚点。
>
> 2026-10-17 追加：content 模块八板块已入 next_update.md，实现细案见下方「content 模块实现步骤」节；细案落地后随板块完成归档并从本页移除。

# content 模块实现步骤（对应 next_update.md 板块一~八）

> 通用纪律适用 next_update.md「代码部分」①~⑥；事件发送一律本地事务提交后 afterCommit；SQL 全走 #{}；所有 CAS 补 del_flag=0；CAS affected=0 一律静默跳过事件发送（不落库不发送，对齐 review publisher「落库失败不发」铁律，防免审直落并发双击/重复提交把重复事件打进 search/notification）。

# 验证部分实现步骤（对应 next_update.md V1/V2）

## V1 发布链路联调

- [ ] [V1] 前置：litisle-ai chat 端点落地、review 送审契约就绪。
- [ ] [V1] 全链路：发布→content.submitted→review 消费端切片送审→review 判定→moderation.result 回写→状态流转→作者通知；分别验证 PASS/REJECT/SUSPICIOUS 三分支。
- [ ] [V1] 免审直落：同内容二次发布验证 last-hash 命中 0→2 直落。

## V2 可见性与缓存验证

- [ ] [V2] visibility 四级逐一验证（含 visibility=3 关注关系回查、visibility=4 密码限频）。
- [ ] [V2] 推荐流展示前回查：下架/转私密后推荐位与标签聚合页即时剔除。
- [ ] [V2] 缓存三防演练：并发重建/空值穿透/TTL 抖动观察。


