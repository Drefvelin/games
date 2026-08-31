package net.tfminecraft.games.card;

import java.util.Locale;

import org.bukkit.Color;

/**
 * Suit colors match Magic {@code elements.yml}. Rank names are catalog ranks (ace is 1).
 */
public final class CardNames {

    private CardNames() {}

    public static String suitLabel(String suit) {
        if (suit == null || suit.isBlank()) {
            return "#aaaaaaUnknown";
        }
        return switch (suit.toLowerCase(Locale.ROOT)) {
            case "cerrith" -> "#55ff55Cerrith";
            case "seithr" -> "#ffffffSeithr";
            case "oseni" -> "#ffaa00Oseni";
            case "mitlan" -> "#5555ffMitlan";
            default -> "#aaaaaa" + capitalize(suit);
        };
    }

    public static String rankLabel(Card card) {
        if (card == null) {
            return "#ffffff?";
        }
        if (card.isJoker()) {
            return "#ffffffJoker";
        }
        String name = switch (card.getRank()) {
            case 1 -> "Ace";
            case 11 -> "Jack";
            case 12 -> "Queen";
            case 13 -> "King";
            default -> String.valueOf(card.getRank());
        };
        return "#ffffff" + name;
    }

    public static Color suitDust(Card card) {
        if (card == null || card.isJoker() || card.getSuit() == null) {
            return Color.fromRGB(0xaa, 0xaa, 0xaa);
        }
        return switch (card.getSuit().toLowerCase(Locale.ROOT)) {
            case "cerrith" -> Color.fromRGB(0x55, 0xff, 0x55);
            case "seithr" -> Color.fromRGB(0xff, 0xff, 0xff);
            case "oseni" -> Color.fromRGB(0xff, 0xaa, 0x00);
            case "mitlan" -> Color.fromRGB(0x55, 0x55, 0xff);
            default -> Color.fromRGB(0xaa, 0xaa, 0xaa);
        };
    }

    private static String capitalize(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
