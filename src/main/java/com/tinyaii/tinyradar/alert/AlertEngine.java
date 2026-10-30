package com.tinyaii.tinyradar.alert;

import com.tinyaii.tinyradar.TinyRadar;
import com.tinyaii.tinyradar.config.Settings;
import com.tinyaii.tinyradar.scan.Finding;
import com.tinyaii.tinyradar.text.Reporter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * 自动警报。
 *
 * <p>目标：服主不用盯着后台，服务器开始卡的时候自动被叫醒。
 *
 * <p><b>为什么要「连续 N 次」才报</b>：单次 MSPT 尖刺太常见（区块加载、GC、
 * 玩家传送都会来一下），一超标就喊会让人麻木。要连续几次都超标才是真卡。
 *
 * <p><b>为什么警报内容和扫描绑在一起</b>：光喊「服务器卡了」没用，
 * 得同时给出「哪里卡」才有价值 —— 所以触发警报后会立刻扫一遍，
 * 扫完再把结果推给有权限的人。
 */
public final class AlertEngine {

    private final TinyRadar plugin;
    private final Settings settings;

    /** 连续超标的次数。 */
    private int streak = 0;
    /** 上次警报时间（毫秒）。 */
    private long lastAlertAt = 0L;
    /** 警报已经触发、等扫描结果回来再广播。 */
    private boolean pendingBroadcast = false;
    private long pendingSince = 0L;

    public AlertEngine(TinyRadar plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    /** 定时采样（每 sampleSeconds 秒调一次）。 */
    public void sample() {
        if (!settings.alertEnabled) {
            return;
        }
        double mspt = plugin.mspt();
        if (mspt <= 0) {
            return;   // 还没有采样数据
        }
        if (mspt > settings.alertMspt) {
            streak++;
            if (streak >= settings.alertStreak) {
                fire(mspt);
            }
        } else {
            streak = 0;
        }
    }

    private void fire(double mspt) {
        long now = System.currentTimeMillis();
        if (now - lastAlertAt < settings.alertCooldown * 1000L) {
            return;   // 冷却中，别刷屏
        }
        lastAlertAt = now;
        streak = 0;

        plugin.getLogger().warning("[警报] MSPT " + String.format("%.1f", mspt)
                + " ms，连续 " + settings.alertStreak + " 次超标，开始扫描");

        if (plugin.scanner().isRunning()) {
            // 已经在扫了，等这次结果就好
            pendingBroadcast = true;
            pendingSince = now;
            return;
        }
        boolean started = plugin.scanner().startScan();
        if (!started) {
            // 扫不了（可能没有区块），至少把 TPS 报出去
            broadcastPlain(mspt);
            return;
        }
        pendingBroadcast = true;
        pendingSince = now;
    }

    /** 扫描完成时由主类调用。 */
    public void onScanFinished() {
        if (!pendingBroadcast) {
            return;
        }
        boolean stale = System.currentTimeMillis() - pendingSince > 60_000L;
        pendingBroadcast = false;
        if (stale) {
            return;   // 扫太久才回来，别拿旧数据喊人
        }
        broadcastWithFindings();
    }

    // ────────────────────────── 广播 ──────────────────────────

    private void broadcastWithFindings() {
        List<Finding> suspects = plugin.scanner().correlated(3);
        if (suspects.isEmpty()) {
            suspects = plugin.scanner().top(3);
        }

        String msptText = String.format("%.0f", plugin.mspt());
        String tpsText = String.format("%.1f", plugin.tps());

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.hasPermission("tinyradar.alert")) {
                continue;
            }
            p.sendMessage(ChatColor.GOLD + "[卡顿雷达] " + ChatColor.RED + "服务器在卡");
            p.sendMessage(ChatColor.GRAY + "  MSPT " + ChatColor.WHITE + msptText + "ms"
                    + ChatColor.GRAY + "  |  TPS " + Reporter.colorTps(plugin.tps()));

            if (suspects.isEmpty()) {
                p.sendMessage(ChatColor.GRAY + "  扫了一遍，没找到明显可疑的区块");
                p.sendMessage(ChatColor.GRAY + "  可能是 GC 或外部原因，试试 /tinyradar tps");
                continue;
            }
            Finding top = suspects.get(0);
            p.sendMessage(ChatColor.GRAY + "  最可疑: " + ChatColor.WHITE
                    + (top.reasons.isEmpty() ? "分数 " + (long) top.score : top.reasons.get(0))
                    + ChatColor.GRAY + "  在 " + ChatColor.WHITE
                    + "x" + top.blockX() + " z" + top.blockZ());
            if (top.correlated) {
                p.sendMessage(ChatColor.GREEN + "    -> 这块区域是近期才出现的 [相关]");
            }
            p.sendMessage(ChatColor.GRAY + "  详情: " + ChatColor.YELLOW
                    + "/tinyradar where");
        }

        plugin.getLogger().warning("[警报] 已通知在线管理员（TPS " + tpsText
                + " / MSPT " + msptText + "ms，可疑点 " + suspects.size() + " 处）");
    }

    private void broadcastPlain(double mspt) {
        String text = ChatColor.GOLD + "[卡顿雷达] " + ChatColor.RED + "服务器在卡"
                + ChatColor.GRAY + "  MSPT " + String.format("%.0f", mspt) + "ms"
                + ChatColor.GRAY + "  （扫不了区块，无法定位）";
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("tinyradar.alert")) {
                p.sendMessage(text);
            }
        }
    }

    /** reload 后清一下状态。 */
    public void reset() {
        streak = 0;
        pendingBroadcast = false;
    }
}
