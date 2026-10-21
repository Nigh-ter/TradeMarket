# TradeMarket

**Minecraft Paper 服务器的完整玩家经济** —— 全球市场、竞价拍卖、托管求购、离线邮箱，基于「主线程串行」的事务模型，从结构上杜绝刷物品 / 刷钱。

[![Paper](https://img.shields.io/badge/Paper-1.21.x-3da638)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://adoptium.net)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

English documentation: [README.md](README.md)

---

## 目录

- [为什么重写](#为什么重写)
- [功能](#功能)
- [安装](#安装)
- [命令](#命令)
- [权限](#权限)
- [配置](#配置)
- [架构（给维护者）](#架构给维护者)
- [从源码构建](#从源码构建)
- [已知限制](#已知限制)
- [许可证](#许可证)

---

## 为什么会有这个插件

第一版是交给 AI 编码工具（TraeCode）生成的。想法其实很全：市场、拍卖、邮箱、管理员补偿、跟称号/抽奖箱之类的插件联动……但落地之后几乎每个功能都只完成了一半 —— 玩家付了钱物品不到账、拍卖倒计时对不上、邮件永远卡在「处理中」、语言键满屏 `<gui.button.xxx>`。它「编译通过、能启动、界面能打开」，但**没有一个闭环能真正跑完**。

继续在这个地基上打补丁是没有意义的：问题不在某一行代码，而在架构选择本身。所以这一版是从零重写的：先定下「主线程串行 + 两阶段提交 + 一切交割走邮箱」这三条硬规则，再往上长功能。宁可功能少一项，也不要多一个「看起来能用、实际会吞钱」的按钮。

顺带说一句，这也是我给所有服主的一句实话：**市场类插件最贵的不是功能，是数据一致性**。一次刷物品事件就能让整个服务器的经济报废，且几乎无法回滚。

### 1.x 到底坏在哪

1.x 版本存在根本性的线程模型缺陷：数据库操作被丢到异步线程，随后在那个线程里调用 Bukkit API。实际表现是——玩家付了钱、物品没到、没有任何报错；同时锁在数据库线程释放必然抛异常，泄漏的锁层层级联，最终整个经济系统卡死。

本版是从零重写的 2.0 架构，只立一条规矩：

> **所有业务状态变更只在主线程串行执行，不存在并发，因此不需要任何业务锁。**

数据库仍然是同步写入（SQLite WAL，本地亚毫秒级），改变的是顺序一致性从「碰运气」变成「有保证」。

| 1.x 的问题 | 本版的解法 |
|---|---|
| 锁在数据库线程释放必抛异常 → 扣钱不发货、无提示、锁泄漏级联卡死 | 全部业务状态变更只在主线程串行执行 |
| 数据库事务内调用 Vault，回滚不回滚钱 | **两阶段提交**：先守护性落库（行级条件 UPDATE，只成功一次）→ 再动钱 → 再发货，任一步失败全额回退 |
| 物品直发背包，满了就丢 | **一切物品交割走邮箱**，背包满只影响领取时机，永不丢件 |
| 语言键系统性错位，满屏 `<gui.button.xxx>` | 语言键与代码同源自检，启动时逐条报告缺失键 |
| 半成品功能（收购订单 / 搜索 / 奖励中心等） | 只保留市场 / 拍卖 / 求购 / 邮箱四个完整闭环 + 管理员工具 |

## 功能

### 市场
- GUI 浏览，分类标签（全部 / 武器 / 工具 / 盔甲 / 方块 / 食物 / 杂项）
- 价格区间筛选、按名称排序
- **潜影盒预览**：右键潜影盒类商品，不开箱直接看内部物品
- 物品名走原版可翻译组件：中文客户端显示「橡木原木 / 下界合金剑」，铁砧改过名的物品显示自定义名
- 成交税、上架手续费、在售上限、过期天数、材质黑名单全部可配
- **定价防手滑**：单价偏离近期成交均价超过设定倍数时弹出二次确认

### 上架与求购
- `/tk sell <单价> [数量]` 上架手中物品，单价 × 数量整批成交
- `/tk order <单价> [数量]` 发布求购单，资金**全额托管**
- 求购不是即时成交：卖家把物品托管进「验货」流程，买家在邮箱里查看完整物品信息后**接受或拒绝**
- 托管申请超时自动退回卖家；卖家主动取消收取手续费

### 拍卖
- **出价即托管**：出价瞬间扣款，被超越者立即原路退款
- **一口价即上限**：出价达到或超过一口价直接成交
- **防狙击**：结束前 N 秒内有人出价自动延长
- 倒计时全服播报（带可点击的「打开拍卖」按钮）、成交播报、每场拍卖的完整出价记录

### 邮箱
- 支持物品邮件、金币邮件、**命令邮件**：领取时以控制台身份执行命令，`%player%` 替换为领取者 —— 任何有「发放」命令的插件都能接进来，无需专门适配
- 支持离线玩家；未领邮件永不清理，已领邮件 N 天后清理
- 玩家互发（`/tk send`），可设手续费，带确认 GUI；tab 只补全在线玩家，发离线玩家需输入完整 ID
- 上线提示未领数量，久未领取二次提醒

### 管理
- `mail` / `mailonline` / `mailall` 三个指令按下回车**统一弹出确认 GUI**，先看清投递什么再发送
- `mailundo` 撤销批次、`refund` 应急直充、`redeliver` 补发卡住的物品、`stats` 实时统计
- 每笔事务写入 `tm_txlog` 流水表，是人工修复的唯一依据

### 服务器集成
- **Vault** 经济插件必需；**LuckPerms** 可选，用于分组差异化上限（如 vip 组可同时发更多求购单）
- 自定义物品识别：ItemsAdder / Oraxen / MMOItems / MythicMobs 的物品会记录来源，求购单按来源精确匹配
- 分世界限制：可整体禁用某世界的交易，也可只允许其中某几个子命令
- 完整的 `zh_CN` / `en_US` 语言文件，`/tk reload` 热切换

## 安装

1. **前置**：Paper 1.21.x（针对 1.21.11 构建）、Java 21、**Vault + 任一 Vault 经济插件**（EssentialsX / CMI 等）。
2. 把 `TradeMarket-1.0.0.jar` 放进 `plugins/`，启动服务器。
3. 首次启动会生成 `plugins/TradeMarket/`（`config.yml`、`lang/zh_CN.yml`、`lang/en_US.yml`）与数据库 `plugins/TradeMarket/market.db`。

> **从 1.x 升级**：检测到旧配置会整体备份到 `plugins/TradeMarket/backup-v1-<时间戳>/` 并重新生成；1.x 的测试数据**不做迁移**，数据库是全新的 `market.db`。

插件会在启动时**逐条报告缺失的语言键**，而不是在界面上显示原始键名。语言文件整个缺失时会重新生成；已存在的文件不会被强制覆盖。

## 命令

主命令 `/market`（别名 `/tk`）。

| 命令 | 说明 | 权限 |
|---|---|---|
| `/tk` / `/tk market` | 打开全球市场 | `trademarket.use` |
| `/tk sell <单价> [数量]` | 上架手中物品 | `trademarket.sell` |
| `/tk my` | 我的在售（点击下架 → 物品退回邮箱） | `trademarket.sell` |
| `/tk order <单价> [数量]` | 发布求购单（资金全额托管） | `trademarket.sell` |
| `/tk orders` | 求购大厅（把自己的物品卖给别人的求购单） | `trademarket.use` |
| `/tk myorders` | 我的求购单 | `trademarket.sell` |
| `/tk offers` | 待我验货的托管申请 | `trademarket.use` |
| `/tk auction` / `/tk ah` | 打开竞价拍卖 | `trademarket.auction` |
| `/tk auction sell <起拍价> [一口价] [分钟]` | 拍卖手中物品 | `trademarket.auction` |
| `/tk mailbox` / `/tk mail` | 打开邮箱 | `trademarket.mailbox` |
| `/tk send <玩家> <item\|money\|command> ...` | 给其他玩家发邮件 | `trademarket.use` |
| `/tk help` | 帮助（管理员额外看到管理区段） | `trademarket.use` |
| `/tk reload` | 热重载配置与语言 | `trademarket.admin` |
| `/tk admin help` | 管理员命令总览 | `trademarket.admin` |
| `/tk admin mail <玩家> <item\|money\|command> [...]` | 给单个玩家发邮件 | `trademarket.admin` |
| `/tk admin mailonline <item\|money\|command> [...]` | 给所有在线玩家发邮件 | `trademarket.admin` |
| `/tk admin mailall <item\|money\|command> [...]` | 给**全体玩家（含离线）**发邮件 | `trademarket.admin` |
| `/tk admin mailundo` | 撤销一个邮件批次 | `trademarket.admin` |
| `/tk admin refund <玩家> <金额>` | 应急直充 | `trademarket.admin` |
| `/tk admin redeliver <auction\|listing> <编号>` | 补发卡住的物品（编号见控制台） | `trademarket.admin` |
| `/tk admin stats` | 在售 / 拍卖 / 未领邮件实时统计 | `trademarket.admin` |

三个邮件指令发送前都会弹出确认 GUI。金额支持 `k` / `w` / `m` 后缀（`1k` = 1000，`1w` = 10000，`2m` = 2000000）与配置的货币符号。

## 权限

| 节点 | 默认 | 含义 |
|---|---|---|
| `trademarket.use` | 所有人 | 市场 / 邮箱 / 求购大厅基础权限 |
| `trademarket.sell` | 所有人 | 上架物品、发布求购单 |
| `trademarket.auction` | 所有人 | 出价与发起拍卖 |
| `trademarket.mailbox` | 所有人 | 打开邮箱 |
| `trademarket.mail.nofee` | 无 | 免除玩家互发邮件手续费 |
| `trademarket.admin` | OP | 重载与全部 `/tk admin` 命令 |

## 配置

`plugins/TradeMarket/config.yml` 全文带注释，主要分区：

- **economy** —— 货币符号、音效开关
- **market** —— 价格上下限、上架费、成交税、在售上限、过期天数、定价确认倍数、材质黑名单
- **auction** —— 起拍价范围、最低加价、默认/最长时长、成交税、防狙击窗口与延长秒数、倒计时播报节点
- **order** —— 求购播报开关、每人上限（支持 LuckPerms 分组覆盖）、单张最大数量、成交税、托管超时、取消手续费
- **world-limits** —— 全局禁用世界 + 可选的按世界子命令白名单
- **advanced** —— 自定义物品识别开关、求购过期、流水/终态记录/成交历史保留天数
- **mailbox** —— 上线提示、已领邮件清理、二次提醒、玩家互发手续费

`/tk reload` 即时生效；只有涉及数据库结构变更时才需要重启。

## 架构（给维护者）

- **线程模型**：所有业务操作（上架 / 购买 / 出价 / 结算 / 领取）在主线程串行执行，内存中的 Map 是权威状态；SQLite（WAL，`synchronous=NORMAL`）同步直写，本地写入亚毫秒级，换来的是顺序一致性，整类并发缺陷不复存在。
- **两阶段提交**（市场购买 / 拍卖成交 / 邮件领取）：
  1. **守护性落库**：`UPDATE ... SET status='SOLD' WHERE id=? AND status='ACTIVE'`，影响行数 = 1 才继续 —— 崩溃或重复点击下保证只结算一次；
  2. **扣款 → 入账 → 发货**，任一步失败把状态改回 `ACTIVE` 并全额退款；
  3. 邮箱发货成功是「不可回退点」，此后的极端故障（磁盘坏）通过 `tm_txlog` 流水表 + `/tk admin redeliver` 补救。
- **拍卖托管**：出价瞬间扣款，前任最高出价者立刻原路退款；写库失败自动回退内存与资金；无人出价时才能取消。
- **物品序列化**：Bukkit `BukkitObjectOutputStream` + Base64 文本落库（完整保留 NBT）；反序列化失败返回 `null`，标记 `BROKEN` 并跳过 —— 单条坏数据只影响自己，不炸界面。
- **启动自检**：语言键缺失在控制台逐条报告，物品数据损坏的行自动隔离。
- **零打包依赖**：jar 内不打包任何第三方库，SQLite 驱动、Adventure、Vault 全部由服务器在运行时提供。

## 从源码构建

无需 Gradle / Maven / 网络，`build.cmd` 里就是一次 `javac`：

```bat
build.cmd
```

产物：`dist/TradeMarket-1.0.0.jar`。

编译期引用放在 `libs/`（Paper API 1.21.11、Vault、Adventure、LuckPerms）。这些 jar **只是编译期引用**，已刻意不纳入版本库 —— 构建前把对应 jar 放进 `libs/`：

- `paper-api-1.21.11-R0.1-SNAPSHOT.jar`（PaperMC 仓库）
- `Vault.jar`（VaultAPI）
- Adventure API 与 text serialiser（Maven Central，或直接从 Paper API 的传递依赖里取）
- `LuckPerms-Bukkit-5.5.53.jar`（可选，用于分组上限 hook）
- `guava`、`snakeyaml`、`sqlite-jdbc`（传递编译引用）

离线自检在 `tools/`（`SelfCheck.java`、`EdgeCheck.java`），覆盖语言键对齐、金额解析、SQLite DDL。

## 已知限制

- SQLite 单文件存储，**不支持跨服共享数据**（多服互通需另做后端）。
- Vault 没有事务，余额变动与数据库写入无法原子化；服务器进程被强杀的瞬间存在极小的资金不一致窗口，以 `tm_txlog` 为准，用 `redeliver` / `refund` 修复。
- 除 ItemsAdder / Oraxen / MMOItems / MythicMobs 之外的自定义物品插件未做显式识别 —— 原版 NBT 序列化通常能带住，但不保证全部。
- 不兼容 Folia（`folia-supported: false`）。

## 许可证

GPL-3.0，见 [LICENSE](LICENSE)。
