package net.tfminecraft.games.wager;

import net.tfminecraft.games.cache.Cache;

/** How a pot pile is drawn. Overrides merge onto defaults. */
public record WagerPileStyle(int stackMax, int stackUnit, float layerGap, float scale, boolean randomYaw,
        String model, float pitch, double yOffset) {

    public static WagerPileStyle defaults() {
        return new WagerPileStyle(
                Cache.wagerStackMax,
                Cache.wagerStackUnit,
                Cache.wagerLayerGap,
                Cache.wagerItemScale,
                Cache.wagerRandomYaw,
                null,
                Cache.tableCardPitch,
                Cache.wagerYOffset);
    }
}
