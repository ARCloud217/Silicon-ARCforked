package silicon.util;

import arc.graphics.Color;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.world.blocks.defense.turrets.Turret;

/**
 * 建筑强化系统：统一管理强化器（{@link Provider}）给建筑提供 boost 的全过程。
 *
 * <p>接口契约（{@link Boost} / {@link Provider} / {@link BoostVisual}）全部收敛在本类，
 * 具体效果实现放在 {@code silicon.util.boosts} 包（实现 {@link Boost} 并在加载期
 * {@link #register(Boost)} 自动注册）。
 *
 * <p>职责划分：
 * <ul>
 *   <li><b>驱动</b>：强化器每帧调一次 {@link #updateBoosts(Provider)}，System 先把
 *       所有强化器对同一目标的意愿合并去重，再在每 tick 统一裁决一次后应用/撤销——
 *       保证同一个目标、同一个效果 id，一帧最多调用一次 {@link Boost#apply(Building)}；</li>
 *   <li><b>不叠加规则</b>：一个目标只能持有同一效果的一份（如同"两个一样的 buff 不会并存"）。
 *       即使多个强化器同时为一个目标提供同一效果，效果也只生效一份、不会成倍放大；</li>
 *   <li><b>资格与队伍</b>：目标过滤四层把关，任何效果生效前都过这几关，
 *       防止对非目标方块误用炮塔专用 API 造成崩溃：
 *       <ol>
 *         <li>Provider 前置过滤 {@link Provider#targets()}（如只把炮塔交给 System，非可用对象不进循环）；</li>
 *         <li>System 全局名单 {@link #boostableTypes}；</li>
 *         <li>同队 {@link #sameTeam(Building, Building)}；</li>
 *         <li>每个 Boost 自带的目标过滤 {@link Boost#canTarget(Building)}（由 System 读取执行）。
 *             任一关不过即不登记该效果的贡献。</li>
 *       </ol></li>
 *   <li><b>生命周期（持续由强化器控制）</b>：效果只要强化器还在提供（如仍存油）就持续生效；
 *       强化器停止提供/被拆除/目标失效时，System 自动撤销（{@link Boost#remove(Building)}），
 *       不留残留——强化器经 {@link #removeProvider(Provider)}（在其 removed() 里调用）
 *       或在每帧清扫中发现失效而主动清理；</li>
 *   <li><b>互斥裁决</b>：冲突关系声明在每个 {@link Boost} 实现里
 *       （{@link Boost#conflictsWith(String)} + {@link Boost#priority()}），
 *       裁决与执行由本系统统一完成：同目标上冲突效果按优先级（同级按 id 字典序）选胜者，
 *       败者保持挂起不生效，胜者消失后自动解除；</li>
 *   <li><b>视觉调度</b>：显示内容由 boost 经 {@link Boost#visual(Building)} 提供，
 *       本系统负责经可插拔的 {@link VisualRenderer} 调度 {@link #drawBoosts()} 绘制
 *       （renderer 尚未定型，先留扩展点）。</li>
 * </ul>
 *
 * <p>apply/remove 语义：激活期间（count>0 且未被挂起）每帧调用一次 {@link Boost#apply(Building)}
 * （注入式每帧累加；引用式需幂等，首次才真正改动字段）；条件失格后调用一次
 * {@link Boost#remove(Building)} 干净撤销。
 */
public final class BuildingBoostSystem {

    /** 可视化渲染接入点（预留）：由渲染管线设置为真正的实现；未设置时视觉不绘制。 */
    public static VisualRenderer visualRenderer;

    /** 渲染接入接口：System 负责调度，具体画法尚未定型。 */
    public interface VisualRenderer {
        void render(Building target, BoostVisual visual);
    }

    /** 可被强化的方块类型名单：只有命中的方块才允许被施加任何强化。默认仅放行炮塔。 */
    public static final Seq<Class<? extends Building>> boostableTypes = new Seq<>(Class.class);

