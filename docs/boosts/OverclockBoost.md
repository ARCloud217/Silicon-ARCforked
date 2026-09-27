# OverclockBoost

## 基本信息

| 属性 | 值 |
|------|----|
| 类名 | `OverclockBoost` |
| 文件 | `src/silicon/util/boosts/OverclockBoost.java` |
| 包 | `silicon.util.boosts` |
| 类型 | `BuildingBoostSystem.Boost` + `BlockConsumerHooks.FactorSource`（**钩子式 + 注入式**混合） |
| id | `overclock` |
| 单例 | `OverclockBoost.instance`（静态块自动 `register` 进 System 与钩子注册表） |
| 提供方 | 效率控制塔（`docs/blocks/EfficiencyControlTower.md`），需切到「超频」模式 |

超频：作用对象与 [`EnergySavingBoost`](EnergySavingBoost.md) **完全一致**（耗电工厂），
但方向相反——纯粹的「以机器寿命换产能」。

## 目标过滤

与节能逐字相同（同一套判定，便于统一维护口径）：

| 方法 | 行为 |
|------|------|
| `canTarget(Building)` | `block.consPower != null`（耗电）且 `block instanceof GenericCrafter`（工厂） |
| `shouldApply(Building)` | `target.enabled`（工厂未被玩家关闭） |

## 子效果

### 生产速度 +50%

`speedScale = 1.5f`。与节能同一入口：经 `BlockConsumerHooks.SpeedTaxConsume` 把该建筑的
`efficiency` 抬到 1.5，生产进度 `progress += (1/craftTime) × efficiency × delta()` 随之等比变快。

### 耗电 +75%

`powerScale = 1.75f`。经 `BlockConsumerHooks.ScaledConsumePower` 把 `requestedPower(build)` 乘 1.75，
只提高该建筑自己的请求电量（`PowerGraph.getPowerNeeded` 汇总时生效）。

### 每 3 秒扣 10 点生命

`damage = 10f` / `damageInterval = 3f`。

- **注入式**：`apply()` 每 tick 调用一次，按 tick 累计真实时间
  （`Time.delta / 60f` 折算为秒，与注入器耗油口径一致），跨过间隔即扣血并保留余量（避免长期漂移）。
- 走引擎标准伤害链路 `target.damage(damage)`，因此：
  - 自动计入 `Rules.blockHealth(team)`（规则可调的伤害系数）；
  - 血量归零时由引擎触发 `Call.buildDestroyed` → **正常爆炸拆除**（两端都正确：
    `Call.buildDestroyed` 在客户端走本地销毁、服务端额外广播）；
  - 有命中反馈（`hitTime`）。
- **逐建筑计时**用 `damageTimers`（`ObjectMap<Building, Float>`）记录——引擎没有可借用的每建筑字段。
  清理依赖 System 的撤销保证：`reconcile`（不再生效时）与 `sweepInvalid`（清扫失效目标）
  都必定回调一次 `remove(Building)`，故不会泄漏表项。`remove()` 里删除该条目。

## 生命周期语义

- **倍率部分（耗电 / 速度）**：钩子式——`apply()` 只负责惰性安装钩子（与节能共用同一对钩子），
  倍率由钩子每次被引擎查询时回调 `BlockConsumerHooks.powerFactor/speedFactor` 实时读取。
  撤销时倍率自动回到 1.0，`remove()` 无需还原倍率。
- **掉血部分**：注入式——`remove()` 只清计时器，不需还原（没有累积型副作用）。
- 安装钩子与节能**共用同一次安装**：钩子每次查询时取「所有生效来源倍率之积」，
  与已安装几个效果无关（见 `BlockConsumerHooks` 类注释「多效果叠加」）。

## 互斥：与节能冲突时恒为节能胜出

`conflictsWith("energy_saving")`（节能侧对称声明）——两者是同一概念的相反档位，不应同时作用于一台工厂。

裁决规则（`BuildingBoostSystem.resolveMutex`）：优先级 → **效果 id 字典序**。
`energy_saving` < `overclock`，故冲突时**恒为节能胜出、本效果被挂起不生效**。

