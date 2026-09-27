# EfficiencyControlTower

## 基本信息

| 属性 | 值 |
|------|----|
| 类名 | `EfficiencyControlTower` |
| 父类 | `Block` |
| 方块 id | `efficiency-control-tower` |
| 分类 | `Category.effect` |
| 尺寸 | 3x3 |
| 血量 | 300 |
| 架构 | `BuildingBoostSystem.Provider`（提供 `EnergySavingBoost`，由 System 驱动） |

## 合成配方

| 材料 | 数量 |
|------|------|
| Copper | 200 |
| Lead | 150 |
| Silicon | 80 |

> 配方为本方块自定初值（需求未指定），可按平衡需要调整。

## Block 属性

- `update`: true
- `solid`: true
- `configurable`: true
- `saveConfig`: true（随存档保存模式）
- `copyConfig`: true（复制/粘贴蓝图保留模式）
- 区域边长：`range = 15f`（格），以本方块**中心**为中心的正方形区域

## 机制说明

效率控制塔是一个**区域型支援方块**：以塔为中心、15×15 格的区域内，己方所有**消耗电力的工厂**
附上 `EnergySavingBoost`（电力 −20%、生产速度 −10%）。

本方块只负责三件事：**圈定区域内耗电建筑**（`targets()`）、**当前是否提供**（`canTarget()`）、
**声明自己提供节能效果**（`boosts()`）。效果本身怎么算、怎么注入全在 `EnergySavingBoost` 里，
资格/队伍/不叠加/apply-remove 生命周期全由 System 兜底。

| 归属 | 内容 |
|------|------|
| 本方块 | 区域有多大、区域内哪些建筑算目标、什么模式下提供 |
| `EnergySavingBoost` | 耗电与速率两个子效果的具体实现、目标过滤、展示信息 |
| `BuildingBoostSystem` | 登记、队伍门禁、不叠加、apply/remove 驱动、撤销生命周期 |

### 目标过滤

| 层级 | 判定 |
|------|------|
| `targets()` | 区域内 + 同队 + `block.consPower != null`（**耗电**）。非耗电建筑不进 System 循环 |
| `canTarget()` | `enabled && mode != 关闭`（当前是否提供） |
| `Boost.canTarget()` | 耗电 **且** `block instanceof GenericCrafter`（**工厂**：炉/熔炉/压机/粉碎机/合金熔炉等及其子类） |
| `Boost.shouldApply()` | 目标工厂自身 `enabled`（被关掉的工厂不生效） |

> 「工厂」判定在效果侧，故 `targets()` 只做粗筛（耗电），避免把区域内所有建筑都塞进 System 循环。

### 模式（配置 UI）

配置面板只有一个**滑块**，两个离散档位：

| 档位 | 值 | 效果 |
|------|-----|------|
| 关闭 | 0 | 不提供任何强化 |
| 节能 | 1 | 区域内耗电工厂附上 `EnergySavingBoost` |

- 走**标准 config 链路**：UI `configure(mode)` → `Call.tileConfig` → 两端 `configured()` → 本方块的
  `config(Integer.class, ...)` 处理器，故多人下全端一致。
- 模式经 `write`/`read` 持久化，随存档保存；`config()` 返回当前模式，故复制蓝图/放置预设会带上模式。
- 被开关禁用的塔（`enabled == false`）停止提供。

### 区域查询与绘制

- 走**本队 `buildingTree` 空间树**做矩形相交查询（与 `ItemTransferHub` 同款），非逐格扫描。
- 结果按 `Vars.state.tick` 缓存，同一 tick 内复用同一 `Seq`（零分配）。
- 区域以**方块中心**为基准，半边长 `(range - 1) / 2 = 7` 格，故 15×15 恰好覆盖 15 格、中心落在中间格。
- **arc 的绘制 API 锚点不一致（重要）**：
  - `Fill.rect(x, y, w, h)` 经 `Draw.rect` → `Batch.draw(x - w/2, y - h/2, ...)`，是**中心锚点**；
  - `Lines.rect(x, y, w, h)` 的 6 参重载按 `center = (0, 0)` 换算，是**左下角锚点**。

  两者传同一组坐标必然错位，故 `drawArea` 分别按各自锚点换算：填充传中心、描边传 `中心 - 半边长`。
  若统一按一种锚点写，会出现「填充对了边框错 / 边框对了填充错」的反复现象（本项目已踩过一轮）。

