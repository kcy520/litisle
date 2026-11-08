# litisle-file 文件模块业务指南（学习向）

> 定位：**快速理解文件域业务 + 学习上传/访问/隔离/回收的实现逻辑**，不承载决策推演（存储选型、S3 细节、对账推演看 [File-Architecture.md](../Architecture/File-Architecture.md)，接口与表结构速查看 [file-index.md](../index/file-index.md)）。
>
> 阅读方式：每个板块固定两段——**详细版**（真实类名 + 关键逻辑）→ **通俗版**（无代码名）。先读全景图，再按需跳板块。

## 〇、全景图：一张图片从上传到可用、隔离与回收

**主链路（正常文件）**

客户端上传（登录用户，`bizType` 声明业务类型）
→ 校验（单文件 ≤10MB、MIME 白名单、文件头魔数）
→ 审核前置（违规黑名单拒传 / 审核结果缓存命中直接复用结论）
→ 秒传判重（公开文件全局去重；私有文件按 owner 隔离去重）
→ 未命中：锁内写库占位（上传中）→ 锁外写对象存储 → 置为上传完成
→ 未命中审核缓存：`safe_flag=待检测` + 异步送审（default-deny，未出结论不对外展示）
→ 业务方绑定引用（`file_ref` 四元组，与业务表同事务）
→ 访问（公开文件发直链；私有文件发 30 分钟预签名 URL）
→ 审核结论回写（通过=正常 / 违规=物理隔离 / 可疑=占位待人审）
→ 删除（先解绑本人引用；无人引用才软删；7 天缓冲后由对账任务物理清理）

**违规图副链路（隔离与申诉）**

审核判定违规 → 对象本体与全部缩略图移入 `quarantine/` 前缀（原直链立即失效）+ `safe_flag=违规`
→ review 侧隔离保留 30 天（有申诉待裁决则顺延）
→ 到期委派 file 侧软删（解绑全部引用）→ 再走 7 天缓冲 → 对账任务物理清理
→ 若申诉改判成功：移回原前缀 + `safe_flag=正常`；已在缓冲期内软删的，可在对象仍存在时完整恢复。

**一句话边界**：file 管「文件本体 + 元数据 + 引用计数 + 访问凭证 + 审核安全状态」，content 管「图片放在文章哪里、怎么对齐、哪张是封面」，review 管「判定是否违规、人审与申诉裁决」。

## 架构设计

```
litisle-file（端口 8083）
└─ com.litisle.litislefile
   ├─ controller              # HTTP 入口，按业务域拆分
   │  └─ feign                # 对外 Feign 提供方（implements FileClient，网关不路由、仅服务内可达）
   ├─ service                 # 业务域接口：上传 / 访问 / 管理 / 审核
   │  ├─ impl                 # 四域实现（FileUploadServiceImpl / FileAccessServiceImpl / FileManageServiceImpl / FileModerationServiceImpl）
   │  └─ support              # FileMetaSupport：元数据查询与公开直链拼装的公共收口
   ├─ domain
   │  ├─ entity               # file_meta（文件元数据）/ file_ref（引用登记）
   │  ├─ dto                  # FileDownloadResource / FileQuotaUsage
   │  └─ vo                   # FileUploadVO / FileMetaVO / PresignVO / ThumbnailVO
   ├─ enums                   # StorageBizTypeEnum / AccessLevelEnum / SafeFlagEnum / UploadStatusEnum / AlignEnum
   ├─ mapper                  # FileMetaMapper / FileRefMapper
   ├─ interceptor             # FileGlobalQpsInterceptor（全站 QPS）+ ModerationResultListener（审核/申诉结果消费）
   ├─ task                    # FileReconcileTask（每日 02:00 两步对账）
   ├─ thumbnail               # ThumbnailGenerator（按需生成，派生 key 不落库）
   ├─ util                    # FileMagicValidator（文件头魔数校验）
   ├─ client                  # SeaweedFsClient（唯一接触 AWS SDK v2：put/get/getStream/delete/presign/listKeys/exists/copy）
   ├─ constant                # FileLimitConstant（各接口限流阈值）
   └─ config                  # FileProperties（上传/配额/缩略图/对账/限流/审核参数外置）
```

**各包作用说明（litisle-file）：**

> - controller：只做参数接收、登录态与接口级限流（`@RateLimiter`），业务委托 service；`feign` 子包是 file 作为提供方暴露给 content/user/review 的内部端点，不加登录校验，靠「网关不路由 + 仅服务内可达」保证安全。
> - service：按业务域拆成上传、访问、管理、审核四块，避免早期单类全量实现；`support/FileMetaSupport` 收口「查活跃元数据 → 不存在抛 FILE_NOT_FOUND」与公开直链拼装，防各域口径漂移。
> - domain：entity 对应两张表；dto/vo 防裸实体透传。
> - enums：`StorageBizTypeEnum` 决定 bucket 与访问级别；`SafeFlagEnum` 是审核安全状态机；`UploadStatusEnum` 区分「占位/完成」；`AlignEnum` 只是文件维度冗余，权威在 content 的 `article_image.align`。
> - mapper：文件元数据与引用的持久层；秒传判重、配额聚合、对账扫描、批量回查都在这里。
> - interceptor：全站 QPS 拦截器 + 审核结果消费者；后者是 file 侧唯一 MQ 入口，只消费图片维度（`bizType=6`）。
> - task：对账任务，做「上传补偿」与「软删到期物理清理」两件事。
> - thumbnail / util / client：存储与图片处理的边界，业务代码不直接碰 SDK。
> - constant / config：限流阈值与运营参数外置，便于 Nacos 调整。
>
> 说明：`FileController` 与 `FileServiceImpl` 是早期「单类聚合」实现，代码已整体注释、不参与运行；当前入口以三个业务域 Controller + 四个业务域 Service 为准。

## 一、模块定位与职责边界

**功能描述：**

> - 一句话：**系统里所有「文件字节」相关的动作都收口到 file 模块**，其他服务只保存 `fileId` 和业务语义。
> - 向上提供六类能力：上传（含秒传）、元数据查询、访问 URL（直链/预签名）、缩略图、流式下载、删除；对内提供引用登记/解绑、批量归属校验、审核结果回写与隔离。
> - 向下屏蔽 SeaweedFS：业务服务不感知 bucket、path、S3 预签名、对象移动等细节。

**功能描述——详细版：**

> **负责**
>
> - 文件本体：写入/读取/移动/删除对象存储，维护 `bucket + path`。
> - 元数据：`file_meta` 记录 `sha256 / size / mime / 宽高 / 访问级别 / 上传状态 / 审核安全状态 / 归属人 / 业务类型`。
> - 引用计数：`file_ref` 四元组 `(file_id, biz_type, biz_id, owner_id)`，支撑「多篇文章共享同一张图」时不被误删。
> - 访问凭证：公开文件拼直链；私有文件签短时效预签名 URL；流式下载支持 Range。
> - 审核安全状态落地：上传前置检查、异步送审、结论回写、违规图物理隔离与恢复、到期软删。
> - 后台自愈：上传占位补偿、软删到期物理清理、隔离前缀统一。
>
> **不负责**
>
> - 文章/草稿里图片的位置、顺序、对齐、封面选择：权威在 content 的 `article_image`；`file_meta.align` 只是冗余字段，当前没有写入方。
> - 违规判定本身：机审模型、人审、申诉裁决都在 review；file 只执行「结论落到文件上」。
> - 账号、角色、封禁：由 user 与 common-security 提供；file 只调用角色判断工具。
> - 音视频、OCR、文档解析、图片裁剪/水印、分片上传：当前均未实现。

**边界对照：**

