package net.tfminecraft.games.game;

import java.util.Locale;

/**
 * Resolves a table gameId. Unknown ids are ignored.
 */
public final class GamesRegistry {

    private static final Game POKER = new PokerGame();
    private static final Game BLACKJACK = new BlackjackGame();
    private static final Game FREEPLAY = new FreePlayGame();

    private GamesRegistry() {}

    public static Game of(String gameId) {
        if (gameId == null) {
            return null;
        }
        if ("poker".equals(gameId.toLowerCase(Locale.ROOT))) {
            return POKER;
        }
        if ("blackjack".equals(gameId.toLowerCase(Locale.ROOT))) {
            return BLACKJACK;
        }
        if ("freeplay".equals(gameId.toLowerCase(Locale.ROOT))) {
            return FREEPLAY;
        }
        return null;
    }
}
