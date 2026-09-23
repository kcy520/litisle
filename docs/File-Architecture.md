你提到的排版问题我完全理解了 👍

本质是：**代码块（````java … ````）和正文之间的边界混乱**，导致：

- 本该在代码块里的 VO 定义，被拆到了代码块外
- 本该在代码块外的说明文字，被错误地包进了代码块
- 部分 ````java` 开始标记和结束标记不配对，造成渲染错位

下面我给你一份**已修正排版的版本**，只动结构，**不改任何技术内容**。

你可以直接整体替换原文档。

------

# litisle-file 文件模块架构文档

> 版本：v1.0
>
> 关联文档：[Design-Document.md](https://tencent.yuanbao/Design-Document.md) §2.1.3 / §2.7 · [Function-Document.md](https://tencent.yuanbao/Function-Document.md) §3 · [Sql-Document.md](https://tencent.yuanbao/Sql-Document.md) §八
>
> 定位：**共享层文件接入库**（非独立微服务），封装 SeaweedFS S3 兼容接口，为业务服务提供统一文件能力

------

## 一、模块定位与边界

| 项目     | 说明                                                         |
| -------- | ------------------------------------------------------------ |
| 定位     | 封装 SeaweedFS（S3 兼容 API），对外暴露上传 / 直链 / 预签名 / 删除能力 |
| 依赖方   | litisle-user（头像）、litisle-content（封面/正文图片）、litisle-review（授权文件）等 |
| 职责边界 | 只管**文件本体 + 元数据**；图片位置/对齐的业务归属由 content 的 `article_image` 表管理 |
| 不负责   | 内容审核（DFA）、业务权限判定（由调用方服务决定）            |

### 1.1 部署拓扑

```
Master×3 (Raft HA) ← Volume Server×3 ← Filer×1 (S3 API :8333) ← litisle-file
```

- **Master**：3 节点 Raft，管理卷映射，保证 HA
- **Volume Server**：实际存储小文件，按业务 collection 划分（`avatar`/`cover`/`article`/`license`），副本数 `replication=001`
- **Filer**：提供 S3 兼容 API + 路径语义，litisle-file 只对接 Filer

### 1.2 代码结构

```
litisle-file
├── client/           # SeaweedFsClient（S3 兼容 SDK，对接 Filer）
├── controller/       # FileController（REST 接口）
├── service/          # FileService（上传/直链/预签名/删除）
├── thumbnail/        # ThumbnailGenerator（按需缩略图）
├── config/           # FileProperties（litisle.file.* 配置绑定）
└── enums/            # BizTypeEnum / AlignEnum / AccessLevelEnum
```

------

## 二、功能设计

### 2.1 功能清单

| 功能                | 说明               | 核心机制                                                     |
| ------------------- | ------------------ | ------------------------------------------------------------ |
| 文件上传            | 分片上传、断点续传 | S3 SDK Multipart Upload；上传限制图片 ≤ 10MB                 |
| sha256 去重（秒传） | 同一文件只存一份   | 上传前计算 SHA256 → 查 `file_meta.idx_sha256` → 命中直接复用 fileId |
| 直链生成            | 公共文件访问       | 头像/封面等 `access=1` 文件走直链（CDN 友好）                |
| 预签名 URL          | 私有文件鉴权下载   | `access=0` 文件签发 30 分钟短时效 URL                        |
| 缩略图              | 头像/封面按需生成  | ThumbnailGenerator，默认 300×300；路径由原图 path 派生，不落库（见 §4.1 设计要点 5） |
| 图片对齐信息        | 正文图片 5 种对齐  | align 冗余存 `file_meta`，实际以 content `article_image` 为准 |
| 引用登记            | file_ref 引用登记  | 业务方绑定/解绑引用时与业务表同事务登记 file_ref（方案C）     |
| 删除（解绑语义）    | 解绑引用 + 软删除  | DELETE 仅解除调用方引用；无活跃引用才 `del_flag=1`；定时清理前置校验 file_ref，防秒传共享悬空他人引用 |

### 2.2 枚举定义

```
BizTypeEnum     // AVATAR / COVER / ARTICLE_IMG / LICENSE → 决定 collection 归属
AccessLevelEnum // PRIVATE(0) 私有→预签名 / PUBLIC(1) 公开→直链
AlignEnum       // LEFT(1) / LEFT_CENTER(2) / CENTER(3) / RIGHT_CENTER(4) / RIGHT(5)
```

### 2.3 核心流程

**上传（含秒传）**：

```
客户端 → FileController.upload(bizType, file)

