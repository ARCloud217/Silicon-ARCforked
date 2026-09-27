# EnergySavingBoost

## 基本信息

| 属性 | 值 |
|------|----|
| 类名 | `EnergySavingBoost` |
| 文件 | `src/silicon/util/boosts/EnergySavingBoost.java` |
| 包 | `silicon.util.boosts` |
| 类型 | `BuildingBoostSystem.Boost` 实现（**钩子式**效果单元） |
| id | `energy_saving` |
| 单例 | `EnergySavingBoost.instance`（静态块自动 `register` 进 System） |
| 提供方 | 效率控制塔（`docs/blocks/EfficiencyControlTower.md`），需在配置面板切到「节能」模式 |

节能：**耗电工厂**同时获得 **电力消耗 −20%** 与 **生产速度 −10%** 的两个子效果。

## 目标过滤

| 方法 | 行为 |
|------|------|
| `canTarget(Building)` | `block.consPower != null`（该方块类型登记了电力 consumer，即**耗电**）**且** `block instanceof GenericCrafter`（**工厂**） |

- `GenericCrafter` 覆盖炉、熔炉、压机、粉碎机、烧矿厂、合金熔炉、相位合成器等，
  其子类 `HeatCrafter` / `AttributeCrafter` 一并命中。
- **不**覆盖：钻机 / 抽油机（`Drill` / `Pump`）、`Separator`、`Incinerator`、`WallCrafter`
  （它们不继承 `GenericCrafter`）。要扩展只需放宽此判断。
- `consPower != null` 亦是引擎判定「该建筑是否登记了电力需求」的依据（`PowerGraph.getPowerNeeded` 只认它），
  故此过滤同时保证「钩子装得上去、也真会被查询到」。

## 子效果

### 电力消耗 −20%

`powerScale = 0.8f`。

引擎侧电力需求按 `Σ block.consPower.requestedPower(build) × build.delta()` 汇总（`PowerGraph.getPowerNeeded`），
故覆写 `requestedPower(Building)` 即可得到**每建筑独立**的电力倍率。

### 生产速度 −10%

`speedScale = 0.9f`。

生产进度按 `progress += (1 / craftTime) × edelta()` 累加，而 `edelta = efficiency × delta()`；
`efficiency` 由 `Building.updateConsumption()` 取所有**非可选** consumer 的 `efficiency(build)` 最小值（初值 1）。
故注册一个返回 `0.9` 的「速率税」consumer → `efficiency = 0.9` → 生产速率 ×0.9。

## 为什么用钩子而不是直接改字段

MJ 里工厂的电力与速率**都不是每建筑可写字段**：

| 想改的东西 | 实际归属 | 直接改的后果 |
|-----------|---------|-------------|
| `ConsumePower.usage`（耗电量） | **方块类型级**共享 | 连带影响该类型**所有**建筑（含敌方、含未强化建筑） |
| `GenericCrafter.craftTime`（速率） | **方块类型级**共享 | 同上 |

故只能用引擎留给「按建筑个体」的两个 consumer 扩展点（详见 `BlockConsumerHooks` 类注释）：

- `BlockConsumerHooks.ScaledConsumePower` —— 包装原电力 consumer，覆写 `requestedPower(Building)`；
- `BlockConsumerHooks.SpeedTaxConsume` —— 参与 `efficiency` 最小值，把速率压到 0.9。

**两个子效果互不干扰**：电力需求只乘 `delta()`、**不乘** `efficiency`；生产速率两者都乘。
故「降电力 0.8」与「降速率 0.9」可各自独立取值，**无需任何系数换算**。

## 生命周期语义

- **钩子式**：`apply()` 只负责把钩子装进方块（幂等、惰性），真正的倍率由钩子在**每次被引擎查询时**
  回调 `BuildingBoostSystem.isActive(build, id)` 实时读取。
- 因此 `remove()` **无需任何还原动作**：本效果撤销后倍率自动回到 `1.0`，无残留。
- `apply()` 只在效果生效时被调用，故**只有真正被命中的方块类型会被装钩子**，未命中的方块完全不受影响。
- 幂等与自愈：`installed(block)` 以电力钩子为标记判断；若钩子被 `Block.reinitializeConsumers()` 冲掉
  （`consumeBuilder` 是 protected 内容加载期数据，无法改写），下次生效时会重新安装。