### 范围重叠检测

**规则：同队两座塔的范围不得重叠。**

| 时机 | 行为 |
|------|------|
| **放置时** | `canPlaceOn` 检测到与同队已建成的塔范围重叠 → **拒绝放置**（幽灵变红） |
| **运行时** | 若仍被放置成功（旧存档、蓝图/命令等绕过路径），该塔**不运行**：`provides()` 恒 false → 不提供任何强化，且原有贡献被 System 撤销 |
| **视觉** | 放置预览与选中时，重叠的塔范围画**红色**（`Pal.remove`）；正常为放置蓝 / 强调绿 |

- 判定口径：两塔中心距在**两轴上都小于 `range` 格**即重叠（恰好相切不算重叠）。
  两者 `range` 相同故对称，实现上取各自 `[中心 - range, 中心 + range]` 矩形相交。
- **只拦同队**：敌队塔与本塔的覆盖对象本就不重叠（各自只强化本队工厂），
  互相拦只会被敌方用来「用一座废塔废掉你的塔」，故不拦。
- 运行时结果缓存于 `conflicted` 字段：每 tick 在 `update()` **开头**重算，
  必须早于 `updateBoosts()`——`provides()` 是在登记过程中被同步读取的。
- 客户端与服务器都会执行该判定（纯几何 + 同队塔列表，两端一致），不会出现「一边能放一边不能」。

### 叠加与互斥

- **同模式多塔**：多台塔覆盖同一工厂时，System 按「同一目标同一效果**至多一份**」处理，
  被 3 台塔覆盖也只生效一份（不叠乘）。
- **异模式重叠**：一台节能塔 + 一台超频塔覆盖同一工厂时，两者互相冲突（`conflictsWith`），
  由 System 的互斥裁决按**效果 id 字典序**取一个——`energy_saving` < `overclock`，故**恒为节能胜出、超频不生效**。
  字典序是纯函数：两端必然算出同一结果，且**与放置顺序/玩家操作时序无关**（不会因「谁先放」而改变）。
  （正常放置已被范围重叠检测拦住，此规则主要服务于旧存档/强制放置的遗留重叠。）

### 多人安全

- 只作用于**同队**工厂，队伍比较用 `==`（`Team` 枚举单例，跨端一致，天然排除敌队/derelict）。
- 本塔**不消耗任何资源**（不耗电、不耗液体），故不存在「扣多少网络化状态」的分支，
  无需确定性认领规则（对比 `LubricantInjector` 的 `owns()`：那边要扣油，必须裁决唯一认领者）。
- 生效集合完全由「本队空间树 × 固定区域 × 模式」推导，两端输入一致。
- 模式本身经标准 config 链路同步，两端一致。
- 范围重叠判定（放置与运行时）只用几何量与同队塔列表，两端一致；互斥裁决只用效果 id 字典序（纯函数），两端一致。

### 关键实现约束：`boosts()` 恒定返回完整列表

**`boosts()` 永远返回 `[EnergySavingBoost, OverclockBoost]` 完整列表，模式与冲突判断只放在 `provides()`。**

原因：System 撤销某个 Provider 贡献的唯一路径是「该 Provider 仍被遍历到、但它对某个 boost id 的
意愿为 false」（见 `BuildingBoostSystem.collectContributions`）。若某模式下让 `boosts()` 只返回
「该模式那一个效果」（或返回空列表），或让 `targets()` 返回空列表，System 会因 `targets().isEmpty()`
提前 return，**旧模式的贡献永远不会被撤销**——切到超频后节能仍不消失，两者相乘成 0.8×1.75 / 0.9×1.5 的杂交值。

