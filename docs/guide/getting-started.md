# 快速开始

欢迎使用 Cook! 这是一个帮助你决定"今天吃什么"的应用。

## 环境准备

### 系统要求

- Node.js >= 18
- pnpm >= 8

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

# 或手动编辑 app/data/recipe.csv 后转换为 JSON
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

#### iOS

```bash
# 构建 iOS 应用
pnpm build:ios

# 在 Xcode 中打开
open ios/App/App.xcworkspace
```

#### Android

```bash
# 构建 Android 应用
pnpm build:android

# 在 Android Studio 中打开
# File > Open > android/
```

## 项目结构

```
cook/
├── app/                # 应用源码
│   ├── components/     # Vue 组件
│   ├── composables/    # 组合式函数
│   ├── data/           # 菜谱数据 (CSV + JSON)
│   ├── pages/          # 页面路由
│   └── utils/          # 工具函数
├── docs/               # 文档网站
├── packages/
│   └── cook/           # CLI 工具
├── android/            # Android 应用
├── ios/                # iOS 应用
└── public/             # 静态资源
```

## 下一步

- 📖 查看 [CLI 文档](/dev/cli) 了解数据管理
- 📱 查看 [移动应用开发](/dev/app) 了解跨平台构建
- 🍳 开始添加你喜欢的菜谱!