- 安装时**替换**（而非并列新增）电力 consumer，以保持 `Building.updateConsumption()` 里
  `cons == block.consPower` 的身份判断成立——该判断会跳过「`efficiency ≤ 1e-7` 则不计电力需求」分支，
  对电力 consumer 本身必须跳过，否则断电建筑会退出电网需求、造成供电震荡。

## 生效条件

| 方法 | 行为 |
|------|------|
| `shouldApply(Building)` | `target.enabled`（工厂已启用） |

刻意**不**用 `shouldConsume()` / `efficiency > 0` 等运行时状态：输出槽被塞满时那些条件会翻转，
导致强化图标反复闪烁。玩家手动关闭工厂 → 效果撤销、倍率回到 1.0。

## 展示信息

| 方法 | 内容 | 来源 |
|------|------|------|
| `name()` | `节能` / `Energy Saving` | bundle `boost.energy_saving.name` |
| `description()` | `-20% 电力消耗；-10% 生产速度` | bundle `boost.energy_saving.desc` |
| `visual(Building)` | 原版状态图标 `StatusEffects.slow.uiIcon` | 强化按钮图标（底色 `Pal.powerLight` 电量蓝，区别于润滑油的默认绿） |

> 按钮**如何绘制**（位置/尺寸/按光标距离淡入/点击命中）与点击后**投递什么格式的消息**，
> 由 `BuildingBoostSystem` 与 `silicon.util.BoostOverlay` 负责，见 `docs/utils/BuildingBoostSystem.md`。

图标选 `slow` 而非 `overclock`（润滑油用），使两者形成「快 / 慢」对照，玩家一眼能看出方向。

## 多人安全

- 钩子**不缓存**任何每建筑状态：每次被引擎查询都回到 System 问一次 `isActive(build, id)`，
  故不存在「客户端缓存了、服务端没缓存」的两端分歧窗口。
- 判定输入只有 `Building` 引用与 System 生效状态，两端一致。
- 电力图 / consumer 结算若早于本 System 冲洗，读到的是上一 tick 状态——对倍率类效果只是 1 tick 滞后，
  不产生持久分歧（且不涉及液体/库存等网络化状态的扣加）。
- 电力需求本身是**纯本地模拟**（由本地电网供需推导），两端各自算，不需网络同步。

## 已知副作用

- `efficiency` 被压到 0.9 是**总标量**，故凡依赖它的判定都等比变化（生产进度、`optionalEfficiency`、
  依赖 `efficiency > 0` 的产出节流）——这正是「生产速度 −10%」的期望语义。
- 方块面板的**耗电量**显示读的是钩子镜像的原值 `usage`（故仍显示标称值），
  **电力条**显示的是供电满足度 `power.status`，均不受倍率影响。
- 若同一方块类型上还有别的 mod 也替换 `consPower`，安装时以当时的实例为基准，不会重复包装。

## 配置参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `powerScale` | float | `0.8f` | 电力需求倍率（0.8 = −20%） |
| `speedScale` | float | `0.9f` | 生产速率倍率（0.9 = −10%） |

两者均为 `EnergySavingBoost.instance` 上的 public 字段，钩子每次查询时实时读取，可直接改：
`EnergySavingBoost.instance.speedScale = 0.95f;`

## 现状

由**效率控制塔**（3x3，15×15 区域）提供：塔的配置面板切到「节能」模式后，区域内耗电工厂附上本效果。
同队两塔的**范围不得重叠**（放置时被拒，仍被放置成功则该塔不运行），故区域内工厂的归属唯一；
即便多塔覆盖，System 也保证同一工厂上本效果**只生效一份**。

与 [`OverclockBoost`](OverclockBoost.md)（超频）是**互斥**关系（`conflictsWith`）——两者是同一概念的
相反档位。单台塔任一时刻只提供一个效果；若两台**不同模式**的塔范围真的重叠了（旧存档/强制放置的遗留场景），
由 System 互斥裁决按**效果 id 字典序**取一个：`energy_saving` < `overclock`，故**恒为节能胜出、超频不生效**。
字典序是纯函数，两端必然一致，且与放置顺序无关。

## 版本历史

| 版本 | 变更 |
|------|------|
| a0.x | 初始实现：耗电工厂（`consPower != null` 且 `GenericCrafter`）获得电力 −20% / 生产速度 −10%；经 `BlockConsumerHooks` 两个 consumer 钩子实现（替换式安装、幂等自愈、`remove` 无需还原）；图标 `StatusEffects.slow`、底色 `Pal.powerLight` |
