package com.tinyaii.tinyradar.cmd;

import com.tinyaii.tinyradar.TinyRadar;
import com.tinyaii.tinyradar.scan.Finding;
import com.tinyaii.tinyradar.text.Reporter;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * /tinyradar 指令。
 */
public final class RadarCommand implements CommandExecutor, TabCompleter {

    private static final String PERM = "tinyradar.use";

    private final TinyRadar plugin;

    public RadarCommand(TinyRadar plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERM)) {
            sender.sendMessage(ChatColor.RED + "你没有权限。");
            return true;
        }

        if (args.length == 0) {
            help(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();

        if (match(sub, "scan", "扫描", "扫", "查", "check")) {
            return scan(sender);
        }
        if (match(sub, "top", "排行", "最高", "排名", "rank")) {
            return top(sender);
        }
        if (match(sub, "where", "哪里", "定位", "哪", "find")) {
            return where(sender);
        }
        if (match(sub, "tps", "状态", "性能", "状态信息", "status")) {
            return status(sender);
        }
        if (match(sub, "reload", "重载", "重新加载", "rel")) {
            plugin.reloadAll();
            plugin.resetTickSamples();
            sender.sendMessage(ChatColor.GREEN + "✔ 配置已重载");
            return true;
        }
        if (match(sub, "help", "帮助", "?", "？")) {
            help(sender, label);
            return true;
        }
        help(sender, label);
        return true;
    }

    /** 子命令名匹配（中英文都认）。 */
    private static boolean match(String sub, String... names) {
        for (String n : names) {
            if (n.equals(sub)) {
                return true;
            }
        }
        return false;
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GOLD + "========== 卡顿雷达 TinyRadar ==========");
        row(sender, label, "scan", "扫描", "扫一遍所有已加载区块");
        row(sender, label, "top", "排行", "看分数最高的几处（绝对卡顿）");
        row(sender, label, "where", "哪里", "只看「近期才出现」的（更可能是元凶）");
        row(sender, label, "tps", "状态", "看当前 TPS / MSPT");
        row(sender, label, "reload", "重载", "重载配置");
        sender.sendMessage(ChatColor.DARK_GRAY + "中英文都能用，比如 /" + label
                + " 扫描 = /" + label + " scan");
        sender.sendMessage(ChatColor.DARK_GRAY + "本插件只观察，不杀实体、不卸区块、不改世界。");
    }

    /** 一行双语的帮助。 */
    private void row(CommandSender sender, String label, String en, String cn, String desc) {
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " " + en
                + ChatColor.DARK_GRAY + "  |  "
                + ChatColor.YELLOW + "/" + label + " " + cn
                + ChatColor.GRAY + "  " + desc);
    }

    // ────────────────────────── 子命令 ──────────────────────────

    private boolean scan(CommandSender sender) {
        if (plugin.scanner().isRunning()) {
            sender.sendMessage(ChatColor.GRAY + "已经在扫了，等一下…");
            return true;
        }
        boolean started = plugin.scanner().startScan();
        if (!started) {
            sender.sendMessage(ChatColor.RED + "没有已加载的区块可扫。");
            return true;
        }
        sender.sendMessage(ChatColor.GRAY + "开始扫描（分帧进行，不会卡服）…扫完会出报告。");
        sender.sendMessage(ChatColor.DARK_GRAY + "可以继续做别的，也可以过几秒 /"
                + "tinyradar top 看结果。");
        return true;
    }

    private boolean top(CommandSender sender) {
        Reporter.header(sender, plugin);
        if (!plugin.scanner().hasResult()) {
            sender.sendMessage(ChatColor.GRAY + "还没有扫描结果 —— 先跑一次 /tinyradar scan");
            return true;
        }
        int n = plugin.settings().top;
        List<Finding> list = plugin.scanner().top(n);
        Reporter.scanSummary(sender, plugin, plugin.scanner().getLastScannedChunks(),
                plugin.scanner().getLastScanMillis(), plugin.scanner().all().size());
        Reporter.list(sender, list);
        return true;
    }

    private boolean where(CommandSender sender) {
        Reporter.header(sender, plugin);
        if (!plugin.scanner().hasResult()) {
            sender.sendMessage(ChatColor.GRAY + "还没有扫描结果 —— 先跑一次 /tinyradar scan");
            return true;
        }
        List<Finding> list = plugin.scanner().correlated(plugin.settings().top);
        Reporter.where(sender, list);
        return true;
    }

    private boolean status(CommandSender sender) {
        Reporter.header(sender, plugin);
        sender.sendMessage(ChatColor.GRAY + "扫描中: "
                + (plugin.scanner().isRunning() ? ChatColor.YELLOW + "是" : ChatColor.GREEN + "否"));
        sender.sendMessage(ChatColor.GRAY + "上次扫描: "
                + plugin.scanner().getLastScannedChunks() + " 个区块 / "
                + plugin.scanner().getLastScanMillis() + " ms");
        sender.sendMessage(ChatColor.GRAY + "上次发现: "
                + plugin.scanner().all().size() + " 处问题");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return Collections.emptyList();
        }
        List<String> subs = new ArrayList<String>(Arrays.asList(
                "scan", "top", "where", "tps", "reload",
                "扫描", "排行", "哪里", "状态", "重载", "帮助"));
        String prefix = args[0].toLowerCase();
        List<String> out = new ArrayList<String>();
        for (String s : subs) {
            if (s.startsWith(prefix)) {
                out.add(s);
            }
        }
        return out;
    }
}
