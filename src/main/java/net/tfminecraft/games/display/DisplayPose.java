package net.tfminecraft.games.display;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.DisplayRotator;

/**
 * Local transform on a packet ItemDisplay. Origin Location stays fixed.
 */
public final class DisplayPose {

    /** Matches {@code ItemDisplay.ItemDisplayTransform} ordinals. GROUND scales items down. */
    public static final byte ITEM_NONE = 0;

    private final Vector3f translation;
    private final Quaternionf leftRotation;
    private final Vector3f scale;
    private final Quaternionf rightRotation;
    private final byte itemTransform;

    public DisplayPose(Vector3f translation, Quaternionf leftRotation, Vector3f scale, Quaternionf rightRotation,
            byte itemTransform) {
        this.translation = new Vector3f(translation);
        this.leftRotation = new Quaternionf(leftRotation);
        this.scale = new Vector3f(scale);
        this.rightRotation = new Quaternionf(rightRotation);
        this.itemTransform = itemTransform;
    }

    public static DisplayPose identity(float uniformScale) {
        return new DisplayPose(
                new Vector3f(),
                new Quaternionf(),
                new Vector3f(uniformScale, uniformScale, uniformScale),
                new Quaternionf(),
                ITEM_NONE);
    }

    public DisplayPose withTranslation(float x, float y, float z) {
        return new DisplayPose(new Vector3f(x, y, z), leftRotation, scale, rightRotation, itemTransform);
    }

    public DisplayPose withScale(float uniformScale) {
        return new DisplayPose(
                translation, leftRotation, new Vector3f(uniformScale, uniformScale, uniformScale), rightRotation,
                itemTransform);
    }

    /**
     * Card lying on the table. Facing (tableYaw + yaw-offset) plus flatten (pitch, roll) are all
     * baked into left_rotation. Entity spawn pitch and yaw are kept at 0 so the entity frame is
     * identity, matching how InteractibleFurniture handles ItemDisplay rotation.
     * Stack height must be applied on the spawn Location, not here.
     */
    public static DisplayPose flatOnTable(float uniformScale, float y, float tableYawDeg) {
        return flatOnTable(uniformScale, y, tableYawDeg, 0f);
    }

    /**
     * Same as {@link #flatOnTable(float, float, float)} with extra pitch (used to flip a hand
     * face so rank/suit read correctly).
     */
    public static DisplayPose flatOnTable(float uniformScale, float y, float tableYawDeg, float extraPitchDeg) {
        // Minecraft yaw is clockwise; JOML rotateY is CCW. Negate to match.
        float yaw = -tableYawDeg + Cache.tableCardYawOffset;
        Quaternionf flat = DisplayRotator.fromYawPitchRoll(
                yaw, Cache.tableCardPitch + extraPitchDeg, Cache.tableCardRoll);
        return new DisplayPose(
                new Vector3f(0f, y, 0f),
                flat,
                new Vector3f(uniformScale, uniformScale, uniformScale),
                new Quaternionf(),
                ITEM_NONE);
    }

    public Vector3f translation() {
        return new Vector3f(translation);
    }

    public Quaternionf leftRotation() {
        return new Quaternionf(leftRotation);
    }

    public Vector3f scale() {
        return new Vector3f(scale);
    }

    public Quaternionf rightRotation() {
        return new Quaternionf(rightRotation);
    }

    public byte itemTransform() {
        return itemTransform;
    }

    public boolean matches(DisplayPose other) {
        if (other == null || itemTransform != other.itemTransform) {
            return false;
        }
        return near(translation, other.translation)
                && near(scale, other.scale)
                && quatNear(leftRotation, other.leftRotation)
                && quatNear(rightRotation, other.rightRotation);
    }

    private static boolean near(Vector3f a, Vector3f b) {
        return a.distanceSquared(b) < 1.0e-8f;
    }

    private static boolean quatNear(Quaternionf a, Quaternionf b) {
        float dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w;
        return dot > 0.9999f || dot < -0.9999f;
    }
}
