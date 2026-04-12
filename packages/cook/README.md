# @yunyoujun/cook

Cook CLI 工具 - 用于管理菜谱数据和检索菜谱的命令行工具。

## 功能

- 🔍 按食材/厨具/难度/标签/做法检索菜谱（支持食材别名）
- 🚀 从飞书 Wiki 拉取菜谱数据
- 📝 CSV ↔ JSON 双向转换
- ✅ 完整的单元测试覆盖
- 🔄 数据格式一致性保证

## 命令

### search

检索菜谱（支持 AI Skill 调用）：

```bash
# 按食材搜索
pnpm search --stuff "鸡蛋,番茄"

# 组合筛选
pnpm search --stuff "鸡蛋,番茄" --tool "电饭煲" --difficulty "简单"

# 支持别名（西红柿 → 番茄）
pnpm search --stuff "鸡蛋,西红柿"
```

参数：

- `--stuff <items>`: 食材，逗号/顿号分隔
- `--tool <tool>`: 厨具（电饭煲/烤箱/空气炸锅/微波炉/一口大锅）
- `--difficulty <level>`: 难度（简单/普通/困难）
- `--tag <tag>`: 标签（懒人/下饭/减脂 等）
- `--method <method>`: 做法（炒/煎/蒸/煮/烤/炸 等）
- `--limit <n>`: 最大返回数（默认 10）

### fetch

从飞书 Wiki 拉取最新菜谱数据：

```bash
pnpm fetch
```

功能：

- 获取飞书电子表格数据（API 返回二维数组）
- 自动清洗和格式化
- 使用 papaparse 生成标准 CSV（自动处理特殊字符）
- 同时生成 CSV 和 JSON 文件

**为什么不直接下载 CSV？**

- 飞书 API 没有提供直接导出 CSV 的接口
- API 返回的是原始表格数据（二维数组），需要转换为 CSV
- 使用 papaparse 确保生成的 CSV 符合标准，能正确处理字段中的逗号、引号、换行符等特殊字符

### convert

将本地 CSV 转换为 JSON：

```bash
pnpm convert
```

功能：

- 读取 `app/data/recipe.csv`
- 解析并验证数据
- 生成 `app/data/recipe.json`
- 更新 `lastDbUpdated` 时间戳

## 数据格式

### CSV 结构

```csv
name,stuff,bv,difficulty,tags,methods,tools,
宫保鸡丁,鸡胸肉、花生米,BV1234567890,简单,川菜、家常菜,炒、煎,炒锅,
```

字段说明：

- `name`: 菜名（必填）
- `stuff`: 食材列表（用 `、` 分隔）
- `bv`: B站视频 BV 号
- `difficulty`: 难度（简单/普通/困难）
- `tags`: 标签列表（用 `、` 分隔）
- `methods`: 烹饪方式（炒/煎/烘/炸，用 `、` 分隔）
- `tools`: 所需工具（用 `、` 分隔）

### JSON 结构

```json
{
  "name": "宫保鸡丁",
  "stuff": ["鸡胸肉", "花生米"],
  "bv": "BV1234567890",
  "difficulty": "简单",
  "tags": ["川菜", "家常菜"],
  "methods": ["炒", "煎"],
  "tools": ["炒锅"]
}
```

## 测试

运行测试：

```bash
# 运行所有测试
pnpm test

# 运行 CLI 测试
pnpm test packages/cook

# 测试覆盖率
pnpm test:coverage
```

测试内容：

- ✅ CSV 解析和格式化
- ✅ BV 号清洗（URL 前缀去除）
- ✅ 空值和边界情况处理
- ✅ 数组字段空值过滤
- ✅ CSV/JSON 往返一致性
- ✅ fetch/convert 输出格式一致性

## 开发

### 项目结构

```
packages/cook/
├── src/
│   ├── commands/
│   │   ├── fetch.ts       # 飞书数据拉取
│   │   ├── convert.ts     # CSV 转 JSON
│   │   └── search.ts      # 菜谱检索
│   ├── utils/
│   │   ├── alias.ts       # 食材别名映射
│   │   ├── config.ts      # 配置和常量
│   │   ├── csv.ts         # CSV 处理工具
│   │   ├── csv.test.ts    # CSV 测试
│   │   ├── feishu.ts      # 飞书 API 封装
│   │   └── search.ts      # 检索核心逻辑
│   └── index.ts           # CLI 入口
├── types.ts               # 类型定义
├── package.json
└── tsconfig.json
```

### 技术栈

- **CLI 框架**: [cac](https://github.com/cacjs/cac)
- **交互提示**: [@clack/prompts](https://github.com/natemoo-re/clack)
- **日志输出**: [consola](https://github.com/unjs/consola)
- **飞书 SDK**: [@larksuiteoapi/node-sdk](https://github.com/larksuite/node-sdk)
- **运行时**: [tsx](https://github.com/privatenumber/tsx)

## 配置

需要配置飞书应用凭证（`.env` 文件）：

```bash
FEISHU_APP_ID=cli_xxxxxxxxxxxxxxxx
FEISHU_APP_SECRET=xxxxxxxxxxxxxxxxxxxxxxxx
```

获取方式：[飞书开放平台](https://open.feishu.cn/app)

## 许可

MIT
