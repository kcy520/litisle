



# seaweedfs

## 概念相关

### URL 获取与预签名



#### 一、两种访问模式的本质区别



文件系统里每个文件都有`access` 属性（0-私有 / 1-公开），对应两种完全不同的“给 URL”方式：

##### 1. 公开直链（Direct URL / Public URL）

```
`http://localhost:8333/avatar/2026/09/14/abc123.jpg
       └── S3 网关 ──┘└── bucket ─┘└── 对象 key ────┘`
```



- 任何人拿到 URL 就能访问 ，没有时效、没有签名
- 服务端做的事就是 字符串拼接 ：`endpoint + "/" + bucket + "/" + path` ——零开销
- 适用：头像、封面、正文图片——这些内容“被人看到”本身就是目的
- 代价：URL 永久有效， 泄露了就等于文件公开了 ，所以只给低敏感内容用

##### 2. 预签名 URL（Presigned URL）

私有文件不能“给个链接就完事”，但也不能每次都让文件服务自己当中转（`用户 → file 服务 → SeaweedFS` 回源，带宽和内存全压在 file 服务上，10MB 图片转发几次就把服务打满了）。

预签名 URL 是两全方案 ：服务端把“授权”编码进 URL 本身，让用户 绕过 file 服务、直连 SeaweedFS ，但只有授权过的人、在时限内才能访问：

```
`http://localhost:8333/license/2026/09/14/xyz789.pdf
    ?X-Amz-Algorithm=AWS4-HMAC-SHA256
    &X-Amz-Credential=litisle/20260914/us-east-1/s3/aws4_request
    &X-Amz-Date=20260914T120000Z
    &X-Amz-Expires=1800          ← 有效期 30 分钟
    &X-Amz-SignedHeaders=host
    &X-Amz-Signature=5f3a9b...   ← SigV4 签名（核心）`
```



工作原理 （就是 SigV4 签名的“预计算”变体）：

1. file 服务用 secretKey 对`HTTP方法 + 对象key + 过期时间` 等要素做 HMAC 运算，生成签名串
2. 签名和参数拼进 URL 发给用户
3. 用户直接拿这个 URL 请求 SeaweedFS 网关
4. 网关用 自己的 secretKey 副本重算一遍签名 比对——一致且未过期 → 放行返回文件流

  ```
底层发生了什么（纯本地，零网络）

整个过程就是 CPU 算 hash，不连 SeaweedFS。 所以这一步极快（微秒级），但签出来的 URL 只有 SeaweedFS 网关能验证。

输入：
  - HTTP 方法：GET
  - bucket：license
  - object key：2026/09/14/xyz789.pdf
  - 过期时间：5 分钟后
  - 当前时间：2026-09-14T12:00:00Z
  - secretKey：xxxxxxxx（从配置加载，不出 JVM）

处理（SDK 内部）：
  1. 构造 Canonical Request 字符串
  2. 拼 StringToSign
  3. 用 secretKey 做多轮 HMAC-SHA256
  4. 得到签名串：5f3a9b...

输出：
  https://localhost:8333/license/2026/09/14/xyz789.pdf
    ?X-Amz-Algorithm=AWS4-HMAC-SHA256
    &X-Amz-Credential=litisle/20260914/us-east-1/s3/aws4_request
    &X-Amz-Date=20260914T120000Z
    &X-Amz-Expires=300
    &X-Amz-SignedHeaders=host
    &X-Amz-Signature=5f3a9b...
  ```

  关键理解： 签名是“数学上不可伪造”的 。用户没有 secretKey，改 URL 里的任何参数（比如把`X-Amz-Expires=1800` 改成`99999` ）都会导致签名校验失败，直接 403。secretKey 永远不出服务端。
   你前面 curl 验证过的`--aws-sigv4` 是"现算现用”的 SigV4 请求签名；预签名是同一套算法的“先算好、给用户稍后用”版本。同一把 key，两种用法。



#### **补充：secretKey **

SeaweedFS 启动 S3 网关时，需要配置一组（或多组）凭证，通常长这样：



```bash
# 启动参数或配置文件里
--s3.access_key=litisle
--s3.secret_key=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

或者在 `security.toml` / `s3.json` 里配置多租户：



```json
{
  "identities": [{
    "name": "litisle",
    "credentials": [{
      "accessKey": "litisle",
      "secretKey": "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
    }],
    "actions": ["Read", "Write", "List"]
  }]
}
```

| 字段            | 角色                             | 是否暴露                 |
| --------------- | -------------------------------- | ------------------------ |
| `accessKey`     | 身份标识，告诉网关"我是谁"       | ✅ 可以出现在配置、日志里 |
| **`secretKey`** | **签名密钥，用来算 HMAC 的"盐"** | ❌ **永远不出服务端**     |

 它实际做了什么

预签名 URL 的生成过程，本质上就是：

```
secretKey + HTTP方法 + 对象路径 + 时间戳 + 过期时间
    ↓ HMAC-SHA256（多轮嵌套）
签名串（signature）
```

**secretKey 是 HMAC 运算的输入之一。** 没有它就算不出正确的签名；拿到签名的人如果不知道 secretKey，也反推不出来（这是 HMAC-SHA256 的数学性质，不可逆）。

**架构位置：**

```
┌─────────────────────────────────────────────────────┐
│  file 服务（你的 Java 应用）                          │
│                                                     │
│  application.yml:                                   │
│    seaweedfs:                                       │
│      endpoint: http://localhost:8333                │
│      access-key: litisle                            │
│      secret-key: xxxxxxxx  ← 存在这里               │
│                                                     │
│  生成预签名时：SDK 内部用 secret-key 做 HMAC 计算     │
│  生成的是签名串，secret-key 本身不进 URL              │
└───────────────────────┬─────────────────────────────┘
                        │ 签好的 URL（含签名串，不含 secretKey）
                        ▼
┌─────────────────────────────────────────────────────┐
│  用户浏览器                                         │
│  拿到 https://xxx?X-Amz-Signature=5f3a9b...         │
│  直接请求 SeaweedFS S3 网关                          │
└───────────────────────┬─────────────────────────────┘
                        ▼
┌─────────────────────────────────────────────────────┐
│  SeaweedFS S3 网关                                  │
│                                                     │
│  自己也有同一份 secretKey（启动时配的同一个）          │
│  收到请求 → 用 secretKey 重算签名 → 比对              │
│  一致 + 未过期 → 返回文件                             │
│  不一致 → 403 Forbidden                             │
└─────────────────────────────────────────────────────┘
```

**关键点：file 服务和 SeaweedFS 网关各持一份相同的 secretKey，但都不通过任何渠道把它传给用户。**





#### 二、实现逻辑

````Java

`public String getUrl(String fileId, Long userId) {
    FileMeta meta = fileMetaMapper.selectById(fileId);
    // 1. 查不到或已软删除 → FILE_NOT_FOUND
    // 2. 按 access 分流
    if (AccessLevelEnum.PUBLIC.equals(meta.getAccess())) {
        // 公开：纯拼接，O(1)
        return properties.getEndpoint() + "/" + meta.getBucket() + "/" + meta.getPath();
    }
    // 私有：先做归属校验（只有 owner 或 ADMIN 能拿到下载授权）
    checkPermission(meta, userId);
    // 3. 纵深防御第三层：预签发前二次校验 bizType
    //    LICENSE 类拒绝超过限时的签发（防上层逻辑漏拦）
    Duration expire = resolveExpire(meta.getBizType());
    // 4. 委托 SDK 的 S3Presigner 生成带签名的 GET URL
    return seaweedFsClient.presign(meta.getBucket(), meta.getPath(), expire);
}`

对应`SeaweedFsClient.presign` 的底层（SDK v2）：


````



```java

`public String presign(String bucket, String key, Duration expire) {
    GetObjectRequest get = GetObjectRequest.builder()
            .bucket(bucket).key(key).build();
    // S3Presigner 在启动时构建一次（复用凭证），这里只是签名计算，不发起网络请求
    return presigner.presign(GetObjectPresignRequest.builder()
            .signatureDuration(expire)
            .getObjectRequest(get))
            .url().toString();
}`
```



注意一个容易踩的点： presign 是纯本地计算，不联网 。所以“预签名 URL 生成”这个接口很快，但它不保证对象真的存在——文件被删了，签出来的 URL 一样会 404。业务上如果要保证可用性，先查`file_meta` （我们本来就查了）即可，不必去 SeaweedFS 探测。

#### 三、为什么这样设计（安全与性能的权衡）

决策 替代方案 为什么不 私有文件走预签名直连 file 服务中转下载流 中转消耗服务带宽/内存/连接数，10MB 级文件很容易打爆；预签名把流量卸载给 SeaweedFS 30 分钟短时效 永久签名 / 长 24h 时效越短，泄露窗口越小；30 分钟足够“点开下载”这个动作，且可重复签发 签发前校验 owner/ADMIN 任何人可签 预签名本质是"代用户行使权限”，签发点就是鉴权点；否则私有文件形同虚设 LICENSE 限时签发二次校验 只在写入时定 access 纵深防御：access 字段可能被误更新为 PUBLIC，签发层独立再拦一道

这套设计与你文档里的既定决策一一对应：`access` 分流对应 File-Architecture.md §4.1 设计要点 3，LICENSE 二次校验对应 §3.1 纵深防御，30 分钟默认值就在你 application.yml 的`presign-expire-minutes: 30` 。

一句话总结： 公开文件用拼接（性能优先），私有文件用预签名（安全优先），预签名 = 把 SigV4 授权“冷冻”进 URL，限时 + 不可伪造 + 流量不过业务服务 。





### endpoint

endpoint 就是 SeaweedFS Filer 的 S3 网关地址，是 Java 后端（AWS SDK）用来跟 SeaweedFS 通信的唯一入口。

一句话：**endpoint = 告诉 SDK "往哪儿发 S3 请求"**。

**具体干什么**

你项目里 `SeaweedFsClient` 用 AWS SDK v2，所有操作最终都发 HTTP 请求到 endpoint：

| **操作**       | **SDK 实际做的事**                       |
| :------------- | :--------------------------------------- |
| `putObject`    | POST `http://endpoint/bucket/path`       |
| `getObject`    | GET `http://endpoint/bucket/path`        |
| `deleteObject` | DELETE `http://endpoint/bucket/path`     |
| `headObject`   | HEAD `http://endpoint/bucket/path`       |
| `presign`      | 本地用 endpoint 的 host 算签名，生成 URL |

所以 endpoint 决定了：

1. **请求发到哪台机器**（Filer 的 IP/域名 + 端口 8333）
2. **签名里的 host 字段是什么**（影响预签名 URL 能否校验通过）
3. **SDK 能不能连上存储**（配错 = 全部文件操作失败）

**你项目里的配置**

```yaml
seaweedfs:
  endpoint: http://localhost:8333
```