| 协作方 | file 负责 | 对方负责 |
| --- | --- | --- |
| content（封面/配图） | 保存文件字节与元数据；提供 `bindRef/unbindRef`；提供图片 URL；执行违规隔离 | 保存 `article_image`（`position_order / align`）；决定哪张图是封面；发布/编辑/删除时绑定或解绑引用；校验图片归属必须是文章作者 |
| review（机审/人审/申诉） | 上传时发 `image.submit`；消费 `moderation.result` / `moderation.appeal.result`；执行隔离/恢复 | 机审与人审判定；申诉受理与改判；隔离 30 天保留期与到期清扫的调度 |
| common（锁/限流/MQ/权限工具） | 使用分布式锁、接口限流、MQ ACK 工具、角色判断、统一错误码 | 提供 Redisson 锁、Redis 限流、`MqAckHelper/MqTraceSupport`、`SaRoleHelper` 等公共能力 |
| user（头像） | 接收 `AVATAR` 上传；提供引用登记/解绑 | 校验「本人上传 + AVATAR 类型」；维护 `sys_user.avatar_file_id`；换头像时先解绑旧图再绑定新图 |
| 运营/后台 | 提供同一套上传与访问能力 | 系统没有独立的「运营」角色；运营只能以现有角色（普通用户/ADMIN/REVIEWER）使用同一套能力，上传文件的 `owner` 永远是上传者本人 |

**链路位置**：文件域是**被业务服务调用的基础能力**，不在 content 发布主链路里同步阻塞；content 发布时只校验并登记 `fileId` 引用，真正取图发生在详情渲染/列表批量取 URL。

<br />

**详细版**：

- A1 上传收口：所有文件进入系统都必须经过 `FileUploadController`（登录 + 用户级限流），没有绕过入口直接写存储的通道。
- A2 元数据唯一：一个物理文件对应一条 `file_meta`；秒传命中复用同一条记录与同一个 `fileId`，不会为「第二篇文章引用同一张图」再存一份。
- A3 引用与删除解耦：业务方删除文章只解绑自己的引用；只有最后一个引用消失才软删文件——这是「秒传共享不误删」的前提。
- A4 访问级别由业务类型决定：`LICENSE` 强制私有（预签名），其余（头像/封面/正文图）为公开（直链）；调用方不传访问级别，避免口径漂移。
- A5 审核归 review、状态归 file：file 不判定内容，但会把「待检测/正常/可疑/违规」落到 `safe_flag`，并在所有发 URL 的出口统一拦截。
- A6 业务归属由调用方校验：file 只保证「操作者是文件 owner 或 ADMIN/REVIEWER」；「这张图是否属于这篇文章的作者」由 content/user 自己查 `batchGetOwners` 后判定。

<br />

**通俗版**：\
文件服务就是系统的「仓库 + 门禁 + 登记簿」。\
仓库负责把图片存进去、取出来；门禁负责谁能看（公开直链 / 私有临时链接）；登记簿记录「哪篇文章、哪个用户正在用这张图」。\
它不管图片在文章里排第几、左对齐还是居中，也不判断图片有没有违规——这些分别归内容服务和审核服务。\
删除时它也不会直接销毁文件，而是先看还有没有人引用；没人用了才进回收缓冲，防止把别人还在用的图删掉。

**代码部分：**

> - FileUploadController.java：
>   litisle_file\controller\FileUploadController.java：
>   作用：上传入口（单传/批量），登录校验 + 用户级限流，`ownerId` 取当前登录人。
> - FileAccessController.java：
>   litisle_file\controller\FileAccessController.java：
>   作用：访问入口（取 URL / 预签名 / 元数据 / 缩略图 / 流式下载），登录校验 + 分接口限流。
> - FileManageController.java：
>   litisle_file\controller\FileManageController.java：
>   作用：管理入口（删除），登录校验 + 删除限流。
> - FileFeignProviderController.java：
>   litisle_file\controller\feign\FileFeignProviderController.java：
>   作用：内部 Feign 提供方，实现 `FileClient` 契约（引用登记/解绑、批量 URL、批量归属、批量摘要、隔离/恢复/到期删除、内部元数据与内容读取）。
> - FileMetaSupport.java：
>   litisle_file\service\support\FileMetaSupport.java：
>   作用：元数据查询收口（活跃 / 含软删两种口径）与公开直链拼装，防各业务域重复实现。
> - FileProperties.java：
>   litisle_file\config\FileProperties.java：
>   作用：上传上限、配额、缩略图、对账、限流、审核 provider/模型版本、占位图等运营参数载体。

## 二、角色与典型场景

**功能描述：**

> - 作者：上传正文配图与封面（都是图片），上传后可拿到 `fileId`，发布时由 content 登记引用。
> - 普通用户：上传头像（`AVATAR`）。
> - 运营：使用现有业务类型上传素材；没有独立的「运营素材」类型，也不能把文件挂到他人文章。
> - 系统后台：对账、隔离清扫、审核结果消费、内部批量取 URL / 归属 / 摘要。

**功能描述——详细版：**

> **作者上传文章配图 / 封面**
>
> - 正文图与封面在 file 侧都按 `ARTICLE_IMG`（bucket=`article`）上传；区别只在 content 登记引用时的维度：正文图用 `ARTICLE_IMG`，封面用 `COVER`。
> - 前端批量上传走 `/upload/batch`，单批最多 9 张；中途某张失败不回滚已成功的图片，调用方需处理部分成功。
> - 图片上传成功不代表可展示：若 `safeFlag=待检测`，业务方应展示占位图并轮询元数据，直到正常/违规。
>
> **用户上传头像**
>
> - 头像按 `AVATAR`（bucket=`avatar`）上传；user 服务设置头像时校验「文件归属=当前用户且类型=AVATAR」，然后解绑旧头像、绑定新头像。
> - 头像属于公开面文件；未出审核结论前，取 URL 会拿到占位图。
>
> **运营上传素材**
>
> - 当前没有独立的「运营素材」业务类型，也没有「代作者上传并挂到作者文章」的能力：文件的 `owner_id` 永远是上传者本人，content 会校验配图归属必须是文章作者。
> - `LICENSE`（授权文件）是预留的私有类型：支持 PDF/Word/ZIP 等非图片类型、强制预签名访问、跳过机审；但当前没有业务调用方使用它。
>
> **系统后台**
>
> - 每日 02:00 对账：补偿「上传中」占位记录、清理软删满 7 天的文件。
> - review 侧每日 03:00 清扫隔离图：到期后委派 file 软删。
> - MQ 消费：审核结果与申诉结果落到 `safe_flag`、执行隔离/恢复。

<br />

**详细版**：

- B1 权限口径：上传/取 URL/下载/缩略图/删除均要求登录；删除只允许 owner 或 ADMIN；私有文件读取允许 owner、REVIEWER、ADMIN（审核员需要看证据/授权文件）。
- B2 归属校验：file 不校验「这张图是否属于某篇文章」，只提供 `batchGetOwners` 给 content/user 做校验；头像由 user 校验 owner + `AVATAR`；文章配图与封面由 content 校验 owner + `ARTICLE_IMG`。
- B3 运营边界：运营若需要「平台素材」，只能以自己账号上传并按现有业务类型使用；跨账号代传/代挂不在当前能力范围。
- B4 审核员场景：`REVIEWER` 可读取私有（`LICENSE`）文件与违规证据图；但删除等写操作仍只认 owner/ADMIN，审核员没有删除权。

<br />

**通俗版**：\
作者传文章里的图，普通用户传头像，运营传自己的素材——三者走的都是同一个上传接口，只是声明不同的业务类型。\
文件属于「上传的人」，所以运营不能替作者传图再挂到作者文章上，文章发布时会校验图片归属。\
审核员可以看私有文件和违规证据图，但不能删文件；删文件只认上传者本人或管理员。\
后台不需要人操作，靠两个定时任务和消息消费者把「上传失败残留、违规图到期、审核结论」自动收尾。

**代码部分：**

