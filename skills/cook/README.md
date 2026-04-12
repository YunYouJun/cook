# 🍳 Cook Skill — 食用手册 AI 美食助手

> 围绕「今天吃什么」的生活灵感助手，内置数百道中文家常菜谱数据库。

## 功能

- **食材找菜谱** —— 告诉 AI 你有什么食材，推荐能做的菜（附 B站视频教程）
- **饮食规划** —— 一周菜单安排，荤素搭配，买一次菜用一周
- **食材知识** —— 食物相克查询、保存方法、食材替代方案
- **烹饪技巧** —— 新手友好的调味比例、翻车避坑指南

## 数据源

菜谱数据来自 [Cook（食用手册）](https://github.com/YunYouJun/cook) 项目，包含：

- 数百道家常菜谱（含食材、难度、厨具、做法、B站视频链接）
- 食物相克数据
- 食材/厨具分类

数据通过 GitHub Raw URL 在线获取，无需本地项目。

## 安装

将 `cook/` 文件夹（包含 `SKILL.md`）放入你的 Skills 目录：

### CodeBuddy IDE

**方式一：手动复制**

```bash
# 项目级（仅当前项目生效）
cp -r cook/ <your-project>/.codebuddy/skills/cook/

# 用户级（所有项目生效）
cp -r cook/ ~/.codebuddy/skills/cook/
```

**方式二：IDE 导入**

设置页 → Skills → 点击「导入 Skill」 → 选择 `cook/` 文件夹

### Claude Code

```bash
# 项目级
cp -r cook/ <your-project>/.claude/skills/cook/

# 用户级
cp -r cook/ ~/.claude/skills/cook/
```

安装后可通过 `/cook` 斜杠命令手动调用，或由 AI 根据对话内容自动触发。

## 兼容性

| 工具                 | Skills 路径          | 状态 |
| -------------------- | -------------------- | ---- |
| CodeBuddy IDE        | `.codebuddy/skills/` | ✅   |
| CodeBuddy Code (CLI) | `.codebuddy/skills/` | ✅   |
| Claude Code          | `.claude/skills/`    | ✅   |

## 许可

[MIT](https://github.com/YunYouJun/cook/blob/main/LICENSE)