意思是：AWS SDK 所有 S3 请求都往 `localhost:8333` 发。

- 开发环境：你本机跑 Filer，`localhost` ✅ 没问题
- 生产环境：必须改成内网地址，比如 `http://seaweed-filer:8333` 或 `http://10.0.3.21:8333`

**endpoint ≠ 用户访问地址**

这是最容易混淆的点：

```
┌─────────────┐         endpoint (内网)        ┌──────────────┐
│  Java 后端   │ ──────────────────────────────→ │ SeaweedFS    │
│  (SDK)       │ ← 只给后端用                    │ Filer :8333  │
└─────────────┘                                 └──────────────┘
       │                                                ↑
       │ public-url-base (CDN/浏览器用)                  │
       │ presign URL (浏览器临时访问)                    │
┌─────────────┐                                        
│  浏览器/前端 │ ────────────────────────────────────────┘
└─────────────┘
```

**为什么预签名也依赖 endpoint**

预签名 URL 的签名里包含 **host** 字段。SDK 签名时用的是 endpoint 的 host：

```
算签名 → host = seaweed-filer:8333
生成 URL → https://seaweed-filer:8333/bucket/path?X-Amz-Signature=xxx
```

浏览器拿着这个 URL 去访问，SeaweedFS 收到后：

1. 从 URL 里提取 host
2. 用同一个 host 重算签名
3. 比对 → 一致才放行

**如果浏览器实际访问的域名和签名时的 host 不一致，校验直接失败。**

所以接 CDN 后必须告诉 Filer："外部看到的域名是 xxx"，通过启动参数 `-s3.externalUrl=https://files.litisle.com` 解决。



### 预签名

**预签名（Presigned URL）**就是：后端用密钥**算出一个带签名的 URL**，拿着这个 URL 的人可以在有效期内**直接访问私有文件**，不需要登录、不需要 token。

> 你进不了公司大楼（没门禁卡），但行政给你开了一张**临时访客条**（带水印+过期时间），保安看到条就放行。条过期作废，行政不用跟着你一起进门。

**它解决什么问题**

你的文件分两种：

| **类型** | **访问方式**                                  | **问题**                               |
| :------- | :-------------------------------------------- | :------------------------------------- |
| PUBLIC   | `https://img.xxx.com/avatar/xxx.jpg` 直接打开 | 谁都能看，不适合敏感文件               |
| PRIVATE  | 不能直链                                      | 但有时候你**确实需要让别人临时看一眼** |

PRIVATE 文件的困境：

- 方案 A：后端做代理转发 → 后端带宽被打满，慢
- 方案 B：把文件改成 PUBLIC → 泄密
- ✅ **方案 C：预签名 URL** → 浏览器直接访问存储，但只有 30 分钟有效



**它到底长什么样**

普通直链：

```
https://img.litisle.com/license/2026/01/abc.pdf
```

预签名 URL：

```
https://files.litisle.com/license/2026/01/abc.pdf?
  X-Amz-Algorithm=AWS4-HMAC-SHA256
  &X-Amz-Credential=litisle%2F20260115%2Fus-east-1%2Fs3%2Faws4_request
  &X-Amz-Date=20260115T103000Z
  &X-Amz-Expires=1800
  &X-Amz-SignedHeaders=host
  &X-Amz-Signature=a3f8c2d9e1b7...
```

后面那一坨就是**签名**，SeaweedFS 收到请求后自己验证：

- 签名对不对？（用同一个密钥重算）
- 过期没？（`Date + Expires`）
- host 对不对？

全过 → 返回文件内容；任一不过 → 403。



**你的代码里怎么做的**

```java
// FileServiceImpl 里的核心逻辑
public String presign(Long fileId, Integer expireMinutes, UserVO user) {
    FileMeta meta = getById(fileId);
    
    // 1. 权限校验：只有 owner 或 ADMIN 才能申请
    if (!meta.getOwnerId().equals(user.getId()) && !user.isAdmin()) {
        throw new BizException(FILE_ACCESS_DENIED);
    }
    
    /// 2. 本地计算签名（不联网！不请求 SeaweedFS！）
    
    
    //第 1 步：构造一个"假请求"
//你在告诉 SDK："我要签名的这个 URL，将来是用来 GET（下载） 哪个 bucket 里的哪个文件"
    GetObjectRequest getReq = GetObjectRequest.builder()
        .bucket(meta.getBucket())
        .key(meta.getPath())
        .build();
    
    //第 2 步：用 Presigner 算签名   全程纯 CPU 运算，零网络 IO。
    PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(b -> b
        .signatureDuration(Duration.ofMinutes(expireMinutes))
        .getObjectRequest(getReq)
    );
    
    // 3. 返回 URL 字符串
    //返回的 URL = 传进去的 URL + 签名参数（凭证）
    return presigned.url().toString();

}
```

> SeaweedFS 收到请求后做的事：
>
> ```
> 1. 从 URL 解析出 bucket、key、date、expires、credential
> 2. 用 credential 里的 accessKey 查到对应的 secretKey
> 3. 用同样的算法、同样的参数重新算一遍签名
> 4. 比对 URL 里的 Signature 和自己算的是否一致
> 5. 检查当前时间是否在有效期内
> 6. 全过 → 返回文件；任一不过 → 403
> ```

关键点：

| 步骤       | 说明                                                         |
| ---------- | ------------------------------------------------------------ |
| 权限校验   | 后端先确认"你有没有资格申请"                                 |
| 签名计算   | **纯本地 CPU 运算**，用 accessKey/secretKey + 算法算出来的   |
| 不请求存储 | 签名时 SeaweedFS 完全不知道这件事                            |
| 返回 URL   | 前端拿到后直接 `<a href="预签名URL">下载</a>` 或 `window.open()` |

**完整时序**

```
浏览器              后端                    SeaweedFS Filer
  │                  │                          │
  │  GET /file/123   │                          │
  │  /presign        │                          │
  │─────────────────→│                          │
  │                  │  查 file_meta (权限校验)   │
  │                  │──────────┐               │
  │                  │←─────────┘               │
  │                  │                          │
  │                  │  本地算签名 (CPU)         │
  │                  │  ← 不联网 →              │
  │                  │                          │
  │  { url: "https   │                          │
  │   ://files...    │                          │
  │   ?X-Amz-..." }  │                          │
  │←─────────────────│                          │
  │                  │                          │
  │  用 URL 直接下载  │                          │
  │─────────────────────────────────────────────→│
  │                  │                          │ 验签 → 通过
  │  ← 文件内容 ──────────────────────────────────│
  │                  │                          │

```

 **为什么叫"预"签名**

因为**签名发生在访问之前**，而且签名是后端提前算好给前端的。前端拿到 URL 后直接去访问存储，全程不经过你的后端。

对比普通鉴权：

|                    | **普通 Token 鉴权**          | **预签名 URL**                   |
| :----------------- | :--------------------------- | :------------------------------- |
| 每次请求过不过后端 | ✅ 过                         | ❌ 直接到存储                     |
| ---                | ---                          | ---                              |
| 后端带宽消耗       | 高（代理转发）               | 零                               |
| 有效期             | 靠 token 过期                | URL 里写死（如 30 分钟）         |
| 能不能转发给别人   | token 在 header 里，相对安全 | URL 本身就是凭证，**转发即授权** |
| 适合场景           | API 请求                     | 大文件下载/预览、临时分享        |

**你项目里的关键设计**

1. **LICENSE 文件强制 PRIVATE** → 永远不能直链，只能预签名
2. **默认 30 分钟过期** → 够用但不永久
3. **owner/ADMIN 才能申请** → 防止任意用户给私有文件生成临时链接
4. **签名纯本地计算** → 不依赖 SeaweedFS 在线，性能好
5. **URL 即凭证** → 拿到 URL 的人在 30 分钟内随便下，所以入口权限必须严



> **预签名 = 后端用密钥给一个私有文件"开一张限时通行证"，浏览器拿着证直接去存储取文件，证过期作废，全程不走后端。**





## SeaweedFS 是什么




结合你 compose.yaml 里那个单容器模式（`server -master -volume -filer -s3`）来讲，学完概念就能对上你自己的部署。

### 一、SeaweedFS 是什么

一句话：**为海量小文件设计的分布式文件存储系统**（源自 Facebook 的 Haystack 论文）。它的设计目标恰好击中传统方案痛点：

- 存图床/网盘场景，用 MySQL 存文件路径 + 磁盘目录，几千万小文件后 **inode 耗尽、目录列表巨慢、磁盘随机寻道成为瓶颈**
- SeaweedFS 的核心优化：**一个 Volume 文件里连续存几百万个小文件的“内容”，读文件只需 1 次磁盘寻道**（传统文件系统每个文件都要读 inode + 数据块，多次寻道）

你项目选它而不是 MinIO/OSS 的原因（对应你的文档）：S3 兼容 + 轻量（单容器就能跑）+ 小文件性能优秀。

### 二、四个核心角色（对应你容器里的 4 个进程）

你 compose 里的启动命令是 `server -master -volume -filer -s3`，一个 weed 进程同时扮演四个角色：

#### 1. Master（你映射的 9333 端口）

**集群的“大脑/元数据管理器”**，只存元数据不存文件：

- 管理 Volume Server 的拓扑：哪些节点活着、每个 Volume 落在谁那里、哪个可写
- 分配文件 ID：上传时 Master 告诉客户端“往 Volume 7 写”
- 生产环境 Master×3 组成 Raft 选主（对应你文档 §1.1 拓扑图），单机开发模式只有 1 个

#### 2. Volume Server（容器内 8080，你映射到 18080）

**真正存文件的地方**：

- 数据按 **Volume（卷）** 组织——一个大文件（默认 30GB 封顶），里面紧密排列成百上千万个小文件的内容
- 每个卷写满或到期就冻结，新建空卷继续写
- `replication=001`（你文档里的副本策略）：000=无副本、001=同机架 1 副本、010=不同机架各 1 副本……副本规则由 Master 决定往哪几个 Volume Server 写

#### 3. Filer（你映射的 8888 端口）

**给原始存储“套上目录树”的一层**：

- Master/Volume 那套用的是“文件 ID”（如 `3,01637037d6`——volume id + cookie 拼接），**没有文件名、没有目录**，人没法用
- Filer 在上面补了 POSIX 风格的路径：`/avatar/2026/09/14/abc.jpg`，内部用一张元数据表（可存 MySQL/LevelDB 等）记录“路径 → 文件 ID”的映射
- **你的 file_meta 表就是同思路**：MySQL 里存路径和元数据，SeaweedFS 里存本体——文档说“职责分离”就是指这个

#### 4. S3 网关（你映射的 8333 端口）

**挂在 Filer 前面的协议翻译层**，把 S3 协议请求翻译成 Filer 操作：

```
PUT /bucket/key  →  Filer 的 /bucket/key 路径
```

