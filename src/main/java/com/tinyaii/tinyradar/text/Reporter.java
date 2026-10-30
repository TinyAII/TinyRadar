package com.tinyaii.tinyradar.text;

import com.tinyaii.tinyradar.TinyRadar;
import com.tinyaii.tinyradar.scan.Finding;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * 报告渲染。
 *
 * <p>目标是「服主看一眼就知道该查哪」，所以：
 * <ul>
 *   <li>先给结论（TPS/MSPT + 几处问题），不先甩数字</li>
 *   <li>每条问题都带「为什么判定为问题」（超标多少）</li>
 *   <li>近期才出现的标出来 —— 那才是值得先查的</li>
 *   <li>附上 teleport 用的坐标，不用服主自己换算</li>
 * </ul>
 */
public final class Reporter {

    private Reporter() {
    }

    public static void header(CommandSender to, TinyRadar plugin) {
        to.sendMessage(ChatColor.GOLD + "========== 卡顿雷达 ==========");
        to.sendMessage(ChatColor.GRAY + "TPS " + colorTps(plugin.tps())
                + ChatColor.GRAY + "  |  MSPT " + String.format("%.1f", plugin.mspt()) + " ms");
    }

    public static void scanSummary(CommandSender to, TinyRadar plugin,
                                   int chunks, long millis, int found) {
        to.sendMessage(ChatColor.GRAY + "扫描了 " + chunks + " 个区块，用时 "
                + String.format("%.2f", millis / 1000.0) + " 秒");
        if (found == 0) {
            to.sendMessage(ChatColor.GREEN + "没发现明显问题 —— 当前区块都还算干净");
            return;
        }
        to.sendMessage(ChatColor.RED + "发现 " + found + " 处问题：");
    }

    /** 打印问题列表。 */
    public static void list(CommandSender to, List<Finding> findings) {
        if (findings.isEmpty()) {
            to.sendMessage(ChatColor.GRAY + "（没有记录。先跑一次 /tinyradar scan）");
            return;
        }
        int i = 1;
        for (Finding f : findings) {
            String head = ChatColor.YELLOW + "#" + i + " " + ChatColor.WHITE
                    + f.world + ChatColor.GRAY + "  x" + f.blockX() + " z" + f.blockZ();
            to.sendMessage(head);

            StringBuilder meta = new StringBuilder();
            meta.append(ChatColor.GRAY).append("   分数 ").append(ChatColor.YELLOW)
                    .append(String.format("%.0f", f.score));
            if (f.correlated) {
                meta.append(ChatColor.GREEN).append("  [近期才出现，先查这个]");
            }
            to.sendMessage(meta.toString());

            for (String r : f.reasons) {
                to.sendMessage(ChatColor.GRAY + "   - " + ChatColor.WHITE + r);
            }
            if (f.topPlacer != null) {
                to.sendMessage(ChatColor.GRAY + "   - 主要放置者: "
                        + ChatColor.WHITE + f.topPlacer);
            }
            i++;
        }
    }

    /** `where` 指令：只看近期才涨起来的。 */
    public static void where(CommandSender to, List<Finding> findings) {
        if (findings.isEmpty()) {
            to.sendMessage(ChatColor.GRAY + "没有「近期才出现」的可疑区域。");
            to.sendMessage(ChatColor.GRAY + "如果是持续性的卡，跑 /tinyradar top 看绝对分数最高的。");
            return;
        }
        to.sendMessage(ChatColor.GOLD + "最值得先查的地方：");
        list(to, findings);
    }

    public static String colorTps(double tps) {
        String text = String.format("%.1f", tps);
        if (tps >= 19.0) {
            return ChatColor.GREEN + text;
        }
        if (tps >= 15.0) {
            return ChatColor.YELLOW + text;
        }
        return ChatColor.RED + text;
    }

    /** 一行短结论，用于警报。 */
    public static String oneLine(TinyRadar plugin) {
        return ChatColor.GRAY + "MSPT " + String.format("%.0f", plugin.mspt()) + "ms"
                + ChatColor.GRAY + "  |  TPS " + colorTps(plugin.tps());
    }
}