> - UserProfileServiceImpl.java：
>   litisle_user\service\impl\UserProfileServiceImpl.java：
>   作用：头像设置链路——校验归属与 AVATAR 类型、解绑旧头像、绑定新头像、更新用户表。
> - ArticleImageRefSupport.java：
>   litisle_content\service\support\ArticleImageRefSupport.java：
>   作用：content 侧配图/封面的归属校验与引用登记/解绑；文章维度 `ARTICLE_IMG`，草稿维度 `DRAFT_IMAGE`，封面引用维度 `COVER`。
> - FileClient.java：
>   litisle_api\feign\FileClient.java：
>   作用：跨服务文件契约（上传不在此契约，上传由前端直连 file；此处是引用、批量查询、审核隔离等内部能力）。
> - StorageBizTypeEnum.java：
>   litisle_file\enums\StorageBizTypeEnum.java：
>   作用：业务类型到 bucket/访问级别的映射依据（`AVATAR / COVER / ARTICLE_IMG / DRAFT_IMAGE / LICENSE`）。

## 三、上传与秒传判重

**功能描述：**

> - 单文件上传：校验 + 摘要 + 判重 + 落库占位 + 写对象存储，返回 `fileId / url / safeFlag / deduplicated`。
> - 秒传：同一份文件（同摘要、同大小、同业务类型）只存一份；命中直接复用已有 `fileId`，不再写存储。
> - 并发去重：多个请求同时上传同一份文件时，用分布式锁串行化「查库 + 占位写库」并做二次检查；但锁不覆盖写存储，极端并发下仍可能产生重复的完成态记录（现状，见下方 C8）。

**功能描述——详细版：**

> **校验**
>
> - 空文件拒绝；大小 ≤ `max-image-size`（默认 10MB）；MIME 白名单按业务类型区分：头像/封面/正文图仅 `jpeg/png/webp/gif`，`LICENSE` 额外放开 `pdf/doc/docx/zip`。
> - 文件头魔数校验：读取前 12 字节确认真实格式与声明 MIME 一致，防「改扩展名 + 伪造 Content-Type」。
>
> **摘要与判重**
>
> - 流式计算 SHA256（不整份读内存），同时记录 `size` 作为判重键的一部分。
> - 判重条件：`sha256 + size + biz_type + 未删除 + 上传完成 + 非违规`；私有类型（`LICENSE`）再追加 `owner_id`，防跨用户秒传探测隐私。
> - 锁外先查一次（快路径）；未命中再进分布式锁，拿锁后二次检查（防等锁期间别人已写入）。
>
> **写库与写存储的顺序**
>
> - 锁内只做「查库 + 写库」：生成 32 位 `fileId`，路径 `yyyy/MM/dd/{fileId}.{ext}`，插入 `status=上传中` 的占位记录，然后释放锁。
> - 锁外把整份字节写入对象存储（同 key 覆盖，幂等）；成功后解析图片宽高并置 `status=上传完成`。
> - 顺序不能反：先写库再写存储，写存储失败时还有占位记录可被对账任务发现；反过来会产生「对象存在但库里没有」的孤儿对象。
>
> **审核前置**
>
> - 仅公开面文件做：先查违规黑名单（命中直接拒传），再查审核结果缓存（PASS 免审直落、REJECT 拒传、其他/未命中进待检测）。
> - `LICENSE` 是私有面，豁免机审，初始即正常。

**链路位置**：上传位于「文件进入系统」的最前端；content 发布链路只消费上传返回的 `fileId`，不重复读文件。

<br />

**详细版**：

- C1 判重键：`sha256 + size + biz_type + del_flag=0 + status=COMPLETED + safe_flag != VIOLATIVE`；公开类型全局共享，私有类型按 owner 隔离。
- C2 秒传命中不占配额、不写存储、不发审核事件；返回 `deduplicated=true`，直接复用已有记录与 URL。
- C3 并发去重锁：`lock:file:sha:{sha256}`，`tryLock` 最多等 5 秒（5 是等锁上限），租期 `-1` 开启看门狗；等锁超时返回「系统繁忙，请稍后重试」（SYSTEM_ERROR）。临界区只包「查库 + 占位写库」，put 在锁外。
- C4 占位记录：`status=UPLOADING` 时对象可能还没写完，因此**不参与秒传**；这也是避免「秒传到一半的坏链」的关键过滤。
- C5 写存储失败：记录保留在 `UPLOADING`，对账任务 30 分钟后探测对象是否存在，存在则补成完成态，不存在则软删废弃。
- C6 批量上传：单批最多 9 张，逐个复用单文件链路；中途失败已成功的图片不回滚，接口返回失败异常，调用方需处理部分成功。
- C7 上传响应：`FileUploadVO` 同时返回 `url` 与 `safeFlag`；`safeFlag=待检测` 时前端应展示占位并轮询元数据，不要直接展示 `url`。
- C8 并发窗口：A 在锁内插入 `status=上传中` 后立即释放锁，再在锁外 put；B 此时拿到锁，双检只认完成态，查不到 A 的记录，会再插入一条并各自写一个对象。判重键没有数据库唯一约束兜底，因此**「并发去重」只降低重复、不保证唯一**；串行重复上传（第一次已完成）才稳定秒传。该行为是否符合预期需确认（见文末「不一致与待确认」）。

<br />

**通俗版**：\
上传前先看「文件多大、是不是真图片」；然后给文件算一个指纹，用「指纹 + 大小 + 用途」判断系统里是不是已经有同一份文件。\
有就直接把原来的 ID 还给你，不会再存一遍，这就是秒传。\
没有就先把「这个文件正在上传」写进数据库，再去写存储；写成功才改成「上传完成」。\
同一份文件同时被很多人上传时，会用一把按指纹命名的锁排队，保证只写一条记录。\
图片是否违规不在这里判断，上传接口只负责把「待检测」标好并通知审核服务。

**代码部分：**

> - FileUploadServiceImpl.java：
>   litisle_file\service\impl\FileUploadServiceImpl.java：
>   作用：上传全链路——校验、SHA256、审核前置、锁外/锁内双检、占位写库、锁外 put、完成态更新、异步送审。
> - FileMagicValidator.java：
>   litisle_file\util\FileMagicValidator.java：
>   作用：文件头魔数校验，只读前 12 字节识别真实格式，防伪造 MIME。
> - DistributedLock.java：
>   litisle_common\common_redis\lock\DistributedLock.java：
>   作用：分布式锁公共能力（本模块使用 `tryLock(key, waitTime, unit)`，租期 `-1` 走看门狗）。
> - FileMetaMapper.java：
>   litisle_file\mapper\FileMetaMapper.java：
>   作用：秒传判重、配额聚合、对账扫描等查询入口。
> - FileUploadVO.java：
>   litisle_file\domain\vo\FileUploadVO.java：
>   作用：上传回执（fileId/url/摘要/宽高/结论状态/是否秒传）。

## 四、存储落库、引用绑定与删除回收

**功能描述：**

> - 存储：按业务类型分 bucket，路径按日期分层，公开直链由「基址 + bucket + path」拼出。
> - 引用：业务方在本地事务里绑定/解绑 `file_ref`；同一文件可被多篇文章、多个用户引用。
> - 删除：只解绑「当前操作者」的引用；还有别人的引用就不动文件；最后一个引用消失才软删，7 天后物理清理。

**功能描述——详细版：**

> **存储布局**
>
> - bucket 映射：`avatar→avatar`、`cover→cover`、`article→article`、`license→license`；`DRAFT_IMAGE` 没有 bucket 映射，不能用于上传，只作为草稿引用维度存在。
> - 对象路径：`{yyyy/MM/dd}/{fileId}.{ext}`；扩展名取原始文件名，缺失或异常兜底 `bin`。
> - 公开直链：`{public-url-base}/{bucket}/{path}`，基址含 `/buckets`；生产可替换为 CDN 域名。
>
> **引用绑定**
>
> - 四元组 `(file_id, biz_type, biz_id, owner_id)`，唯一索引兜底并发重复；绑定要求文件存在且未删除。
> - 幂等：先查同四元组是否存在，存在即跳过；并发下后到者插入触发唯一键冲突，代码吞掉异常维持幂等。
> - 与业务表同事务由调用方保证：业务表写入失败时引用一起回滚，避免「文章没存上但引用已登记」。
> - 解绑必须传完整四元组，禁止只按 `fileId` 删——同一张图可能被多人/多篇共享。
>
> **删除**
>
> - 仅 owner 或 ADMIN；`REVIEWER` 无删除权。
> - 第一步只删「当前操作者」的引用行（`file_id + owner_id`，不限业务类型，一次清完此人名下引用）。
> - 第二步仍有其他人的引用 → 直接返回，文件不动。
> - 第三步无人引用 → `del_flag=1` + 删除时间，退出秒传判重池；对象保留 7 天缓冲。
>
> **物理清理**
>
> - 对账任务扫描软删满 7 天的记录；清理前再次校验 `file_ref`，若仍有引用则跳过并告警（宁可晚删，不误删他人引用）。
> - 删除对象本体与全部缩略图派生 key，再物理删元数据行，并防御性清理残留引用行。

