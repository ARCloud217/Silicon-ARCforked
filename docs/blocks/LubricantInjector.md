# LubricantInjector

## 基本信息

| 属性 | 值 |
|------|----|
| 类名 | `LubricantInjector` |
| 父类 | `Block` |
| 分类 | Category.turret |
| 尺寸 | 2x2 |
| 血量 | 220 |
| 架构 | `BuildingBoostSystem.Provider`（强化内容封装为 Boost，由 System 驱动） |

## 合成配方

| 材料 | 数量 |
|------|------|
| Copper | 120 |
| Lead | 80 |
| Silicon | 40 |

## Block 属性

- `update`: true
- `solid`: true
- `hasLiquids`: true
- `outputsLiquid`: false
- `liquidCapacity`: 300
- `alwaysUnlocked`: true

## 机制说明

润滑油注入器是建筑强化系统（`BuildingBoostSystem`，见 `docs/utils/BuildingBoostSystem.md`）的第一个
Provider 落地实现。本方块**不再私写炮塔专用逻辑**，只负责四件事：

1. **前置目标过滤**：`targets()` 只把紧贴的炮塔交给 System——非炮塔/非可用对象**连 System 循环都不进入**，
   不尝试附 boost，避免无谓登记。
2. **锁油量 + 认领检查**：`canTarget()` 返回「存油 > 0.001 且 目标未被其它注入器抢先认领」——有润滑油才提供强化；
   油尽时 System 自动撤销已生效效果；目标已被别台注入器认领时本机**主动取消提供**（`claimedByOther`）。
3. **扣油**：按「本机实际认领且攻击中」的己方炮塔数量消耗（默认 5/s 每只，`consumePerTurret`）——唯生效者才扣油。
4. **声明 Boost**：`boosts()` 返回一个效果单元 `LubricantBoost`。

### 提供的效果（Boost）

润滑油提供的两种强化合并为**一个效果单元** `LubricantBoost`（id=`lubricant`），内部含两个子效果，
各自生效条件独立判定、激活期间每帧按需注入（注入式语义，失格即自动失效）：

| 子效果 | 内容 | 触发条件 | 注入内容 |
|--------|------|----------|----------|
| 攻速 | +20%（`firePotency=0.2`） | 炮塔 `isShooting()` | 每帧 `reloadCounter += firePotency×edelta()×ammoRM`，与强化液同加法池 |
| 转角 | 速率 ×2（`rotationPotency=1.0`，引擎 1×+效果 1×） | `hasAmmo() && shouldTurn()` | 与引擎 turnToTarget 同式再推一格，目标角与引擎分支同源（自动索敌/玩家控制/逻辑控制），本帧无瞄准角则跳过 |

- 攻速子效果仅攻击中生效 → 无攻击时零消耗零加成。
- 转角子效果只要存油即生效，不额外耗油。
- 效果为**注入式**（`remove` 无残留），自动注册进 System（静态块 `register(instance)`）。
- **调试视觉**：`visual()` 返回带标签（`boost.lubricant.name`，中英 bundle 已配）与颜色（润滑油色
  `8a5a2b`）的 `BoostVisual`；调试阶段由 `BoostOverlay` 在受惠炮塔上方显示「润滑油强化」字样。

### 不叠加与持续规则

- **不叠加**：效果持续由注入器控制（只要这台注入器还认领着并向炮塔提供就保持生效）；同一炮塔的 `lubricant`
  效果无论多少台注入器紧贴都只生效**一份**，`apply` 每帧至多一次、不会成倍放大（见
  `docs/utils/BuildingBoostSystem.md`「不叠加规则」）。
- **认领制（唯生效者耗油）**：本注入器在 `canTarget()` 里检查目标——若该炮塔的润滑油效果已被别台注入器
  登记认领（`claimedByOther`），本机**取消提供**；只有占到认领的那台提供效果并扣油，其余紧贴注入器不提供
  也不耗油。两台注入器夹同一炮塔时 → 效果一份、油耗一份（无双击油耗）。
- 认领随提供失效自然交接：占位者油尽/失格被 System 移出贡献后，认领空出，紧贴的另一台下一帧自动接管。

### 驱动流程

```
LubricantInjectorBuild.update()
 ├─ updateBoosts()  →  System（按世界 tick 每帧统一冲洗一次）
 │                     ├─ 前置过滤：targets() 只交炮塔（非可用对象不进循环）
 │                     ├─ 资格：同队 + 名单(isBoostable) + canTarget(存油 && 未被他人认领)
 │                     ├─ Boost 目标过滤：LubricantBoost.canTarget(仅炮塔)
 │                     ├─ 问 LubricantBoost.shouldApply → 每帧按认领集合重算计数
 │                     ├─ 互斥裁决 → apply（生效期间每帧一次，不叠加）/ remove
 │                     └─ sweepInvalid：清扫失效目标 / 失效注入器
 └─ 按「认领中(isProviderOf)且攻击中」的炮塔数扣油（Time.delta/60 折算真实秒）
LubricantInjectorBuild.onRemoved()
 └─ removeProviderBoosts()  →  System 即时撤销本机提供的一切强化
```

### 生涯/状态管理

- 目标炮塔失格（停止攻击/弹药耗尽/油尽/被拆）→ System 自动调 `remove()`，无残留。
- 注入器被拆除/摧毁 → `onRemoved()` 里调 `removeProviderBoosts()`，System 即时撤销它的一切贡献。
- 炮塔不满足 `isBoostable`（默认名单仅 `TurretBuild`）→ 直接跳过，不会误用炮塔专用 API。

## 配置参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `consumePerTurret` | float | 5f | 每个攻击中的受惠炮塔每秒润滑油消耗 |

Boost 的数值可改单例公开字段：`LubricantBoost.instance.firePotency` / `LubricantBoost.instance.rotationPotency`。

## 液体处理

- **仅接受同队供给、且本机留有余量**：`acceptLiquid = source.team == team && liquid == lubricant && 存量 < 容量`
- 多人安全：队伍比较用 `==`（Team 枚举单例，见 BuildingBoostSystem 文档）

## 版本历史

| 版本 | 变更 |
|------|------|
| a0.x | 初始：攻速 +20%（强化液同池加法）、转角 ×2（自动索敌/玩家控制同等生效）、5/s 消耗、同队门禁 |
| a0.x | 重构为 BuildingBoostSystem Provider：强化内容抽为 Boost（`silicon.util.boosts`），资格/队伍/互斥/撤销生命周期交由 System 统一管理 |
| a0.x | 合并为单一 `LubricantBoost`（id=`lubricant`）：攻速/转角作为两个子效果打包在一个效果单元内 |
| a0.x | 落实「不叠加 + 持续由注入器控制」：System 每帧统一冲洗、同一效果至多一份；新增 `onRemoved()` → `removeProviderBoosts()` 拆除即撤销 |
| a0.x | 认领制（唯生效者耗油）：`canTarget` 检查 `claimedByOther`，目标已被别台认领则本机取消提供；扣油按 `isProviderOf(target) && isShooting` 计数，双注入器夹同一炮塔不再双倍油耗 |
| a0.x | 目标过滤双层化：注入器 `targets()` 前置过滤只交炮塔（非可用对象不进 System 循环）；`Boost.canTarget` 目标过滤 + System 读取执行（`LubricantBoost.canTarget` = 仅炮塔） |
| a0.x | 调试视觉：`LubricantBoost.visual()` 返回带名/色的 `BoostVisual`，受惠炮塔上方由 `BoostOverlay` 绘制效果名 |