package net.tfminecraft.games.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A player came out of a settled table round ahead. Informational only: fired once per player per
 * settled round, only when {@link #getProfit()} is above zero, and never cancellable. The profit is
 * net denar gained, after the player's own stake is returned and before citizen tax is withheld.
 */
public class PlayerWonMoneyEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final double profit;
    private final String game;

    public PlayerWonMoneyEvent(Player player, double profit, String game) {
        this.player = player;
        this.profit = profit;
        this.game = game;
    }

    /** The winner. */
    public Player getPlayer() {
        return player;
    }

    /** Net denar won this round, stake excluded, before citizen tax. */
    public double getProfit() {
        return profit;
    }

    /** Game that paid out, for example {@code blackjack}. */
    public String getGame() {
        return game;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
