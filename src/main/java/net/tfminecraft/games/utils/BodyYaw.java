package net.tfminecraft.games.utils;

import java.lang.reflect.Method;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Torso yaw. Paper / current Spigot expose {@code LivingEntity.getBodyYaw()}; the compile-time
 * spigot-api jar may not, so this is resolved at runtime.
 */
public final class BodyYaw {

    private static final Method GET_BODY_YAW = resolve();

    private BodyYaw() {}

    public static float of(Player player) {
        if (player == null) {
            return 0f;
        }
        if (GET_BODY_YAW != null) {
            try {
                Object value = GET_BODY_YAW.invoke(player);
                if (value instanceof Number number) {
                    return number.floatValue();
                }
            } catch (ReflectiveOperationException ignored) {
                // Fall through to look yaw.
            }
        }
        return player.getLocation().getYaw();
    }

    private static Method resolve() {
        try {
            return LivingEntity.class.getMethod("getBodyYaw");
        } catch (NoSuchMethodException ignored) {
            try {
                return Player.class.getMethod("getBodyYaw");
            } catch (NoSuchMethodException missing) {
                return null;
            }
        }
    }
}
