package net.tfminecraft.games.wager;

/** Visible ItemDisplay layers for a pot pile. */
public final class PotLayout {

    private PotLayout() {}

    public static int visibleLayers(int pieces, WagerPileStyle style) {
        if (pieces <= 0 || style == null) {
            return 0;
        }
        int max = Math.max(1, style.stackMax());
        int unit = Math.max(1, style.stackUnit());
        if (unit == 1) {
            return Math.min(pieces, max);
        }
        int layers = (int) Math.round(pieces / (double) unit * max);
        if (layers < 1) {
            layers = 1;
        }
        return Math.min(layers, max);
    }

    public static boolean canAdd(int pieces, WagerPileStyle style) {
        if (style == null) {
            return false;
        }
        int max = Math.max(1, style.stackMax());
        if (Math.max(1, style.stackUnit()) == 1) {
            return pieces < max;
        }
        return visibleLayers(pieces, style) < max;
    }

    public static int room(int pieces, WagerPileStyle style) {
        if (style == null) {
            return 0;
        }
        int max = Math.max(1, style.stackMax());
        if (Math.max(1, style.stackUnit()) == 1) {
            return Math.max(0, max - pieces);
        }
        int n = pieces;
        int added = 0;
        while (canAdd(n, style) && added < 1024) {
            n++;
            added++;
        }
        return added;
    }
}
