package net.tfminecraft.games.layout;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;

import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.DisplayPose;

/**
 * Default reveal line (same as the hand fan). Games may return a different slot list.
 */
public final class RevealLayout {

    public static final float SANDWICH_GAP = 0.02f;
    public static final float SANDWICH_SCALE = 0.01f;

    private RevealLayout() {}

    public static List<DisplayPose> line(int count, Location origin, Location anchor, float placeYaw, boolean sitting,
            float extraPitch) {
        List<DisplayPose> slots = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            slots.add(HandLayout.fanSlot(i, count, origin, anchor, placeYaw, sitting, false, 0f, extraPitch));
        }
        return slots;
    }

    public static DisplayPose withPitch(DisplayPose slot, float placeYaw, float extraPitch) {
        var t = slot.translation();
        return DisplayPose.flatOnTable(Cache.cardScale, 0f, placeYaw, extraPitch)
                .withTranslation(t.x, t.y, t.z);
    }

    /**
     * Same slot as the face, opposite extra pitch, slightly larger. Both lerp the same +180.
     */
    public static DisplayPose sandwichBack(DisplayPose face, float placeYaw, float faceExtraPitch) {
        return withPitch(face, placeYaw, faceExtraPitch + 180f)
                .withScale(Cache.cardScale + SANDWICH_SCALE);
    }
}
