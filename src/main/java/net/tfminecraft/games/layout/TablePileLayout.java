package net.tfminecraft.games.layout;

import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.DisplayPose;
import net.tfminecraft.games.layout.TableLayout.PileSlot;
import net.tfminecraft.games.table.Table;

/**
 * Line of table-owned cards relative to the shoe. Offsets come from the game layout when set.
 */
public final class TablePileLayout {

    private TablePileLayout() {}

    public static DisplayPose slot(Table table, String pile, int index, int count, boolean faceUp) {
        double yawRad = Math.toRadians(table.getYaw());
        double fx = -Math.sin(yawRad);
        double fz = Math.cos(yawRad);
        double rx = fz;
        double rz = -fx;
        PileSlot origin = pileOrigin(table, pile);
        double mid = (Math.max(1, count) - 1) * 0.5;
        double side = Cache.handSpread * (index - mid);
        double right = origin.right() + side;
        float dx = (float) (fx * origin.forward() + rx * right);
        float dz = (float) (fz * origin.forward() + rz * right);
        float yaw = table.getYaw();
        if (pile != null && pile.equalsIgnoreCase("dealer")) {
            yaw += 180f;
        }
        float pitch = faceUp ? HandLayout.FACE_UP_PITCH : HandLayout.FACE_DOWN_PITCH;
        return DisplayPose.flatOnTable(Cache.cardScale, 0f, yaw, pitch)
                .withTranslation(dx, 0f, dz);
    }

    static PileSlot pileOrigin(Table table, String pile) {
        TableLayout layout = Cache.layoutOf(table.getGameId());
        if (layout != null) {
            PileSlot named = layout.pile(pile);
            if (named != null) {
                return named;
            }
        }
        int row = row(pile);
        return new PileSlot((row + 1) * Cache.tableBoardOffset, 0);
    }

    static int row(String pile) {
        if (pile != null && pile.equalsIgnoreCase("dealer")) {
            return 1;
        }
        if (pile != null && pile.equalsIgnoreCase("board")) {
            return 0;
        }
        return 2;
    }
}
