# 网页版域名与原有数据

云食册的产品官网和文档位于 <https://cook.yunle.fun/>；食用手册网页版继续位于 <https://cook.yunyoujun.cn/>。官网提供产品介绍、iOS 发布信息和使用指南，点击「打开网页版」即可开始找菜。

## 以前在 cook.yunle.fun 保存的内容

两个域名的浏览器本机存储相互独立。收藏、浏览记录、私人菜谱和个人食材厨具不会因为官网改版自动迁移；使用同一个账号也不代表这些本机内容已经同步。

如果你以前在 `cook.yunle.fun` 使用过网页版，请在**原来的浏览器和设备**打开下面的入口：

- <a class="vp-raw" href="https://cook.yunle.fun/home" target="_self">打开原域名网页版</a>：查看保存在该域名的记录。
- <a class="vp-raw" href="https://cook.yunle.fun/settings" target="_self">打开原域名设置</a>：使用页面提供的导出功能保存备份。

请先导出，再到 <a class="vp-raw" href="https://cook.yunyoujun.cn/settings" target="_self">新版网页版设置</a>使用相应导入功能，并核对结果。私人菜谱和个人食材厨具的迁移范围见[私人菜谱与文件迁移](/dev/private-recipes)。各类文件的格式和覆盖规则以导入页面说明为准。

官网改版不会删除浏览器里的既有数据。迁移完成前，请保留导出文件，并避免清除原域名的浏览器数据。不同浏览器、无痕窗口或另一台设备不一定有原来的记录。

## 登录和链接

旧域名的登录回调和常用应用路径继续保留，以便完成已有登录流程和访问旧记录。新用户从官网进入 `cook.yunyoujun.cn` 使用网页版即可。

使用指南、隐私政策等文档统一使用 `cook.yunle.fun`。原文档地址 `docs.cook.yunle.fun` 已通过保留路径与查询参数的永久重定向兼容旧链接，部署与验收记录见[文档站部署](/dev/docs-site)。iOS 版的本机 / iCloud 数据与网页版数据分别保存，官网域名调整不会自动上传或迁移它们。
