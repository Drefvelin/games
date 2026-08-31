package net.tfminecraft.games.table;

import java.util.Locale;

/**
 * Shoe recycle vs full shuffle each round. Blackjack reads this; the deck engine stays dumb.
 */
public enum ShufflePolicy {
    SHOE,
    ROUND;

    public static ShufflePolicy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return SHOE;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return SHOE;
        }
    }

    public ShufflePolicy next() {
        return this == SHOE ? ROUND : SHOE;
    }
}