- 每个桶其实就是 Filer 根目录下的一个文件夹
- 你业务（litisle-file 用 AWS SDK）只对接 8333，不感知底下 Master/Volume 的存在
- 鉴权在这里做：`s3.json` 里的 identities（就是你前两天配的 `litisle / litisle_s3_pass` + actions）+ SigV4 签名校验

### 三、一次上传/下载的完整旅程（把四个角色串起来）

**上传** `PUT /avatar/1.jpg`：

```
客户端(带SigV4签名) → S3网关:8333 验签/鉴权(actions校验)
                    → Filer:8888 记录路径映射
                    → Master 分配可写 Volume
                    → Volume Server:8080 数据落盘(追加写入)
```

**下载** `GET /avatar/1.jpg`：

```
客户端 → S3网关 → Filer 查映射得到文件ID(如 7,abc123)
       → 按 volume id=7 问 Master/本地缓存: 7号卷在哪
       → Volume Server 读磁盘: 读卷索引→定位偏移量→一次寻道取出内容
```

### 四、几个容易混淆的名词辨析

| 名词           | 是什么                                                       | 类比                         |
| -------------- | ------------------------------------------------------------ | ---------------------------- |
| **Collection** | 逻辑分组/命名空间，同一批 Volume 归入一个 collection；**S3 桶 ≈ collection** | 数据库的“库”                 |
| **Volume**     | 实际存储大文件，内部装着海量小文件内容                       | 数据文件（如 InnoDB 的 ibd） |
| **File ID**    | Volume 内寻址凭证：`volumeId,fileKey`                        | 行的物理地址 rowid           |
| **Filer Path** | 人类可读路径，映射到 File ID                                 | 主键索引                     |
| **Bucket**     | S3 层面的桶，落到 Filer 就是顶级目录                         | schema                       |

你 yml 里的 `collections: avatar/cover/article/license` 就是按业务分 collection——**同一 collection 的文件写进专属的 Volume**，好处：将来某个业务要迁移/删除/提高副本数，按 collection 操作即可，不影响其他业务。

### 五、对照你的部署：单机 vs 生产拓扑

|            | 你的开发环境（单容器） | 文档 §1.1 生产拓扑                                           |
| ---------- | ---------------------- | ------------------------------------------------------------ |
| Master     | 1 个                   | 3 个（Raft 选主，防脑裂）                                    |
| Volume     | 1 个                   | 3 个（数据分片 + `replication=001` 副本）                    |
| Filer      | 1 个                   | 1 个（无状态可加）                                           |
| 对业务影响 | 无                     | **对 litisle-file 透明**——只依赖 S3 API（8333），换拓扑只改 endpoint |

这就是文档里“生产/联调环境切换完整拓扑，对 litisle-file 透明”的含义：应用面永远只见 S3 协议，扩容是运维层面的事。

### 六、快速上手检查清单（你的环境已全通过）

```powershell
# 1. Master 活着（集群状态）
curl http://localhost:9333/cluster/status

# 2. Filer 活着
curl http://localhost:8888/

# 3. S3 网关 + SigV4 鉴权通（你已验证过 200）
curl --aws-sigv4 "aws:amz:us-east-1:s3" --user "litisle:litisle_s3_pass" http://localhost:8333/
```

三个都通，说明 master/volume/filer/s3 四个角色全在线，可以开写 SeaweedFsClient 了。

**最小记忆版**：Master 管元数据、Volume 存数据、Filer 管路径、S3 网关管协议和鉴权；bucket=collection，file_meta 存路径，SeaweedFS 存本体。概念上还有哪里想深挖（比如 Volume 的 TTL/压缩机制、副本放置策略）可以继续问。







## 企业规范参考



The user asks: "企业规范是怎样的？" — about the previous topic: how enterprises handle public file direct links vs anonymous access in S3-compatible storage. I should explain industry practice: CDN + signed URLs, bucket policy, separate public/private buckets, etc. Keep it educational, in Chinese, and give recommendation. No tools needed.
好问题。上一轮的“方案 A vs 方案 B”其实在大厂体系里有非常成熟的对应做法，我按真实企业演进路径讲，你会发现你的场景正好落在某一级。

### 一、企业里的标准分层：**“公私有桶分离”是铁律，先于一切**

成熟团队的第一原则不是“怎么让匿名访问 8333”，而是：

```
私有桶（private-bucket）         公开桶（public-bucket）
  ├─ license/ 授权文件             ├─ avatar/ 头像
  ├─ resume/ 简历                  ├─ cover/  封面
  └─ 任何用户数据/敏感内容          └─ article/ 正文图片
       │                                │
       ▼                                ▼
  100% 走预签名 URL                 100% 走 CDN 域名
  （每次访问都鉴权）                （URL 即公开承诺）
```

规范依据（阿里 OSS / 腾讯 COS / AWS S3 的官方最佳实践都一致）：

1. **公开读权限只能授予“桶级别”**，绝不授予桶内个别对象——因为“哪些对象是公开的”会随业务演化变得不可审计
2. **私有桶即使匿名请求存在，也必须 403**——防御纵深里“存储层是最后一道门”，应用层的 `access` 字段判断可以出 bug，存储层不能出
3. 一个文件的公私属性**从创建那刻就确定且不可逆**（LICENSE 永远进私有桶），而不是“同一个桶里靠字段区分”——字段可能被误 UPDATE，桶 ACL 不会

对应到你的项目：你 yml 里已经按业务分了 4 个 collection（avatar/cover/article/license），**这已经天然是“公开桶（avatar/cover/article）+ 私有桶（license）”的雏形**，只差把“access 由 bizType 决定”这个规则坐实——bizType=LICENSE → 私有桶，其余 → 公开桶。这比我之前说的“按 access 字段分流”更符合企业规范，两者可以兼得：**bucket 决定存储位置，access 字段只做冗余记录/审计**。

### 二、公开访问的企业标准形态：**业务服务不直接暴露，前面必有 CDN**

企业实践中，“用户浏览器 → S3 网关直连”这个形态**只存在于开发和内网环境**。生产链路是：

```
浏览器 → CDN（如阿里云 CDN / CloudFront）
              │  ① 命中缓存 → 直接返回图片（99% 的请求到这就结束了）
              │  ② 未命中 → 回源拉取 S3/OSS
              ▼
        对象存储（桶设为公共读 或 CDN 用专用回源身份）
```

为什么必须隔一层 CDN：

| 维度   | 裸暴露 S3 网关                       | CDN 前置                                  |
| ------ | ------------------------------------ | ----------------------------------------- |
| 性能   | 每次回源站                           | 边缘节点就近返回                          |
| 成本   | 公网带宽贵                           | CDN 流量单价远低于源站带宽                |
| 安全   | 源站 IP/端口直接暴露在公网，攻击面大 | 源站可完全收回内网，只允许 CDN 回源 IP 段 |
| 防盗链 | 要自己在网关层做                     | CDN 层配 Referer/UA/URL 鉴权一行配置      |
| HTTPS  | 证书自己管                           | CDN 统一托管证书                          |

**关键规范**：对象存储的 endpoint **永远不出现在给前端的 URL 里**。前端拿到的 URL 是 CDN 域名（如 `https://img.litisle.com/avatar/xxx.jpg`），将来换存储厂商，只改 CDN 回源配置，**所有存量图片 URL 不变**——这是你之前决定“库存 fileId、URL 运行时拼”的最终回报：拼接逻辑换成 CDN 域名，一次配置全局生效。

### 三、私有文件的企业标准形态：预签名的三种时效策略

预签名在企业里也不是“一个 30 分钟走天下”，按业务敏感度分档：

| 档位     | 时效                       | 典型场景                                    |
| -------- | -------------------------- | ------------------------------------------- |
| 极敏感   | 1–5 分钟                   | 银行对账单、合同 PDF、身份证明              |
| 常规私有 | 15–60 分钟                 | 你的 LICENSE 授权文件（30 分钟合适）        |
| 半私有   | 签发时带内容哈希，可数小时 | 视频付费内容的播放地址（配合 CDN URL 鉴权） |

另一个企业实践：**敏感文件不走公共预签名，走“授权下载接口”**——file 服务校验权限后，从存储拉流再以流式响应返回（`Content-Disposition: attachment`），全程不过浏览器缓存、URL 从未离开过服务端。判断标准：**“文件泄露的代价” × “文件大小”**——授权文件小且极敏感，中转的代价可接受；视频大，中转不可接受，用预签名+短时效。

你的 LICENSE 场景（PDF、几百 KB、极敏感）其实两个都成立，建议默认预签名 30 分钟（性能好、够安全），将来若有“仅本人可下载授权文件”的强需求再升级为中转下载。

### 四、匿名身份（s3.json 的 anonymous）在企业语境下的定位

现在可以精确回答上一轮方案 A 的“企业规范位置”了：

- **开发环境**：加 `anonymous: [Read]` 完全 OK——目的只是让浏览器能看图调试，规范允许
- **生产环境**：**没有“匿名读 S3 网关”这个形态**。公开桶的匿名读是**交给 CDN 回源身份 + 桶策略**实现的，S3 网关本身对公网不可见（安全组只放行 CDN 回源 IP 段）

也就是说，企业规范不反对 anonymous 读，而是**把“谁可以匿名读”的控制权从存储网关上移到了 CDN/网关层**。你现在加 anonymous 是正确的开发姿势，但要在心里标记：这是 dev 环境特例，生产拓扑里 8333 不对公网开放，anonymous 也就无所谓了。

### 五、落地到你的项目：建议的最终形态

| 环境             | 公开文件 URL 形态                                          | 私有文件                               |
| ---------------- | ---------------------------------------------------------- | -------------------------------------- |
| **dev（现在）**  | `http://localhost:8333/{bucket}/{path}`（加 anonymous 读） | 预签名 30 分钟                         |
| **生产（规划）** | `https://img.litisle.com/{bucket}/{path}`（CDN 域名）      | 预签名 30 分钟；LICENSE 可升级中转下载 |

代码上只需要一个**环境无关的 URL 拼接组件**：

```java
// FileProperties 里加一项 public-url-base
// dev:  http://localhost:8333
// prod: https://img.litisle.com （CDN 域名，通过 Nacos 配置注入）
public String buildPublicUrl(FileMeta meta) {
    return properties.getPublicUrlBase() + "/" + meta.getBucket() + "/" + meta.getPath();
}
```

业务代码从头到尾不感知 endpoint 和 CDN 的区别——这就是企业规范里“URL 是派生数据”原则的完整闭环。

**总结成三句话**：① 公私有按桶（collection）分离，属性由 bizType 决定且创建即定型；② 公开文件生产必走 CDN，存储 endpoint 永不外露；③ 匿名读是 dev 特例，生产由 CDN 回源身份替代。你的现有设计（fileId 入库 + 拼接派生 + 预签名）已经和企业规范同构，只差 s3.json 加 anonymous（dev 便利）和未来加 CDN 配置项两步。