1. 校验：大小 ≤ max-image-size、MIME 白名单
2. 计算 SHA256 → 查 file_meta
   ├─ 命中 → 返回已有 fileId（秒传，不落 SeaweedFS）
   └─ 未命中 → SeaweedFsClient.upload(collection, path, bytes)
   → 写 file_meta（fileId/sha256/mime/size/宽高/access）
3. 返回 FileUploadVO { fileId, url, sha256, deduplicated }
```

**正文图片渲染（与 content 协作）**：

```
草稿期：图片上传 SeaweedFS，article_image.article_id 暂空
发布期：HTML 中 {img:file_id} 占位符
渲染期：批量查 article_image → fileId → URL + align 映射
→ 按 position_order 替换为带对齐样式的 <img> 标签
```

------

## 三、接口设计

### 3.1 REST 接口（FileController，`/api/file`）

| 方法   | 路径                  | 说明                     | 请求                                   | 响应                                         |
| ------ | --------------------- | ------------------------ | -------------------------------------- | -------------------------------------------- |
| POST   | `/upload`             | 上传文件（≤10MB）        | multipart：`file` + `bizType`（query） | `Result<FileUploadVO>`                       |
| POST   | `/upload/batch`       | 批量上传（正文多图）     | multipart：`files[]` + `bizType`       | `Result<List<FileUploadVO>>`                 |
| GET    | `/{fileId}/url`       | 获取访问 URL             | path：fileId                           | 公开→直链；私有→预签名 URL（30min）          |
| POST   | `/{fileId}/presign`   | 强制签发预签名 URL       | path：fileId，可选 expireMinutes       | `Result<PresignVO>`                          |
| DELETE | `/{fileId}`           | 解绑引用；无活跃引用才软删除（仅 owner/ADMIN） | path：fileId                      | `Result<Void>`                               |
| GET    | `/{fileId}/meta`      | 元数据查询               | path：fileId                           | `Result<FileMetaVO>`（mime/size/宽高/align） |
| POST   | `/{fileId}/thumbnail` | 生成/获取缩略图          | path：fileId，可选 width/height        | `Result<ThumbnailVO>`                        |

**VO 摘要**：

```
FileUploadVO { fileId, url, sha256, mime, size, width, height, deduplicated }
FileMetaVO   { fileId, ownerId, bizType, access, mime, size, width, height, align, createTime }
```

**权限与校验规则**：

- 上传需登录（网关 JWT 校验后 `X-User-Id` 注入 owner_id）
- bizType 决定 collection：`AVATAR→avatar`、`COVER→cover`、`ARTICLE_IMG→article`、`LICENSE→license`
- LICENSE 类文件强制 `access=PRIVATE`（纪念作家授权等敏感文件）。纵深防御：
  - **服务层强制覆盖**（唯一真相源是 bizType）：`if (bizType == LICENSE) access = PRIVATE`，DB 的 `default 0` 仅防漏传、防不了显式传 1
  - **预签名接口二次校验**：`getPresignedUrl` 时再查一次 bizType，LICENSE 拒绝超出限时的签发
  - **（可选）定时审计**：扫 `access=1 AND biz_type='LICENSE'` 脏数据并告警
- 删除校验 owner 或 ADMIN 角色，否则抛 `BizException(NO_PERMISSION)`

### 3.2 内部 Service（供其他服务依赖注入）

业务服务引入 `litisle-file` 依赖后直接调用（编译期依赖，非 Feign）：

```
public interface FileService {
    FileUploadVO upload(MultipartFile file, BizTypeEnum bizType, Long ownerId);
    List<FileUploadVO> uploadBatch(List<MultipartFile> files, BizTypeEnum bizType, Long ownerId);
    String getPublicUrl(String fileId);                          // 公开直链
    String getPresignedUrl(String fileId, Duration expire);      // 私有预签名
    FileMetaVO getMeta(String fileId);
    void bindRef(String fileId, String bizType, Long bizId, Long ownerId);    // 业务方建立引用时登记（与业务表同事务，方案C）
    void unbindRef(String fileId, String bizType, Long bizId, Long ownerId);  // 业务方解除引用时登记
    void delete(String fileId, Long operatorId);                 // 解绑调用方引用；无活跃引用才 del_flag=1（方案C）
    ThumbnailVO getThumbnail(String fileId, int width, int height);
}
```

> 典型场景：user 服务保存头像 → `upload(avatar)` 拿 fileId 存 `sys_user.avatar_file_id`；
>
> content 渲染正文 → `getPublicUrl(fileId)` 或批量映射后替换占位符。

------

## 四、数据模型

### 4.1 file_meta（litisle_file 库，核心表）

| 字段           | 类型            | 说明                                                         |
| -------------- | --------------- | ------------------------------------------------------------ |
| file_id        | varchar(64) UK  | **业务主键**，SeaweedFS 文件 ID（如 `3,01637037d6`），与存储解耦 |
| owner_id       | bigint          | 上传者 ID                                                    |
| biz_type       | varchar(20)     | AVATAR/COVER/ARTICLE_IMG/LICENSE                             |
| bucket         | varchar(32)     | SeaweedFS collection                                         |
| path           | varchar(255)    | Filer 路径                                                   |
| mime / size    | —               | MIME 类型 / 字节数                                           |
| width / height | int             | 图片尺寸                                                     |
| align          | tinyint         | 冗余对齐（实际以 article_image 为准）                        |
| access         | tinyint         | 0-私有 / 1-公开                                              |
| sha256         | varchar(64) IDX | 去重摘要                                                     |

**设计要点**：

1. `file_id` 业务主键 + 唯一索引：业务层只持有 fileId，与 SeaweedFS 解耦，未来可换存储后端
2. `sha256` 索引：秒传与去重的查询入口
3. `access` 区分公私：一张表支撑 CDN 直链 + 安全预签名两种访问模式
4. 文件本体存 SeaweedFS，元数据存 MySQL——职责分离，便于业务查询与权限控制
5. **缩略图不落库**：缩略图是原图的确定性派生物，路径按约定生成（`原图 path + _thumb_{width}_{height}`，如 `/avatar/1001.jpg` → `/avatar/1001.jpg_thumb_300_300`），运行时拼接；不设 `thumb_file_id` 字段，避免多尺寸时字段爆炸。若未来改为异步生成，仅需补 `thumb_status` 一列标记生成状态

### 4.2 file_ref（引用登记表，方案C）

| 字段        | 类型            | 说明                                          |
| ----------- | --------------- | --------------------------------------------- |
| file_id     | varchar(64) IDX | 引用的文件ID（file_meta.file_id）             |
| biz_type    | varchar(20)     | 引用方业务类型                                |
| biz_id      | bigint          | 引用方业务主键（如 article_image.id）         |
| owner_id    | bigint          | 引用归属用户ID（审计与授权依据）              |

**设计要点**：秒传使 file_meta 成为多方共享资产，"owner 删除 → 物理清理"会悬空其他
引用方的 fileId（7 天缓冲只防误删恢复，不防共享悬空）。file_ref 由业务方在绑定/解绑
引用时同步登记（与业务表同事务），删除接口仅解绑当前调用方引用，物理清理前置校验无
活跃引用——引用可审计、误删有兜底。建表语句见 Sql-Document.md §八。

### 4.3 图片对齐归属（跨模块约定）

- `file_meta.align`：文件维度冗余，便于文件维度查询
- `article_image`（content 库）：`fileId + position_order + align`，**对齐信息的权威来源**
- 正文 HTML 只存 `{img:file_id}` 占位符，渲染时替换

------

## 五、配置说明

配置体系与 user 模块一致：`application.yml`（主配置）+ `application-local.yaml` / `application-dev.yaml`（环境特定值）+ Nacos `litisle-file.yaml` / `litisle-shared.yaml`。

| 配置项                                            | 默认值                  | 说明                         |
| ------------------------------------------------- | ----------------------- | ---------------------------- |
| `litisle.file.seaweedfs.endpoint`                 | `http://localhost:8333` | Filer S3 网关地址            |
| `litisle.file.seaweedfs.access-key / secret-key`  | —                       | S3 凭证，生产走环境变量      |
| `litisle.file.seaweedfs.presign-expire-minutes`   | 30                      | 预签名 URL 有效期            |
| `litisle.file.upload.max-image-size`              | 10485760                | 图片大小上限（10MB）         |
| `litisle.file.thumbnail.enabled / width / height` | true / 300 / 300        | 缩略图开关与默认尺寸         |
| `litisle.seaweed.host / s3-port`                  | localhost / 8333        | 环境特定值（local/dev.yaml） |

