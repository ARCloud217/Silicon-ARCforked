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
| `name()` | 显示名称（本地化，UI 展示用），默认取 `id()` |
| `description()` | **简要强化描述**（本地化），一句话说明收益，如「+100% 旋转速度；+20% 攻击速度」。**每个 Boost 必须实现**；数值须与实际生效值一致 |
| `canTarget(target)` | **目标过滤**（写在各 Boost 里，System 登记前读取）：只接受本效果能作用的对象（如只收炮塔），默认放行 |
| `shouldApply(target)` | 触发条件（无副作用，每帧询问） |
| `apply(target)` | 生效（激活期间每 tick 一次） |
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
| `targets()` | 候选目标集合（默认 `proximity`）；返回 `Seq` 供零分配遍历。强化器可**前置过滤**——非可用对象（如非炮塔）不进 System 循环 |
| `boosts()` | 本强化器提供的效果列表（返回 `Seq`；实现方宜缓存复用该 Seq，避免每帧新建） |
| `canTarget(target)` | 自身附加过滤（默认全放行，如「存油才提供」） |
| `updateBoosts()` | 接入 System 驱动，Build 的 `update()` 末尾调用 |
| `removeProviderBoosts()` | 在 Build 的 `onRemoved()` 里调用，让 System 撤销本强化器提供的一切强化 |

### `BoostVisual` / `VisualRenderer`

- `Boost` 通过 `visual()` 告诉 System「显示什么」（描述对象）；
- `BoostVisual` 目前提供三个字段方法：
  - `type()` 视觉类型标识（渲染管线分发用，未定型）；
  - `icon(target)` **强化图标**（`TextureRegion`）——建议直接取用**原版图集资源**以贴合游戏风格
    （如 `StatusEffects.overclock.uiIcon`），null 则不绘制；
  - `color()` 徽记配色（用作底板色，**null 时渲染器用默认 `Pal.accent`**）；
- System 经可插拔的 `visualRenderer`（`VisualRenderer` 接口）在 `drawBoosts()` 里统一调度绘制：
  `render(target, visual, index)`——`index` 为该 boost 在目标身上的序号（0 起），供多个徽记排开；
- **现成渲染器**：`silicon.util.BoostOverlay`——实现 `VisualRenderer` 并注册为 `visualRenderer`，
  挂 `Trigger.draw` → `drawBoosts()`，绘制与交互规则：
  - **强化信息按钮**：只要目标身上有任意一个生效的 boost，就在方块左下角渲染一个**可点击按钮**；
    固定 **0.5 格（4×4px）**，锚在 footprint 左下角那一格内侧（紧贴角、不越出方块）；
  - **按钮外观**：统一色 `#99cc3366` 底板（与消息气泡同色，alpha `0x66`=0.4，悬停提亮至 0.62）
    + 亮色细描边 + 原版图标（占 80%）；**悬停时**底板与描边提亮并放大 1.25 倍，提示可点
    （悬停判定用世界坐标比对按钮矩形）；
  - **多个效果合并为一个按钮**（只画第一个），各自详情在消息面板里列出；
  - **点击行为**：向消息面板（`MessageSystem`）投递一条 **10s 时限**消息——
    气泡色 `#99cc3366`（`background()` 同时用作消失时间覆盖层色）；
    标题 `{方块名称}中生效的Boost`，其中**方块名以 `[accent][]` 强调**（key `boost.info.title` + `{0}` 占位符）；
    内容逐行一个 boost，格式 `[cyan]{强化名称}[]：{强化效果}`（key `boost.info.line` 经 `bundle.format` 填充，
    青色名称 + 全角冒号分隔，换行分隔多行）；
    **消息图标用该建筑自身的贴图**（`block.uiIcon` 包成 `TextureRegionDrawable` 并染白，
    缺失时回退 `Icon.info`），便于一眼看出是哪个方块上的强化；
  - 命中测试用**上一帧**记录的按钮世界矩形（`Trigger.update` 轮询 `keyTap(mouseLeft)` + `mouseWorldX/Y`），
    `Hit` 对象按索引复用，无每帧分配；
  - 只画己方 + 视野外跳过（视野矩形每帧只算一次）。
  接入入口 `BoostOverlay.init()`（在 `Silicon.init()` 中调用一次；无头服务器自动跳过）。

## 状态表

| 表 | 结构 | 作用 |
|------|------|------|
| `registry` | `id → Boost` | 效果单元注册表（自动注册，重复 id 覆盖） |
| `contributors` | `target → (boostId → 强化器集合)` | **唯一意愿来源**：集合人数即提供者计数，亦是撤销归属；空集合/空条目即时回收 |
| `suppressed` | `target → (败者Id → 胜者Id)` | 互斥挂起 |
| `active` | `target → (boostId → 是否生效)` | 实际生效快照，驱动 apply/remove（**原地更新**，不每 tick 新建快照） |
| `providerSet` | `Provider 集合` | 存活强化器集合，供每 tick 清扫失效 provider |