要我现在出 s3.json 加 anonymous 的 diff 吗？改完你浏览器就能直接看图，dev 联调体验立刻闭环。





### 关于cdn补充

 **URL 永远是“运行时拼接”，绝不落库**

这是整个迁移方案的基石（你已经做到了）：

- 数据库里只存`file_id / bucket / path` —— 不含域名
- URL 在接口返回时用`public-url-base` 配置拼接
  这样 CDN 上线 = 改一个配置值，所有历史图片的 URL 自动变成新域名。如果 URL 存了库（哪怕是“当时想的直链”），迁移时要全表刷数据。

###  配置结构现在就按“可换源”设计

在 application.yml 的`litisle.file.seaweedfs` 下把两个地址 分开定义 ：

`````yml
litisle:
  file:
    seaweedfs:
      endpoint: http://${litisle.seaweed.host:localhost}:${litisle.seaweed.s3-port:8333}
        # ↑ 服务器内部地址：SDK 上传/下载/预签名用它，永不给前端
      public-url-base: http://${litisle.seaweed.host:localhost}:${litisle.seaweed.s3-port:8333}
        # ↑ 公开读地址：拼直链给前端用
        # dev 阶段 = endpoint（同一个 8333）
        # 接 CDN 后 = https://img.litisle.com （只改这一处）
`````



拼接逻辑：



```java
`public String buildPublicUrl(FileMeta meta) {
    return properties.getPublicUrlBase() + "/" + meta.getBucket() + "/" + meta.getPath();
}`
```



上传、删除、预签名这些 服务器操作永远走`endpoint` （SDK 要签名，必须直连源站）；只有公开直链走`public-url-base` 。两个配置从第一天就分离，CDN 接入点就已经预留好了。









## 补充

### S3

> **S3 是一种对象存储接口标准。你的项目通过 AWS SDK 操作 SeaweedFS，本质是把 SeaweedFS 当成“兼容 S3 协议的存储后端”来用。**

**S3** 全称是 **Amazon Simple Storage Service**，中文通常叫“简单存储服务”。

它最早是 **AWS 亚马逊云** 提供的一种对象存储服务。你可以把它理解成一个：

> **无限大的、按“桶 + 对象”组织的云端文件系统。**

但注意：S3 不是传统意义上的“文件夹 + 文件”系统，而是 **对象存储**。





**有了S3，我在更换其他文件存储框架的时候，需要什么变动？**

> **如果你现在只用了 AWS SDK 的 S3 标准接口，没有用 SeaweedFS 私有特性，那么换存储框架时：只需要改配置，不需要改业务代码。**

最坏情况也只需要：

- 改 `application.yml` 里的 endpoint / bucket / AK / SK
- 或加一个 `StorageProvider` 抽象层（可选）

**不需要改 Controller、Service、file_meta 表结构、秒传逻辑。**





#### **S3 的核心概念**

| **概念**              | **类比**      | **说明**                                                  |
| :-------------------- | :------------ | :-------------------------------------------------------- |
| Bucket                | 仓库 / 文件夹 | 用来存放一组文件，比如 `avatar`、`license`                |
| Object / Key          | 文件 + 路径   | 真正存进去的文件，比如 `2026/09/17/uuid.jpg`              |
| Endpoint              | 仓库大门地址  | 客户端要访问 S3，必须知道门牌号，比如 `http://filer:8333` |
| AccessKey / SecretKey | 门禁卡 + 私钥 | 用来证明“我是谁”和“我有没有权限”                          |
| Presigned URL         | 临时通行证    | 后端签发的限时访问链接                                    |

**S3 不是某个软件，而是一种“接口标准”**

这里特别关键：

> **S3 是一种 API 规范，不是一个必须跑在 AWS 上的东西。**

只要某个系统实现了 S3 兼容接口，你就可以用 AWS SDK 去操作它。

所以你现在项目里：

```yml
seaweedfs:
  endpoint: http://localhost:8333
```

虽然你用的是 **SeaweedFS**，但因为你用的是 AWS SDK v2：

```java
S3Client.putObject(...)
S3Client.getObject(...)
S3Presigner.presignGetObject(...)
```

所以你的代码**不关心底层是不是 AWS**，它只关心：

> “对方是不是实现了 S3 接口？”

这就是 S3 在你项目里的真正含义。



#### 为什么要用 S3 接口

主要有三个原因：

##### ① 不绑死某个存储厂商

如果代码直接写死调用 SeaweedFS 的私有 API：

```
seaweedFsClient.upload(...)
```

以后想换成 MinIO、阿里云 OSS、腾讯云 COS，就要改大量代码。

而你现在用的是 AWS SDK：

```
s3Client.putObject(bucket, key, bytes);
```

只要对方支持 S3 协议，就可以平滑替换。

##### ② S3 是业界事实标准

几乎所有对象存储都兼容 S3：

- AWS S3
- MinIO
- 阿里云 OSS（兼容 S3）
- 腾讯云 COS（兼容 S3）
- SeaweedFS Filer S3 网关

所以你学一次 S3，等于学会了“通用对象存储访问方式”。

##### ③ 工具链成熟

AWS SDK 已经帮你做好了：

- 签名
- 重试
- 流式上传
- 分片上传
- 预签名 URL
- 权限模型

你不需要自己造轮子。



### 双重检查

