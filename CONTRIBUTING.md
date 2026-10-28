# 提交规范

本仓库使用 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/) 的子集：
**type / scope 用英文，描述用中文**。格式由 `.githooks/commit-msg` 强制校验。

## 一次性安装（克隆后必做）

```bash
git config core.hooksPath .githooks
```

装上之后：`commit-msg` 校验提交信息格式，`pre-commit` 扫描凭据 / token / 公网 IP。
跳过钩子只能用于真正的紧急情况（`--no-verify`），跳完要在同一个 PR 里说明原因。

## 格式

```
<type>(<scope>): <描述>
```

- `type`、`scope` 一律小写英文；`scope` 可省略。
- 冒号是**半角冒号 + 一个空格**。
- 描述用中文，写清楚做了什么，不要写「修改了一些东西」。
- 整行不超过 72 个字符，结尾不加句号。
- 破坏性变更：type 后加 `!`，并在正文写 `BREAKING CHANGE:` 说明。

## type 取值

| type | 用于 |
| --- | --- |
| `feat` | 新增功能 |
| `fix` | 修复缺陷 |
| `refactor` | 重构，不改变外部行为 |
| `perf` | 性能优化 |
| `docs` | 仅文档 / 注释 |
| `style` | 仅格式（空格、换行、import 排序），不改逻辑 |
| `test` | 测试相关 |
| `build` | 构建脚本 / 依赖 / Gradle 配置 |
| `ci` | CI 配置 |
| `chore` | 杂项：初始化、版本号、忽略文件等 |
| `revert` | 回滚某次提交 |

## scope 取值

取改动所在的层次，同一模块固定同一个词：

`home` 首页 · `search` 搜索 · `detail` 详情 · `player` 播放器（含投屏/面板/手势）· `settings` 设置 ·
`auth` 授权登录 · `data` 网络/仓库/缓存 · `model` 数据模型 · `ui` 通用组件与主题 ·
`docs` 文档 · `build` 构建 · `ci` 持续集成

新增模块时先在此处登记，再使用。

## 示例

```
feat(player): 支持 DLNA 投屏到局域网电视
fix(detail): 选集区首行被标题栏压住
refactor(home): 分类分页改成按用户排序渲染
perf(player): 弹幕层改为复用节点池
docs: 补充手机端与 TV 端的差异说明
build(deps): 升级 Media3 到 1.4.1
chore: 初始化 ModaTv 手机端工程骨架
```

反例，钩子会直接拒绝：

```
update                     # 没有 type
fix:xxx                    # 冒号后缺少空格
Fix(Player): xxx           # type / scope 必须小写
fix(player):修复            # 冒号后缺少空格
修复播放器 bug              # 没有 type
docs: 补充说明。            # 结尾不加句号
```

## 提交粒度

- 一次提交只做一件事：说得清、能单独回滚。
- 格式化与逻辑改动不要混在同一个提交里。
- 不相关模块不要塞进同一个提交。
- 提交前至少跑通 `./gradlew assembleDebug`；界面的改动还要 `adb install` 后手动走查涉及的触摸路径
  （本项目没有单元测试，手动走查是唯一的验收手段）。

## 不提交的东西

`local.properties`、`.idea/`、`build/`、任何真实的 token / 授权码 / 设备指纹 / 签名文件。
`pre-commit` 会在提交前扫一遍：命中就拒绝，先确认是真泄露还是误报，不要习惯性 `--no-verify`。
