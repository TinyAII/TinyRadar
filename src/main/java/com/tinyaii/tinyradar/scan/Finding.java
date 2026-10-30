package com.tinyaii.tinyradar.scan;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条「这里有问题」的记录。
 *
 * <p>不是单纯的数字，而是带上「为什么判定为问题」—— 报告要能说人话，
 * 就必须在扫描时把依据攒下来，事后再拼不回去。
 */
public final class Finding {

    public final String world;
    public final int chunkX;
    public final int chunkZ;
    /** 卡顿分（后面排序用）。 */
    public final double score;
    /** 命中原因，比如「村民 128 个（上限 30）」。 */
    public final List<String> reasons = new ArrayList<String>();

    /** 这个区块第一次超标的时间（毫秒）。0 = 之前没超过。 */
    public long firstExceedAt;
    /**
     * 是不是「最近才涨起来的」。
     *
     * <p>卡顿时只有刚出现的区域值得怀疑 —— 一个存在了三小时的刷怪塔
     * 很难解释「为什么刚刚才卡」。
     */
    public boolean correlated;

    /** 这个区块里放置相关方块最多的玩家（形如「Steve（128 个）」），没记录就是 null。 */
    public String topPlacer;

    public Finding(String world, int chunkX, int chunkZ, double score) {
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.score = score;
    }

    public String key() {
        return world + ":" + chunkX + ":" + chunkZ;
    }

    /** 区块西北角的方块坐标，方便服主 tp 过去看。 */
    public int blockX() {
        return chunkX << 4;
    }

    public int blockZ() {
        return chunkZ << 4;
    }
}