    /** 注册表：id → 效果单元，加载期由各 boost 自动注册。 */
    private static final ObjectMap<String, Boost> registry = new ObjectMap<>();

    /** 贡献者：目标 → (boost id → 当前希望其生效的强化器集合)，用于去重/计数/撤销归属。 */
    private static final ObjectMap<Building, ObjectMap<String, ObjectSet<Provider>>> contributors = new ObjectMap<>();

    /** 本 tick 待裁决意愿：目标 → (boost id → 希望其生效的强化器数量)，跨强化器累计。 */
    private static final ObjectMap<Building, ObjectMap<String, Integer>> pending = new ObjectMap<>();

    /** 互斥挂起：目标 → (败者 id → 胜者 id)。胜者仍占位期间败者保持不生效。 */
    private static final ObjectMap<Building, ObjectMap<String, String>> suppressed = new ObjectMap<>();

    /** 当前实际生效状态：目标 → (boost id → 是否生效)，用于对比做应用/撤销。 */
    private static final ObjectMap<Building, ObjectMap<String, Boolean>> active = new ObjectMap<>();

    /** 存活强化器集合：用于每帧清扫失效 provider（防穿漏）。 */
    private static final ObjectSet<Provider> providerSet = new ObjectSet<>();

    /** 已冲洗本 tick 的标记（按世界 tick 归组，保证一帧只统一裁决一次）。 */
    private static double flushedTick = Double.MIN_VALUE;

    private BuildingBoostSystem() {
    }

    static {
        boostableTypes.add(Turret.TurretBuild.class);
    }

    /**
     * 一次具体的建筑强化效果单元（一个「buff」的实现）。具体实现放
     * {@code silicon.util.boosts} 包，加载期 {@link #register(Boost)} 自动注册。
     *
     * <p>职责：功能的判定/实现全部写在这里；System 只负责驱动、资格、队伍、
     * 互斥、不叠加与应用/撤销，不感知效果细节。
     *
     * <p>{@link #apply(Building)} 两种语义（实现自选并保持），系统统一按
     * 「激活期间每帧一次 apply、失格时一次 remove」驱动，且**不叠加**：一个目标
     * 同一效果每帧至多被 apply 一次，无论多少强化器在提供：
     * <ul>
     *   <li><b>注入式</b>：apply 每帧把数值加进目标（如 {@code reloadCounter += ...}），
     *       remove 无需还原，条件消失即自动失效；</li>
     *   <li><b>引用式</b>：apply 需幂等（内部引用计数，首次才真正改动字段），
     *       remove 计数递减、归零才还原。</li>
     * </ul>
     *
     * <p>互斥声明与本效果优先级都在这里声明，具体裁决与执行交给 {@link BuildingBoostSystem}。
     */
    public interface Boost {

        /** 唯一 id：自动注册进 System 注册表、供互斥声明与日志引用。 */
        String id();

        /**
         * 目标过滤：System 在登记贡献前读取本方法，控制本效果能否作用于该目标
         * （如只接受炮塔 {@code target instanceof Turret.TurretBuild}）。
         * 前置类型校验放这里，具体效果不必在 {@link #shouldApply(Building)}/{@link #apply(Building)}
         * 里反复判型。默认放行。
         */
        default boolean canTarget(Building target) {
            return true;
        }

        /** 触发条件：当前时刻 target 是否应保持本强化（System 每帧询问，需无副作用）。 */
        boolean shouldApply(Building target);

        /** 生效：激活期间每帧调用一次（同目标同效果每帧至多一次，不叠加）；实现需符合注入式/引用式约定。 */
        void apply(Building target);

        /** 撤销：失格 / 被互斥顶替 / 目标或强化器失效时由 System 调用，必须可还原、不得残留。 */
        void remove(Building target);

