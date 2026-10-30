package com.tinyaii.tinyradar.scan;

import com.tinyaii.tinyradar.TinyRadar;
import com.tinyaii.tinyradar.config.Settings;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 区块扫描器。
 *
 * <p><b>为什么要分帧</b>：一个 5000 区块的服，一次扫完会卡好几秒 ——
 * 一个用来查卡顿的插件自己造成卡顿就没意义了。
 * 所以每 tick 只处理 {@code chunks-per-tick} 个，扫完自动停。
 *
 * <p><b>相关性是怎么判的</b>：给每个区块记「第一次超标的时间」。
 * 卡顿发生时，如果某区块是最近 {@code correlation.window-seconds} 秒内
 * 才刚超标的，就标记 correlated —— 时间上说得通才值得怀疑。
 *
 * <p><b>玩家不计入</b>：玩家不是卡顿源（至少在实体计数这一层不是），
 * 数进去只会让主城永远排第一。
 */
public final class ChunkScanner {

    private final TinyRadar plugin;
    private final Settings settings;

    /** "world:x:z" -> 首次超标的时间戳（毫秒）。 */
    private final Map<String, Long> firstExceed = new ConcurrentHashMap<String, Long>();

    private volatile boolean running = false;
    private long startedAt = 0L;

    /** 本次扫描待处理的区块。 */
    private List<ChunkRef> queue = new ArrayList<ChunkRef>();
    private int cursor = 0;

    /** 扫出来的结果（只保留有问题的）。 */
    private final List<Finding> findings = new ArrayList<Finding>();

    /** 上一次完整扫描的耗时与区块数，报告里会显示。 */
    private long lastScanMillis = 0L;
    private int lastScannedChunks = 0;

    public ChunkScanner(TinyRadar plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public boolean isRunning() {
        return running;
    }

    public long getLastScanMillis() {
        return lastScanMillis;
    }

    public int getLastScannedChunks() {
        return lastScannedChunks;
    }

    /**
     * 开一次扫描。已经在跑就直接忽略。
     *
     * <p><b>为什么不能只扫 getLoadedChunks()</b>：
     * 没玩家在线时，除出生点外几乎所有区块都会被卸载，
     * 光看"已加载区块"会得到空列表 —— 空服永远扫不出东西。
     *
     * <p>所以目标区块是三者并集：
     * <ol>
     *   <li>已加载的区块</li>
     *   <li>每个在线玩家周围 radius 区块（这些一定在 tick，最可能是卡顿源）</li>
     *   <li>该世界没玩家时，扫出生点周围（半径取 radius/2，避免空服扫太多）</li>
     * </ol>
     */
    public boolean startScan() {
        if (running) {
            return false;
        }
        List<ChunkRef> refs = new ArrayList<ChunkRef>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        int radius = settings.scanRadius;

        for (World w : plugin.getServer().getWorlds()) {
            String worldName = w.getName();

            for (Chunk c : w.getLoadedChunks()) {
                addRef(refs, seen, worldName, c.getX(), c.getZ());
            }

            for (Player p : w.getPlayers()) {
                int px = p.getLocation().getBlockX() >> 4;
                int pz = p.getLocation().getBlockZ() >> 4;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        addRef(refs, seen, worldName, px + dx, pz + dz);
                    }
                }
            }

            if (w.getPlayers().isEmpty()) {
                org.bukkit.Location sp = w.getSpawnLocation();
                int sx = sp.getBlockX() >> 4;
                int sz = sp.getBlockZ() >> 4;
                int sr = Math.max(2, radius / 2);
                for (int dx = -sr; dx <= sr; dx++) {
                    for (int dz = -sr; dz <= sr; dz++) {
                        addRef(refs, seen, worldName, sx + dx, sz + dz);
                    }
                }
            }
        }

