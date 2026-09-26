# BuildingBoostSystem

## 基本信息

| 属性 | 值 |
|------|----|
| 文件 | `src/silicon/util/BuildingBoostSystem.java` |
| 包 | `silicon.util` |
| 类型 | `final class` 静态工具/调度器 |
| 效果实现位置 | `src/silicon/util/boosts/`（具体 Boost 效果类） |

建筑强化（Boost）体系：一套给「建筑/机器」附加 buff 的**建筑专用强化功能系统**，统一管理强化器（Provider）→ 效果（Boost）→ 目标（Building）的全流程。

## 设计目标

把「谁可以被强化、由谁提供强化、强化什么」解耦成注册式系统，供各类支援方块（如润滑油注入器）复用，避免每个方块私写一套炮塔专用逻辑。

**职责分工**：

| 角色 | 职责 |
|------|------|
| `System` | 驱动循环、资格/队伍校验、跨强化器状态归并、互斥裁决、应用/撤销生命周期、视觉调度 |
| `Boost` | 只写「这个 buff 怎么算、怎么生效」：判定/字段操作/互斥声明/视觉描述 |
| `Provider` | 只声明「我是谁、影响谁、提供哪些 Boost」 |

## 接口契约（System 的嵌套类型）

### `Boost` — 一个 buff 效果单元

| 方法 | 说明 |
|------|------|
| `id()` | 唯一 id，自动注册进注册表，互斥声明/日志引用用 |
| `canTarget(target)` | **目标过滤**（写在各 Boost 里，System 登记前读取）：只接受本效果能作用的对象（如只收炮塔），默认放行 |
| `shouldApply(target)` | 触发条件（无副作用，每帧询问） |
| `apply(target)` | 生效（激活期间每帧一次） |
| `remove(target)` | 撤销（失格/被顶替/目标失效时一次） |
| `priority()` | 互斥优先级，数值大者胜，默认 0 |
| `conflictsWith(otherId)` | 互斥声明：与指定效果是否冲突 |
| `visual(target)` | 视觉描述（可空，返回 `BoostVisual`） |

`apply` 的两种实现语义（实现自选，保持统一）：

| 语义 | apply | remove | 适用 |
|------|-------|--------|------|
| 注入式 | 每帧把数值加进目标（如 `reloadCounter += ...`） | 无需还原 | 数值累加类（攻速充能） |
| 引用式 | 幂等（内部引用计数，首次才改字段） | 计数递减归零才还原 | 离散倍率类（如 reloadMultiplier） |

### `Provider` — 强化器 mixin

| 方法 | 说明 |
|------|------|
| `building()` | 自身建筑实体 |
| `targets()` | 候选目标集合（默认 `proximity`）；强化器可**前置过滤**——非可用对象（如非炮塔）不进 System 循环，避免无谓登记 |
| `boosts()` | 本强化器提供的效果列表 |
| `canTarget(target)` | 自身附加过滤（默认全放行，如「存油才提供」） |
| `updateBoosts()` | 接入 System 驱动，Build 的 `update()` 末尾调用 |
| `removeProviderBoosts()` | 在 Build 的 `onRemoved()` 里调用，让 System 撤销本强化器提供的一切强化 |

### `BoostVisual` / `VisualRenderer`

- `Boost` 通过 `visual()` 告诉 System「显示什么」（描述对象）；
- `BoostVisual` 目前提供三个字段方法：
  - `type()` 视觉类型标识（渲染管线分发用，未定型）；
  - `label(target)` 显示名称——**调试渲染**：在目标方块上方绘制该效果名（null/空串不绘制）；
  - `color()` 显示颜色（null 用渲染器默认色）；
- System 经可插拔的 `visualRenderer`（`VisualRenderer` 接口）在 `drawBoosts()` 里统一调度绘制；
- **现成渲染器**：`silicon.util.BoostOverlay`（调试用）——实现 `VisualRenderer` 并注册为
  `visualRenderer`，挂 `Trigger.draw` → `drawBoosts()`，在己方且视野内的受惠方块顶部绘制效果名
  （每帧遍历 active 状态；复用 SignalOverlay 的 Fonts 绘制模式，绘制后恢复字体状态）。
  接入入口 `BoostOverlay.init()`（在 `Silicon.init()` 中调用一次；无头服务器自动跳过）。