> 排序键刻意只用纯函数（优先级 + 效果 id），**不引入建筑 id / 放置顺序**：
> 字典序两端必然算出同一结果，且结果与玩家操作时序无关——不会因「谁先放」而改变。
>
> 单台效率控制塔任一时刻只提供一个效果（由 `Provider.provides(Boost)` 保证），
> 且**同队两塔范围重叠已被放置校验拦截**，故本规则实际只在旧存档/强制放置的遗留重叠场景生效。

## 展示信息

| 方法 | 内容 | 来源 |
|------|------|------|
| `name()` | `超频` / `Overclock` | bundle `boost.overclock.name` |
| `description()` | `+50% 生产速度；+75% 电力消耗；每 3 秒 -10 生命` | bundle `boost.overclock.desc` |
| `visual(Building)` | 原版状态图标 `StatusEffects.overdrive.uiIcon` | 强化按钮图标（底色 `Pal.lightFlame` 火焰橙） |

> 按钮**如何绘制**（位置/尺寸/按光标距离淡入/点击命中）与点击后**投递什么格式的消息**，
> 由 `BuildingBoostSystem` 与 `silicon.util.BoostOverlay` 负责，见 `docs/utils/BuildingBoostSystem.md`。

图标选 `overdrive`（而非 `overclock`——后者已用于润滑油），语义同为「加速」且火焰橙底板表达过热风险，
与润滑油的默认绿、节能的电量蓝区分开。

## 多人安全

- 倍率钩子**不缓存**每建筑状态：每次被引擎查询都回到 System 问一次 `isActive(build, id)`，无两端分歧窗口。
- 掉血在**两端各自**执行：血量是**服务器权威 + 同步**的网络化状态，故客户端的本地扣血只是即时反馈，
  会被服务器同步纠正，不会造成持久分歧。唯一时序风险是「两端进入超频的时刻相差 1 tick」，
  表现为客户端短暂多/少扣一次血，随后被同步抹平。
- 生效集合完全由「本队空间树 × 固定区域 × 模式」推导，两端输入一致。
- 本效果**不扣任何液体/物品**，无「扣多少网络化状态」的分支，故不需要确定性认领规则。

## 已知副作用

- `efficiency` 被抬到 1.5 是**总标量**，故凡依赖它的判定都等比变化（生产进度、`optionalEfficiency`、
  依赖 `efficiency > 0` 的产出节流）——这正是「生产速度 +50%」的期望语义。
- 方块面板的**耗电量**显示读的是钩子镜像的原值 `usage`（故仍显示标称值），
  **电力条**显示的是供电满足度 `power.status`，均不受倍率影响。即超频在面板上不可见，
  只能通过电网负载与产出速率观察（掉血则直接可见）。
- 掉血会**摧毁**工厂：血量归零即正常爆炸，可能连带损毁同格其他建筑——这是「以寿命换产能」的预期代价。
  若不希望它停机，把 `OverclockBoost.instance.damage` 设为 0 即可（代码已判 `damage <= 0`）。

## 配置参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `speedScale` | float | `1.5f` | 生产速率倍率（1.5 = +50%） |
| `powerScale` | float | `1.75f` | 电力需求倍率（1.75 = +75%） |
| `damage` | float | `10f` | 每次扣血量（生命值），≤0 则关闭扣血 |
| `damageInterval` | float | `3f` | 扣血间隔（秒），≤0 则关闭扣血 |

倍率与扣血字段均为 `OverclockBoost.instance` 上的 public 字段，实时读取，可直接改。

## 版本历史

| 版本 | 变更 |
|------|------|
| a0.x | 初始实现：耗电工厂获得生产 +50% / 耗电 +75%（经 `BlockConsumerHooks`，与节能共用钩子）+ 每 3 秒扣 10 生命（注入式，`ObjectMap` 逐建筑计时，`remove()` 清理）；与节能互斥；图标 `StatusEffects.overdrive`、底色 `Pal.lightFlame` |
| a0.x | 互斥裁决沿用「优先级 + 效果 id 字典序」（恒为节能胜出），未采用「先放置者胜」 |