<br />

**详细版**：

- D1 引用维度由业务方决定：正文图 `ARTICLE_IMG`、草稿图 `DRAFT_IMAGE`、封面引用 `COVER`；file 不解释维度含义，只按四元组计数。
- D2 封面虽然以 `COVER` 维度登记引用，但文件本身是 `ARTICLE_IMG` 类型上传的（bucket=`article`）；content 校验封面时也要求 `bizType=ARTICLE_IMG`。
- D3 共享文件删除：A 删除文章只解绑 A 的引用；B 还在引用时文件保持活跃，B 的图片不受影响。
- D4 软删不是立即消失：7 天缓冲期内对象与元数据都保留，可人工/申诉恢复；期满才不可恢复。
- D5 对账只做「数据库 → 存储」方向：库里有占位/软删记录才去探测/清理存储；**没有反向扫描「存储有、库里没有」的孤儿对象**（见「未纳入」）。
- D6 隔离图到期删除也走同一套删除纪律：软删 + 解绑全部引用 + 7 天缓冲，不再豁免 `quarantine/` 前缀。
- D7 ADMIN 代删有名无实：删除只解除「操作者本人」的引用行；ADMIN 代删时删的是 ADMIN 自己的引用（通常不存在），真 owner 的引用仍在，因此**被引用的文件连 ADMIN 也删不掉**，只有最后一位引用者才能完成软删。

<br />

**通俗版**：\
图片按用途放进不同的桶，路径按日期分目录，公开图片直接用「域名/桶/路径」访问。\
业务方在保存文章/头像时，顺手在文件服务登记「谁在用这张图」；登记是幂等的，重复调用不会多出一条。\
删除时先只解除自己的登记，如果还有别人在用，文件原封不动；最后一个人不用了，才标记删除并保留 7 天。\
7 天后后台任务确认确实没人引用，才真正删掉图片和缩略图。\
违规图到期删除也走这条流程，只是先由审核服务判断「保留期到了、没有待裁决申诉」。

**代码部分：**

> - FileManageServiceImpl.java：
>   litisle_file\service\impl\FileManageServiceImpl.java：
>   作用：引用登记/解绑、删除（解绑语义）、owner/ADMIN 校验。
> - FileRef.java / FileMeta.java：
>   litisle_file\domain\entity\FileRef.java / FileMeta.java：
>   作用：引用四元组与文件元数据表实体。
> - FileReconcileTask.java：
>   litisle_file\task\FileReconcileTask.java：
>   作用：每日 02:00 两步对账——补偿上传占位、物理清理软删满 7 天且无引用的文件。
> - SeaweedFsClient.java：
>   litisle_file\client\SeaweedFsClient.java：
>   作用：对象存储读写、预签名、前缀列举、存在性探测、复制与删除；所有存储异常统一转 `FILE_STORAGE_UNAVAILABLE`。
> - FileProperties.java：
>   litisle_file\config\FileProperties.java：
>   作用：bucket 映射、公开直链基址、预签名有效期、对账阈值与隔离前缀。

## 五、访问与鉴权

**功能描述：**

> - 统一入口 `getUrl(fileId)`：公开文件发直链，私有文件校验权限后发预签名 URL。
> - 所有发 URL/发下载流的出口都按 `safe_flag` 做访问闸口：违规拒绝，待检测/可疑在公开面展示占位图。
> - 私有文件只有 owner、REVIEWER、ADMIN 能读；公开文件登录即可取 URL/下载。

**功能描述——详细版：**

> **公开文件**
>
> - `safe_flag=正常` → 返回公开直链。
> - `safe_flag=违规` → 抛 `FILE_VIOLATIVE(90008)`，不泄露任何真实地址。
> - `safe_flag=待检测/可疑` → 返回配置的占位图 `/img/file-pending.svg`，不返回真实直链。
>
> **私有文件（`LICENSE`）**
>
> - 先校验 owner / REVIEWER / ADMIN；通过后签发预签名 URL，默认 30 分钟。
> - 违规仍然拒绝；待检测/可疑不占位（审核员看证据图不能被占位挡住）。
>
> **批量取 URL（内部 Feign）**
>
> - 供列表页一次取回多个文件 URL，避免 N+1；公开面规则与单条一致。
> - 私有文件在此内部接口直接预签名、不做权限校验（仅限服务间调用；若未来暴露 REST 必须补鉴权）。
> - 注意批量语义是「整批」：列表里任一公开文件违规，整批抛 `FILE_VIOLATIVE`，不会逐张降级为占位。
>
> **流式下载**
>
> - 公开文件：违规拒绝；待检测/可疑抛 `FILE_NOT_READY(90010)`（下载没有「占位流」语义）。
> - 私有文件：owner / REVIEWER / ADMIN。
> - Range：无 Range/非法/多段→200 全量；合法区间→206；不可满足→416；`ETag` 用 `sha256`，总大小取库内 `size`。
>
> **元数据与缩略图**
>
> - 元数据：非 owner 且非 ADMIN 时隐藏 `ownerId`，防枚举他人文件归属。
> - 缩略图：私有文件拒绝；功能开关关闭拒绝；非图片拒绝；宽高 1~2000，默认 300×300；派生 key 不落库。

<br />

**详细版**：

- E1 访问级别固定：`LICENSE` 私有，其余公开；调用方不能指定，避免「本该私有却发直链」。
- E2 直链即凭证：公开文件是对象存储直链，拿到 URL 就能访问；服务端闸口发生在「签发 URL 时」，不是每次访问时。违规隔离通过**物理移走对象**让旧 URL 失效，这是物理隔离而非逻辑隐藏的根本原因。
- E3 私有凭证短时效：预签名最长 30 分钟，接口允许调用方指定更短有效期（1~30 分钟），防止凭证长期传播。
- E4 删除/下载/预签名的敏感操作都收口 owner/ADMIN；REVIEWER 只能读私有文件，不能删。
- E5 缩略图接口只校验「私有/开关/图片类型/尺寸」，**没有接 `safe_flag` 闸口**（代码现状；隔离图缩略图是否也应拦截待确认，见文末「不一致与待确认」）。
- E6 内部端点不走登录：`/api/feign/file/**` 由 `FileClient` 调用，网关不路由，仅服务内可达。

<br />

**通俗版**：\
公开图片给一个永久直链，谁拿到链接都能看；私有文件给一个 30 分钟有效的临时链接，过期要重新申请。\
系统在「发链接」时检查状态：违规不给链接，待审核只给占位图。\
因为公开链接本身是存储直链、绕不过去，所以违规图的处理不是「改个标记」，而是把图片真的搬到隔离目录，旧链接自然 404。\
私有文件只有本人、审核员、管理员能拿链接；删除只有本人或管理员能做。\
批量取链接时，只要有一张违规，整批都会报错，调用方需要自己兜住这个异常。

**代码部分：**

> - FileAccessServiceImpl.java：
>   litisle_file\service\impl\FileAccessServiceImpl.java：
>   作用：取 URL、批量取 URL、流式下载（Range）、预签名、元数据、缩略图的访问闸口与权限校验。
> - FileMetaSupport.java：
>   litisle_file\service\support\FileMetaSupport.java：
>   作用：活跃/含软删元数据查询与公开直链拼装。
> - ThumbnailGenerator.java：
>   litisle_file\thumbnail\ThumbnailGenerator.java：
>   作用：按需生成/复用缩略图，派生 key 为「原图 path + `_thumb_{w}_{h}`」，不落库。
> - AccessLevelEnum.java：
>   litisle_file\enums\AccessLevelEnum.java：
>   作用：公开（直链）/ 私有（预签名）两档访问级别。
> - FileClient.java：
>   litisle_api\feign\FileClient.java：
>   作用：内部批量取 URL、批量归属、隔离/恢复等契约入口。

