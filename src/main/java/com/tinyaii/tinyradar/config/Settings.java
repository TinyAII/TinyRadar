package com.tinyaii.tinyradar.config;

import com.tinyaii.tinyradar.TinyRadar;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 配置读取。
 *
 * <p>一次性把 yml 里的字符串名解析成枚举，运行期不再查配置 ——
 * 扫描是高频操作，每次 getConfig() 都是浪费。
 *
 * <p>版本兼容：用 {@code valueOf} + try 而不是直接引用枚举常量。
 * 这样 config 里写了 1.21 才有的 {@code crafter}、{@code trial_spawner} 时，
 * 在 1.13 上只是"这条被忽略"，不会启动失败。
 */
public final class Settings {

    private final TinyRadar plugin;

    /** 版本检查服务的默认地址与 app_key（公开标识，不是密钥）。 */
    private static final String DEFAULT_UPDATE_ENDPOINT = "https://bump.tinyaii.cn";
    private static final String DEFAULT_APP_KEY = "app_5a2f555560d8117e0ae8d0a2";

    // 扫描
    public int chunksPerTick = 40;
    public int top = 10;
    public int intervalSeconds = 300;
    /** 扫描玩家周围多少区块（半径）。没玩家时扫出生点。 */
    public int scanRadius = 8;

    // 权重
    public double entityDefault = 1.0;
    public double tileDefault = 0.1;
    public double redstonePerSecond = 0.5;
    public final Map<EntityType, Double> entityWeights = new HashMap<EntityType, Double>();
    public final Map<Material, Double> tileWeights = new HashMap<Material, Double>();

    // 阈值
    public int entityPerType = 80;
    public int entityTotal = 300;
    public int tilePerType = 250;
    public int tileTotal = 600;
    public final Map<EntityType, Integer> entityLimits = new HashMap<EntityType, Integer>();
    public final Map<Material, Integer> tileLimits = new HashMap<Material, Integer>();

    // 红石
    public boolean redstoneEnabled = true;
    public int redstoneWindow = 30;
    public int redstonePerSecondLimit = 40;

    // 放置者
    public boolean placerEnabled = true;
    public final Set<Material> placerBlocks = new HashSet<Material>();

    // 相关性
    public int correlationWindow = 180;

    // 警报
    public boolean alertEnabled = true;
    public double alertMspt = 55;
    public int alertStreak = 3;
    public int alertSampleSeconds = 5;
    public int alertCooldown = 300;

    // 在线更新检查
    private boolean updateCheckEnabled = true;
    private String updateEndpoint = DEFAULT_UPDATE_ENDPOINT;
    private String updateAppKey = DEFAULT_APP_KEY;
    private String updateChannel = "stable";
    private int updateTimeoutSeconds = 3;
    private int updateIntervalHours = 6;
    private String updateNotifyPermission = "tinyradar.alert";