> 无独立「pending 计数表」：冲洗发生在本 tick 首个强化器登记**之前**，此时 `contributors` 恰是上一 tick
> 登记完的最终状态，直接读集合人数即可得出应生效集合——省掉每目标每 tick 的内层 Map 分配与计数维护。

## 每帧驱动流程（两阶段，按世界 tick 归组）

`updateBoosts(provider)`（每强化器每帧调一次）：

1. **注册阶段**：对每个目标过公共关（Provider 前置 `targets()` 已滤掉的不会到此）`sameTeam(自身, 目标) && isBoostable(目标) && provider.canTarget(目标)`，
   通过后再过每个 Boost 自身的 `canTarget(目标)` 过滤，最后问 `Boost.shouldApply`；把「想要」的强化器记进 `contributors` 集合，
   （**零分配**：仅在真正增删成员时创建内层容器，稳态不新建对象；空集合/空条目即时回收）。
2. **冲洗阶段**（用 `Vars.state.tick` 归组，**每 tick 只会执行一次**，由本 tick 首次调用触发）：应生效集合直接由
   `contributors` 推导（人数 > 0 且未被挂起），无需独立计数表：
   - `resolveMutex`：对目标上所有「有人提供」的效果按优先级（同级按 id 字典序）排序、贪心保留；与已保留胜者冲突者写进 `suppressed`（挂起不生效）。挂起表每 tick 按本轮结论重建，胜者消失后败者自然恢复参选。**快速路径**：效果数 ≤ 1 直接判定无冲突，跳过排序与临时集合；
   - `reconcile`：应生效的**每 tick 调用一次 `apply`**（保证注入式逐帧累加、不叠加），不再应生效的调用一次 `remove`；生效状态表**原地更新**，不新建快照；
   - 随后 `sweepInvalid()`（**每 tick 一次**，不再每强化器每帧各跑一遍）清扫失效目标/强化器。

## 不叠加规则

**一个目标、同一个效果 id，无论本 tick 有多少强化器在提供它，效果也只生效一份、不会成倍放大**——只问「提供者人数 > 0」，
因此每 tick 至多 `apply` 一次（如同「两个一样的 buff 不会并存」）。效果**持续由强化器控制**：只要还有强化器在提供
就保持生效；提供方全部撤走 / 强化器失效 / 目标失格时才 `remove`。

## 生命周期兜底

- `sweepInvalid()`：**每 tick 一次**（随 `flushFrame` 触发，不再每强化器每帧各跑一遍），扫 `active`/`contributors`/`suppressed`，目标已失效/拆除时调 `removeBuilding()`——撤销该目标仍生效的效果并清光全部状态；同时扫 `providerSet`，强化器本机已失效的调 `removeProvider()` 清掉贡献——**不留残留、不漏撤销**。
- `removeProvider(provider)`：**即时**撤销该强化器对全部目标的贡献并重算受影响目标（在 Build.`onRemoved()` 里经默认方法 `removeProviderBoosts()` 调用，也可由清扫兜底触发），防止拆除后贡献残留在状态表里。

## 性能与资源约定

| 点 | 做法 |
|----|------|
| 每帧分配 | 登记路径**零分配**：仅在成员真正增删时创建内层容器；`flushBuffer`/`affectedBuffer`/`idBuffer` 等暂存集合全部复用 |
| 快照 | `active` 生效表**原地更新**，不再每 tick 为每个目标新建快照 Map |
| 清扫 | `sweepInvalid` 由每帧 N 次（每强化器一次）降为**每 tick 1 次** |
| 互斥 | 效果数 ≤ 1 走快速路径，跳过排序与临时 Seq/Map |
| 迭代 | `ObjectMap`/`ObjectSet` 一律「先收集再删除」（`keys()` 依赖内部数组，边遍历边 remove 会抛并发修改异常）；用可复用缓冲中转 |
| Provider 侧 | `targets()`/`boosts()` 返回 `Seq` 走下标遍历；实现方应缓存复用（注入器按 tick 重建并复用炮塔缓存，扣油阶段共用同一份） |
| 渲染 | `BoostOverlay` 每帧只算一次视野矩形；徽记为固定 4px 按钮故不会超出方块轮廓；命中区对象按索引复用；无面板 UI（详情走消息面板，零 UI 开销） |

## 多人 / 队伍安全

- `sameTeam` 直接以 `Team` 枚举单例的 `==` 比较：跨端一致、确定，天然排除敌队/derelict，不依赖本地身份。
- **目标过滤四层把关**（缺一不可，任何效果生效前都过）：
  1. Provider `targets()` 前置过滤——非可用对象连 System 循环都不进；
  2. System 全局名单 `boostableTypes`（默认仅放行炮塔）；
  3. 同队 `sameTeam`；
  4. 每个 Boost 自带的 `canTarget(target)`（过滤逻辑写在 Boost 单元内，由 System 读取执行）。
  防止对非目标方块误用炮塔专用 API 造成崩溃。