## 六、安全与合规：上传即预审、隔离与免审边界

**功能描述：**

> - 公开面图片「上传即进入审核流程」：黑名单直接拒传，缓存有终局结论直接复用，其余一律先标「待检测」再异步送审。
> - 默认拒绝：未出审核结论前，公开面取 URL 只给占位图，不给真实直链。
> - 违规图物理隔离：对象与缩略图移入隔离前缀，旧链接失效；申诉成功再移回。
> - 免审只发生在「同一份文件已有可信的通过结论」时，不是内容维度的免审。

**功能描述——详细版：**

> **上传前置检查（仅公开面）**
>
> - 违规黑名单：key `review:img-blacklist:{sha256}`，由 review 在终局 REJECT 时写入，TTL 30 天；命中直接 `FILE_UPLOAD_REJECTED(90009)`，不落库、不进 MQ。
> - 审核结果缓存：key `review:img-result:{provider}:{modelVersion}:{sha256}`，由 review 写入，TTL 72 小时；PASS→直接正常；REJECT→拒传；未命中或非终局（如可疑）→待检测。
> - provider 与 modelVersion 必须与 review 侧配置一致，否则缓存 key 漂移、前置检查永不命中。
>
> **`safe_flag` 四态**
>
> | 值 | 含义 | 公开面访问 | 私有面访问 |
> | --- | --- | --- | --- |
> | 0 违规 | 已判定违规/隔离中 | 拒绝（90008） | 拒绝（90008） |
> | 1 正常 | 检测通过 | 直链 | 预签名 |
> | 2 待检测 | 已送审未出结论 | 占位图 | 预签名（不占位） |
> | 3 可疑 | 机审命中待人审 | 占位图 | 预签名（不占位） |
>
> - 转正常：审核 PASS；转违规：审核 REJECT（同时物理隔离）；转可疑：审核 SUSPICIOUS，等人审终局。
> - 初始值：公开面缓存未命中=待检测；缓存 PASS=正常；私有面（LICENSE）=正常。
>
> **物理隔离**
>
> - 隔离：把对象本体与全部缩略图派生 key复制到 `quarantine/` 前缀后删除原 key，元数据 `path` 改为隔离路径、`safe_flag=违规`；`del_flag` 保持 0，引用登记不动（隔离是暂存证据，不是删除）。
> - 恢复：把对象从 `quarantine/` 移回原前缀、`safe_flag=正常`；已在缓冲期内软删的记录，先确认对象存在，再移回并清空删除时间、恢复为活跃。
> - 幂等：已经在隔离前缀就跳过重复隔离；已经不在隔离前缀就跳过重复恢复。
>
> **隔离期与到期清理**
>
> - 保留期由 review 侧控制：默认 30 天；若该图有「待裁决申诉」则本轮跳过（防止申诉时证据已删）。
> - 清扫前看「最新一条审核留痕」：最新结论仍为 REJECT 才删；后续改判 PASS 的已被恢复，不再删。
> - 到期后 review 委派 file 侧软删：校验路径必须在隔离前缀（防误删正常文件）→ 软删 + 解绑全部引用。
> - 软删后再保留 7 天，期满由 file 对账任务物理清理。
>
> **免审边界**
>
> - 只在「同 sha256、同 provider/模型版本、缓存结论为 PASS」时跳过送审；黑名单优先，缓存 PASS 也挡不住黑名单。
> - 可疑结论不享受免审；缓存未命中一律送审。
> - `LICENSE` 因是私有面而豁免图片机审；这是访问级别豁免，不是「审核通过」。
> - content 的「相同内容免审发布」是文本维度机制，与 file 的图片缓存免审不是一回事，不能混用口径。

<br />

**详细版**：

- F1 上传不是「先发布后审核」，而是「先标待检测、后异步送审」；未出结论期间公开面只给占位图。
- F2 违规隔离是物理动作：同 bucket 内 `copy + delete`，不是逻辑标记；因此隔离后旧直链立即 404。
- F3 缩略图一并移动：隔离时按原路径前缀列出全部 key（含 `_thumb_*`），逐个搬走，防止旧缩略图链接继续可访问。
- F4 隔离不改 `del_flag`：隔离图仍在活跃池，引用仍有效；只有「保留期到期的软删」才解绑全部引用。
- F5 恢复支持软删态：缓冲期内即使已被软删，只要对象还在隔离前缀，申诉改判后可以完整恢复；对象已被物理清理则报 `FILE_NOT_FOUND`，由调用方兜底。
- F6 免审可复用但不可伪造：缓存 key 的服务端派生段只来自 sha256 与配置，不拼接用户原始字符串。

<br />

**通俗版**：\
图片一上传就先过关：见过并被判违规的图直接拒收；见过并已通过的图直接放行；没见过的先标「待检测」，交给审核服务。\
在结论出来之前，公开页面只显示占位图，不显示真图。\
判违规后不是打个标签，而是把图片和缩略图真的搬到隔离目录，原来的链接立刻失效；申诉成功再搬回来。\
隔离图保留 30 天，没人申诉或申诉维持原判，就到期软删；软删后还有 7 天缓冲，最后才物理删除。\
所谓免审，只是「同一张图之前已经被可信地判过通过」，不是「这篇文章以前通过过所以图片也免审」。

**代码部分：**

> - FileUploadServiceImpl.java：
>   litisle_file\service\impl\FileUploadServiceImpl.java：
>   作用：上传前置黑名单/缓存检查、初始 `safe_flag` 决策、异步发 `image.submit`。
> - FileModerationServiceImpl.java：
>   litisle_file\service\impl\FileModerationServiceImpl.java：
>   作用：隔离/恢复/到期软删/按 fileId 解绑全部引用/审核状态回写/批量查询。
> - ModerationResultListener.java：
>   litisle_file\interceptor\ModerationResultListener.java：
>   作用：消费 `moderation.result` 与 `moderation.appeal.result`，按结论执行回写、隔离、恢复。
> - SafeFlagEnum.java：
>   litisle_file\enums\SafeFlagEnum.java：
>   作用：违规/正常/待检测/可疑四态定义（default-deny）。
> - ModerationConstant.java：
>   litisle_api\constant\ModerationConstant.java：
>   作用：黑名单/结果缓存 key 模板与结论常量，file 与 review 共用防口径漂移。
> - SeaweedFsClient.java：
>   litisle_file\client\SeaweedFsClient.java：
>   作用：隔离/恢复所需的 copy、delete、listKeys、exists。

## 七、容量、配额与限流

**功能描述：**

> - 单文件与批量上限：单文件 ≤10MB，单批 ≤9 张，请求体 ≤100MB。
> - 用户配额：容量 200MB、文件数 200，超限拒绝；当前配置默认关闭。
> - 限流：每用户分接口限流 + 全站 QPS，超限统一返回 `TOO_MANY_REQUESTS(10005)`。
> - 存储清理：软删 7 天缓冲 + 隔离图 30 天保留，没有手工回收站。

**功能描述——详细版：**

