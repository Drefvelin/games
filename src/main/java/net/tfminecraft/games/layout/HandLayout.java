package net.tfminecraft.games.layout;

import java.util.Comparator;
import java.util.List;

import org.bukkit.Location;

import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.card.Card;
import net.tfminecraft.games.display.DisplayPose;

/**
 * Fan from a locked anchor. Place yaw is supplied by the caller (sticky).
 */
public final class HandLayout {

    public static final float FACE_UP_PITCH = 180f;
    public static final float FACE_DOWN_PITCH = 0f;

    private HandLayout() {}

    public static Comparator<Card> orderFor(String gameId) {
        return Comparator
                .comparing(Card::isJoker)
                .thenComparingInt(card -> Cache.sortValue(gameId, card.getRank()))
                .thenComparing(Card::getSuit, Comparator.nullsLast(String::compareTo));
    }

    public static void sort(List<Card> cards, String gameId) {
        cards.sort(orderFor(gameId));
    }

    public static DisplayPose fanSlot(int index, int count, Location origin, Location anchor, float placeYaw,
            boolean sitting, boolean selected, float extraDistance) {
        return fanSlot(index, count, origin, anchor, placeYaw, sitting, selected, extraDistance, FACE_UP_PITCH);
    }

    public static DisplayPose fanSlot(int index, int count, Location origin, Location anchor, float placeYaw,
            boolean sitting, boolean selected, float extraDistance, float extraPitch) {
        double yawRad = Math.toRadians(placeYaw);
        double[] forward = new double[] {-Math.sin(yawRad), Math.cos(yawRad)};
        float distance = sitting ? sitDistance(selected) : (selected ? Cache.handSelectedDistance : Cache.handDistance);
        distance += extraDistance;
        float[] offset = worldOffset(index, count, origin, anchor, forward, distance);
        return DisplayPose.flatOnTable(Cache.cardScale, 0f, placeYaw, extraPitch)
                .withTranslation(offset[0], offset[1], offset[2]);
    }

    static float sitDistance(boolean selected) {
        float inset = Cache.handSitInset;
        if (selected) {
            inset += Math.max(0f, Cache.handSelectedDistance - Cache.handDistance);
        }
        return inset;
    }

    public static float candidatePlaceYaw(Location origin, Location player, float tableYaw, float facingYaw) {
        return clampLookToDeck(facingYaw, deckYaw(origin, player, tableYaw));
    }

    public static float deckYaw(Location origin, Location from, float tableYaw) {
        double dx = origin.getX() - from.getX();
        double dz = origin.getZ() - from.getZ();
        double horiz = Math.hypot(dx, dz);
        if (horiz < 1e-4) {
            return tableYaw;
        }
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    static float clampLookToDeck(float lookYaw, float deckYaw) {
        float limit = Math.max(0f, Cache.handYawLimit);
        float delta = wrapDegrees(lookYaw - deckYaw);
        if (delta > limit) {
            delta = limit;
        } else if (delta < -limit) {
            delta = -limit;
        }
        return deckYaw + delta;
    }

    public static float wrapDegrees(float deg) {
        float d = deg % 360f;
        if (d > 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    /**
     * Translation from table origin (identity entity frame): X, Y, Z.
     * Anchor Y is already chest height (standing) or table top (sitting).
     */
    static float[] worldOffset(int index, int count, Location origin, Location anchor, double[] forward,
            float distance) {
        double tx = forward[0];
        double tz = forward[1];
        double px = -tz;
        double pz = tx;
        double mid = (count - 1) * 0.5;
        double side = Cache.handSpread * (index - mid);
        double worldX = anchor.getX() + tx * distance + px * side;
        double worldZ = anchor.getZ() + tz * distance + pz * side;
        double worldY = anchor.getY() + index * Cache.handLayerGap;
        return new float[] {
                (float) (worldX - origin.getX()),
                (float) (worldY - origin.getY()),
                (float) (worldZ - origin.getZ())
        };
    }
}
