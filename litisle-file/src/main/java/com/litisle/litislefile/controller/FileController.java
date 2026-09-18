package com.litisle.litislefile.controller;

/**
 * REST 接口层（路径前缀 /api/file，Knife4j 已扫本包）：
 *
 * | 方法   | 路径                 | 说明                                        | 权限            |
 * |--------|---------------------|---------------------------------------------|-----------------|
 * | POST   | /upload             | 单文件上传（multipart: file + bizType）      | 登录            |
 * | POST   | /upload/batch       | 批量上传（正文多图，上限 9 张）               | 登录            |
 * | GET    | /{fileId}/url       | 取 URL：PUBLIC→直链；PRIVATE→预签名(30min)   | 登录(PUBLIC)；PRIVATE 需 owner/ADMIN |
 * | POST   | /{fileId}/presign   | 强制预签名（expireMinutes ≤ 配置上限，二次校验） | owner/ADMIN     |
 * | GET    | /{fileId}/meta      | 元数据（mime/size/宽高/align）                | 登录            |
 * | POST   | /{fileId}/thumbnail | 缩略图 URL（w/h 可选，默认 yml 配置）         | 登录            |
 * | DELETE | /{fileId}           | 软删除 del_flag=1（7 天后定时任务物理清理）   | owner/ADMIN     |
 *
 * 要点：
 * - ownerId 统一从网关注入的 X-User-Id Header 取（HeaderConstant），不信任请求参数
 * - 权限口径统一：PRIVATE 文件取 URL 与 presign 一致，一律 owner/ADMIN 校验——
 *   预签名 URL 本质是可传播凭证，入口必须收紧（否则任意登录用户可拿 URL 转发，见审查表 #6）
 * - expireMinutes 必须校验 ≤ 配置上限（presign-expire-minutes），防传 99999 绕过 LICENSE 限时
 * - MIME 白名单按 bizType 区分：图片类限 image/jpeg|png|webp|gif；LICENSE 放开授权文件类型
 * - 所有返回 Result<T>；错误码见 File-Architecture.md §六
 * - presign/DELETE 需校验 owner 或 ADMIN，否则 BizException(NO_PERMISSION)
 */
public class FileController {
}
