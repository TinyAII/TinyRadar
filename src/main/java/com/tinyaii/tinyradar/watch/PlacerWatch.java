package com.tinyaii.tinyradar.watch;

import com.tinyaii.tinyradar.TinyRadar;
import com.tinyaii.tinyradar.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 放置者追踪。
 *
 * <p>光说「这里 300 个漏斗」用处有限 —— 服主要的是「这 300 个漏斗是谁盖的」。
 * 所以在方块被放下的那一刻记一笔，诊断时按人汇总。
 *
 * <p><b>只记插件装上之后放的</b>：老地图上已有的漏斗查不到是谁放的，
 * 这是硬限制（除非读整个区块的 NBT，代价太大）。
 *
 * <p><b>存内存 + 定期落盘</b>：每次放置都写文件会疯狂 IO；
 * 放内存里查得快，每 N 分钟落一次盘，重启不会全丢。
 */
public final class PlacerWatch implements Listener {

    private final TinyRadar plugin;
    private final Settings settings;
    private final File file;

    /** "world:x:y:z" -> 放置者 UUID */
    private final Map<String, UUID> placers = new ConcurrentHashMap<String, UUID>();
    /** 玩家名缓存，报告里显示名字不用每次查 Bukkit。 */
    private final Map<UUID, String> nameCache = new ConcurrentHashMap<UUID, String>();

    public PlacerWatch(TinyRadar plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.file = new File(plugin.getDataFolder(), "placers.yml");
    }

    // ────────────────────────── 事件 ──────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!settings.placerEnabled) {
            return;
        }
        try {
            Block block = event.getBlockPlaced();
            Material type = block.getType();
            if (!settings.placerBlocks.contains(type)) {
                return;
            }
            Player player = event.getPlayer();
            placers.put(key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()),
                    player.getUniqueId());
            nameCache.put(player.getUniqueId(), player.getName());
        } catch (Throwable ignored) {
            // 追踪失败不影响正常游戏
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!settings.placerEnabled) {
            return;
        }
        try {
            Block block = event.getBlock();
            placers.remove(key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ────────────────────────── 查询 ──────────────────────────

    public UUID placerOf(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }
        return placers.get(key(loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
    }

    /**
     * 统计某区块内每个放置者的数量。
     *
     * <p>直接遍历整个 map 是 O(n) —— 但只在扫描时报一次，
     * 而且 placers 一般只有几百到几千条，可接受。
     * 真要优化就按区块分组存，但没必要。
     */
    public Map<UUID, Integer> countInChunk(String world, int chunkX, int chunkZ) {
        Map<UUID, Integer> out = new HashMap<UUID, Integer>();
        String prefix = world + ":";
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int maxX = minX + 15;
        int maxZ = minZ + 15;

        for (Map.Entry<String, UUID> e : placers.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith(prefix)) {
                continue;
            }
            try {
                String[] parts = k.substring(prefix.length()).split(":");
                int x = Integer.parseInt(parts[0]);
                int z = Integer.parseInt(parts[2]);
                if (x < minX || x > maxX || z < minZ || z > maxZ) {
                    continue;
                }
                UUID u = e.getValue();
                Integer cur = out.get(u);
                out.put(u, cur == null ? 1 : cur + 1);
            } catch (Throwable ignored) {
                // 脏数据跳过
            }
        }
        return out;
    }

    /** 取该区块放置最多的那个玩家名（用于报告）。 */
    public String topPlacerInChunk(String world, int chunkX, int chunkZ) {
        Map<UUID, Integer> counts = countInChunk(world, chunkX, chunkZ);
        UUID best = null;
        int bestCount = 0;
        for (Map.Entry<UUID, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        if (best == null) {
            return null;
        }
        String name = nameCache.get(best);
        if (name == null) {
            // 玩家可能已经下线，插件也没缓存过名字
            name = best.toString().substring(0, 8);
        }
        return name + "（" + bestCount + " 个）";
    }

    public int size() {
        return placers.size();
    }

    // ────────────────────────── 存档 ──────────────────────────

    public void load() {
        placers.clear();
        if (!file.exists()) {
            return;
        }
        try {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection root = yml.getConfigurationSection("placers");
            if (root != null) {
                for (String k : root.getKeys(false)) {
                    String v = root.getString(k);
                    if (v == null) {
                        continue;
                    }
                    try {
                        placers.put(k, UUID.fromString(v));
                    } catch (IllegalArgumentException ignored) {
                        // 脏数据跳过
                    }
                }
            }
            ConfigurationSection names = yml.getConfigurationSection("names");
            if (names != null) {
                for (String k : names.getKeys(false)) {
                    try {
                        nameCache.put(UUID.fromString(k), names.getString(k, k));
                    } catch (IllegalArgumentException ignored) {
                        // ignore
                    }
                }
            }
            plugin.getLogger().info("[放置者] 载入 " + placers.size() + " 条记录");
        } catch (Throwable t) {
            plugin.getLogger().warning("[放置者] 载入失败: " + t.getMessage());
        }
    }

    public void save() {
        if (!settings.placerEnabled) {
            return;
        }
        try {
            YamlConfiguration yml = new YamlConfiguration();
            for (Map.Entry<String, UUID> e : placers.entrySet()) {
                yml.set("placers." + e.getKey(), e.getValue().toString());
            }
            for (Map.Entry<UUID, String> e : nameCache.entrySet()) {
                yml.set("names." + e.getKey(), e.getValue());
            }
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            yml.save(file);
        } catch (Throwable t) {
            plugin.getLogger().warning("[放置者] 保存失败: " + t.getMessage());
        }
    }

    /** 清理已经被拆掉的记录（定期调用，避免无限增长）。 */
    public void prune() {
        if (!settings.placerEnabled || placers.isEmpty()) {
            return;
        }
        List<String> dead = new ArrayList<String>();
        for (Map.Entry<String, UUID> e : placers.entrySet()) {
            try {
                String[] parts = e.getKey().split(":");
                org.bukkit.World w = Bukkit.getWorld(parts[0]);
                if (w == null) {
                    continue;   // 世界没加载，先留着
                }
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;   // 区块没加载，判断不了，先留着
                }
                Material now = w.getBlockAt(x, y, z).getType();
                if (!settings.placerBlocks.contains(now)) {
                    dead.add(e.getKey());
                }
            } catch (Throwable ignored) {
                dead.add(e.getKey());   // 解析不了的直接清掉
            }
        }
        for (String k : dead) {
            placers.remove(k);
        }
        if (!dead.isEmpty()) {
            plugin.getLogger().info("[放置者] 清理了 " + dead.size() + " 条失效记录");
        }
    }

    public void clear() {
        placers.clear();
        nameCache.clear();
    }

    private static String key(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }
}