故本方块：

- `boosts()` 恒定返回完整效果列表（与模式无关）；
- `provides(boost)` 里判 `!conflicted && mode == modeOf(boost.id())`——**逐效果**表达开关，
  返回 false 即触发 System 的正规撤销路径；
- `canTarget()` 只判 `enabled`（本机是否开机）；
- `targets()` 也与模式无关地照常返回区域内耗电建筑。

> System 为此在 `Provider` 上新增了 `provides(Boost)`（默认恒为是）——因为「同一 Provider 按模式
> 提供不同效果」无法用 `canTarget(Building)` 表达（它拿不到当前是哪个效果）。

代价是「关闭」/冲突模式下仍会做一次区域查询（为了能正确撤销），但结果按 tick 缓存，开销可忽略。

### 视觉

- 放置预览与选中时画出 15×15 影响区域（半透明填充 + 描边），与实际判定同口径。
- 区域内被强化的工厂左下角会出现强化信息按钮（`BoostOverlay` 绘制 + 交互），
  点击查看该建筑上生效的强化详情。**按钮的绘制与消息输出不属于本方块**，见 `docs/utils/BuildingBoostSystem.md`。

## 配置参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `range` | float | `15f` | 影响区域边长（格），以本方块为中心 |

## 已知取舍

- **不耗电、不耗资源**：需求未指定成本，故未加。一个 3x3 塔免费强化 15×15 区域，强度偏高；
  若要平衡，可加 `consumePower(...)`（此时本塔进入电网，`Building.cheating()` 路径下 consumer 不参与结算，
  需另行确认断电时的降级行为）。
  配方（Copper 200 / Lead 150 / Silicon 80）亦为自定初值，待定。
- **「关闭」/冲突模式下仍查询区域**：见上「关键实现约束」，是正确性所迫，非疏忽。
- **只拦同队范围重叠**：敌队塔可与本塔范围重叠（见「范围重叠检测」）。
- **异模式重叠时按效果 id 字典序裁决**（恒为节能胜出），而非按玩家意图或塔的类型优先级。
- **不覆盖非 `GenericCrafter` 的耗电建筑**（钻机、抽油机、`Separator`、`Incinerator` 等）：
  「工厂」判定在效果侧，放宽只需改 `EnergySavingBoost.canTarget` / `OverclockBoost.canTarget`。

## 版本历史

| 版本 | 变更 |
|------|------|
| a0.x | 初始：3x3 方块，15×15 区域内耗电工厂附上 `EnergySavingBoost`；配置滑块「关闭/节能」走标准 config 链路 + 存盘持久化；区域查询走本队 buildingTree 并按 tick 缓存；`boosts()` 恒定返回完整列表（条件只判 `provides()`/`canTarget()`），避免旧模式贡献无法撤销；不耗资源 |
| a0.x | 新增「超频」档位（`OverclockBoost`：生产 +50%、耗电 +75%、每 3 秒扣 10 生命）；System 新增 `Provider.provides(Boost)` 逐效果开关（`canTarget` 拿不到「当前是哪个效果」，无法表达按模式提供不同效果） |
| a0.x | **修复范围显示错位**：arc 的 `Fill.rect` 是中心锚点而 `Lines.rect` 是左下角锚点，原先两者传同一组坐标 → 填充与边框必然错位（表现为「填充对了边框错 / 边框对了填充错」交替出现）。现 `drawArea` 分别按各自锚点换算 |
| a0.x | **范围重叠检测**：同队两塔范围重叠时①放置被拒（`canPlaceOn`）②仍被放置成功则该塔不运行（`provides()` 恒 false，原贡献被撤销）③预览/选中画红色提示。互斥裁决沿用「优先级 + 效果 id 字典序」（恒为节能胜出），未引入放置顺序 |
