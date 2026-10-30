package com.tinyaii.tinyradar.scan;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.HashMap;
import java.util.Map;

/**
 * 枚举 -> 中文名。
 *
 * <p>只覆盖卡顿诊断里常见的那几十个；没收录的回退成英文枚举名，
 * 反正服主看得懂 IRON_GOLEM。
 *
 * <p>不用 switch 一大坨，用 Map 表 —— 加词条只改一行。
 */
public final class Names {

    private static final Map<String, String> ENTITY = new HashMap<String, String>();
    private static final Map<String, String> BLOCK = new HashMap<String, String>();

    static {
        // ── 实体 ──
        e("ITEM", "掉落物");
        e("EXPERIENCE_ORB", "经验球");
        e("ARROW", "箭");
        e("SPECTRAL_ARROW", "光灵箭");
        e("VILLAGER", "村民");
        e("WANDERING_TRADER", "流浪商人");
        e("IRON_GOLEM", "铁傀儡");
        e("SNOW_GOLEM", "雪傀儡");
        e("MINECART", "矿车");
        e("HOPPER_MINECART", "漏斗矿车");
        e("CHEST_MINECART", "运输矿车");
        e("FURNACE_MINECART", "动力矿车");
        e("TNT_MINECART", "TNT 矿车");
        e("ARMOR_STAND", "盔甲架");
        e("ITEM_FRAME", "物品展示框");
        e("GLOW_ITEM_FRAME", "荧光物品展示框");
        e("PAINTING", "画");
        e("FALLING_BLOCK", "下落方块");
        e("MARKER", "标记");
        e("ITEM_DISPLAY", "物品展示实体");
        e("BLOCK_DISPLAY", "方块展示实体");
        e("TEXT_DISPLAY", "文字展示实体");
        e("INTERACTION", "交互实体");
        e("AREA_EFFECT_CLOUD", "区域效果云");
        e("FIREWORK_ROCKET", "烟花");
        e("BOAT", "船");
        e("ZOMBIE", "僵尸");
        e("SKELETON", "骷髅");
        e("CREEPER", "苦力怕");
        e("SPIDER", "蜘蛛");
        e("ENDERMAN", "末影人");
        e("SLIME", "史莱姆");
        e("MAGMA_CUBE", "岩浆怪");
        e("BLAZE", "烈焰人");
        e("GHAST", "恶魂");
        e("PHANTOM", "幻翼");
        e("DROWNED", "溺尸");
        e("HUSK", "尸壳");
        e("STRAY", "流浪者");
        e("PILLAGER", "掠夺者");
        e("VINDICATOR", "卫道士");
        e("EVOKER", "唤魔者");
        e("RAVAGER", "劫掠兽");
        e("WARDEN", "监守者");
        e("PIG", "猪");
        e("COW", "牛");
        e("SHEEP", "羊");
        e("CHICKEN", "鸡");
        e("RABBIT", "兔子");
        e("HORSE", "马");
        e("WOLF", "狼");
        e("CAT", "猫");
        e("BEE", "蜜蜂");
        e("TURTLE", "海龟");
        e("DOLPHIN", "海豚");
        e("AXOLOTL", "美西螈");
        e("ALLAY", "悦灵");
        e("BAT", "蝙蝠");
        e("SQUID", "鱿鱼");
        e("COD", "鳕鱼");
        e("SALMON", "鲑鱼");
        e("TROPICAL_FISH", "热带鱼");
        e("PUFFERFISH", "河豚");
        e("END_CRYSTAL", "末地水晶");
        e("TNT", "点燃的 TNT");
        e("LIGHTNING_BOLT", "闪电");
        e("EGG", "鸡蛋（抛出的）");

        // ── 方块实体 ──
        b("HOPPER", "漏斗");
        b("SPAWNER", "刷怪笼");
        b("TRIAL_SPAWNER", "试炼刷怪笼");
        b("CHEST", "箱子");
        b("TRAPPED_CHEST", "陷阱箱");
        b("BARREL", "木桶");
        b("SHULKER_BOX", "潜影盒");
        b("FURNACE", "熔炉");
        b("BLAST_FURNACE", "高炉");
        b("SMOKER", "烟熏炉");
        b("BREWING_STAND", "酿造台");
        b("BEACON", "信标");
        b("DISPENSER", "发射器");
        b("DROPPER", "投掷器");
        b("CRAFTER", "自动合成器");
        b("SCULK_SENSOR", "幽匿感测体");
        b("CALIBRATED_SCULK_SENSOR", "校准幽匿感测体");
        b("BEEHIVE", "蜂箱");
        b("BEE_NEST", "蜂巢");
        b("ENCHANTING_TABLE", "附魔台");
        b("LECTERN", "讲台");
        b("JUKEBOX", "唱片机");
        b("NOTE_BLOCK", "音符盒");
        b("OBSERVER", "侦测器");
        b("PISTON", "活塞");
        b("STICKY_PISTON", "粘性活塞");
        b("REDSTONE_WIRE", "红石线");
        b("REPEATER", "中继器");
        b("COMPARATOR", "比较器");
        b("REDSTONE_TORCH", "红石火把");
        b("DAYLIGHT_DETECTOR", "阳光探测器");
        b("SLIME_BLOCK", "黏液块");
        b("HONEY_BLOCK", "蜂蜜块");
        b("SIGN", "告示牌");
        b("WALL_SIGN", "墙上的告示牌");
        b("CAMPFIRE", "营火");
        b("SOUL_CAMPFIRE", "灵魂营火");
        b("CONDUIT", "潮涌核心");
        b("BELL", "钟");
        b("END_PORTAL", "末地传送门");
        b("NETHER_PORTAL", "下界传送门");
    }

    private Names() {
    }

    private static void e(String key, String cn) {
        ENTITY.put(key, cn);
    }

    private static void b(String key, String cn) {
        BLOCK.put(key, cn);
    }

    public static String entity(EntityType type) {
        if (type == null) {
            return "未知实体";
        }
        String cn = ENTITY.get(type.name());
        return cn != null ? cn : type.name();
    }

    public static String block(Material material) {
        if (material == null) {
            return "未知方块";
        }
        String cn = BLOCK.get(material.name());
        return cn != null ? cn : material.name();
    }
}
