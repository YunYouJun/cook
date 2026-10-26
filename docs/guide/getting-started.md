# 快速开始

欢迎使用 Cook! 这是一个帮助你决定"今天吃什么"的应用。

## 环境准备

### 系统要求

- Node.js >= 22
- pnpm >= 11.21

### 安装依赖

```bash
# 克隆项目
git clone https://github.com/YunYouJun/cook.git
cd cook

# 安装依赖
pnpm install
```

## 开发

### 启动开发服务器

```bash
pnpm dev
```

访问 <http://localhost:3000> 查看应用。

### 数据管理

菜谱数据存储在飞书 Wiki 电子表格中：[菜谱数据表格](https://yunlefun.feishu.cn/wiki/KgxowvnB9iM91AkZEJHcNGoQnPg?sheet=X4j9Bn)

#### 配置飞书应用

如果需要从飞书拉取菜谱数据,请先配置环境变量:

```bash
# 1. 复制环境变量模板
cp .env.example .env

# 2. 编辑 .env 文件
# FEISHU_APP_ID=cli_xxxxxxxxxxxxxxxx
# FEISHU_APP_SECRET=xxxxxxxxxxxxxxxxxxxxxxxx
```

获取飞书凭证:

1. 访问 [飞书开放平台](https://open.feishu.cn/app)
2. 创建或选择应用
3. 复制 App ID 和 App Secret

#### 更新菜谱数据

```bash
# 从飞书拉取最新数据
pnpm fetch

# 或手动编辑 packages/data/recipe.csv 后转换为 JSON
pnpm convert
```

详细的 CLI 使用说明请查看 [Cook CLI 文档](/dev/cli)。

## 构建

### Web 应用

```bash
# 构建生产版本
pnpm build

# 预览构建结果
pnpm preview
```

### 移动应用

原生 iOS 位于 `apps/ios`，使用 SwiftUI。需要 Xcode 和 XcodeGen：

```bash
pnpm convert
xcodegen generate --spec apps/ios/project.yml
open apps/ios/Cook.xcodeproj
```

完整运行、签名和测试说明见 [iOS README](https://github.com/YunYouJun/cook/blob/main/apps/ios/README.md)。

历史 Capacitor 工程及构建命令已移除；原生 Android 计划使用 Kotlin + Jetpack Compose，尚未创建工程。技术栈和分阶段待办见 [Android 开发计划](/dev/android-roadmap)。

## 项目结构

```text
cook/
├── apps/
│   ├── web/            # Nuxt + Ionic 网页与静态资源
│   └── ios/            # 原生 SwiftUI 应用与 CookCore
├── docs/               # 产品官网与开发文档
├── packages/
│   ├── data/           # 共享 CSV、JSON、Schema 与测试用例
│   └── cook/           # TypeScript 核心与 CLI
├── cloudfunctions/     # 反馈与数据同步服务
└── scripts/            # 构建与数据工具
```

未来的原生 Android 工程放在 `apps/android`。

## 下一步

- 📖 查看 [CLI 文档](/dev/cli) 了解数据管理
- 📱 查看 [移动应用开发](/dev/app) 了解原生客户端与 Web 的分工
- 🍳 开始添加你喜欢的菜谱!