[归路](#双重检查)

#### 1. 它解决什么问题

双重检查出现在你这个流程里：

```text
查 file_meta.idx_sha256（del_flag=0）
├─ 命中 → 直接返回已有 fileId
└─ 未命中 → Redisson 分布式锁 biz:file:sha:{hash}
    ├─ 拿锁后双重检查
    └─ 临界区只包「查+写」
```

它解决的是：

> **高并发下，同一个文件被重复上传、重复落库的问题。**

------

#### 2. 如果没有双重检查，会发生什么

假设两个用户同时上传同一张图片，hash 都是 `abc123`。

##### 时间线



```text
线程A：计算 hash = abc123
线程A：查数据库 → 没有命中
线程A：准备上传文件

线程B：计算 hash = abc123
线程B：查数据库 → 也没有命中（因为 A 还没写完）
线程B：准备上传文件
```

结果：

- SeaweedFS 里存了两份一模一样的文件
- `file_meta` 表里插了两条记录
- 存储浪费
- 秒传去重失效

------

#### 3. 为什么不能只靠“查一次”

你可能会想：

> 那我在查之前加锁不就行了？

可以，但问题是：

- 如果每次上传都加锁，哪怕是两个**完全不同的文件**，也要抢同一把锁
- 吞吐量会严重下降

所以设计目标是：

> **只有 hash 没命中时，才需要加锁；而且只对同一个 hash 加锁。**

------

#### 4. 双重检查的完整流程

**完整流程**：

````java
// ① 第一次检查（无锁）
FileMeta exist = fileMetaMapper.selectBySha256(hash);
if (exist != null) {
    return exist.getFileId(); // 秒传
}

// ② 拿锁
RLock lock = redissonClient.getLock("biz:file:sha:" + hash);
lock.lock();
try {
    // ③ 双重检查（锁内）
    FileMeta existAgain = fileMetaMapper.selectBySha256(hash);
    if (existAgain != null) {
        return existAgain.getFileId(); // 秒传
    }

    // ④ 只做 DB 写入（锁内）
    insertFileMeta(); // 先落 DB，标记"这个 hash 有人在处理了"

} finally {
    lock.unlock();
}

// ⑤ 上传 SeaweedFS（锁外）
uploadToSeaweedFS();



````



##### 第一次检查：不加锁

```java
// ① 第一次检查（无锁）
FileMeta exist = fileMetaMapper.selectBySha256(hash);
if (exist != null) {
    return exist.getFileId(); // 秒传
}
```

这一步不加锁，因为：

- 大多数情况下，文件确实没传过
- 不需要锁
- 性能好



##### 加锁：只对同一个 hash 加锁



```java
// ② 拿锁
RLock lock = redissonClient.getLock("biz:file:sha:" + hash);
lock.lock();

```

注意：

- 锁的 key 是 `hash`
- 不同文件之间不会互相阻塞
- 只有“同一个文件”的并发请求才会抢同一把锁

##### 第二次检查：拿锁后再查一次



```java
try {
    // ③ 双重检查（锁内）
    FileMeta existAgain = fileMetaMapper.selectBySha256(hash);
    if (existAgain != null) {
        return existAgain.getFileId(); // 秒传
    }

    // ④ 只做 DB 写入（锁内）
    insertFileMeta(); // 先落 DB，标记"这个 hash 有人在处理了"

} finally {
    lock.unlock();
}

// ⑤ 上传 SeaweedFS（锁外）
uploadToSeaweedFS();

```

------

#### 5. 为什么第二次检查是必须的

因为从“第一次检查”到“拿到锁”，中间有时间窗口。



```text
线程A：第一次查 → 没命中
线程B：第一次查 → 没命中
线程A：拿到锁
线程B：等锁
```

当线程 B 拿到锁的时候，线程 A 很可能已经：

- 上传完了
- 数据库也写完了

所以线程 B 必须再查一次：

- 如果查到了 → 说明 A 已经传过了 → B 直接秒传
- 如果没查到 → 说明自己是第一个真正要传的人

这就是“双重检查”。

------

#### 6. 用现实例子理解

可以把双重检查理解成“抢厕所”：

```text
场景：公司只有一个厕所，门口没有显示屏
```

##### 错误做法

你走到门口，发现门上没写“有人”，于是你开始找钥匙准备进。

结果里面其实有人，你白忙活。

##### 正确做法：双重检查



```text
第一次检查：看门上有没有“有人”牌子
  → 没有

第二次检查：开门前再确认一眼
  → 还是没人

然后你进去，并把牌子翻到“有人”
```

分布式锁就相当于：

> **“进去之前先把牌子翻过来”**

而双重检查相当于：

> **“翻牌子之前，再确认一遍里面是不是真的没人”**

------

#### 7. 为什么临界区只包“查 + 写”，不包 S3 put

这是你代码注释里很关键的一点：

```text
临界区只包「查+写」；S3 put 幂等放锁外，不占锁
```

##### 原因

S3 上传可能要几秒甚至十几秒。

如果上传也在锁里面：

```
lock.lock();
try {
    uploadToS3(); // 很慢
    insertDB();
} finally {
    lock.unlock();
}
```

后果：

- 其他线程要等好几秒
- 并发量一上来，系统直接卡死

##### 为什么可以放锁外

因为 S3 put 是**幂等**的：

```text
同一个 bucket + 同一个 key
第一次 put：创建文件
第二次 put：覆盖，结果一样
```

所以即使两个线程都执行了 `put`，也不会破坏数据。

但数据库写入不能重复，所以：

> **锁只保护“数据库查 + 数据库写”这个关键路径。**

------

#### 8. 一句话总结

##### S3

> **S3 是一种对象存储接口标准。你的项目通过 AWS SDK 操作 SeaweedFS，本质是把 SeaweedFS 当成“兼容 S3 协议的存储后端”来用。**

##### 双重检查

> **双重检查是为了在高并发下既保证正确性，又保证性能：第一次无锁快速判断，第二次拿锁后防止并发重复写入。**





### 哈希查重与秒传（企业规范完整链路）

**总结：**

> 秒传链路由"无锁快查 → 分布式锁双重检查 → 锁内占位 → 锁外幂等上传 → 异步对账补偿"五段构成，在保证并发安全与数据一致性的前提下，实现了存储去重与隐私隔离的双重目标。

#### 3.1 设计目标

在文件上传链路中同时实现三个目标：

| 目标     | 手段                                        |
| -------- | ------------------------------------------- |
| 存储去重 | 相同内容文件只存一份，节省存储与带宽        |
| 并发安全 | 同一文件并发上传不产生重复记录、不重复落盘  |
| 隐私合规 | 公开内容全局去重，私有内容按 owner 隔离去重 |

------

#### 3.2 完整流程图

```
① 计算 hash（SHA256 + size）
        │
        ▼
② 无锁查重：SELECT ... WHERE sha256=? AND size=? AND del_flag=0
   [owner 隔离时追加 AND owner_id=?]
        │
        ├─ 命中 ────────────────────────────────→ 返回 fileId，deduplicated=true（秒传）
        │
        └─ 未命中
                │
                ▼
③ 获取分布式锁：lockKey = "biz:file:sha:{sha256}"
   leaseTime=10s，看门狗自动续期
                │
                ▼
④ 锁内双重检查：再次执行 ② 的查询
        │
        ├─ 命中 ─→ unlock ─────────────────────→ 返回 fileId，deduplicated=true（秒传）
        │
        └─ 未命中
                │
                ▼
⑤ 临界区（锁内）：insert file_meta（status=0 上传中）
                │
                ▼
⑥ unlock（释放锁）
                │
                ▼
⑦ 锁外执行：S3 put（幂等，同 bucket+key 覆盖无副作用）
        │
        ├─ 成功 → update file_meta set status=1（上传完成）
        │
        └─ 失败 → 保留 status=0，由对账任务清理
                │
                ▼
⑧ 返回 fileId，deduplicated=false
```

>  **补充说明**
>  [owner 隔离时追加 AND owner_id=?]
>
>  > **为了防止"我传了一个私有文件，别人通过秒传机制探测到我也用了这个系统"。**
>
>  ## 先说场景
>
>  假设你的平台有两个用户：
>
>  | 用户 | 行为                                                |
>  | ---- | --------------------------------------------------- |
>  | 小明 | 上传了一份 LICENSE 授权文件（PRIVATE）              |
>  | 小红 | 拿到了同一份 LICENSE 文件（比如同一款软件的授权书） |
>
>  如果**不做 owner 隔离**，走全局去重：
>
>  ```
>  小红上传 LICENSE → 算 hash → 查 DB → 命中（小明之前传过）
>  → 系统返回小明的 fileId
>  → 小红秒传成功
>  ```
>
>  表面上看没问题，文件确实一样，省了存储。
>
>  **但问题在于：小红现在知道了"小明也传过这个文件"。**
>
>  ### 为什么"知道别人传过这个文件"是安全问题
>
>  ### 场景 1：推断用户行为
>
>  
>
>  ```
>  攻击者上传某本教材的 PDF 扫描版
>  → hash 命中 → 秒传返回
>  → 攻击者得出结论：这个系统里有人在用这本教材
>  → 进一步推断：这个人是哪个专业、哪个年级
>  ```
>
>  ### 场景 2：推断用户身份（更严重）
>
>  
>
>  ```
>  攻击者上传一份公司内部合同的模板文件
>  → hash 命中
>  → 结论：这家公司有人在这个平台上存了合同
>  → 泄露商业关系
>  ```
>
>  ### 场景 3：版权/合规风险
>
>  
>
>  ```
>  某用户上传了一份有版权争议的文件
>  → hash 被别人命中
>  → 平台变成了"文件传播的中转站"
>  → 平台方可能被追责"协助传播"
>  ```
>
>  ### 场景 4：LICENSE 文件本身就是敏感信息
>
>  
>
>  ```
>  LICENSE 文件里可能有：
>  - 授权方公司名称
>  - 授权范围（哪些人能用、用多久）
>  - 授权金额
>  - 授权码/序列号
>  
>  如果别人能秒传命中，就等于知道"谁在用这个软件、用了多少份"
>  ```

#### 3.3 伪代码

```java
public UploadResult upload(MultipartFile file, BizType bizType, User user) {

    // ① 流式计算 SHA256 + size（不整读内存）
    HashResult hash = digestUtil.streamDigest(file.getInputStream());
    String sha256 = hash.getSha256();
    long size = hash.getSize();

    // ② 无锁首次查重（公开内容全局去重；私有内容按 owner 隔离）
    FileMeta exist = fileMetaMapper.selectBySha256AndSize(
            sha256, size, delFlag(0), ownerIdIfPrivate(bizType, user));
    if (exist != null) {
        return UploadResult.of(exist.getFileId(), true); // 秒传
    }

    // ③ 获取分布式锁
    RLock lock = redissonClient.getLock("biz:file:sha:" + sha256);
    lock.lock(10, TimeUnit.SECONDS); // 看门狗自动续期

    try {
        // ④ 锁内双重检查
        FileMeta existAgain = fileMetaMapper.selectBySha256AndSize(
                sha256, size, delFlag(0), ownerIdIfPrivate(bizType, user));
        if (existAgain != null) {
            return UploadResult.of(existAgain.getFileId(), true); // 秒传
        }

        // ⑤ 临界区：仅写 DB，占位并标记上传中
        FileMeta meta = buildMeta(sha256, size, bizType, user, Status.UPLOADING);
        fileMetaMapper.insert(meta);
        return meta;

    } finally {
        // ⑥ 释放锁
        lock.unlock();
    }
} // 锁已释放

// ⑦ 锁外：实际上传（幂等，不占锁）
try {
    seaweedFsClient.put(meta.getBucket(), meta.getPath(), file.getBytes(), meta.getMime());
    // ⑧ 上传成功，标记完成
    fileMetaMapper.updateStatus(meta.getId(), Status.UPLOADED);
} catch (Exception e) {
    // 保留 status=0，由对账任务清理
    log.error("upload failed, fileId={}", meta.getFileId(), e);
}
return UploadResult.of(meta.getFileId(), false);
```

#### 3.4 关键设计说明

**3.4.1 判重键：sha256 + size 联合**

| 方案                      | 安全性                                     |
| ------------------------- | ------------------------------------------ |
| 仅 sha256                 | 理论上存在碰撞（2^128 分之一），企业不推荐 |
| **sha256 + size**         | 碰撞概率趋近 0，企业推荐做法 ✅             |
| sha256 + size + 前 N 字节 | 过度设计，当前规模无需                     |

对应索引：

```
CREATE INDEX idx_dedup ON file_meta(sha256, size, del_flag);
-- 私有内容隔离时追加 owner_id 到联合索引
```

 **3.4.2 双重检查的意义**

| 检查   | 是否加锁 | 作用                                   |
| ------ | -------- | -------------------------------------- |
| 第一次 | 无锁     | 快速路径，绝大多数请求在此返回，不阻塞 |
| 第二次 | 加锁     | 防止"等锁期间其他线程已写入"的竞态条件 |

这是并发编程中处理"检查-然后-执行"竞态的标准模式（Double-Checked Locking）。

**3.4.3 锁粒度与临界区**

| 操作            | 耗时量级 | 是否在锁内 | 理由                                                      |
| --------------- | -------- | ---------- | --------------------------------------------------------- |
| 查 DB           | ~1ms     | ✅          | 必须串行化                                                |
| 写 DB（insert） | ~2ms     | ✅          | 必须串行化，防重复记录                                    |
| S3 put          | 200ms~3s | ❌          | 放锁内会严重降低并发；且 S3 put 幂等，同 key 覆盖无副作用 |

**锁只保护"查+写"，不保护 IO。**

 **3.4.4 锁的过期与防死锁**

- `leaseTime=10s`：超过此时间锁自动释放，防止实例宕机导致死锁
- 看门狗：Redisson 默认每 3s 续期一次，业务未完成时锁不会提前失效
- 锁 key 按 `sha256` 细分：不同文件之间不互相阻塞

**3.4.5 秒传命中后的 URL 拼接规则（防跨 bucket 坏链）**

> 秒传命中时返回的 URL **必须以命中记录的 `bucket/path` 为准**，不能用本次请求的 bizType 映射拼 URL。

原因：去重是跨 bizType 的（公开内容全局去重），同一份文件可能先以 AVATAR 传入 `avatar` bucket，后来又以 ARTICLE_IMG 上传命中。若按本次请求的 bizType（article）拼 URL，对象实际在 avatar bucket，必然 404 坏链。

> ### 详细解释
>
> **总结：**
>
> > **秒传可能跨业务命中——文件实际在哪个 bucket 是由"第一次上传它的人"决定的，不是由"这次请求的业务类型"决定的。所以秒传返回后拼 URL 必须读 DB 记录里的 bucket/path，不能按本次 bizType 重新映射，否则必然 404。**
>
> #### 先说"坑"长什么样
>
> 假设你系统里有这两个 bucket：
>
> | bizType     | bucket 名 | 用途     |
> | ----------- | --------- | -------- |
> | AVATAR      | `avatar`  | 用户头像 |
> | ARTICLE_IMG | `article` | 文章配图 |
>
> ### 时间线
>
> 
>
> ```
> 09:00 小明上传头像 → bizType=AVATAR → 存到 SeaweedFS
>   bucket = "avatar"
>   path   = "2026/09/17/uuid-a.jpg"
>   file_meta 记录：file_id=A, bucket=avatar, path=2026/09/17/uuid-a.jpg
> 
> 09:30 小红写文章，插入同一张图片 → bizType=ARTICLE_IMG
>   → 算 hash → 查 DB → 命中（小明那张）
>   → 秒传返回 file_id=A ✅
> ```
>
> **到这里一切正常。**
>
> ### 然后前端要展示这张图
>
> 前端拿到 `file_id=A`，需要拼一个 URL 来显示图片。
>
> **错误做法**：按本次请求的 bizType 拼 URL
>
> 
>
> ```java
> // 小红这次请求是 ARTICLE_IMG
> // 代码按 ARTICLE_IMG 映射 bucket
> String bucket = "article";  // ← 错！实际文件在 avatar bucket
> String url = "http://filer:8333/article/2026/09/17/uuid-a.jpg";
> //                                                     ^^^^ 拼出来的
> ```
>
> **实际文件在哪？**
>
> 
>
> ```
> 真实位置：http://filer:8333/avatar/2026/09/17/uuid-a.jpg
>                                     ^^^^^^ 实际 bucket
> ```
>
> **结果：404。图片裂了。**
>
> ------
>
> ## 为什么会写出这种 bug
>
> 开发者直觉上会这么写：
>
> ```java
> // 伪代码：秒传返回后拼 URL
> public String buildUrl(String fileId, BizType bizType) {
>     FileMeta meta = fileMetaMapper.selectById(fileId);
>     String bucket = bizTypeToBucket(bizType); // ← 按"本次请求"的 bizType 映射
>     return endpoint + "/" + bucket + "/" + meta.getPath();
> }
> ```
>
> 逻辑看起来没问题：**"我要什么业务类型，就去什么 bucket 找"**。
>
> 但秒传打破了这个假设——**文件不是这次传的，是以前别人传的，在别人的 bucket 里。**
>
> ------
>
> ## 正确的做法
>
> ### 秒传返回时，URL 必须基于命中记录自身的 bucket/path
>
> ```
> // 正确做法：从 file_meta 记录里取 bucket 和 path
> public String buildUrl(FileMeta meta) {
>     return endpoint + "/" + meta.getBucket() + "/" + meta.getPath();
> }
> // meta.getBucket() = "avatar"（命中记录里的值）
> // meta.getPath()   = "2026/09/17/uuid-a.jpg"
> ```
>
> **file_meta 表里已经存了 bucket 和 path，直接用就行，不要重新映射。**
>
> ------
>
> ## 那为什么会有两种选择（二选一）
>
> 文档里说的"二选一"是什么意思：
>
> ### 选择 A：去重是全局的 → URL 必须跟命中记录走
>
> 
>
> ```
> 去重查询：WHERE sha256=? AND size=? AND del_flag=0
> （不限 bizType）
> 
> → 命中后返回 fileId
> → URL 用命中记录的 bucket/path
> → 同一个文件可能出现在不同业务的页面上
> ```
>
> **优点**：去重率高，省存储
>
> **代价**：前端拼 URL 时必须从后端拿完整 URL，不能自己按 bizType 拼
>
> ### 选择 B：去重限定 bizType → URL 可以按 bizType 拼
>
> 
>
> ```
> 去重查询：WHERE sha256=? AND size=? AND del_flag=0 AND biz_type=?
> （限定 bizType）
> 
> → 头像的 hash 和文章图片的 hash 互不影响
> → 每个 bizType 各存各的
> → URL 可以安全地按 bizType 映射拼
> ```
>
> **优点**：URL 拼接逻辑简单，不会跨 bucket
>
> **代价**：同一张图片如果既当头像又当文章配图，会存两份
>
> ------
>
> ## 为什么"不能混用"
>
> | 混用的情况                         | 结果                             |
> | ---------------------------------- | -------------------------------- |
> | 全局去重 + 按 bizType 拼 URL       | ❌ 跨 bucket 404                  |
> | 按 bizType 去重 + 按命中记录拼 URL | ✅ 能跑，但去重率低（没充分利用） |
>
> **两种策略选一种，代码逻辑要一致：**
>
> | 你选的策略       | 去重查询条件        | URL 拼接方式                     |
> | ---------------- | ------------------- | -------------------------------- |
> | 全局去重         | 不限 bizType        | **必须用命中记录的 bucket/path** |
> | bizType 隔离去重 | 加 `AND biz_type=?` | 可以按 bizType 映射拼            |
>
> **最容易被忽略的坑**：选了全局去重，但 URL 拼接代码写成了按 bizType 映射——编译不报错、单测不报错、单用户测试不报错，**只有秒传跨 bizType 命中时才 404**，而且还是偶发的。

实现约定：

- 秒传返回的 fileId 指向**已有记录**，其 bucket/path/access 均以命中记录为准
- 若业务强约束「同一文件必须归属本 bizType 的 bucket」，则去重查询应限定 `(sha256, size, biz_type)`——二选一，不能混用
- 对应企业规范审查表 #5，是秒传设计中最容易被忽略的坑

> **eg｜本项目决策（LitIsle 实习项目）**：采纳「去重查询限定 `(sha256, size, biz_type)`」方向，即判重键 = `(sha256, size, biz_type[, owner_id])`。
>
> 原因：
>
> 1. **bizType → bucket 是强归属场景**：头像就该在 avatar 桶、授权文件就该在 license 桶，跨 bizType 复用 fileId 会让「文件归属」语义模糊，后续按 bizType 做权限/配额/清理时会引入特判
> 2. **URL 永远不会拼错**：判重限定在本 bizType 内，命中记录的 bucket 必然等于本次请求的 bucket，从根上消除坏链问题，不再依赖「记得用命中记录拼 URL」这种约定式防守
> 3. **LICENSE 的 owner 隔离可自然叠加**：判重条件演化为 `WHERE sha256=? AND size=? AND biz_type=? AND del_flag=0`，PRIVATE 类追加 `AND owner_id=?`，与 3.6.1 去重边界无缝衔接，查询模板统一
>
> 相应调整：
>
> - 索引由 `idx_dedup(sha256, size, del_flag)` 调整为 **`idx_dedup(sha256, size, biz_type, del_flag)`**（owner 隔离场景走前缀匹配 + 回表过滤 owner_id，当前量级足够；若后期 PRIVATE 量大再扩 owner_id 进索引）
> - 代价：同一份图片以头像和封面两种 bizType 各传一次时**不会互相秒传**（各存一份）。本项目图片上限 10MB、规模为个人实习量级，这点存储冗余可接受——用少量存储换语义清晰与零坏链，符合企业规范中「简单性优先于过度优化」的原则

**3.4.6 在途记录（status=0）的秒传边界（已知取舍）**

边界场景：双重检查命中一条 `status=0`（锁内占位已写库、锁外 S3 put 尚未完成）的记录时：

```text
命中 status=0 记录
  → S3 对象可能还没传完
  → 秒传返回 fileId，调用方立即访问 → 短暂 404
```

**已知取舍（个人/实习项目采纳）**：命中即返回，不做等待。理由：

- S3 put 秒级完成，404 窗口极短，调用方重试即可自愈
- 若要彻底消除，需引入「等待 put 完成」或「命中在途记录时挂起轮询」机制，复杂度不成比例
- 极端情况下由对账任务兜底（put 失败 → status=0 记录被清理），保证最终一致

> ⚠️ 面试/评审时会被问到此点，回答口径：*"这是可用性与复杂度的权衡：put 幂等保证最终一致，status 状态机 + 对账任务兜底硬不一致；若业务对首次可访问性敏感，可增加『命中 status=0 时轮询/挂起』的增强点。"*

------

#### 3.5 一致性与异常处理

##### 3.5.1 四种异常场景

| 场景                             | 结果                     | 处理                     |
| -------------------------------- | ------------------------ | ------------------------ |
| put 成功 + DB 更新成功           | ✅ 正常                   | 无                       |
| put 成功 + DB 更新失败           | ⚠️ SeaweedFS 存在孤儿对象 | 对账任务清理（见 3.5.2） |
| put 失败 + DB 未写入             | ✅ 无副作用               | 返回失败，前端重试       |
| put 失败 + DB 已写入（status=0） | ⚠️ 幽灵记录               | 对账任务清理             |

#### 3.5.2 对账清理任务

```
定时任务（每日 02:00 执行）

Step1：补偿上传
  SELECT * FROM file_meta WHERE status=0 AND create_time < now()-30min
  对每条记录：
    IF SeaweedFS 对象存在 → UPDATE status=1（补偿成功）
    IF 不存在         → UPDATE del_flag=1（标记废弃）

Step2：清理物理存储
  SELECT * FROM file_meta WHERE del_flag=1 AND delete_time < now()-7day
  对每条记录：
    DELETE SeaweedFS 对象（如有）
    DELETE file_meta 记录
```

**设计取舍**：允许短暂"孤儿对象"，不允许"DB 有记录但文件不存在"——后者是硬不一致，前者可通过对账恢复。

------

#### 3.6 安全与隐私约束

##### 3.6.1 去重边界（重要）

| 内容类型                         | 去重范围                 | 原因                   |
| -------------------------------- | ------------------------ | ---------------------- |
| 头像 / 封面 / 正文配图（PUBLIC） | **全局去重**             | 公开内容，无隐私风险   |
| LICENSE / 私有文档（PRIVATE）    | **按 owner_id 隔离去重** | 防止文件存在性探测泄露 |

实现差异仅在查询条件：

```
-- 公开内容：全局去重
WHERE sha256 = ? AND size = ? AND del_flag = 0

-- 私有内容：按 owner 隔离
WHERE sha256 = ? AND size = ? AND del_flag = 0 AND owner_id = ?
```

##### 3.6.2 预签名访问约束

- PRIVATE 文件**禁止**前端直接拼 `bucket + path` 访问
- 必须通过后端鉴权后签发预签名 URL（有效期 ≤ 30 分钟）
- 鉴权链：登录态校验 → owner/ADMIN 权限校验 → 签发 URL

##### 3.6.3 内容安全扫描（预留扩展位）

```
put 成功 → status=1
    → 发送异步消息（fileId）
    → 消费者执行：病毒扫描 / 违规图片检测
    → 通过 → safe_flag=1
    → 不通过 → safe_flag=0，禁止访问，通知管理员
```

当前版本因规模限制未启用，架构上已预留接入点，符合企业合规演进路径。

------

#### 3.7 一句话总结

> 秒传链路由"无锁快查 → 分布式锁双重检查 → 锁内占位 → 锁外幂等上传 → 异步对账补偿"五段构成，在保证并发安全与数据一致性的前提下，实现了存储去重与隐私隔离的双重目标。

------

##### 这份链路相对你原版补齐了什么

| 补齐项                    | 企业价值           |
| ------------------------- | ------------------ |
| sha256 + size 联合判重    | 消除哈希碰撞隐患   |
| 锁 leaseTime + 看门狗     | 防死锁             |
| 锁内/锁外操作边界明确     | 吞吐量与正确性兼顾 |
| 四种异常场景处理          | 一致性有保障       |
| 对账任务完整逻辑          | 孤儿对象可清理     |
| 去重边界（全局 vs owner） | 防隐私泄露         |
| 内容安全扫描预留位        | 合规演进           |

需要我把这份**替换进你原来的完整 6 步流程文档**（作为第 3 步的升级版），输出一份新的完整文档吗？





### 大文件上传+断点续传方案



> 基于Litisle项目基础
> The user is asking conceptual questions:
>
> 1. 企业规范是怎样的？(What's the enterprise standard?)
> 2. 大文件下载的流程是怎样的？(What's the large file download flow?)
> 3. 是否考虑了断点续传？(Is resumable/range download considered?)





#### **大文件下载的企业规范（结论）**：

> 服务端**不代理文件流**；下载走「客户端直连对象存储/CDN」，服务端只签发**带有效期的预签名 URL** 并做鉴权；断点续传由 **HTTP Range** 在对象存储/CDN 侧完成。

Three reasons: 带宽成本、可扩展性、Range 原生支持。

**何时才用服务端代理流式**：必须经过服务端做鉴权/审计/脱敏/水印/防直链（如付费内容、涉密文档），且文件不大或流量可控时。

**大文件上传**的企业规范是分片上传（multipart），下载是 Range 断点续传——两者对称。

Now let me write.
For the download flow:

1. 客户端发起下载意图 → 服务端鉴权（是否可下载该 fileId）
2. 服务端签发预签名 URL（短有效期）或返回直链（PUBLIC）
3. 客户端 HEAD/GET 获取元数据（size、Accept-Ranges、ETag）
4. 分块下载（并发 Range 分片）或顺序下载
5. 中断后：读取本地已下载字节数 offset → 发 `Range: bytes=offset-` → 收 `206` 续传
6. 全部完成 → 校验 size/ETag

Range 响应头：

- 请求：`Range: bytes=0-1023`
- 响应：`206 Partial Content`, `Content-Range: bytes 0-1023/12345678`, `Accept-Ranges: bytes`, `ETag`
- 不支持 Range 的服务器返回 `200` + 全量（客户端需回退）

Let me write it now. And end with the design question re-framed.

Actually, I want to make sure I answer "是否考虑了断点续传" for THIS project. Answer: 当前项目走直连（presign/直链），断点续传由 SeaweedFS S3 网关的 Range 能力天然支持，服务端无需额外实现——前提是客户端发 Range 头。但**若做成服务端代理端点（方案A），则必须自己实现 Range 透传**，否则断点续传失效。这是一个重要取舍点。

Good. Let me write.
好问题——这三点正好决定了该清单项该怎么做。我先讲企业规范，再讲流程与断点续传，最后落回你的项目。

#### 企业规范说明

##### 一、企业规范：大文件下载是"直连"，不是"服务端中转"

企业里文件下载有两套范式，**大文件一律走直连**：

| 范式                     | 做法                                                         | 适用                                                 |
| ------------------------ | ------------------------------------------------------------ | ---------------------------------------------------- |
| **直连范式**（主流规范） | 服务端只**签发带有效期的预签名 URL**；客户端**直连对象存储 / CDN** 拉文件 | 大文件、高并发、绝大多数场景                         |
| **代理范式**             | 文件流经服务端边读边转发（`StreamingResponseBody`）          | 必须经服务端做鉴权/审计/脱敏/水印/防直链，且流量可控 |

选直连的三个硬理由：

1. **不占服务端带宽与线程**——大文件代理会迅速打满连接池/内存，成本极高；
2. **断点续传、并发分片由对象存储/CDN 原生提供**，服务端零成本；
3. **可无缝接 CDN**——CDN 回源只认对象存储，代理模式无法缓存加速。

> 对称地：**大文件上传**的规范是**分片上传（Multipart Upload）**，**下载**的规范是 **Range 断点续传**。两者都落在对象存储侧。

#### 二、大文件下载的标准流程

```
① 客户端请求下载 fileId
② 服务端鉴权（该用户是否有权下载该文件）→ 签发短时效预签名 URL（PRIVATE）
   或直接返回公开直链（PUBLIC）
③ 客户端 HEAD 探测：size / Accept-Ranges / ETag
④ 分块拉取（可并发多个 Range 分片加速）
⑤ 中断 → 读本地已下载字节数 offset → 发 Range: bytes=offset-
⑥ 服务端/对象存储返回 206 续传；全部完成 → 校验 size/ETag 一致
```

关键点：**步骤 ③~⑥ 全部发生在客户端 ↔ 对象存储之间，服务端不参与。**

##### 三、断点续传的协议机制（HTTP Range）

断点续传**不是应用层功能，而是 HTTP 协议能力**，靠这几个头实现：

```
请求：  Range: bytes=1048576-            ← 从第 1MB 处续传
响应：  206 Partial Content
        Content-Range: bytes 1048576-8388607/8388608
        Accept-Ranges: bytes
        ETag: "abc123"                    ← 用于校验文件未变更
```

- 客户端本地记录"已下载字节数"，续传时作为 `Range` 起点；
- 服务器若**不支持 Range**，会返回 `200` + 全量（客户端须回退重下）——所以要先看 `Accept-Ranges`；
- 用 `ETag` 防止"续传期间文件被替换"导致拼接出错。

**两套范式下的实现差异（这是关键取舍）**：

|          | 直连范式                                | 代理范式                                                     |
| -------- | --------------------------------------- | ------------------------------------------------------------ |
| 断点续传 | **对象存储/CDN 原生支持，服务端零代码** | **服务端必须自己实现**：解析 `Range` 头 → 透传给 S3 → 回写 `206`+`Content-Range`，否则断点续传直接失效 |











## Litisle项目-接入层：业务思路与流程说明

> 版本：v1.0 ｜ 日期：2026-09-17 ｜ 适用范围：litisle-file 模块
> 关联文档：[File-Architecture.md](File-Architecture.md) · [Sql-Document.md](Sql-Document.md) §八
> 定位：本文只聚焦 **SeaweedFS 对接层**（client + 配置 + 流程），业务级设计见 File-Architecture.md

---

#### 一、定位与部署拓扑

litisle-file 是 LitIsle 的文件服务模块（端口 8082），**只对接 SeaweedFS Filer 的 S3 兼容网关（8333）**，不直接操作 Master / VolumeServer。

```text
Master×3 (Raft HA) ← Volume Server×3 ← Filer×1 (S3 API :8333) ← litisle-file (AWS SDK v2 / pathStyle)
     │                        │
     └─ replication=001 ──────┘
```

| 组件              | 职责                                                         |
| ----------------- | ------------------------------------------------------------ |
| Filer S3 网关     | 唯一对接入口，提供 S3 兼容 API（上传/删除/预签名）+ 路径语义 |
| Volume Server     | 按 collection 存实际文件，当前副本策略 `replication=001`（单副本） |
| file_meta (MySQL) | 元数据（fileId/sha256/bucket/path/access/status），与对象存储解耦 |

---

#### 二、核心设计思路

##### 2.1 双地址模型（最关键的设计）

| 地址                        | 用途             | 说明                                                         |
| --------------------------- | ---------------- | ------------------------------------------------------------ |
| `seaweedfs.endpoint`        | **SDK 内部地址** | 上传/删除/预签名走这里，指向 `http://{host}:8333`，仅服务间可见 |
| `seaweedfs.public-url-base` | **前端直链基址** | dev 阶段与 endpoint 相同；接 CDN 后改为 CDN 域名（如 `https://img.litisle.com`），endpoint 保持内网不变 |

> 即：**SDK 访问地址与对外访问地址强制分离**，为 CDN 接入预留了无侵入的切换点。

##### 2.2 业务集合（collection）划分

bizType 决定 bucket 归属，一个 bizType 一个 collection：

| BizTypeEnum | collection | 默认 access         | 典型场景                   |
| ----------- | ---------- | ------------------- | -------------------------- |
| AVATAR      | avatar     | PUBLIC              | 头像（CDN 友好）           |
| COVER       | cover      | PUBLIC              | 封面                       |
| ARTICLE_IMG | article    | PUBLIC              | 正文图片                   |
| LICENSE     | license    | **PRIVATE（强制）** | 授权文件，仅预签名 30 分钟 |

##### 2.3 访问级别与凭证模型

- **PUBLIC（直链）**：`publicUrlBase + "/" + bucket + "/" + path`，永久有效，无鉴权，CDN 友好
- **PRIVATE（预签名）**：`S3Presigner` 基于 GetObjectRequest + SigV4 计算，默认 30 分钟，**纯本地签名计算不联网**，不保证对象存在（file_meta 已查即可）
- **凭证**：`StaticCredentialsProvider` 取 `litisle.file.seaweedfs.access-key/secret-key`，与 `docker/init/seaweedfs/s3.json` 的 identity 保持一致；Region 任意（SeaweedFS 不校验）；`pathStyleAccess=true` 必须开启

---

#### 三、核心业务流程

##### 3.1 上传（含秒传去重）— 最复杂链路

```text
客户端 → FileController.upload(file, bizType)
 1. 校验：size ≤ 10MB；MIME 白名单按 bizType 区分
    （AVATAR/COVER/ARTICLE_IMG → image/jpeg|png|webp|gif；LICENSE → 放开授权文件类型如 pdf，审查表 #11）
 2. DigestInputStream 流式算 SHA256（不整读内存）+ 记录 size（联合判重键一部分）
 3. 无锁快查判重：WHERE sha256=? AND size=? AND biz_type=? AND del_flag=0
    （LICENSE/PRIVATE 追加 AND owner_id=? 按 owner 隔离，防跨用户秒传探测泄露）
    走 idx_dedup(sha256, size, biz_type, del_flag)【Sql-Document §八已同步】
    ├─ 命中 → 返回已有 fileId + deduplicated=true（不落 SeaweedFS）
    │   ├─ URL 以「命中记录」的 bucket/path 为准；判重已限定 biz_type，
    │   │   命中记录 bucket 必等于本次请求 bucket，从根上消除跨桶坏链（§3.4.5 eg 决策）
    │   └─ 命中 status=0 在途记录：短暂 404 —— 已知取舍（§3.4.6），put 秒级 + 重试自愈
    └─ 未命中 → Redisson 分布式锁 biz:file:sha:{hash}
        ├─ leaseTime=10s + 看门狗自动续期（防实例宕机死锁）
        ├─ 拿锁后双重检查（防等锁期间其他线程已写入）
        └─ 临界区只包「查 DB + 写 DB」；S3 put 幂等放锁外，不占锁
 4. 锁内：fileId = UUID(无连字符)，path = {yyyy/MM/dd}/{fileId}.{ext}
    → insert file_meta：status=0 占位（上传中）+ ownerId=X-User-Id
    （LICENSE 由服务层强制 access=PRIVATE，DB default 0 仅兜底）→ 释放锁
 5. 锁外：SeaweedFsClient.put(bucket, path, bytes, mime)
 6. put 成功 → ImageIO 解析宽高 → update status=1（上传完成）
    put 失败 → 保留 status=0，由对账任务清理（见 3.4）
 7. 【预留扩展位】内容安全扫描：status=1 后发异步消息（fileId）→ 消费者执行
    病毒扫描 / 违规图片检测 → 通过 → safe_flag=1；
    不通过 → safe_flag=0 禁止访问 + 通知管理员（详见 3.6，设计见 §3.6.3）
    ⚠️ 扫描为异步旁路（先发后审）：扫描期间文件可访问，PUBLIC 类业务可接受；
    若需「先审后发」则上传响应改为 pending、扫描通过后可见——当前规模不启用
```

##### 3.2 URL 获取（access 分流，统一入口）

```text
getUrl(fileId) → 查 file_meta
  ├─ PUBLIC  → publicUrlBase + bucket + path（直链，不走预签名）
  └─ PRIVATE → 校验 owner/ADMIN + bizType 二次校验 → presign(30min)
```

调用方（业务服务/Feign）**无需感知 access 属性**，内部分流。批量场景走 `batchGetUrls` 一次往返避免 N+1。

> 秒传命中文件的 URL 一律以**命中记录**的 bucket/path 为准，与本次请求的 bizType 无关（详见 3.1 / 审查表 #5）。

##### 3.3 预签名签发（纵深防御）

- `GET /{fileId}/url` 对 PRIVATE 自动签发；`POST /{fileId}/presign` 支持显式指定 expireMinutes
- LICENSE 超限时拒绝签发（业务级二次校验，属于纵深防御，不是唯一防线）
- ⚠️ 预签名 URL 一旦签发，持有 URL 者在有效期内均可访问——**URL 即凭证**

##### 3.4 软删除与定时物理清理

```text
DELETE /{fileId}（owner/ADMIN 校验）
  → del_flag=1（软删，7 天缓冲，防误删不可恢复）

对账任务 @Scheduled 每日 02:00（多实例需 Redisson 分布式锁保证单实例执行，审查表 #7）：
Step1 补偿上传：扫 status=0 且超 30min
  → S3 head 对象存在 → update status=1（补偿成功）
  → 不存在 → update del_flag=1（标记废弃）
Step2 物理清理：扫 del_flag=1 且超 7 天
  → 删 SeaweedFS 本体（含缩略图派生 key _thumb_*）→ 物理删元数据
```

##### 3.5 缩略图按需生成

- 派生 key 约定：原图 path + `_thumb_{w}_{h}`（**不落库**，避免多尺寸字段爆炸）
- head 探测存在 → 直接返回直链；不存在 → get 原图 → resize → put 回同 bucket
- 并发重复生成无妨（put 幂等，不加锁）

##### 3.6 内容安全扫描（预留扩展位，设计详图见 §3.6.3）

- **触发点**：上传链路 status=1 后发异步消息（fileId），主链路零阻塞——扫描不通过不回滚上传，仅置 safe_flag=0
- **处理动作**：病毒扫描（ClamAV）+ 违规图片检测（云厂商内容安全 API）
- **访问闸口**：getUrl / batchGetUrls 返回前校验 safe_flag，=0 则拒绝并提示违规（单点收口，不散落在各业务）
- **接入路径**：litisle-file 引 MQ 依赖（RocketMQ）+ 新增 ScanResultConsumer；表结构仅需 file_meta 加 safe_flag 列，SeaweedFsClient 与秒传链路零改动
- **当前不启用原因**：个人实习项目无合规强约束，引入 MQ 的运维成本 > 收益；但消息发起点（status=1 后）与校验点（getUrl 前置）已在架构上预留，演进零侵入

---

#### 四、配置与凭证映射

| 配置项                     | 位置                          | 当前值                             | 说明                  |
| -------------------------- | ----------------------------- | ---------------------------------- | --------------------- |
| endpoint / public-url-base | application.yml               | `http://localhost:8333`            | 双地址模型            |
| access-key / secret-key    | application.yml               | `${LITISLE_S3_ACCESS_KEY:litisle}` | 默认值即 s3.json 明文 |
| presign-expire-minutes     | application.yml               | 30                                 | 私有文件时效          |
| max-image-size             | application.yml               | 10485760 (10MB)                    | 上传上限              |
| thumbnail width/height     | application.yml               | 300×300                            | 缩略图默认尺寸        |
| s3.json identity           | docker/init/seaweedfs/s3.json | litisle / litisle_s3_pass          | Admin+Read+Write      |

---

#### 五、企业规范符合性审查（不符合项与提示 ⚠️）

| #    | 不符合项                                                     | 风险                                                         | 建议                                                         |
| ---- | ------------------------------------------------------------ | ------------------------------------------------------------ | ------------------------------------------------------------ |
| 1    | **凭证明文硬编码**：`s3.json` 与 yml 默认值写死 `litisle/litisle_s3_pass` | 仓库泄露即存储被接管（Admin 权限）                           | 生产必须环境变量覆盖并**强制首次变更**；密钥托管 Vault/KMS；建立轮换机制 |
| 2    | **单副本策略 `replication=001`**                             | 卷损坏/节点宕机即丢数据，无容灾                              | 至少 `replication=010`（同机双副本）或跨机 `100`；license 等敏感 collection 单独调高 |
| 3    | **Filer 单点**（拓扑中 Filer×1）                             | Filer 故障 = 文件服务整体不可用                              | 企业生产建议 Filer 集群（或至少主备 + 健康检查）             |
| 4    | **endpoint 明文 HTTP**                                       | 内网穿透/旁路窃听可抓包上传内容与凭证                        | 生产走 HTTPS/TLS，或至少内网 VPC 隔离                        |
| 5    | **秒传去重跨 bizType 的 bucket 归属歧义**：sha256 命中直接复用 fileId，但 URL 必须取**已有记录**的 bucket/path 拼接，不能用本次 bizType 的映射 | 若按本次 bizType 拼 URL，会出现对象 key 与 bucket 不匹配的坏链 | 秒传返回时强制以命中记录的 bucket/path 为准；或 sha256 查询限定 (sha256, biz_type) |
| 6    | **getUrl 与 presign 权限口径不一致**：Controller 注释 getUrl 仅"登录"，而 Service 注释 PRIVATE 需 owner/ADMIN | PRIVATE 文件可能被任意登录用户拿到预签名 URL 并转发          | 统一口径：PRIVATE 一律 owner/ADMIN 校验（预签名 URL 本质可传播，入口必须收紧） |
| 7    | **定时清理任务分布式重复执行**：多实例部署时 @Scheduled 会重复触发物理删除 | 重复删除/并发删同一对象（put 幂等但 delete 不幂等语义需确认） | 用 Redisson 分布式锁保证单实例执行（项目已有该依赖）         |
| 8    | **无一致性补偿**：S3 put 成功但 DB 写失败 → 孤儿对象         | 数据泄漏 + 存储浪费（秒传去重下还会长期占位）                | 增加定时对账任务（DB 有 meta 无对象 / 对象无 meta），或 TTL/回收桶 |
| 9    | **预签名 URL 传播风险**：30 分钟时效 + 无访问审计            | URL 泄露可被转发下载敏感 LICENSE 文件                        | 缩短时效（如 5-10 分钟）、按文件限次、记录签发与访问审计日志 |
| 10   | **缺操作审计**：上传/删除/预签名无审计日志                   | 无法追溯数据泄露/误删责任                                    | 关键操作（LICENSE 上传、删除、预签名）落审计日志（含操作者、IP、fileId） |
| 11   | **MIME 白名单与 LICENSE 冲突**：白名单仅图片类型，若 LICENSE 为 PDF/其他格式会被误拦 | 业务文件上传失败                                             | 白名单按 bizType 区分：图片类限图片，LICENSE 放开至授权文件类型 |
| 12   | **CORS 未配置**：前端若用 fetch/Canvas 直读图片直链会跨域失败 | 浏览器端功能异常                                             | Filer S3 网关配置 CORS，或经网关/业务层代理                  |
| 13   | **SigV4 Region 硬编码 us-east-1**                            | 换 MinIO/真实 S3 时需改动（当前 SeaweedFS 无碍）             | 抽到配置项，为存储替换留口                                   |

> 已符合规范的部分（可放心）：双地址分离、LICENSE 强制私有 + 纵深防御、SHA256 流式计算 + 秒传分布式锁 + 双重检查、软删除缓冲、批量查避免 N+1、S3 异常统一转 BizException 不向业务层泄漏 SDK 类型、ownerId 取自网关 X-User-Id 不信任请求参数。
>
> **审查状态更新（2026-09-17 代码注释与 SQL 同步后）**：
>
> - **已闭环**：#5（判重键限定 biz_type，idx_dedup 落地 Sql-Document §八）、#6（getUrl 对 PRIVATE 统一 owner/ADMIN）、#8（status 状态机 + 对账任务两步补偿）、#11（MIME 白名单按 bizType 区分）——均已同步至 FileServiceImpl / FileController / FileMeta / FileMetaMapper 注释
>
> - **暂不优化项及原因**（个人实习项目定位取舍）：
>
>   | #                | 暂不优化原因                                                 |
>   | ---------------- | ------------------------------------------------------------ |
>   | 1 凭证明文       | 本地/dev 无真实资产风险；生产用 LITISLE_S3_ACCESS_KEY/SECRET_KEY 环境变量覆盖，代码已预留 |
>   | 2 单副本         | docker-compose 单机部署，多副本需多节点，超出当前基础设施；上线前随部署架构调整 |
>   | 3 Filer 单点     | 属部署层改造与代码无关，单机实习项目可接受                   |
>   | 4 HTTP 明文      | 内网 localhost 通信无窃听面；生产随 HTTPS/VPC 一并处理       |
>   | 9 预签名无审计   | 个人项目无多租户合规压力；签发入口集中在 Service 层，后续补审计只需一处埋点 |
>   | 10 操作审计      | 合规等级低于核心链路正确性，排期靠后                         |
>   | 12 CORS          | 前端当前经网关代理同源访问，未直读直链；接 CDN 时一并配置    |
>   | 13 Region 硬编码 | SeaweedFS 不校验 Region；实现 SeaweedFsClient 时抽配置项即可（实现期顺手项） |

---

#### 六、结论

整体设计在企业规范上属于**中上水准**：解耦（SeaweedFS 唯一接触点在 SeaweedFsClient）、性能（秒传/批量/流式哈希）、安全（双地址/预签名/强制私有）都已考虑。

**代码与设计层问题已全部闭环**：#5（判重键限定 biz_type）、#6（PRIVATE 权限口径统一）、#8（status 状态机 + 对账补偿）、#11（MIME 按 bizType 区分）。

**上线前仅需处理部署层三项**：#1（凭证环境变量覆盖）、#2（副本策略调高）、#3（Filer 高可用）；#4/#9/#10/#12/#13 属低风险遗留（原因见审查状态更新表），按里程碑择机处理。

**内容安全扫描**：架构已预留扩展位（见 3.1 第 7 步 / 3.6 / §3.6.3），启用仅需 file_meta 加 safe_flag 列 + MQ 异步消费，主链路零改动。





下面给你一份**可以直接放进文档的、符合企业规范的"秒传完整链路"**。我按「流程图 → 伪代码 → 异常处理 → 一致性保证 → 安全约束」五段式来写，逻辑闭环，没有歧义。

------