package silicon.util;

import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
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
 *       所有强化器对同一目标的意愿合并去重（记入持久认领表 {@code contributors}），再在每 tick
 *       统一裁决一次后应用/撤销——保证同一个目标、同一个效果 id，一 tick 最多调用一次
 *       {@link Boost#apply(Building)}；</li>
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
 *       不留残留——强化器经 {@link #removeProvider(Provider)}（在其 onRemoved() 里调用）
 *       即时清理，或由每 tick 一次的清扫兜底；</li>
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
 *
 * <p><b>多人兼容约定</b>：本系统状态（contributors/active/…）是纯 JVM 本地的，客户端与服务器各自
 * 独立驱动，不做任何网络同步——因为它只影响<b>本地模拟</b>（炮塔充能/转角等），
 * 而这些量在两端由相同输入算出相同结果。为此要求：
 * <ol>
 *   <li><b>队伍检查</b>：一律用 {@code ==} 比较 {@link mindustry.game.Team}（枚举单例，跨端一致，
 *       天然排除敌队/derelict），禁止用 {@code equals/ordinal} 之外的假设或本地玩家身份做判定；</li>
 *   <li><b>确定性</b>：任何影响<b>网络化状态</b>（如液体量、功率、库存）的决策都不得依赖
 *       「谁先跑 update()」或遍历顺序——必须由两端一致的量（tile 坐标、建筑 id、队伍、同步的液体量等）
 *       推导。参考实现：{@code LubricantInjector.owns()}（按 tile 坐标裁决唯一认领者）。</li>
 * </ol>
 */
public final class BuildingBoostSystem {

    /** 可视化渲染接入点（预留）：由渲染管线设置为真正的实现；未设置时视觉不绘制。 */
    public static VisualRenderer visualRenderer;

    /** 渲染接入接口：System 负责调度，具体画法尚未定型。 */
    public interface VisualRenderer {
        /**
         * 绘制某个目标身上的一个生效 boost 徽记。
         *
         * @param target 受惠目标（非空、已生效）
         * @param visual 该 boost 的视觉描述（非空）
         * @param index  该 boost 在目标身上的序号（0 起）——供渲染器把多个徽记依次排开
         */
        void render(Building target, BoostVisual visual, int index);
    }

    /** 可被强化的方块类型名单：只有命中的方块才允许被施加任何强化。默认仅放行炮塔。 */
    public static final Seq<Class<? extends Building>> boostableTypes = new Seq<>(Class.class);

    /** 注册表：id → 效果单元，加载期由各 boost 自动注册。 */
    private static final ObjectMap<String, Boost> registry = new ObjectMap<>();

    /** 贡献者：目标 → (boost id → 当前希望其生效的强化器集合）。计数即集合人数，亦是撤销归属。 */
    private static final ObjectMap<Building, ObjectMap<String, ObjectSet<Provider>>> contributors = new ObjectMap<>();

    /** 互斥挂起：目标 → (败者 id → 胜者 id)。胜者仍占位期间败者保持不生效。 */
    private static final ObjectMap<Building, ObjectMap<String, String>> suppressed = new ObjectMap<>();

    /** 当前实际生效状态：目标 → (boost id → 是否生效)，用于对比做应用/撤销。 */
    private static final ObjectMap<Building, ObjectMap<String, Boolean>> active = new ObjectMap<>();

    /** 存活强化器集合：用于每帧清扫失效 provider（防穿漏）。 */
    private static final ObjectSet<Provider> providerSet = new ObjectSet<>();

    /** 冲洗缓冲：复用以避免每 tick 新建集合（先收集再处理，规避 keys() 边遍历边改）。 */
    private static final ObjectSet<Building> flushBuffer = new ObjectSet<>();
    /** removeProvider 受影响目标缓冲（复用，避免每次分配）。 */
    private static final Seq<Building> affectedBuffer = new Seq<>();
    /** 撤销/回收 id 暂存缓冲（复用；ObjectMap 边遍历边 remove 会抛并发修改异常，须先收集）。 */
    private static final Seq<String> idBuffer = new Seq<>();

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
         * 显示名称（本地化）：用于强化面板等 UI 展示。
         * 默认取 {@link #id()}，实现应覆写为可读名称（建议走 bundle 归类管理）。
         */
        default String name() {
            return id();
        }

        /**
         * 简要强化描述（本地化）：一句话说明本效果带来的收益，如
         * 「+100% 旋转速度；+20% 攻击速度」。用于强化面板展示，<b>每个 Boost 必须给出</b>。
         * 实现建议走 bundle 归类管理；数值应与实际生效值一致。
         */
        String description();

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

        /** 候选目标集合（默认取紧贴 proximity；范围型强化器可自行覆盖）。
         *  <p>返回 {@link Seq} 以便 System 走下标遍历（零迭代器分配）；实现方宜复用同一 Seq 实例，
         *  避免每帧新建。 */
        default Seq<Building> targets() {
            return building().proximity;
        }

        /** 本强化器提供的效果单元列表（可来自 System 注册表按 id 引用，也可直接持有实例）。
         *  <p>返回 {@link Seq} 以便零分配遍历；实现方宜复用/缓存该 Seq。 */
        Seq<Boost> boosts();

        /** 强化器自身的附加目标过滤（如朝向/距离），默认全放行；队伍与名单由 System 统一判。
         *  <p><b>多人注意</b>：本方法在客户端与服务器都会执行且结果必须一致——不得依赖 update 顺序
         *  或本地状态，否则两端提供集合不同会造成分歧。 */
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
     * 调试阶段由 {@link BoostOverlay} 在目标方块**左下角**绘制 {@link #icon(Building)} 徽记；
     * 只要目标身上有任意一个生效的 boost 就显示（多个则沿底边依次排开）。
     * {@link #type()} 供渲染管线分发（未定型，先用类名自由扩展）。
     */
    public interface BoostVisual {

        /** 视觉类型标识，供 System 渲染管线分发（未定型，先用类名自由扩展）。 */
        default String type() {
            return getClass().getSimpleName();
        }

        /** 强化图标：建议直接取用原版图集资源以贴合游戏风格（如 {@code StatusEffects.xx.uiIcon}）。
         *  null 则该效果不绘制图标。 */
        default TextureRegion icon(Building target) {
            return null;
        }

        /** 徽记配色（背景/描边用，null 用渲染器默认色）。 */
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

    /**
     * 查询目标上当前生效的效果单元列表（按显示顺序无关，null 安全）。
     * 供强化面板等 UI 展示：可取 {@link Boost#name()} / {@link Boost#description()} / {@link Boost#visual(Building)}。
     *
     * <p>注意：每次调用会新建 Seq，仅供点击/打开面板等低频路径使用，勿放进每帧循环。
     */
    public static Seq<Boost> activeBoosts(Building target) {
        Seq<Boost> out = new Seq<>();
        ObjectMap<String, Boolean> map = active.get(target);
        if (map != null) {
            for (String id : map.keys()) {
                if (!Boolean.TRUE.equals(map.get(id))) {
                    continue;
                }
                Boost boost = registry.get(id);
                if (boost != null) {
                    out.add(boost);
                }
            }
        }
        return out;
    }

    /** 目标上是否还有生效的强化（零分配，供每帧轮询类逻辑使用）。 */
    public static boolean hasActiveBoosts(Building target) {
        ObjectMap<String, Boolean> map = active.get(target);
        if (map == null) {
            return false;
        }
        for (String id : map.keys()) {
            if (Boolean.TRUE.equals(map.get(id))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 提供归属查询（供强化器决定是否耗资源）：本机当前是否已登记为该目标该效果的有效提供者。
     *
     * <p><b>多人注意</b>：本判定读的是 {@code contributors}（由两端一致的输入推导），故两端结果相同；
     * 可安全用于决定液体等网络化资源的扣减。相对地，<b>不要</b>用「谁先跑 update」这类顺序依赖的规则
     * 决定耗资源——那会因两端 update 顺序不同造成液体分歧（desync）。需要「唯一归属」时，请用
     * tile 坐标/建筑 id 等两端一致的量裁决（参考 {@code LubricantInjector#owns}）。
     */
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

        Seq<Building> targets = provider.targets();
        if (targets == null || targets.isEmpty()) {
            return;
        }
        for (Building target : targets) {
            if (target == null || target == building || !target.isValid()) {
                continue;
            }
            // 先登记本强化器对目标各效果的本 tick 意愿（0/1，跨强化器去重累计）
            collectContributions(provider, target,
                    sameTeam(building, target) && isBoostable(target) && provider.canTarget(target));
        }
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

    /**
     * 登记/撤销本强化器对某目标各效果的意愿。
     *
     * <p><b>零分配</b>：仅在强化器真正「加入/退出」某效果集合时才创建对应内层容器；
     * 稳态（已在集合内、意愿不变）不新建任何对象。空集合即时回收，避免状态表膨胀。
     */
    private static void collectContributions(Provider provider, Building target, boolean eligible) {
        ObjectMap<String, ObjectSet<Provider>> targetContributors = contributors.get(target);

        Seq<Boost> boosts = provider.boosts();
        for (int i = 0, n = boosts.size; i < n; i++) {
            Boost boost = boosts.get(i);
            String id = boost.id();
            // 生效意愿 = 公共关(队伍/名单/Provider.canTarget) && Boost 自身目标过滤 && 触发条件
            boolean want = eligible && boost.canTarget(target) && boost.shouldApply(target);

            ObjectSet<Provider> set = targetContributors == null ? null : targetContributors.get(id);
            if (want) {
                if (set == null) {
                    if (targetContributors == null) {
                        targetContributors = contributors.get(target, ObjectMap::new);
                    }
                    set = targetContributors.get(id, ObjectSet::new);
                }
                set.add(provider);
            } else if (set != null && set.remove(provider)) {
                if (set.isEmpty()) {
                    // 该效果已无人提供 → 连空集合一并回收
                    targetContributors.remove(id);
                    if (targetContributors.isEmpty()) {
                        // 目标已无任何贡献 → 回收条目，并置空局部引用，
                        // 使后续 boost 仍能重新建表（不能 return，否则会漏掉后面的效果）
                        contributors.remove(target);
                        targetContributors = null;
                    }
                }
            }
            // 意愿恒由 contributors 的集合人数表达（无独立计数），无需额外记账
        }
    }

    // —— 内部：每 tick 统一冲洗（裁决 + 应用/撤销） ——

    /**
     * 每 tick 一次的统一冲洗。
     *
     * <p>「本 tick 应生效集合」直接由 {@link #contributors} 推导——冲洗发生在本 tick 首个强化器
     * 登记<b>之前</b>，此时 contributors 恰好仍是上一 tick 登记完的最终状态，故无需额外 pending 快照
     * （省掉每目标每 tick 的内层 Map 分配与计数维护）。
     */
    private static void flushFrame() {
        // 先收集后处理：keys() 迭代器依赖内部数组，而下面会增删状态表
        flushBuffer.clear();
        for (Building target : contributors.keys()) {
            flushBuffer.add(target);
        }
        for (Building target : active.keys()) {
            flushBuffer.add(target); // 覆盖「曾生效但已无贡献」→ 需走撤销
        }

        for (Building target : flushBuffer) {
            ObjectMap<String, ObjectSet<Provider>> tmap = contributors.get(target);
            resolveMutex(target, tmap);
            reconcile(target, tmap);
        }

        sweepInvalid();
    }

    /** 取某效果当前的提供者人数（无提供者记 0）。 */
    private static int count(ObjectMap<String, ObjectSet<Provider>> tmap, String id) {
        if (tmap == null) {
            return 0;
        }
        ObjectSet<Provider> set = tmap.get(id);
        return set == null ? 0 : set.size;
    }

    /**
     * 互斥裁决：目标上冲突的效果按优先级（同级 id 字典序）选胜者，败者挂起；
     * 挂起表每次裁决按本帧结论重建——胜者仍在则败者继续挂起，胜者消失则败者自然参选。
     */
    private static void resolveMutex(Building target, ObjectMap<String, ObjectSet<Provider>> tmap) {
        // 快速路径：至多一个效果时不可能冲突（绝大多数场景），直接清挂起表
        if (tmap == null || tmap.size <= 1) {
            suppressed.remove(target);
            return;
        }

        ObjectMap<String, String> next = new ObjectMap<>();

        Seq<String> ids = new Seq<>();
        for (String id : tmap.keys()) {
            if (count(tmap, id) > 0) {
                ids.add(id);
            }
        }
        if (ids.size <= 1) {
            suppressed.remove(target);
            return;
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
     * 应用/撤销：以「有人提供且未被挂起」为应生效集合——应生效的<b>每 tick 调用一次 apply</b>
     * （保证注入式逐帧累加、不叠加），不再应生效的调用一次 remove。
     * 生效状态表<b>原地更新</b>，不每 tick 新建快照对象。
     */
    private static void reconcile(Building target, ObjectMap<String, ObjectSet<Provider>> tmap) {
        ObjectMap<String, String> targetSuppressed = suppressed.get(target);
        ObjectMap<String, Boolean> state = active.get(target);

        // 1) 应用：应生效集合逐个 apply（本 tick 一次）
        if (tmap != null) {
            for (String id : tmap.keys()) {
                if (count(tmap, id) <= 0) continue;
                if (targetSuppressed != null && targetSuppressed.containsKey(id)) continue;
                Boost boost = registry.get(id);
                if (boost == null) continue;
                boost.apply(target);
                if (state == null) {
                    state = new ObjectMap<>();
                    active.put(target, state);
                }
                if (!Boolean.TRUE.equals(state.get(id))) {
                    state.put(id, true);
                }
            }
        }

        // 2) 撤销：已不满足「有人提供且未被挂起」的（先收集再删——keys() 边遍历边 remove 会抛并发修改）
        if (state != null) {
            idBuffer.clear();
            for (String id : state.keys()) {
                if (count(tmap, id) > 0 && (targetSuppressed == null || !targetSuppressed.containsKey(id))) {
                    continue; // 仍生效
                }
                idBuffer.add(id);
            }
            for (String id : idBuffer) {
                Boost boost = registry.get(id);
                if (boost != null) {
                    boost.remove(target);
                }
                state.remove(id);
            }
            idBuffer.clear();
            if (state.isEmpty()) {
                active.remove(target);
            }
        }
    }

    // —— 内部：强化器/目标生命周期清理 ——

    private static void removeProviderContributions(Provider provider) {
        // 全程「先收集后删除」：contributors/tmap 的 keys() 迭代期间不得修改其自身结构
        affectedBuffer.clear();
        for (Building target : contributors.keys()) {
            ObjectMap<String, ObjectSet<Provider>> tmap = contributors.get(target);
            boolean changed = false;
            idBuffer.clear();
            for (String id : tmap.keys()) {
                ObjectSet<Provider> set = tmap.get(id);
                if (set.remove(provider)) {
                    changed = true;
                    if (set.isEmpty()) {
                        idBuffer.add(id); // 延后回收空集合
                    }
                }
            }
            for (String id : idBuffer) {
                tmap.remove(id);
            }
            idBuffer.clear();
            if (changed) {
                affectedBuffer.add(target);
            }
        }

        for (Building target : affectedBuffer) {
            ObjectMap<String, ObjectSet<Provider>> tmap = contributors.get(target);
            if (tmap != null && tmap.isEmpty()) {
                contributors.remove(target); // 该目标已无任何贡献，回收条目
            }
            resolveMutex(target, tmap);
            reconcile(target, tmap);
        }
        affectedBuffer.clear();
    }

    /** 清扫已失效目标/强化器：杜绝被拆除的建筑在状态表里残留（每 tick 一次，随 flushFrame 触发）。 */
    private static void sweepInvalid() {
        // 先收集再删除：ObjectMap 的 keys() 迭代器依赖内部数组，
        // 边遍历边 remove 会抛并发修改异常/漏扫，故与强化器侧同样先收集后处理。
        ObjectSet<Building> badTargets = new ObjectSet<>();
        collectInvalid(active.keys(), badTargets);
        collectInvalid(contributors.keys(), badTargets);
        collectInvalid(suppressed.keys(), badTargets);
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

    private static void collectInvalid(Iterable<Building> keys, ObjectSet<Building> out) {
        for (Building target : keys) {
            if (target == null || !target.isValid()) {
                out.add(target);
            }
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
        contributors.remove(target);
        suppressed.remove(target);
    }

    /**
     * 视觉调度：渲染管线在每帧绘制阶段调用；内容由各 boost 的 {@link Boost#visual(Building)} 提供。
     * 只要目标身上有任意一个生效的 boost 就会回调一次渲染（序号 index 供多个徽记排开）。
     */
    public static void drawBoosts() {
        if (visualRenderer == null) {
            return;
        }
        for (Building target : active.keys()) {
            ObjectMap<String, Boolean> map = active.get(target);
            if (map == null) {
                continue;
            }
            int index = 0;
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
                    visualRenderer.render(target, visual, index++);
                }
            }
        }
    }

    private static int priorityOf(String id) {
        Boost boost = registry.get(id);
        return boost == null ? 0 : boost.priority();
    }
}