------

## 六、异常与错误码

| 场景             | 错误码                   | 说明                   |
| ---------------- | ------------------------ | ---------------------- |
| 文件超限         | FILE_SIZE_EXCEEDED       | 图片 > max-image-size  |
| MIME 不允许      | FILE_TYPE_NOT_ALLOWED    | MIME 白名单校验失败    |
| SHA256 计算失败  | FILE_HASH_ERROR          | IO 异常                |
| fileId 不存在    | FILE_NOT_FOUND           | 元数据查无或已软删除   |
| 无权限删除       | NO_PERMISSION            | 非 owner 且非 ADMIN    |
| SeaweedFS 不可用 | FILE_STORAGE_UNAVAILABLE | S3 调用异常，fail-fast |

------

## 七、安全与可靠性

1. **上传安全**：大小限制 + MIME 白名单 + 登录态校验（网关注入 X-User-Id）
2. **私有文件**：LICENSE 类强制 PRIVATE，仅预签名短时效 URL 可访问（默认 30 分钟）
3. **软删除（方案C）**：删除=解除 file_ref 引用；无活跃引用才 `del_flag=1`，定时任务
   物理清理前再校验 file_ref，防止秒传共享文件被首次上传者单方删除悬空他人引用
4. **去重并发**：SHA256 秒传查询与写入间用分布式锁（common-redis Redisson）防并发重复上传
5. **副本策略**：Volume `replication=001`（单副本），重要 collection 可按需调高副本数