    public Settings(TinyRadar plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        chunksPerTick = Math.max(1, c.getInt("scan.chunks-per-tick", 40));
        top = Math.max(1, c.getInt("scan.top", 10));
        intervalSeconds = Math.max(0, c.getInt("scan.interval-seconds", 300));
        scanRadius = Math.max(1, Math.min(32, c.getInt("scan.radius-around-players", 8)));

        entityDefault = c.getDouble("weights.entity-default", 1.0);
        tileDefault = c.getDouble("weights.tile-default", 0.1);
        redstonePerSecond = c.getDouble("weights.redstone-per-second", 0.5);

        entityWeights.clear();
        readEntityMap(c.getConfigurationSection("weights.entities"), entityWeights);
        tileWeights.clear();
        readTileMap(c.getConfigurationSection("weights.tiles"), tileWeights);

        entityPerType = Math.max(1, c.getInt("limits.entity-per-type", 80));
        entityTotal = Math.max(1, c.getInt("limits.entity-total", 300));
        tilePerType = Math.max(1, c.getInt("limits.tile-per-type", 250));
        tileTotal = Math.max(1, c.getInt("limits.tile-total", 600));

        entityLimits.clear();
        readEntityLimit(c.getConfigurationSection("limits.entities"));
        tileLimits.clear();
        readTileLimit(c.getConfigurationSection("limits.tiles"));

        redstoneEnabled = c.getBoolean("redstone.enabled", true);
        redstoneWindow = Math.max(5, c.getInt("redstone.window-seconds", 30));
        redstonePerSecondLimit = Math.max(1, c.getInt("redstone.per-second-limit", 40));

        placerEnabled = c.getBoolean("placer.enabled", true);
        placerBlocks.clear();
        for (String raw : c.getStringList("placer.blocks")) {
            Material m = matchMaterial(raw);
            if (m != null) {
                placerBlocks.add(m);
            }
        }

        correlationWindow = Math.max(10, c.getInt("correlation.window-seconds", 180));

        alertEnabled = c.getBoolean("alert.enabled", true);
        alertMspt = c.getDouble("alert.mspt-threshold", 55.0);
        alertStreak = Math.max(1, c.getInt("alert.streak", 3));
        alertSampleSeconds = Math.max(1, c.getInt("alert.sample-seconds", 5));
        alertCooldown = Math.max(0, c.getInt("alert.cooldown-seconds", 300));

        updateCheckEnabled = c.getBoolean("update-check.enabled", true);
        updateEndpoint = c.getString("update-check.endpoint", DEFAULT_UPDATE_ENDPOINT);
        updateAppKey = c.getString("update-check.app-key", "");
        // 老配置没有 update-check 段时用代码兜底；app_key 是公开标识，写死没风险
        if (updateAppKey == null || updateAppKey.trim().isEmpty()) {
            updateAppKey = DEFAULT_APP_KEY;
        }
        updateChannel = c.getString("update-check.channel", "stable");
        updateTimeoutSeconds = Math.max(1, c.getInt("update-check.timeout-seconds", 3));
        updateIntervalHours = Math.max(1, c.getInt("update-check.interval-hours", 6));
        updateNotifyPermission = c.getString("update-check.notify-permission", "tinyradar.alert");
    }

    // ────────────────────────── 解析工具 ──────────────────────────

    private void readEntityMap(ConfigurationSection sec, Map<EntityType, Double> out) {
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            EntityType t = matchEntity(key);
            if (t == null) {
                plugin.getLogger().info("[配置] 忽略未知实体: " + key);
                continue;
            }
            out.put(t, sec.getDouble(key, entityDefault));
        }
    }

    private void readTileMap(ConfigurationSection sec, Map<Material, Double> out) {
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            Material m = matchMaterial(key);
            if (m == null) {
                plugin.getLogger().info("[配置] 忽略未知方块实体: " + key);
                continue;
            }
            out.put(m, sec.getDouble(key, tileDefault));
        }
    }

    private void readEntityLimit(ConfigurationSection sec) {
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            EntityType t = matchEntity(key);
            if (t != null) {
                entityLimits.put(t, sec.getInt(key, entityPerType));
            }
        }
    }

    private void readTileLimit(ConfigurationSection sec) {
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            Material m = matchMaterial(key);
            if (m != null) {
                tileLimits.put(m, sec.getInt(key, tilePerType));
            }
        }
    }

    /** 实体名解析；高版本才有的实体在老版本上返回 null。 */
    public static EntityType matchEntity(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return EntityType.valueOf(raw.trim().toUpperCase());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 方块名解析。用 matchMaterial 能吃 "minecraft:hopper" 和 "hopper" 两种写法。 */
    public static Material matchMaterial(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim();
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        try {
            return Material.matchMaterial(name.toUpperCase());
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean isUpdateCheckEnabled() { return updateCheckEnabled; }

    public String getUpdateEndpoint() { return updateEndpoint; }

    public String getUpdateAppKey() { return updateAppKey; }

    public String getUpdateChannel() { return updateChannel; }

    public int getUpdateTimeoutSeconds() { return updateTimeoutSeconds; }

    public int getUpdateIntervalHours() { return updateIntervalHours; }

    public String getUpdateNotifyPermission() { return updateNotifyPermission; }

    public double weightOf(EntityType type) {
        Double w = entityWeights.get(type);
        return w == null ? entityDefault : w;
    }

    public double weightOf(Material type) {
        Double w = tileWeights.get(type);
        return w == null ? tileDefault : w;
    }

    public int limitOf(EntityType type) {
        Integer l = entityLimits.get(type);
        return l == null ? entityPerType : l;
    }

    public int limitOf(Material type) {
        Integer l = tileLimits.get(type);
        return l == null ? tilePerType : l;
    }
}