        /** 互斥优先级：与其它效果的冲突裁决用，数值大者胜。默认 0。 */
        default int priority() {
            return 0;
        }

        /** 互斥声明：与 {@code otherId} 是否冲突。冲突关系写在各实现里。 */
        default boolean conflictsWith(String otherId) {
            return false;
        }

        /**
         * 视觉描述（可空，预留）：告诉 System「本强化目前应向玩家显示什么」。
         * 渲染细节尚未定型，返回 {@link BoostVisual} 描述对象即可；绘制由 System 调度。
         */
        default BoostVisual visual(Building target) {
            return null;
        }
    }

    /**
     * 强化器（Boost Provider）mixin 接口：由提供 buff 的建筑 Build 类实现
     * （如润滑油注入器），向 System 声明提供哪些效果、作用于哪些目标。
     *
     * <p>Build 类在 update() 末尾调用一次 {@link #updateBoosts()}，System 即完成
     * 本 tick 贡献登记 → 统一裁决 → 应用/撤销（含跨强化器的去重合并）。
     * 强化器被拆除/摧毁时，须在 Build 的 onRemoved() 里调用 {@link #removeProviderBoosts()}，
     * 让 System 立即撤销它提供的一切强化（避免残留）。
     */
    public interface Provider {

        /** 自身建筑实体（System 以此取队伍/位置/团队资格）。 */
        Building building();

        /** 候选目标集合（默认取紧贴 proximity；范围型强化器可自行覆盖）。 */
        default Iterable<Building> targets() {
            return building().proximity;
        }

        /** 本强化器提供的效果单元列表（可来自 System 注册表按 id 引用，也可直接持有实例）。 */
        Iterable<Boost> boosts();

        /** 强化器自身的附加目标过滤（如朝向/距离），默认全放行；队伍与名单由 System 统一判。 */
        default boolean canTarget(Building target) {
            return true;
        }

        /** update() 里调用即可接入 System 的驱动循环。 */
        default void updateBoosts() {
            BuildingBoostSystem.updateBoosts(this);
        }

        /** 方块被拆除/摧毁时调用（写在 Build.onRemoved() 里）：让 System 撤销本强化器提供的一切强化。 */
        default void removeProviderBoosts() {
            removeProvider(this);
        }
    }

    /**
     * 视觉描述（可空，预留扩展点）：boost 通过它「告诉 System 显示什么」。
     * 调试阶段由 {@link BoostOverlay} 在目标方块上方绘制 {@link #label(Building)}；
     * {@link #type()} 供渲染管线分发（未定型，先用类名自由扩展）。
     */
    public interface BoostVisual {

        /** 视觉类型标识，供 System 渲染管线分发（未定型，先用类名自由扩展）。 */
        default String type() {
            return getClass().getSimpleName();
        }

        /** 显示名称：调试渲染时绘制在目标方块上方（null/空串则不绘制文字）。 */
        default String label(Building target) {
            return null;
        }

        /** 显示颜色：调试渲染文字用色（null 用渲染器默认色）。 */
        default Color color() {
            return null;
        }
    }

    /** 向规则名单追加一种可被强化的方块类型（供子类/配置在加载期登记）。 */
    public static void addBoostable(Class<? extends Building> type) {
        boostableTypes.add(type);
    }

    /** 规则名单检查：b 是名单中任一类型的实例才可被强化（null 恒为否）。 */
    public static boolean isBoostable(Building b) {
        if (b == null) {
            return false;
        }
        for (Class<? extends Building> type : boostableTypes) {
            if (type.isInstance(b)) {
                return true;
            }
        }
        return false;
    }

    /** 己方判定：多人下 Team 为跨端一致的枚举单例，== 即网络安全同队判，天然排除敌队/derelict。 */
    public static boolean sameTeam(Building a, Building b) {
        return a != null && b != null && a.team == b.team;
    }