> **单文件 / 类型**
>
> - 单文件上限 `litisle.file.upload.max-image-size`，默认 10485760 字节（10MB）；Servlet 层 `max-file-size=10MB`、`max-request-size=100MB` 同时兜底。
> - 图片类业务类型只允许 `jpeg/png/webp/gif`；`LICENSE` 额外允许 `pdf/doc/docx/zip`；魔数必须与声明类型一致。
>
> **用户配额**
>
> - 口径 = 该用户 `del_flag=0` 的全部记录（含上传中占位，即「预占」），不是去重后的物理对象数。
> - 容量超限或数量超限抛 `FILE_QUOTA_EXCEEDED(90007)`；秒传命中不占新增配额。
> - 校验发生在「锁内、双检后、写库前」；锁维度是 sha256 而不是用户，因此同一用户并发上传不同文件时最坏可超限约一个单文件大小（有 30 次/分钟限流兜底）。
> - **当前 yml `quota-enabled: false`**：配额机制存在但运行时关闭，容量/数量限制与聚合查询都被跳过。
>
> **限流（均为每用户维度，叠加全站 QPS）**
>
> | 接口 | 阈值 |
> | --- | --- |
> | 单文件上传 | 60 秒 / 30 次 |
> | 批量上传 | 60 秒 / 5 次（单批 ≤9 张，等效 45 张/分钟） |
> | 流式下载 | 60 秒 / 120 次 |
> | 缩略图 | 60 秒 / 120 次 |
> | 预签名 | 60 秒 / 60 次 |
> | 删除 | 60 秒 / 30 次 |
> | 全站 `/api/file/**` | 1 秒 / 200 次 |
>
> **存储清理**
>
> - 普通删除：软删 7 天后物理清理。
> - 隔离图：保留 30 天 → 软删 → 再 7 天 → 物理清理。
> - 上传占位：超过 30 分钟的记录进入对账，对象存在补完成、不存在软删。
> - 没有「回收站」页面/恢复接口；缓冲期恢复只发生在隔离图的申诉恢复链路，以及人工直接改库的运维场景。

<br />

**详细版**：

- G1 上限判断是「应用层 + Servlet 层」双保险：先由容器拦住超大请求，再由业务层校验 MIME/魔数。
- G2 批量上传的限流按「请求次数」算，不按图片张数算；5 次 ×9 张是上限口径。
- G3 配额是软限：用户维度不互斥，极端并发可短时超出，靠限流把超出量控制在有界范围。
- G4 配额当前关闭：验收时若需验证 90007，必须先把 `quota-enabled` 改为 `true` 再测；默认配置下传满 200 个也不会报配额错误。
- G5 隔离图保留期在 review 侧配置（`retention-days=30`、单轮 100 条、删除限速 30 次/分钟、03:00 执行），file 侧只执行「到期软删」指令。
- G6 全站 QPS 是固定窗口计数：窗口边界允许短时 2 倍突刺；只对首次请求计数，异步下载的二次分发不重复计数。

<br />

**通俗版**：\
一张图最多 10MB，一次最多传 9 张；图片类型有白名单，改扩展名伪装会被文件头识别拦下。\
每个用户默认有 200MB、200 个文件的额度，但当前配置把额度关掉了，实际不限制；额度是「软限制」，并发时可能短时超一点点。\
每个接口都有「每人每分钟多少次」的限制，另有全站每秒 200 次的总闸门；超了统一提示「请求过于频繁」。\
删除后不是立刻消失：普通文件留 7 天，违规隔离图先留 30 天再软删，再留 7 天，最后才真正清理。

**代码部分：**

> - FileLimitConstant.java：
>   litisle_file\constant\FileLimitConstant.java：
>   作用：各接口限流窗口与阈值常量。
> - FileGlobalQpsInterceptor.java：
>   litisle_file\interceptor\FileGlobalQpsInterceptor.java：
>   作用：全站 QPS 固定窗口计数，超限抛 10005；排除 ASYNC 二次分发避免重复计数。
> - FileProperties.java：
>   litisle_file\config\FileProperties.java：
>   作用：单文件上限、配额开关与额度、缩略图参数、对账阈值、全站 QPS 参数。
> - FileMetaMapper.java：
>   litisle_file\mapper\FileMetaMapper.java：
>   作用：按 owner 聚合已用容量与文件数（`del_flag=0` 全量口径）。
> - ModerationProperties.java：
>   litisle_review\moderation\config\ModerationProperties.java：
>   作用：review 侧隔离保留期、单轮批量、删除限速、清扫锁时长。

## 八、异常与降级

**功能描述：**

> - 存储不可用：上传失败，用户看到「文件存储服务不可用，请稍后重试」；数据库占位由对账任务收尾。
> - 审核不可用：图片停留在「待检测」，用户看到占位图；审核服务重试耗尽后转可疑/死信，仍不露出真图。
> - 清扫/对账失败：任务单条失败不影响整轮，下一轮重扫；用户侧表现为「隔离/删除晚一点完成」。
> - 孤儿文件：库里有记录、存储没有对象 → 补偿或废弃；存储有对象、库里没记录 → 当前没有反向扫描。

**功能描述——详细版：**

> **存储不可用**
>
> - 写对象抛 `FILE_STORAGE_UNAVAILABLE(90005)`；内容校验失败抛 `FILE_CHECKSUM_MISMATCH(90006)`。
> - 此时上传已写入 `status=上传中` 的占位记录，但对象没写成；用户收到错误，重试是安全的（占位记录不参与秒传）。
> - 对账任务在次日 02:00 扫描超过 30 分钟的占位：对象存在 → 补成完成态（说明 put 实际成功但状态更新丢失）；对象不存在 → 软删废弃。
> - 取 URL 本身不访问存储（公开直链是拼接，私有预签名是本地签名），所以「存储短暂不可用」通常不影响已上传文件的 URL 获取；真正受影响的是下载流、缩略图生成、隔离/恢复移动。
>
> **审核不可用**
>
> - 上传侧只发事件、不等结论：发 MQ 失败只记日志，`safe_flag` 停留在待检测（default-deny，不露出真图）。
> - review 消费失败按公共 MQ 纪律重试；重试耗尽进入死信，转「可疑」仍占位，等人审/人工处理。
> - 文件侧消费审核结果失败：业务性失败（如文件不存在）告警后 ACK 丢弃；非预期异常进死信，不做无限重投。
> - 用户侧统一表现：公开面一直占位；私有面可预签名查看（审核员/owner 不受影响）。
>
> **隔离清扫失败**
>
> - review 侧整轮失败只留痕，次日重扫；单条失败也跳过，下一轮再试。
> - file 侧到期软删幂等：元数据不存在/已软删直接跳过；路径不在隔离前缀直接拒绝。
> - 用户侧表现：隔离图仍然拒绝访问（90008），只是保留时间被动延长，不会提前恢复。
>
> **对账发现「孤儿」**
>
> - 幽灵记录（`status=上传中`、对象不存在）：软删废弃，退出秒传池。
> - put 成功但状态未更新（对象存在）：补成完成态，文件恢复可秒传/可访问。
> - 已软删但仍有引用：跳过并告警，绝不删除仍被引用的对象。
> - 存储侧孤儿（对象存在、元数据行已被物理删除）：当前对账不做反向扫描，无法发现；只能靠运维按前缀人工核对。
>
> **批量取 URL 的失败放大**
>
> - 内部批量接口遇到任一公开违规文件会整批抛 90008；调用方如果直接透传，用户会看到「整页图片加载失败」而不是单图占位，需要在业务侧按文件粒度拆分重试或降级。
>
> **锁/限流依赖**
>
> - 秒传锁、全站 QPS、用户限流都依赖 Redis；Redis 不可用时相关请求会直接失败或提示系统繁忙，代码没有本地降级开关。

<br />

**详细版**：

- H1 上传失败的「库先行」设计：对象写失败有占位可对账；不会出现「对象写了、库里没有」的无入口孤儿。
- H2 审核结论丢失不会导致放行：默认待检测/可疑都按占位处理，宁可不展示，也不误放违规图。
- H3 死信不是终态：可疑图仍可被人审终局覆盖，后续 PASS/REJECT 事件会继续回写。
- H4 对账是单条独立容错：某条因存储异常失败，只影响这一条，下一轮重新处理；不会整轮中断。
- H5 隔离图到期删除有「前缀校验」：非隔离前缀一律拒绝，防止错误指令删正常文件。
- H6 删除/物理清理都以「无引用」为前置：残留引用只会让清理延后，不会造成他人图片悬空。

<br />

