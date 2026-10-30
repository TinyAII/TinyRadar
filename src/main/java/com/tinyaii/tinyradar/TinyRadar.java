package com.tinyaii.tinyradar;

import com.tinyaii.tinyradar.cmd.RadarCommand;
import com.tinyaii.tinyradar.config.Settings;
import com.tinyaii.tinyradar.scan.ChunkScanner;
import com.tinyaii.tinyradar.watch.PlacerWatch;
import com.tinyaii.tinyradar.watch.RedstoneWatch;
import com.tinyaii.tinyradar.alert.AlertEngine;
import com.tinyaii.tinyradar.listener.UpdateNotifyListener;
import com.tinyaii.tinyradar.service.UpdateChecker;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * TinyRadar —— 卡顿雷达。
 *
 * <p>只观察，不干预：不杀实体、不卸区块、不改世界。
 * 报告事实 + 给出"该先查哪里"，改不改由服主决定。
 *
 * <p>三个支撑点：
 * <ul>
 *   <li>{@link ChunkScanner} 分帧扫区块，按权重算卡顿分</li>
 *   <li>{@link RedstoneWatch} 统计红石变化频率（这类卡顿看不出来）</li>
 *   <li>Tick 计时 —— TPS/MSPT 尽量从服务端 API 取，取不到自己算</li>
 * </ul>
 */
public final class TinyRadar extends JavaPlugin {

    public static final String VERSION = "1.0.0";

    private Settings settings;
    private ChunkScanner scanner;
    private RedstoneWatch redstone;
    private PlacerWatch placer;
    private AlertEngine alert;
    private UpdateChecker updateChecker;
    private RadarCommand command;

    /** 自己算 tick 间隔时的累计值（拿不到服务端 API 时的兜底）。 */
    private long tickSumNanos = 0L;
    private int tickSamples = 0;
    private long lastTickNanos = 0L;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        settings = new Settings(this);
        settings.reload();

        redstone = new RedstoneWatch(this, settings);
        getServer().getPluginManager().registerEvents(redstone, this);

        placer = new PlacerWatch(this, settings);
        placer.load();
        getServer().getPluginManager().registerEvents(placer, this);

        alert = new AlertEngine(this, settings);

        scanner = new ChunkScanner(this, settings);
        command = new RadarCommand(this);

        PluginCommand cmd = getCommand("tinyradar");
        if (cmd != null) {
            cmd.setExecutor(command);
            cmd.setTabCompleter(command);
        } else {
            getLogger().severe("plugin.yml 里找不到 tinyradar 命令");
        }

        // 每 tick 推进扫描进度（分帧扫，单 tick 只处理一小批）
        getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                scanner.tick();
            }
        }, 1L, 1L);

        // tick 计时：自己采样，不依赖 Paper 专属 API
        getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                long now = System.nanoTime();
                if (lastTickNanos > 0) {
                    long delta = now - lastTickNanos;
                    // 单次间隔超过 1 秒的多半是挂起/GC 停顿，剔除掉免得污染平均
                    if (delta > 0 && delta < 1_000_000_000L) {
                        tickSumNanos += delta;
                        tickSamples++;
                    }
                }
                lastTickNanos = now;
            }
        }, 1L, 1L);

        // 自动扫描
        if (settings.intervalSeconds > 0) {
            long period = 20L * settings.intervalSeconds;
            getServer().getScheduler().runTaskTimer(this, new Runnable() {
                @Override
                public void run() {
                    getLogger().info("[扫描] 自动扫描触发");
                    if (!scanner.isRunning()) {
                        scanner.startScan();
                    }
                }
            }, period, period);
        }

        // 放置者：每 5 分钟落一次盘
        getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                placer.save();
            }
        }, 20L * 300, 20L * 300);

        // 放置者：每 10 分钟清理一次已失效的记录
        getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                placer.prune();
            }
        }, 20L * 600, 20L * 600);

        // 警报采样
        if (settings.alertEnabled) {
            long period = 20L * settings.alertSampleSeconds;
            getServer().getScheduler().runTaskTimer(this, new Runnable() {
                @Override
                public void run() {
                    alert.sample();
                }
            }, period, period);
        }

        // 定期清掉长时间没动静的红石记录
        getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                redstone.prune();
            }
        }, 20L * 120, 20L * 120);

        updateChecker = new UpdateChecker(this, settings);
        updateChecker.start();
        getServer().getPluginManager().registerEvents(
                new UpdateNotifyListener(this, updateChecker), this);

        printBanner();
    }

    @Override
    public void onDisable() {
        if (updateChecker != null) {
            updateChecker.stop();
        }
        if (redstone != null) {
            redstone.clear();
        }
        if (placer != null) {
            placer.save();     // 关服前落盘，别把这一轮记录丢了
            placer.clear();
        }
    }

    public void reloadAll() {
        settings.reload();
        if (alert != null) {
            alert.reset();
        }
    }

    /** 扫描完成回调（警报引擎要用）。 */
    public void onScanFinished() {
        if (alert != null) {
            alert.onScanFinished();
        }
    }

    public PlacerWatch getPlacerWatch() {
        return placer;
    }

    public AlertEngine alert() {
        return alert;
    }

    private void printBanner() {
        getLogger().info("========================================");
        getLogger().info(" TinyRadar v" + VERSION + " —— 卡顿雷达");
        getLogger().info(" 只观察不干预：不杀实体、不卸区块、不改世界");
        getLogger().info(" 扫描: 每 tick " + settings.chunksPerTick + " 区块，"
                + (settings.intervalSeconds > 0
                ? "每 " + settings.intervalSeconds + " 秒自动扫一次" : "仅手动扫描"));
        getLogger().info(" 指令: /tinyradar scan | top | where | reload");
        getLogger().info("========================================");
    }

    // ────────────────────────── 对外 ──────────────────────────

    public Settings settings() {
        return settings;
    }

    public ChunkScanner scanner() {
        return scanner;
    }

    public RedstoneWatch redstone() {
        return redstone;
    }

    /** 某区块最近的红石变化速率（次/秒）。 */
    public double getRedstoneRate(String world, int chunkX, int chunkZ) {
        return redstone == null ? 0.0 : redstone.rate(world, chunkX, chunkZ);
    }

    /**
     * 平均 MSPT（毫秒/tick）。
     *
     * <p>优先问服务端（Paper 有 getAverageTickTime），拿不到就用自己采样的平均值。
     */
    public double mspt() {
        try {
            Object v = Bukkit.class.getMethod("getAverageTickTime").invoke(null);
            if (v instanceof Number) {
                return ((Number) v).doubleValue();
            }
        } catch (Throwable ignored) {
            // Spigot 没这个 API，走自己的采样
        }
        if (tickSamples < 20) {
            return 0.0;
        }
        return (tickSumNanos / (double) tickSamples) / 1_000_000.0;
    }

    /** TPS（20 为满）。 */
    public double tps() {
        try {
            Object v = Bukkit.class.getMethod("getTPS").invoke(null);
            if (v instanceof double[]) {
                double[] arr = (double[]) v;
                if (arr.length > 0) {
                    return Math.min(20.0, arr[0]);
                }
            }
        } catch (Throwable ignored) {
            // 下面用 MSPT 反推
        }
        double m = mspt();
        if (m <= 0) {
            return 20.0;
        }
        return Math.min(20.0, 1000.0 / Math.max(50.0, m));
    }

    /** 重置采样（reload 后重新统计）。 */
    public void resetTickSamples() {
        tickSumNanos = 0L;
        tickSamples = 0;
        lastTickNanos = 0L;
    }
}