## 状态表

| 表 | 结构 | 作用 |
|------|------|------|
| `registry` | `id → Boost` | 效果单元注册表（自动注册，重复 id 覆盖） |
| `pending` | `target → (boostId → 本 tick 意愿计数)` | 每帧按 `contributors` 集合人数重算的当帧意愿，每 tick 冲洗后就地清空 |
| `contributors` | `target → (boostId → 强化器集合)` | 持久意愿来源与撤销归属（谁在提供），**计数之源** |
| `suppressed` | `target → (败者Id → 胜者Id)` | 互斥挂起 |
| `active` | `target → (boostId → 是否生效)` | 实际生效快照，驱动 apply/remove |
| `providerSet` | `Provider 集合` | 存活强化器集合，供每帧清扫失效 provider |

## 每帧驱动流程（两阶段，按世界 tick 归组）

`updateBoosts(provider)`（每强化器每帧调一次）：

1. **注册阶段**：对每个目标过公共关（Provider 前置 `targets()` 已滤掉的不会到此）`sameTeam(自身, 目标) && isBoostable(目标) && provider.canTarget(目标)`，
   通过后再过每个 Boost 自身的 `canTarget(目标)` 过滤，最后问 `Boost.shouldApply`；把「想要」的强化器记进 `contributors` 集合，
   **`pending` 计数每帧按该集合人数重算**（contributors 跨帧持久、pending 每 tick 冲洗即清空，不能只靠增删时的增量——否则首帧后计数永远归零）。
2. **冲洗阶段**（用 `Vars.state.tick` 归组，**每 tick 只会执行一次**，由本 tick 首次调用触发）：对上一 tick 累计的 `pending`：
   - `resolveMutex`：对目标上所有「有人想要」的效果按优先级（同级按 id 字典序）排序、贪心保留；与已保留胜者冲突者写进 `suppressed`（挂起不生效）。挂起表每帧按本帧结论重建，胜者消失后败者自然恢复参选；
   - `reconcile`：以「`pending>0` 且未被挂起」为应生效集合，对比上一帧 `active` 快照——**生效期间每帧调用一次 `apply`**（保证注入式每帧累加、不叠加），条件失格（计数归 0 / 被挂起 / 目标失效）时调用一次 `remove`；写入本帧快照并清空 `pending`。

## 不叠加规则

**一个目标、同一个效果 id，无论本 tick 有多少强化器在提供它，效果也只生效一份、不会成倍放大**——评审只问「计数 > 0」，
因此每帧至多 `apply` 一次（如同「两个一样的 buff 不会并存」）。效果**持续由强化器控制**：只要还有强化器在提供
（计数 > 0）就保持生效；提供方全部撤走 / 强化器失效 / 目标失格时才 `remove`。

## 生命周期兜底

- `sweepInvalid()`：每帧扫 `active`/`pending`/`contributors`/`suppressed`，目标已失效/拆除时调 `removeBuilding()`——撤销该目标仍生效的效果并清光全部状态；同时扫 `providerSet`，强化器本机已失效的调 `removeProvider()` 清掉贡献——**不留残留、不漏撤销**。
- `removeProvider(provider)`：**即时**撤销该强化器对全部目标的贡献并重算受影响目标（在 Build.`onRemoved()` 里经默认方法 `removeProviderBoosts()` 调用，也可由清扫兜底触发），防止拆除后贡献残留在状态表里。

## 多人 / 队伍安全

- `sameTeam` 直接以 `Team` 枚举单例的 `==` 比较：跨端一致、确定，天然排除敌队/derelict，不依赖本地身份。
- **目标过滤四层把关**（缺一不可，任何效果生效前都过）：
  1. Provider `targets()` 前置过滤——非可用对象连 System 循环都不进；
  2. System 全局名单 `boostableTypes`（默认仅放行炮塔）；
  3. 同队 `sameTeam`；
  4. 每个 Boost 自带的 `canTarget(target)`（过滤逻辑写在 Boost 单元内，由 System 读取执行）。
  防止对非目标方块误用炮塔专用 API 造成崩溃。

