# 图文做法配图

云食册的图文做法正文打包在 App 里（`packages/data/recipe-text.json`），配图放在腾讯云 COS 上按需加载，仓库里不放图片。

- 桶：`yunlefun-cos-1253292018`（ap-shanghai，公有读）
- 前缀：`cook/howtocook/`
- 访问地址：`https://yunlefun-cos-1253292018.cos.ap-shanghai.myqcloud.com/cook/`（`recipe-text.json` 的 `imageBase`）

## 为什么用这个桶

云乐坊各项目的公开素材都放在这个桶里，按项目分前缀，直接用 COS 默认域名访问：

| 前缀                 | 用途                                  | 参考                                                                                 |
| -------------------- | ------------------------------------- | ------------------------------------------------------------------------------------ |
| `desktop/app-icons/` | 应用图标（含 Cook 的 `v2/cook.webp`） | [YunLeFun/icons](https://github.com/YunLeFun/icons) `assets/catalog/app-assets.json` |
| `saier/app-icons/`   | 赛尔网站图标                          | 同上                                                                                 |
| `cook/`              | Cook 图文做法配图                     | 本文                                                                                 |

[YunLeFun/www.yunle.fun](https://github.com/YunLeFun/www.yunle.fun) 的 `cloudbaserc.json` 里还有几个桶，都不适合放公开素材：`yunlefun-private-1325586649`、`yunlefun-drive-prod-1325586649` 是私有存储，`yunyoujun-assets-prod-1325586649`（`https://assets.yunyoujun.cn`）是云盘的公开分享文件。

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
      "resource": ["qcs::cos:ap-shanghai:uid/1253292018:yunlefun-cos-1253292018/cook/*"]
    }
  ]
}
```

本地手动上传：把同样的变量写进 `.env`（见 `.env.example`），先 `pnpm howtocook ../HowToCook`，再 `pnpm images:upload`。

## App 里怎么显示

- 地址是 `imageBase + src`；加载前按宽高比例占位，页面不跳动；
- 加载失败就收起那张图，不影响看文字；
- 数据里没有 `imageBase` 时不显示配图，页底提示「原文还有 N 张配图」。

## 换地址

以后给桶配 CDN 或自定义域名，只要重新生成数据：

```bash
COOK_IMAGE_BASE=https://<新域名>/cook/ pnpm howtocook ../HowToCook
```

文件名不变，不用重新上传。旧版本 App 内置的是旧地址，所以 COS 默认域名要继续可以访问。