### 多人兼容约定

- 本系统状态（`contributors`/`active`/…）是**纯 JVM 本地**的，客户端与服务器各自驱动、不同步——
  因为它只影响**纯本地模拟**（炮塔 `reloadCounter`/转角，机制同原版强化液），两端由相同输入算出相同结果。
- **队伍**：一律 `==` 比较 `Team`（跨端一致枚举单例）；禁止用本地玩家身份做规则判定（服务器无本地玩家）。
- **确定性红线**：任何决定「扣/加多少网络化状态（液体/功率/库存）」的分支都**不得依赖 update 顺序或
  「谁先注册」**——两端建筑 update 顺序不保证一致，会造成永久分歧（desync）。必须用两端一致的量裁决
  （tile 坐标、建筑 id、队伍、已同步的液体量）。参考实现：`LubricantInjector.owns()` 按 (tileX, tileY)
  裁决唯一认领者。System 曾提供的 `claimedByOther`（基于「谁先跑 update」）因属顺序依赖、存在 desync 隐患，
  **已删除**，不再作为对外 API。
- 客户端独有逻辑（`BoostOverlay` 调试渲染）由 `Vars.headless` 跳过，且只读网络化状态。

## 对外 API

| 方法 | 说明 |
|------|------|
| `addBoostable(Class)` | 向规则名单追加可强化类型（加载期登记） |
| `isBoostable(Building)` | 名单检查 |
| `sameTeam(Building, Building)` | 同队判定 |
| `get(id)` | 注册表按 id 取效果 |
| `register(Boost)` | 自动注册效果（加载期调用，重复 id 覆盖） |
| `activeBoosts(target)` | 查目标当前生效的**效果单元列表**（`Seq<Boost>`，每次新建，**仅供点击/开面板等低频路径**，勿放每帧循环） |
| `hasActiveBoosts(target)` | 目标是否还有生效强化（零分配，供每帧轮询） |
| `isProviderOf(target, provider, id)` | 提供归属：本强化器是否当前有效提供者（读 `contributors`，两端一致，**可安全用于**耗网络化资源决策） |
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
| a0.x | 多人兼容加固：`sweepInvalid()` 改为先收集再删除（避免 `ObjectMap.keys()` 边遍历边 remove 抛并发修改）；新增「多人兼容约定」章节（队伍 `==`、网络化状态不得依赖 update 顺序）；`claimedByOther` 标注为仅限本地表现 |
| a0.x | 性能优化：删除 `pending` 计数表（应生效集合直接由 `contributors` 人数推导）；`active` 原地更新不再每 tick 新建快照；`sweepInvalid` 由每帧 N 次降为每 tick 1 次；互斥 ≤1 效果走快速路径；登记路径零分配 + 暂存集合全部复用；`Provider.targets()/boosts()` 改返回 `Seq` 走下标遍历；注入器缓存复用炮塔列表与 boost 列表；`BoostOverlay` 每帧只算一次视野、复用 `GlyphLayout` |
| a0.x | 删除顺序依赖的 `claimedByOther` API（无人调用且有 desync 隐患）；修复 3 处「`ObjectMap.keys()` 边遍历边 remove」的并发修改崩溃点 |
| a0.x | 视觉改为**图标徽记**：移除文字显示，`BoostVisual.label` → `icon(TextureRegion)`；`VisualRenderer.render` 增加 `index` 参数支持多徽记沿底边排开；`BoostOverlay` 在方块左下角绘制原版图标 + 底板（`Layer.overlayUI`）；移除随之无用的 `boost.lubricant.name` bundle |
| a0.x | 徽记样式调整：底板色由润滑油棕改为游戏强调色 `Pal.accent`（绿）；边长按方块尺寸自适应、以 32px 为上限（1×1→8×8，2×2→16×16），紧贴 footprint 左下角内侧且不越出方块轮廓；多个徽记按方块宽度换行 |
| a0.x | 徽记固定为左下角 **1×1 格**（8×8px，不再随方块放大）；多效果合并为一枚徽记。`Boost` 新增 `name()` / `description()`（描述为**必实现**项）。新增**点击交互**：点击徽记展开强化详情面板（列出各生效 boost 名称+简述），支持再点/×/Esc/点别处关闭、目标失效自动关闭；新增 `hasActiveBoosts()` 零分配判活；bundle 新增「建筑强化系统」分区（`boost.panel.title`、`boost.lubricant.name/desc`） |
| a0.x | **重写为强化信息按钮**：去掉面板 UI，改为固定 0.5 格（4×4px）可点击按钮（`#99cc3366` 底板 + 亮色描边 + 原版图标，悬停提亮放大）；点击向消息面板投递 10s 时限消息（气泡 `#99cc3366`）：标题 `[accent]{方块名}[]中生效的Boost`，内容逐行 `[cyan]{强化名}[]：{强化效果}`，消息图标用目标建筑的 `uiIcon` |