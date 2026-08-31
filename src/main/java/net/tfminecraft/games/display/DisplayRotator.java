package net.tfminecraft.games.display;

import org.joml.Quaternionf;

/**
 * Builds display left-rotation from yaw / pitch / roll in degrees.
 * Same sequential recipe as InteractibleFurniture: rotateY, then rotateX, then rotateZ
 * (the model is rolled first, then pitched, then yawed, so flatten stays on the table).
 */
public final class DisplayRotator {

    private DisplayRotator() {}

    public static Quaternionf fromYawPitchRoll(float yawDeg, float pitchDeg, float rollDeg) {
        return new Quaternionf()
                .rotateY((float) Math.toRadians(yawDeg))
                .rotateX((float) Math.toRadians(pitchDeg))
                .rotateZ((float) Math.toRadians(rollDeg));
    }
}
