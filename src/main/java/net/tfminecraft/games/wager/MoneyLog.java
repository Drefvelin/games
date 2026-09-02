package net.tfminecraft.games.wager;

import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.table.Table;

/**
 * One line per denar movement so a table can be audited after the fact.
 * Every line is a transfer between the guild bank, the tray, the felt, and players.
 */
public final class MoneyLog {

    private MoneyLog() {}

    /** Guild bank paid the table. */
    public static void bankOut(Table table, int denars, String reason) {
        log(table, "bank -> table", denars, reason);
    }

    /** Table paid the guild bank. */
    public static void bankIn(Table table, int denars, String reason) {
        log(table, "table -> bank", denars, reason);
    }

    /** Staff mint made chips from nothing. Never allowed on a guild table. */
    public static void mint(Table table, int denars, String reason) {
        log(table, "mint -> tray", denars, reason);
    }

    /** Chips added to the tray. */
    public static void trayIn(Table table, int denars, String reason) {
        log(table, "-> tray", denars, reason);
    }

    /** Chips taken out of the tray. */
    public static void trayOut(Table table, int denars, String reason) {
        log(table, "tray ->", denars, reason);
    }

    /** A player put money on the felt. */
    public static void stake(Table table, int denars, String reason) {
        log(table, "player -> felt", denars, reason);
    }

    /** A player got money back off the felt. */
    public static void payout(Table table, int denars, String reason) {
        log(table, "felt -> player", denars, reason);
    }

    /** Chips deleted without a destination. Should only happen for staff mint. */
    public static void burn(Table table, int denars, String reason) {
        log(table, "deleted", denars, reason);
    }

    /** One leg of a transaction, named by where it came from and where it went. */
    public static void move(Table table, String from, String to, int denars, String reason) {
        log(table, from + " -> " + to, denars, reason);
    }

    /** Something worth recording that is not a transfer. */
    public static void note(Table table, int denars, String reason) {
        log(table, "note", denars, reason);
    }

    /** Money did not add up. Always logged, flag or not. */
    public static void mismatch(Table table, String detail) {
        if (Games.plugin == null) {
            return;
        }
        String id = table != null ? table.getId().toString() : "no-table";
        Games.plugin.getLogger().warning("[Money] " + id + " DOES NOT BALANCE: " + detail);
    }

    private static void log(Table table, String move, int denars, String reason) {
        if (!Cache.wagerAuditLog || denars == 0 || Games.plugin == null) {
            return;
        }
        String id = table != null ? table.getId().toString() : "no-table";
        Games.plugin.getLogger().info("[Money] " + id + " " + move + " " + denars
                + (reason == null || reason.isBlank() ? "" : " (" + reason + ")"));
    }
}
