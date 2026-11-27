---
title: 云食册 Android 下载
description: 获取云食册 Android 官方 APK，了解安装、覆盖升级和反馈方式。
---

<script setup lang="ts">
import { data as release } from '../.vitepress/theme/android-download.data'
</script>

# 云食册 Android 下载

<div v-if="release">
  <p><strong><a :href="release.url" download>下载官方 APK</a></strong></p>
  <p>版本 {{ release.versionName }}（{{ release.versionCode }}），需要 Android 8.0 或更新版本。</p>
  <details>
    <summary>安装包 SHA-256 校验值</summary>
    <p style="overflow-wrap: anywhere"><code>{{ release.sha256 }}</code></p>
  </details>
</div>
<p v-else>Android 安装包正在准备中。你可以先<a href="https://cook.yunyoujun.cn/">使用网页版</a>。</p>

## 安装与升级

1. 在手机浏览器下载 APK，从浏览器的下载记录或文件管理器打开。若系统提示需要授权，请在系统设置中允许当前的浏览器或文件管理器安装应用。
2. 已安装本版云食册时，下载新版直接覆盖安装。更新前可在 App 中导出重要备份。若系统提示签名不一致，请先保留原应用和数据并联系支持。
3. 旧版「食用手册」与新版云食册可以同时安装。需要保留旧版收藏与历史时，先保留原应用，再联系开发者协助确认可用的迁移方式。

安装或使用遇到问题，可以从「我的 → 帮助与反馈」提交，或通过[云乐坊支持中心](https://support.yunle.fun/contact?product=cook&source=cook-website&intent=feedback)联系开发者。请附上手机型号、系统和应用版本。

[Android 隐私政策](/privacy-android) · [用户协议](/terms)