**通俗版**：\
存储挂了，上传会报「存储服务不可用」，但数据库里那条「上传中」会留着，第二天对账要么把它补成完成，要么标成废弃；用户重试不会踩到坏记录。\
审核服务挂了，图片就一直显示占位图；审核重试到最后会转「可疑」，还是要人来看，不会自动放行。\
隔离图到期没删成功也没关系，第二天再扫；用户那边仍然是「违规不可看」，不会因为清扫失败就提前放出来。\
对账能处理「库里有、存储没有」和「该删没删」；但「存储里有、库里没有」的孤儿它发现不了，这是目前没做的能力。

**代码部分：**

> - FileReconcileTask.java：
>   litisle_file\task\FileReconcileTask.java：
>   作用：占位补偿与软删到期清理；单条 try-catch，下一轮自愈；多实例锁互斥。
> - SeaweedFsClient.java：
>   litisle_file\client\SeaweedFsClient.java：
>   作用：将 S3 SDK 异常统一转成 `FILE_STORAGE_UNAVAILABLE` / `FILE_CHECKSUM_MISMATCH` / `FILE_NOT_FOUND`。
> - ModerationResultListener.java：
>   litisle_file\interceptor\ModerationResultListener.java：
>   作用：审核/申诉结果消费与 ACK 纪律（业务失败丢弃、非预期异常进死信）。
> - MqAckHelper.java / MqTraceSupport.java：
>   litisle_common\common_mq\consume\MqAckHelper.java / MqTraceSupport.java：
>   作用：公共消费纪律与 traceId 恢复。
> - FileAccessServiceImpl.java：
>   litisle_file\service\impl\FileAccessServiceImpl.java：
>   作用：访问闸口与「批量整批抛错」的契约实现。
> - FileGlobalQpsInterceptor.java：
>   litisle_file\interceptor\FileGlobalQpsInterceptor.java：
>   作用：全站 QPS 依赖 Redis 的固定窗口计数。

## 九、与审核、申诉的联动

**功能描述：**

> - 内容被判违规：review 发结果 → file 回写 `safe_flag=违规` 并物理隔离；引用保留，文章侧由 content 下架/驳回。
> - 申诉成功：review 发改判结果 → file 移回对象并置正常；文章侧由 content 恢复发布并补登记引用。
> - 申诉维持驳回：file 保持隔离，等到 30 天保留期满后软删。

**功能描述——详细版：**

> **违规判定到达 file 时**
>
> - file 只消费 `bizType=6`（图片）的结果；其他业务类型忽略。
> - PASS → `safe_flag=正常`；REJECT → `quarantine()`（隔离 + 违规）；SUSPICIOUS → `safe_flag=可疑`（不移动，占位待人审）。
> - 隔离不解除引用：文章/头像仍然「登记着这张图」，只是访问被拒绝；这样申诉恢复后引用关系还在。
> - 文章侧：content 收到同一批审核结果后执行首发驳回（1→3）或复审下架（2→4），并发下架事件；两边的消费是独立链路，不要求事务一致。
>
> **申诉改判到达 file 时**
>
> - 图片级申诉：review 发 `moderation.appeal.result`（bizType=6，`appealPass=true`）→ file 执行 `unquarantine()`：对象移回原前缀 + 正常。
> - 维持驳回：file 不做动作，图片继续隔离。
> - 文章级申诉：content 在「复审下架 4→2」恢复发布时，会连带逐张调用 `FileClient.unquarantine`（配图 + 封面），并补登记 `file_ref`（因为隔离图到期软删时曾按 fileId 解绑全部引用）；首发驳回 3→2 当前代码未调用解隔离（见文末「不一致与待确认」）。
> - 恢复失败不阻断文章恢复：content 单张失败只留日志，后续靠人工补偿或下一轮处理；file 侧对「对象已被物理清理」的记录返回 `FILE_NOT_FOUND`。
>
> **申诉成功后的访问表现**
>
> - 对象回到原前缀、`safe_flag=正常`：公开文件重新发直链，私有文件重新可预签名。
> - 若记录在缓冲期内被软删并恢复：`del_flag` 回 0、删除时间清空，重新进入秒传判重池。
> - 若记录已被物理清理：无法恢复，返回文件不存在；文章侧应把该图视为失效。

<br />

**详细版**：

- I1 两段式联动：审核判定（review → file 回写/隔离）与文章状态（review → content 驳回/下架）是两条独立消费者，最终一致而非强一致。
- I2 隔离保留引用、到期软删才解绑：这是为了「申诉成功还能恢复」；如果隔离时就解绑，恢复后引用计数会丢失。
- I3 到期软删解绑全部引用：审核终结的违规证据属管理动作，按 `fileId` 全删，不再要求四元组；对账随后可正常物理清理。
- I4 申诉恢复幂等：重复恢复跳过；软删态恢复先验对象存在，避免「元数据恢复成功但对象已没了」的假恢复。
- I5 文章恢复与文件恢复互不阻塞：content 对单张 `unquarantine` 失败只留痕，不阻断文章改判主体。
- I6 封禁前置：content 在申诉改判前会回查作者处罚状态，封禁中不放行；file 不参与该判定。

<br />

**通俗版**：\
审核说这张图违规，文件服务就把图搬进隔离区并标违规，同时保留「谁在引用它」的登记；文章服务那边会把文章驳回或下架。\
作者申诉成功后，审核服务发来「改判通过」，文件服务把图搬回原位并标正常，文章服务再恢复发布并重新登记引用。\
申诉被驳回就什么都不做，图继续隔离，等到 30 天满后软删。\
两边的恢复是「各自消费消息」，不保证同一瞬间完成；文件恢复失败不会把文章恢复也卡住。

**代码部分：**

> - ModerationResultListener.java：
>   litisle_file\interceptor\ModerationResultListener.java：
>   作用：`moderation.result` / `moderation.appeal.result` 两个队列的消费与分流。
> - FileModerationServiceImpl.java：
>   litisle_file\service\impl\FileModerationServiceImpl.java：
>   作用：隔离、恢复、软删态恢复、到期软删、按 fileId 解绑全部引用。
> - ArticleReviewWriteBackServiceImpl.java：
>   litisle_content\service\impl\ArticleReviewWriteBackServiceImpl.java：
>   作用：content 侧文章驳回/下架与申诉改判；4→2 恢复时连带调用 `unquarantine` 并补登记引用。
> - QuarantineSweeper.java：
>   litisle_review\sweeper\QuarantineSweeper.java：
>   作用：review 侧隔离图保留期清扫，筛选「超期 + 最新仍违规 + 无待裁决申诉」后委派 file 软删。
> - FileClient.java：
>   litisle_api\feign\FileClient.java：
>   作用：`quarantine / unquarantine / deleteQuarantined` 的跨服务契约。

## 十、验收口径：业务上怎么算做到

**功能描述：**

> 以下验收点均可通过「接口返回 + 数据库记录 + 对象存储路径」观察，不依赖内部日志推断。

**功能描述——详细版：**

