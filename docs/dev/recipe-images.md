# 图文做法配图

云食册的图文做法正文打包在 App 里（`packages/data/recipe-text.json`），配图放在腾讯云 COS 上按需加载，仓库里不放图片。

- 桶：`yunlefun-assets-prod-1325586649`（ap-shanghai）
- 前缀：`cook/howtocook/`
- 访问地址：`https://assets.yunle.fun/cook/`（`recipe-text.json` 的 `imageBase`，已接入 EdgeOne）
- COS 源站：`https://yunlefun-assets-prod-1325586649.cos.ap-shanghai.myqcloud.com/cook/`

App 通过 EdgeOne 加载配图，EdgeOne 使用私有回源访问这个桶。桶的 ACL 保持私有，只给 `cook/*` 增加公开读策略，允许 `GetObject`、`HeadObject`，也方便直接验证源站。其他前缀沿用已有策略。

2026-10-20 已通过 COS API 核实地域为 `ap-shanghai`，并通过 EdgeOne API 确认 `assets.yunle.fun` 已上线、源站为上述桶。

## 为什么用这个桶

`yunlefun-assets-prod-1325586649` 是云乐坊各项目共用的公开素材桶，每个项目占一个前缀，Cook 用 `cook/`。同一账号（APPID 1325586649）下的其他桶，用途见 [YunLeFun/www.yunle.fun](https://github.com/YunLeFun/www.yunle.fun) 的 `cloudbaserc.json`，都不适合放 App 配图：

| 桶                                 | 用途                                                |
| ---------------------------------- | --------------------------------------------------- |
| `yunlefun-private-1325586649`      | 用户上传的私有文件                                  |
| `yunlefun-drive-prod-1325586649`   | 云盘私有存储                                        |
| `yunyoujun-assets-prod-1325586649` | 云盘公开分享的文件（`https://assets.yunyoujun.cn`） |

另一个账号的 `yunlefun-cos-1253292018` 放着 [YunLeFun/icons](https://github.com/YunLeFun/icons) 的应用图标（`desktop/app-icons/`），也没有用。

## 配图怎么生成

`pnpm howtocook <HowToCook 目录>` 解析正文时把原文配图：

- 按 EXIF 转正，缩到最宽 1080，压成 webp（质量 75）；203 张原图约 100 MB，压完约 13 MB；
- 用原图内容的 sha256 前 16 位命名，例如 `howtocook/8395e4f749720658.webp`；同一张图在哪台机器导入地址都一样，原图换了就是新地址；
- 写到 `.cache/recipe-images/`（不进仓库），正文里记相对路径和压缩后的宽高。

外链图片（目前 2 张）不收录，来源不稳定，也没法设缓存。

## 上传

合并到 main 后，`Recipe Images` 工作流（`.github/workflows/recipe-images.yml`）在 `recipe-text.json` 变化时自动运行：按 `source.commit` 取 HowToCook 的同一个提交，重新生成配图，检查 `recipe-text.json` 没有变化，再上传。也可以在 Actions 页手动运行。

上传规则（`scripts/upload-recipe-images.ts`）：

- 已经存在的对象直接跳过，文件名按内容命名，不会覆盖成别的图；
- 不删除旧图，旧版本 App 还可能在用；
- `Content-Type: image/webp`，`Cache-Control: public, max-age=31536000, immutable`。

### 需要配置的 Secrets

在 cook 仓库的 Settings → Secrets and variables → Actions 里加：

| 名称             | 说明                     |
| ---------------- | ------------------------ |
| `COS_SECRET_ID`  | 上传用子账号的 SecretId  |
| `COS_SECRET_KEY` | 上传用子账号的 SecretKey |

没有配置时工作流只提示一句、不上传，不会让 main 变红。桶、地域、前缀默认就是上面的值，换桶时再加 Variables：`COS_BUCKET`、`COS_REGION`、`COS_PREFIX`。

子账号建议只给这个前缀的上传和查询权限（访问管理 → 用户 → 新建子用户，关联下面的自定义策略）：

```json
{
  "version": "2.0",
  "statement": [
    {
      "effect": "allow",
      "action": ["name/cos:PutObject", "name/cos:HeadObject"],
      "resource": ["qcs::cos:ap-shanghai:uid/1325586649:yunlefun-assets-prod-1325586649/cook/*"]
    }
  ]
}
```

生产上传子账号为 `cook-recipe-images`，不允许控制台登录，使用上面的策略。密钥保存在仓库 Actions Secrets 中。

本地手动上传：把同样的变量写进 `.env`（见 `.env.example`），先 `pnpm howtocook ../HowToCook`，再 `pnpm images:upload`。

## App 里怎么显示

- 地址是 `imageBase + src`；加载前按宽高比例占位，页面不跳动；
- 加载失败就收起那张图，不影响看文字；
- 竖图限制高度不超过 480pt，图片带圆角和细描边；原文里有意义的图片说明（如「牛腩焯水」）显示在图下，只是文件名或菜名的（「XX-预览图-1」「XX成品」）和上一段正文已经写了的（「碗 1」「改刀」）不显示，但仍给旁白读；
- 点图片全屏查看：左右滑动切换同一篇里的配图，双指或双击放大，轻点隐藏按钮，右上角可以分享或存到相册；iOS 18 起从原图位置放大展开、下滑收回，旧系统用关闭按钮；
- 数据里没有 `imageBase` 时不显示配图，页底提示「原文还有 N 张配图」。

## iPad 保存到相册验收

2026-11-18 在远端 `main` 的 `7cbb8b6ba` 上复核。#117 的 iPad 记录停在打开系统分享面板。#209 的 `testReviewedTextSurvivesImageFailureAndLoadsAfterRecovery` 覆盖配图加载恢复。复核时，查看器仍通过 `ShareLink` 分享当前配图，工程配置仍声明仅添加照片的权限说明。见 [#117](https://github.com/YunYouJun/cook/pull/117) 和 [#209](https://github.com/YunYouJun/cook/pull/209)。

- 使用共享池的 iPad Pro 11 英寸（M5）、iPadOS 27.0（24A434）和 Xcode 27.1。Debug 测试包使用独立标识和 `-ui-testing` 内存数据。通过 `COOK_UI_TEST_TEXT_IMAGE_BASE` 从本机回环地址提供「柱候牛腩」的两张公开源图。
- 本地 XCUITest 从「看图文做法」点开第二张图，确认查看器显示 `2 / 16`，点右上角的「分享」，选择系统保存图像动作，在系统权限提示中点「允许」，随后返回查看器。最终执行 1 项，通过 1 项。先前一次运行中，分享弹窗容器出现后，XCTest 在等待窗口内未找到保存动作，同一构建重跑完成了全流程。
- 只读核对 iPad 的照片图库记录和原始文件。图库新增一张 1080 × 810 的 PNG，原有 6 条图库记录和对应文件路径仍在。新增文件与第二张源图解码为 RGB 后，SHA-256 均为 `ec8cac323e444d462dceef30459c6fcaa45a8e299fb5f6ba5f02757e7f0bfeee`。见[第二张源图](https://assets.yunle.fun/cook/howtocook/1d21e2cdde42c3e7.webp)。
- 运行覆盖为 iPadOS 27.0。项目最低部署目标为 iOS 17.0，本机没有对应运行时。

## 换地址

以后给桶配 CDN 或自定义域名，只要重新生成数据：

```bash
COOK_IMAGE_BASE=https://<新域名>/cook/ pnpm howtocook ../HowToCook
```

文件名不变，不用重新上传。旧版本 App 内置的是旧地址，所以 COS 默认域名要继续可以访问。