## 对外 API

| 方法 | 说明 |
|------|------|
| `addBoostable(Class)` | 向规则名单追加可强化类型（加载期登记） |
| `isBoostable(Building)` | 名单检查 |
| `sameTeam(Building, Building)` | 同队判定 |
| `get(id)` | 注册表按 id 取效果 |
| `register(Boost)` | 自动注册效果（加载期调用，重复 id 覆盖） |
| `activeBoosts(target)` | 查目标当前生效效果集 |
| `claimedByOther(target, provider, id)` | 认领检查：该效果是否已被**其它**强化器认领（供 canTarget 取消重复提供） |
| `isProviderOf(target, provider, id)` | 提供归属：本强化器是否当前有效提供者（供耗资源决策） |
| `updateBoosts(provider)` | 每帧驱动入口（兼触发每 tick 统一冲洗） |
| `removeProvider(provider)` | 移除强化器并即时重算受影响目标（`onRemoved` 钩子 / 清扫兜底） |
| `drawBoosts()` | 渲染管线每帧调用，调度视觉 |

## 使用示例

### 1. 实现一个 Boost 效果（`src/silicon/util/boosts/` 下）

```java
public class AttackSpeedBoost implements BuildingBoostSystem.Boost {
    @Override public String id() { return "attack-speed"; }
    @Override public boolean shouldApply(Building target) {
        return target instanceof Turret.TurretBuild t && t.isShooting();
    }
    @Override public void apply(Building target) {   // 注入式：每帧累加
        Turret.TurretBuild t = (Turret.TurretBuild) target;
        t.reloadCounter += 0.2f * t.edelta() * (t.hasAmmo() ? t.peekAmmo().reloadMultiplier : 1f);
    }
    @Override public void remove(Building target) { /* 注入式无需还原 */ }
    @Override public int priority() { return 1; }
    @Override public boolean conflictsWith(String otherId) { return otherId.equals("overdrive"); }
    @Override public BoostVisual visual(Building target) { return new MyBoostVisual(); }
}
```

加载期注册：`BuildingBoostSystem.register(new AttackSpeedBoost());`

### 2. Provider 方块接入

```java
public class LubricantInjectorBuild extends Building implements BuildingBoostSystem.Provider {
    @Override public Building building() { return this; }
    @Override public Iterable<BuildingBoostSystem.Boost> boosts() {
        return Seq.with(BuildingBoostSystem.get("attack-speed"), BuildingBoostSystem.get("rotation-speed"));
    }
    @Override public void update() {
        super.update();
        updateBoosts();   // 一行接入驱动
    }
}
```

## 版本历史

| 版本 | 变更 |
|------|------|
| a0.x | 初始骨架：注册表、状态表、每帧驱动、互斥裁决、撤销生命周期、视觉调度预留 |
| a0.x | 重构为两阶段驱动：`pending` 本 tick 跨强化器累计意愿 + 每 tick 统一冲洗（flushFrame）；生效期间**每帧 apply**、不叠加（同目标同效果至多一份）；新增 `providerSet`/`removeProvider`/`onRemoved` 钩子，强化器拆除即时撤销贡献 |
| a0.x | 目标过滤双层化 + 调试视觉上线：`Boost.canTarget(target)` 过滤由 System 登记前读取；`BoostVisual` 增加 `label`/`color`，新增 `BoostOverlay` 调试渲染器在受惠方块上方绘制效果名（`Trigger.draw` 挂钩，己方 + 视野裁剪） |
| a0.x | 修复生效持续性问题：`pending` 每帧按 `contributors` 集合人数重算（而非仅增删时增量），解决 contributors 持久 + pending 每 tick 清空导致的首帧后计数归零、效果 apply 后被误 remove 的问题 |