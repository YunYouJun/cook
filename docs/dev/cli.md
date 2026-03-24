# Cook CLI

Cook CLI 是一个命令行工具,用于管理菜谱数据。

## 安装

项目依赖已在 workspace 中配置,无需单独安装。

## 环境配置

### 数据源

菜谱数据存储在飞书 Wiki 电子表格中：

- [菜谱数据表格（飞书）](https://yunlefun.feishu.cn/wiki/KgxowvnB9iM91AkZEJHcNGoQnPg?sheet=X4j9Bn)

### 配置飞书应用

1. 复制环境变量模板:

   ```bash
   cp .env.example .env
   ```

2. 填写飞书开放平台应用凭证:

   ```bash
   # .env
   FEISHU_APP_ID=cli_xxxxxxxxxxxxxxxx
   FEISHU_APP_SECRET=xxxxxxxxxxxxxxxxxxxxxxxx
   ```

3. 获取飞书凭证:
   - 访问 [飞书开放平台](https://open.feishu.cn/app)
   - 创建或选择你的应用
   - 在"凭证与基础信息"中获取 `App ID` 和 `App Secret`

::: tip
`.env` 文件已被 `.gitignore` 忽略,不会被提交到 Git。
:::

## 命令

### fetch

从飞书拉取菜谱数据并生成 CSV + JSON 文件。

```bash
pnpm fetch
```

**功能:**

- 从飞书 Wiki 获取电子表格数据
- 解析并清洗数据
- 生成 `app/data/recipe.csv`
- 生成 `app/data/recipe.json`

**输出示例:**

```
✔ 从飞书 Wiki 获取电子表格 token...
✔ 读取到 600 行数据 (含表头)
✔ 解析出 599 条菜谱
✔ 写入 app/data/recipe.csv
✔ 写入 app/data/recipe.json
```

::: warning 注意
此命令会**覆盖**现有的 `recipe.csv` 和 `recipe.json` 文件。
:::

### convert

将本地 CSV 数据转换为 JSON 格式。

```bash
pnpm convert
```

**功能:**

- 读取 `app/data/recipe.csv`
- 转换为 JSON 格式
- 生成 `app/data/recipe.json`

**使用场景:**

- 手动编辑 CSV 后需要重新生成 JSON
- 确保 CSV 和 JSON 数据同步

## 工作流程

### 更新菜谱数据

```bash
# 1. 从飞书拉取最新数据
pnpm fetch

# 2. 启动开发服务器查看效果
pnpm dev
```

### 手动编辑数据

```bash
# 1. 编辑 app/data/recipe.csv

# 2. 转换为 JSON
pnpm convert

# 3. 查看效果
pnpm dev
```

## 数据格式

### CSV 格式

```csv
名称,别名,类别,主料,辅料,调料,做法,难度,耗时,口味,图片,视频
宫保鸡丁,,,鸡胸肉,花生米,干辣椒,炒,简单,30分钟,麻辣,https://...,https://...
```

### JSON 格式

```json
[
  {
    "名称": "宫保鸡丁",
    "别名": "",
    "类别": "",
    "主料": "鸡胸肉",
    "辅料": "花生米",
    "调料": "干辣椒",
    "做法": "炒",
    "难度": "简单",
    "耗时": "30分钟",
    "口味": "麻辣",
    "图片": "https://...",
    "视频": "https://..."
  }
]
```

## 故障排查

### 飞书 API 调用失败

**错误信息:**

```
Error: Feishu API failed with code 99991663
```

**解决方案:**

1. 检查 `.env` 文件是否存在
2. 确认 `FEISHU_APP_ID` 和 `FEISHU_APP_SECRET` 是否正确
3. 验证应用是否有访问 Wiki 的权限
4. 检查飞书 Wiki 链接是否正确配置在代码中

### CSV 解析错误

**错误信息:**

```
Error: Failed to parse CSV
```

**解决方案:**

1. 检查 CSV 文件格式是否正确
2. 确认字段分隔符为逗号
3. 检查是否有未闭合的引号
4. 验证编码是否为 UTF-8

## 相关文档

- [飞书开放平台文档](https://open.feishu.cn/document)
- [开发指南](/dev/app)
- [使用说明](/guide/getting-started)

## 测试

### 运行测试

```bash
# 运行所有测试
pnpm test

# 运行 CLI 相关测试
pnpm test packages/cook

# 运行特定测试文件
pnpm test packages/cook/src/utils/csv.test.ts
```

### 测试覆盖

CLI 工具包含以下测试：

- **CSV 解析测试** (`csv.test.ts`)
  - ✅ BV 号清洗和 URL 处理
  - ✅ CSV 格式解析
  - ✅ 空值和边界情况处理
  - ✅ 数组字段过滤空值
  - ✅ CSV 往返一致性（parse → stringify → parse）
  - ✅ 与 fetch 命令输出格式一致性

测试确保 `fetch` 和 `convert` 命令产生完全一致的数据格式。