        if (refs.isEmpty()) {
            plugin.getLogger().warning("[扫描] 找不到可扫的区块，跳过");
            return false;
        }
        plugin.getLogger().info("[扫描] 准备扫描 " + refs.size() + " 个区块");
        queue = refs;
        cursor = 0;
        findings.clear();
        startedAt = System.currentTimeMillis();
        lastScannedChunks = 0;
        running = true;
        return true;
    }

    private static void addRef(List<ChunkRef> refs, java.util.Set<String> seen,
                               String world, int x, int z) {
        String key = world + ":" + x + ":" + z;
        if (seen.add(key)) {
            refs.add(new ChunkRef(world, x, z));
        }
    }

    /** 每 tick 从这里推进一批。 */
    public void tick() {
        if (!running) {
            return;
        }
        long now = System.currentTimeMillis();
        int limit = Math.min(settings.chunksPerTick, queue.size() - cursor);
        for (int i = 0; i < limit; i++) {
            ChunkRef ref = queue.get(cursor++);
            lastScannedChunks++;
            try {
                scanOne(ref, now);
            } catch (Throwable t) {
                plugin.getLogger().warning("[扫描] 区块 " + ref + " 出错: " + t.getMessage());
            }
        }
        if (cursor >= queue.size()) {
            running = false;
            lastScanMillis = System.currentTimeMillis() - startedAt;
            pruneStaleExceed(now);
            plugin.getLogger().info("[扫描] 完成：" + lastScannedChunks + " 个区块，"
                    + lastScanMillis + " ms，发现 " + findings.size() + " 处问题");
            try {
                plugin.onScanFinished();
            } catch (Throwable ignored) {
                // 警报失败不影响扫描结果
            }
        }
    }

    // ────────────────────────── 单个区块 ──────────────────────────

    private void scanOne(ChunkRef ref, long now) {
        World world = plugin.getServer().getWorld(ref.world);
        if (world == null || !world.isChunkLoaded(ref.x, ref.z)) {
            return;
        }
        Chunk chunk = world.getChunkAt(ref.x, ref.z);

        Map<EntityType, Integer> entityCount = new HashMap<EntityType, Integer>();
        Map<Material, Integer> tileCount = new HashMap<Material, Integer>();

        // 实体：排除玩家
        for (Entity e : chunk.getEntities()) {
            if (e instanceof Player) {
                continue;
            }
            Integer cur = entityCount.get(e.getType());
            entityCount.put(e.getType(), cur == null ? 1 : cur + 1);
        }

        // 方块实体（漏斗、箱子、熔炉…）
        for (BlockState state : chunk.getTileEntities()) {
            Material type = state.getType();
            Integer cur = tileCount.get(type);
            tileCount.put(type, cur == null ? 1 : cur + 1);
        }

        double score = 0.0;
        List<String> reasons = new ArrayList<String>();

        int entitySum = 0;
        for (Map.Entry<EntityType, Integer> e : entityCount.entrySet()) {
            int count = e.getValue();
            entitySum += count;
            score += count * settings.weightOf(e.getKey());
            int limit = settings.limitOf(e.getKey());
            if (count > limit) {
                reasons.add(nameOf(e.getKey()) + " " + count + " 个（上限 " + limit + "）");
            }
        }
        if (entitySum > settings.entityTotal) {
            reasons.add("实体总计 " + entitySum + " 个（上限 " + settings.entityTotal + "）");
        }

        int tileSum = 0;
        for (Map.Entry<Material, Integer> e : tileCount.entrySet()) {
            int count = e.getValue();
            tileSum += count;
            score += count * settings.weightOf(e.getKey());
            int limit = settings.limitOf(e.getKey());
            if (count > limit) {
                reasons.add(nameOf(e.getKey()) + " " + count + " 个（上限 " + limit + "）");
            }
        }
        if (tileSum > settings.tileTotal) {
            reasons.add("方块实体总计 " + tileSum + " 个（上限 " + settings.tileTotal + "）");
        }

        // 红石速率也会加分（如果监测器开着）
        double redstoneRate = plugin.getRedstoneRate(ref.world, ref.x, ref.z);
        if (redstoneRate > 0) {
            score += redstoneRate * settings.redstonePerSecond;
            if (redstoneRate > settings.redstonePerSecondLimit) {
                reasons.add("红石每秒变动 " + (int) redstoneRate + " 次（上限 "
                        + settings.redstonePerSecondLimit + "）");
            }
        }

        String key = ref.key();
        if (reasons.isEmpty()) {
            // 没超标：清掉首次记录，这样下次超标才会重新计时
            firstExceed.remove(key);
            return;
        }

        Finding f = new Finding(ref.world, ref.x, ref.z, score);
        f.reasons.addAll(reasons);

        // 谁盖的 —— 只报有问题的区块，正常区块不查，省一趟遍历
        try {
            if (plugin.getPlacerWatch() != null) {
                f.topPlacer = plugin.getPlacerWatch()
                        .topPlacerInChunk(ref.world, ref.x, ref.z);
            }
        } catch (Throwable ignored) {
            // 追踪失败不影响扫描
        }

        Long prev = firstExceed.get(key);
        if (prev == null) {
            firstExceed.put(key, now);
            f.firstExceedAt = now;
        } else {
            f.firstExceedAt = prev;
        }
        // 只有「近期才出现」的才值得怀疑
        long age = now - f.firstExceedAt;
        f.correlated = age <= settings.correlationWindow * 1000L;

        findings.add(f);
    }

    /** 清掉已经不存在 / 早已不超标的区块记录，防止 Map 越涨越大。 */
    private void pruneStaleExceed(long now) {
        long ttl = Math.max(settings.correlationWindow, 600) * 1000L;
        List<String> dead = new ArrayList<String>();
        for (Map.Entry<String, Long> e : firstExceed.entrySet()) {
            if (now - e.getValue() > ttl) {
                dead.add(e.getKey());
            }
        }
        for (String k : dead) {
            firstExceed.remove(k);
        }
    }

    // ────────────────────────── 查询 ──────────────────────────

    /** 按分数取前 N 条。 */
    public List<Finding> top(int n) {
        List<Finding> copy = new ArrayList<Finding>(findings);
        Collections.sort(copy, new Comparator<Finding>() {
            @Override
            public int compare(Finding a, Finding b) {
                return Double.compare(b.score, a.score);
            }
        });
        if (copy.size() > n) {
            return new ArrayList<Finding>(copy.subList(0, n));
        }
        return copy;
    }

    /** 只看「近期才涨起来」的那些。 */
    public List<Finding> correlated(int n) {
        List<Finding> out = new ArrayList<Finding>();
        for (Finding f : top(findings.size())) {
            if (f.correlated) {
                out.add(f);
                if (out.size() >= n) {
                    break;
                }
            }
        }
        return out;
    }

    public List<Finding> all() {
        return new ArrayList<Finding>(findings);
    }

    public boolean hasResult() {
        return !findings.isEmpty() || lastScannedChunks > 0;
    }

    public void clear() {
        findings.clear();
        firstExceed.clear();
        lastScanMillis = 0L;
        lastScannedChunks = 0;
    }

    // ────────────────────────── 工具 ──────────────────────────

    private static String nameOf(EntityType type) {
        return Names.entity(type);
    }

    private static String nameOf(Material type) {
        return Names.block(type);
    }

    /** 一次扫描里的区块定位（不持有 Chunk 对象，避免区块被卸载后拿到失效引用）。 */
    private static final class ChunkRef {
        final String world;
        final int x;
        final int z;

        ChunkRef(String world, int x, int z) {
            this.world = world;
            this.x = x;
            this.z = z;
        }

        String key() {
            return world + ":" + x + ":" + z;
        }
    }
}
