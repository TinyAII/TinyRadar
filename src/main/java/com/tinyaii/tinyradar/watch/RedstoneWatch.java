package com.tinyaii.tinyradar.watch;

import com.tinyaii.tinyradar.TinyRadar;
import com.tinyaii.tinyradar.config.Settings;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 红石监测。
 *
 * <p><b>为什么单独统计红石</b>：高频红石机器（时钟、活塞阵列）的卡顿
 * 完全看不出来 —— 一个区块可能只有 40 个方块，实体数为 0，
 * 但它每秒切换 200 次红石状态，比 500 个掉落物还狠。
 *
 * <p><b>实现：按秒分桶</b>
 * 不用「记下每次事件的时间戳再剔除过期的」—— 高频红石一小时能有几十万条，
 * 内存直接爆。这里用固定长度的环形数组，每秒一个格子，过期的秒数直接归零。
 */
public final class RedstoneWatch implements Listener {

    private final TinyRadar plugin;
    private final Settings settings;

    /** "world:x:z" -> 每秒计数桶。 */
    private final Map<String, Buckets> data = new ConcurrentHashMap<String, Buckets>();

    public RedstoneWatch(TinyRadar plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        if (!settings.redstoneEnabled) {
            return;
        }
        if (event.getOldCurrent() == event.getNewCurrent()) {
            return;   // 电流没变就不算有效变动
        }
        try {
            String world = event.getBlock().getWorld().getName();
            int cx = event.getBlock().getX() >> 4;
            int cz = event.getBlock().getZ() >> 4;
            String key = world + ":" + cx + ":" + cz;

            Buckets b = data.get(key);
            if (b == null) {
                b = new Buckets(settings.redstoneWindow);
                data.put(key, b);
            }
            b.add();
        } catch (Throwable ignored) {
            // 事件里绝不能抛异常，否则会把红石逻辑带崩
        }
    }

    /**
     * 该区块最近窗口内的红石变动速率（次/秒）。
     */
    public double rate(String world, int chunkX, int chunkZ) {
        Buckets b = data.get(world + ":" + chunkX + ":" + chunkZ);
        if (b == null) {
            return 0.0;
        }
        return b.rate(settings.redstoneWindow);
    }

    /** 清掉完全不动的区块记录，避免长期运行后 Map 越堆越大。 */
    public void prune() {
        long nowSec = System.currentTimeMillis() / 1000L;
        java.util.List<String> dead = new java.util.ArrayList<String>();
        for (Map.Entry<String, Buckets> e : data.entrySet()) {
            if (nowSec - e.getValue().lastTouched > settings.redstoneWindow * 2L) {
                dead.add(e.getKey());
            }
        }
        for (String k : dead) {
            data.remove(k);
        }
    }

    public void clear() {
        data.clear();
    }

    /**
     * 固定长度环形桶：每秒翻转一个格子。
     *
     * <p>相比存时间戳列表，内存是常数（窗口秒数个 int），
     * 高频红石也不会因为记录太多而 OOM。
     */
    private static final class Buckets {
        final int[] counts;
        long lastSecond = 0L;
        long lastTouched = 0L;

        Buckets(int window) {
            this.counts = new int[Math.max(5, window)];
        }

        void add() {
            roll();
            int idx = (int) (lastSecond % counts.length);
            counts[idx]++;
            lastTouched = lastSecond;
        }

        /** 把「已经过去的那些秒」清零。 */
        private void roll() {
            long nowSec = System.currentTimeMillis() / 1000L;
            if (lastSecond == 0L || nowSec == lastSecond) {
                lastSecond = nowSec;
                return;
            }
            long gap = nowSec - lastSecond;
            if (gap >= counts.length) {
                java.util.Arrays.fill(counts, 0);
            } else {
                for (long i = 1; i <= gap; i++) {
                    int idx = (int) ((lastSecond + i) % counts.length);
                    if (idx >= 0 && idx < counts.length) {
                        counts[idx] = 0;
                    }
                }
            }
            lastSecond = nowSec;
            lastTouched = nowSec;
        }

        double rate(int window) {
            roll();
            int sum = 0;
            int n = Math.min(window, counts.length);
            for (int i = 0; i < n; i++) {
                sum += counts[i];
            }
            return n <= 0 ? 0.0 : (double) sum / n;
        }
    }
}