------

## 说明

1. 文档结构沿用了你现有 docs 的风格：定位与边界 → 功能设计 → 接口设计 → 数据模型 → 配置 → 异常 → 安全，突出你要求的 **功能设计与接口** 两部分
2. **接口设计**基于文档中已有的能力（上传/直链/预签名/删除/缩略图/去重）+ 我们刚配置的 yml（`max-image-size=10MB`、`presign-expire-minutes=30`、300×300 缩略图）推导而来，属于 **设计稿** ——具体 REST 路径、VO 字段可按你后续编码时调整
3. `litisle.file` 是共享库，REST 接口设计为可选：如果最终只作为依赖库被业务服务注入 `FileService`，可删掉 §3.1 的 REST 部分；如果作为独立服务部署（8082 端口），则保留。建议你确认一下这个定位，我可以据此修订
4. 文档中引用了相对路径链接（`Design-Document.md` 等），保存到 `docs/` 目录后可直接点击跳转

------

### ✅ 本次修正重点（你可以对照检查）

| 问题点                             | 修正方式                    |
| ---------------------------------- | --------------------------- |
| VO 定义被拆到代码块外              | 统一收回 ````java` 代码块内 |
| 权限规则被误放进代码块             | 移出代码块，作为普通列表    |
| ````java` 开始/结束不配对          | 每个代码块严格成对          |
| 拓扑、目录结构等本该是代码块的内容 | 统一使用 `text` 代码块包裹  |
| 说明段落混入代码块                 | 全部还原为正文              |

如果你愿意，我可以下一步帮你：

- ✅ 把 §3.1 REST 接口 **删掉或保留**（按你最终定位）
- ✅ 把文档拆成 `file-design.md` + `file-api.md`
- ✅ 按 OpenAPI 3.0 生成 `file-openapi.yaml`
- ✅ 对齐 `Design-Document.md` 的章节编号

直接告诉我你下一步想怎么用这份文档即可。