> **上传与秒传**
>
> - 同一账号串行上传同一张图两次（第一次已完成）：第一次 `deduplicated=false`，第二次 `deduplicated=true` 且 `fileId` 相同；`file_meta` 只有一条活跃记录，对象存储只有一个对象。
> - 两个账号上传同一张公开图：第二个账号秒传命中同一个 `fileId`；私有类型（LICENSE）则各自独立，不跨账号命中。
> - 并发上传同一张图：现状**不保证只有一条完成态**——若后到者在先到者 put 完成前进入锁内双检，会再插一条记录。验收时只验证「串行重复上传稳定秒传」，不要把「并发唯一」当作已实现能力（见 C8）。
> - 上传超过 10MB：`FILE_SIZE_EXCEEDED(90001)`；伪装扩展名/伪造 MIME：`FILE_TYPE_NOT_ALLOWED(90002)`。
>
> **审核安全状态**
>
> - 未命中缓存的公开图：上传返回 `safeFlag=待检测`；此时取 URL 得到占位图，元数据为待检测。
> - 审核 PASS 后：取 URL 得到真实直链，元数据为正常。
> - 审核 REJECT 后：取 URL 返回 `FILE_VIOLATIVE(90008)`；对象路径带 `quarantine/` 前缀；`del_flag` 仍为 0；引用记录仍在。
> - 同一张违规图再次上传：`FILE_UPLOAD_REJECTED(90009)`，且不新增 `file_meta` 行。
> - 被判可疑：取 URL 仍为占位图；人审 PASS/REJECT 后按上述终态变化。
> - `LICENSE` 上传：不送图片机审，初始即正常；取 URL 为 30 分钟预签名，未登录/非 owner/非 REVIEWER/非 ADMIN 取不到。
>
> **引用与删除**
>
> - A、B 两篇文章引用同一张图：A 删除文章 → B 的图片仍可访问，`file_meta.del_flag=0`。
> - B 也删除后：`file_meta.del_flag=1`，记录进入 7 天缓冲；缓冲期内对象仍存在。
> - 满 7 天且对账执行后：对象与元数据行消失，残留引用被清理。
> - 非 owner 删除：`NO_PERMISSION`；REVIEWER 删除同样被拒。
>
> **隔离到期**
>
> - 隔离图超过 30 天、最新结论仍为违规、无待裁决申诉：被 review 清扫委派后 `del_flag=1` 且全部引用被解绑；对象仍在 `quarantine/` 前缀。
> - 有「待裁决申诉」：本轮不删除，保留期顺延。
> - 软删满 7 天后：对象与元数据被物理清理。
>
> **申诉恢复**
>
> - 隔离图申诉改判 PASS：对象移回原前缀、`safe_flag=正常`、取 URL 恢复正常。
> - 缓冲期内已软删的隔离图申诉改判 PASS：对象存在 → `del_flag=0`、删除时间清空；对象已被物理清理 → 返回 `FILE_NOT_FOUND`。
>
> **对账**
>
> - 手工造一条 `status=上传中` 且创建时间超过 30 分钟、对象存在的记录：对账后变完成态。
> - 同样造一条对象不存在的记录：对账后 `del_flag=1`。
> - 手工造一条软删满 7 天但仍有引用的记录：对账跳过并告警，不删对象。
>
> **配额与限流**
>
> - 默认 `quota-enabled=false`：传满 200 个文件仍可继续上传（不报 90007）。
> - 改为 `quota-enabled=true` 后：容量或数量超限返回 `FILE_QUOTA_EXCEEDED(90007)`；秒传命中仍不占新增额度。
> - 60 秒内第 31 次单文件上传：返回 `TOO_MANY_REQUESTS(10005)`；全站超过 200 次/秒同样 10005。

<br />

**详细版**：

- J1 秒传验收看「同 fileId + 单条元数据 + 单对象」，不是看接口耗时。
- J2 违规验收必须同时看三处：接口码 90008、元数据 `safe_flag=0`、对象路径在 `quarantine/`；只看到接口拒绝不能证明物理隔离。
- J3 删除验收必须看「最后一个引用消失才软删」，否则无法证明共享文件不被误删。
- J4 到期验收必须区分「软删」和「物理清理」：软删后对象仍在，物理清理后对象与行才消失。
- J5 恢复验收要覆盖「活跃态恢复」和「缓冲期软删态恢复」两种，分别验 `safe_flag` 与 `del_flag`。
- J6 对账验收要覆盖「对象存在」和「对象不存在」两个分支，以及「有引用不删」保护分支。

<br />

**通俗版**：\
验收不用猜：同一张图传两次看是不是同一个 ID、库里是不是只有一条；违规图看接口是不是拒绝、图片是不是真的搬进了隔离目录；删文章看别人的图还在不在；等 7 天看对象是不是真的没了。\
审核中看占位图、审核通过看真图、审核违规看拒绝，这三态能对上就说明安全状态链路是通的。\
申诉恢复要分别验「还没软删」和「已经软删但对象还在」两种情况。

**代码部分：**

> - FileUploadServiceImpl.java：
>   litisle_file\service\impl\FileUploadServiceImpl.java：
>   作用：上传/秒传/审核前置/占位与完成态，验收主入口。
> - FileManageServiceImpl.java：
>   litisle_file\service\impl\FileManageServiceImpl.java：
>   作用：引用解绑与软删判定，验收共享删除行为。
> - FileModerationServiceImpl.java：
>   litisle_file\service\impl\FileModerationServiceImpl.java：
>   作用：隔离/恢复/到期软删，验收安全状态与对象路径。
> - FileReconcileTask.java：
>   litisle_file\task\FileReconcileTask.java：
>   作用：补偿与物理清理，验收对账分支。
> - FileAccessServiceImpl.java：
>   litisle_file\service\impl\FileAccessServiceImpl.java：
>   作用：占位/拒绝/直链/预签名的对外表现。
> - FileMetaMapper.java：
>   litisle_file\mapper\FileMetaMapper.java：
>   作用：配额聚合与对账查询，验收数据口径。

## 附一：不一致与待确认（按代码现状记录，未作为本文结论）

> 以下问题在核对代码时发现，先如实记录、不替任何一方“修正口径”，需要产品或架构确认后再统一：
>
> - **并发秒传不保证唯一**：锁内只写占位、put 在锁外，且判重只认完成态；后到者可能在先到者 put 完成前再插一条记录。判重键没有数据库唯一约束兜底（`idx_dedup` 只是普通索引）。串行重复上传可稳定秒传，并发窗口内可能产生多条完成态记录。（对应 C8）
> - **缩略图未接 `safe_flag` 闸口**：`getUrl/batchGetUrls/download/presign` 都拦截违规，但 `getThumbnail` 只校验私有/开关/类型/尺寸；隔离后 `meta.path` 是 `quarantine/` 前缀，缩略图会按该路径派生并返回公开直链。隔离图缩略图是否也应拒绝，需确认。（对应 E5）
> - **content 文章级申诉的连带恢复只覆盖 4→2**：`ArticleReviewWriteBackServiceImpl` 在「复审下架 4→2」时调用 `unquarantine` 并补登记引用；「首发驳回 3→2」未调用解隔离。3→2 恢复发布后，原被隔离配图可能仍不可访问，需确认是否符合预期。（对应第九节）
> - **`StorageBizTypeEnum` 注释与配置不一致**：枚举注释称 `COVER/DRAFT_IMAGE` 不用于上传落桶、不占 `collections` 映射；实际 yml 有 `cover: cover`，`COVER` 可上传；`DRAFT_IMAGE`（code=`draft`）确实没有映射、上传会报「bizType 未配置 bucket 映射」。业务实际用法是「封面上传用 `ARTICLE_IMG`、引用用 `COVER`」。
> - **上传类注释残留旧结论**：`FileUploadServiceImpl` 类注释仍写「秒传命中可能是 `status=0` 在途记录，已知取舍」；当前 `selectByDedupKey` 已过滤 `status=COMPLETED` 且排除 `VIOLATIVE`，该描述已过时，但“并发唯一”问题仍存在（见第一条）。
> - **架构文档个别表述偏旧**：`File-Architecture.md` 仍把「业务权限判定」整体归为调用方负责；实际 file 自身做了 owner/ADMIN/REVIEWER 校验。其依赖说明中的「后续 content 接入」也已过时，content 已在用。架构文档的判重键描述未包含 `status=COMPLETED` 与 `safe_flag != VIOLATIVE` 两个新过滤条件。

## 附二：未纳入当前的能力

> - 音频/视频的上传、转码与审核：当前只处理图片与授权文档（PDF/Word/ZIP）。
> - OCR、图片内容理解、图片压缩/裁剪/水印：只做等比缩略图。
> - 分片上传、断点续传上传、大文件流式上传：当前为整份读入后一次写入，依赖 10MB 上限。
> - 存储侧反向孤儿扫描（对象有、元数据无）：对账只做「数据库 → 存储」方向。
> - 独立的“运营素材”业务类型与代传/代挂能力：只能以现有类型、由上传者本人使用。
> - 手工回收站/恢复页面：只有隔离图申诉恢复与 7 天软删缓冲，没有面向用户的回收站。