    /** 用 id 从注册表取效果单元（未注册返回 null）。 */
    public static Boost get(String id) {
        return registry.get(id);
    }

    /** 自动注册：把一种效果单元登记进系统（重复 id 覆盖）。加载期调用。 */
    public static void register(Boost boost) {
        registry.put(boost.id(), boost);
    }

    /** 查询目标上当前生效的效果集（只读视角，null 安全）。 */
    public static ObjectSet<String> activeBoosts(Building target) {
        ObjectSet<String> out = new ObjectSet<>();
        ObjectMap<String, Boolean> map = active.get(target);
        if (map != null) {
            for (String id : map.keys()) {
                if (map.get(id, false)) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    /** 认领检查（供强化器在自己 canTarget 里调用）：目标该效果是否已被「其它」强化器登记提供。
     *  true = 已被别人抢先，本机应取消提供（不叠加 + 唯生效者耗资源的协作手段）。 */
    public static boolean claimedByOther(Building target, Provider provider, String boostId) {
        ObjectMap<String, ObjectSet<Provider>> tmap = contributors.get(target);
        if (tmap == null) {
            return false;
        }
        ObjectSet<Provider> set = tmap.get(boostId);
        return set != null && set.size > 0 && !set.contains(provider);
    }

    /** 提供归属查询（供强化器决定是否耗资源）：本机当前是否已登记为该目标该效果的有效提供者。 */
    public static boolean isProviderOf(Building target, Provider provider, String boostId) {
        ObjectMap<String, ObjectSet<Provider>> tmap = contributors.get(target);
        if (tmap == null) {
            return false;
        }
        ObjectSet<Provider> set = tmap.get(boostId);
        return set != null && set.contains(provider);
    }

    /** 每帧驱动入口：强化器在 update() 里调用。按世界 tick 分组，一帧只统一裁决一次。 */
    public static void updateBoosts(Provider provider) {
        Building building = provider.building();
        if (building == null || !building.isValid()) {
            removeProvider(provider);
            return;
        }

        // 新 tick 首次调用时，把上一 tick 汇总好的意愿整体裁决并应用/撤销
        if (flushedTick != Vars.state.tick) {
            flushFrame();
            flushedTick = Vars.state.tick;
        }

        providerSet.add(provider);
        for (Building target : provider.targets()) {
            if (target == null || !target.isValid()) {
                continue;
            }
            // 先登记本强化器对目标各效果的本 tick 意愿（0/1，跨强化器去重累计）
            collectContributions(provider, target,
                    sameTeam(building, target) && isBoostable(target) && provider.canTarget(target));
        }

        sweepInvalid();
    }

    /** 撤销强化器：清掉它的全部贡献并即时重算受影响目标（防止贡献残留、效果失控）。 */
    public static void removeProvider(Provider provider) {
        if (provider == null) {
            return;
        }
        providerSet.remove(provider);
        removeProviderContributions(provider);
    }

    // —— 内部：贡献登记 ——

    private static void collectContributions(Provider provider, Building target, boolean eligible) {
        ObjectMap<String, ObjectSet<Provider>> targetContributors = contributors.get(target, ObjectMap::new);
        ObjectMap<String, Integer> targetPending = pending.get(target, ObjectMap::new);

        for (Boost boost : provider.boosts()) {
            // 生效意愿 = 公共关(队伍/名单/Provider.canTarget) && Boost 自身目标过滤 && 触发条件
            boolean want = eligible && boost.canTarget(target) && boost.shouldApply(target);
            ObjectSet<Provider> set = targetContributors.get(boost.id(), ObjectSet::new);
            if (want && !set.contains(provider)) {
                set.add(provider);
            } else if (!want && set.contains(provider)) {
                set.remove(provider);
            }
            // 每帧按集合人数【重算】本 tick 意愿计数，而非只在增删时增量：
            // contributors 跨帧持久、pending 每 tick 冲洗即清空，只靠增量会导致首帧后计数永远为空。
            putCount(targetPending, boost.id(), set.size);
        }
    }

    private static void putCount(ObjectMap<String, Integer> map, String id, int value) {
        if (value <= 0) {
            map.remove(id);
        } else {
            map.put(id, value);
        }
    }

    // —— 内部：每 tick 统一冲洗（裁决 + 应用/撤销） ——

    private static void flushFrame() {
        ObjectSet<Building> targets = new ObjectSet<>();
        for (Building b : pending.keys()) {
            targets.add(b);
        }
        for (Building b : active.keys()) {
            targets.add(b);
        }
        for (Building target : targets) {
            ObjectMap<String, Integer> desired = pending.get(target);
            resolveMutex(target, desired);
            reconcile(target, desired);
        }
        pending.clear();
    }

    /**
     * 互斥裁决：目标上冲突的效果按优先级（同级 id 字典序）选胜者，败者挂起；
     * 挂起表每次裁决按本帧结论重建——胜者仍在则败者继续挂起，胜者消失则败者自然参选。
     */
    private static void resolveMutex(Building target, ObjectMap<String, Integer> desired) {
        ObjectMap<String, String> next = new ObjectMap<>();

        Seq<String> ids = new Seq<>();
        if (desired != null) {
            for (String id : desired.keys()) {
                if (desired.get(id, 0) > 0) {
                    ids.add(id);
                }
            }
        }

        // 高优先级优先，同级按 id 字典序（结果确定、跨强化器一致）
        ids.sort((a, b) -> {
            int c = Integer.compare(priorityOf(b), priorityOf(a));
            return c != 0 ? c : a.compareTo(b);
        });

        Seq<String> keep = new Seq<>();
        for (String id : ids) {
            boolean conflict = false;
            for (String kept : keep) {
                Boost b = registry.get(id);
                Boost k = registry.get(kept);
                if (b != null && k != null && (b.conflictsWith(kept) || k.conflictsWith(id))) {
                    conflict = true;
                    break;
                }
            }
            if (conflict) {
                next.put(id, keep.first());
            } else {
                keep.add(id);
            }
        }

        if (next.isEmpty()) {
            suppressed.remove(target);
        } else {
            suppressed.put(target, next);
        }
    }

    /**
     * 应用/撤销：以「desired>0 且未被挂起」为应生效集合，对比上帧生效状态——
     * 生效则本帧调一次 apply（不叠加），失格则调一次 remove。
     */
    private static void reconcile(Building target, ObjectMap<String, Integer> desired) {
        ObjectMap<String, String> targetSuppressed = suppressed.get(target);

        Seq<String> ids = new Seq<>();
        ObjectMap<String, Boolean> last = active.get(target);
        if (last != null) {
            ids.addAll(last.keys());
        }
        if (desired != null) {
            for (String id : desired.keys()) {
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            }
        }

        boolean any = false;
        ObjectMap<String, Boolean> snapshot = new ObjectMap<>();
        for (String id : ids) {
            Boost boost = registry.get(id);
            if (boost == null) {
                continue;
            }
            boolean now = desired != null && desired.get(id, 0) > 0
                    && (targetSuppressed == null || !targetSuppressed.containsKey(id));
            boolean was = last != null && Boolean.TRUE.equals(last.get(id));
            if (now) {
                boost.apply(target);
            } else if (was) {
                boost.remove(target);
            }
            snapshot.put(id, now);
            any |= now;
        }

        if (any) {
            active.put(target, snapshot);
        } else {
            active.remove(target);
        }

        // 清理空态，防泄漏
        ObjectMap<String, Integer> tp = pending.get(target);
        if (tp != null && tp.isEmpty()) {
            pending.remove(target);
        }
        ObjectMap<String, ObjectSet<Provider>> tc = contributors.get(target);
        if (tc != null) {
            boolean dead = true;
            for (ObjectSet<Provider> set : tc.values()) {
                dead &= set.isEmpty();
            }
            if (dead) {
                contributors.remove(target);
            }
        }
    }

    // —— 内部：强化器/目标生命周期清理 ——

    private static void removeProviderContributions(Provider provider) {
        Seq<Building> affected = new Seq<>();
        for (Building target : contributors.keys()) {
            ObjectMap<String, ObjectSet<Provider>> tmap = contributors.get(target);
            ObjectMap<String, Integer> targetPending = pending.get(target);
            boolean changed = false;
            for (String id : tmap.keys()) {
                ObjectSet<Provider> set = tmap.get(id);
                if (set.remove(provider)) {
                    changed = true;
                    if (targetPending != null) {
                        // 同步按剩余人数重算计数
                        putCount(targetPending, id, set.size);
                    }
                }
            }
            if (changed) {
                affected.add(target);
            }
        }
        // 收集完再做重算（避免边遍历边改 contributors）
        for (Building target : affected) {
            ObjectMap<String, Integer> desired = new ObjectMap<>();
            ObjectMap<String, ObjectSet<Provider>> tmap = contributors.get(target);
            if (tmap != null) {
                for (String id : tmap.keys()) {
                    if (tmap.get(id).size > 0) {
                        desired.put(id, tmap.get(id).size);
                    }
                }
            }
            resolveMutex(target, desired);
            reconcile(target, desired);
        }
    }

    /** 清扫已失效目标/强化器：杜绝被拆除的建筑在状态表里残留。 */
    private static void sweepInvalid() {
        ObjectSet<Building> badTargets = new ObjectSet<>();
        for (Building target : active.keys()) {
            if (!target.isValid()) {
                badTargets.add(target);
            }
        }
        for (Building target : pending.keys()) {
            if (!target.isValid()) {
                pending.remove(target);
            }
        }
        for (Building target : contributors.keys()) {
            if (!target.isValid()) {
                contributors.remove(target);
            }
        }
        for (Building target : suppressed.keys()) {
            if (!target.isValid()) {
                suppressed.remove(target);
            }
        }
        for (Building target : badTargets) {
            removeBuilding(target);
        }

        Seq<Provider> badProviders = new Seq<>();
        for (Provider provider : providerSet) {
            Building pb = provider.building();
            if (pb == null || !pb.isValid()) {
                badProviders.add(provider);
            }
        }
        for (Provider provider : badProviders) {
            removeProvider(provider);
        }
    }

    /** 目标失效/拆除：清掉它的一切状态，并撤销仍生效的效果。 */
    private static void removeBuilding(Building target) {
        ObjectMap<String, Boolean> map = active.get(target);
        if (map != null) {
            for (String id : map.keys()) {
                if (Boolean.TRUE.equals(map.get(id))) {
                    Boost boost = registry.get(id);
                    if (boost != null) {
                        boost.remove(target);
                    }
                }
            }
        }
        active.remove(target);
        pending.remove(target);
        contributors.remove(target);
        suppressed.remove(target);
    }

    /** 视觉调度：渲染管线在每帧绘制阶段调用；内容由各 boost 的 {@link Boost#visual(Building)} 提供。 */
    public static void drawBoosts() {
        if (visualRenderer == null) {
            return;
        }
        for (Building target : active.keys()) {
            ObjectMap<String, Boolean> map = active.get(target);
            if (map == null) {
                continue;
            }
            for (String id : map.keys()) {
                if (!Boolean.TRUE.equals(map.get(id))) {
                    continue;
                }
                Boost boost = registry.get(id);
                if (boost == null) {
                    continue;
                }
                BoostVisual visual = boost.visual(target);
                if (visual != null) {
                    visualRenderer.render(target, visual);
                }
            }
        }
    }

    private static int priorityOf(String id) {
        Boost boost = registry.get(id);
        return boost == null ? 0 : boost.priority();
    }
}