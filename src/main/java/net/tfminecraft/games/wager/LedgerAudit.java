package net.tfminecraft.games.wager;

import java.util.UUID;

import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.table.Table;

/**
 * Rules the money on a table must always obey.
 *
 * <p>These only ever complain. Quietly correcting a total would hide the bug that caused it, and
 * a wrong total that gets papered over is how a table ends up inflated with nobody knowing when
 * it started.
 */
public final class LedgerAudit {

    private LedgerAudit() {}

    /** Checked after every movement of money. Cheap enough to leave on. */
    public static void check(Table table, String stage) {
        if (table == null) {
            return;
        }
        TableLedger ledger = table.ledger();
        if (ledger.total() < 0) {
            MoneyLog.mismatch(table, stage + " left the table holding " + ledger.total());
        }
        for (UUID owner : ledger.owners()) {
            int held = ledger.total(owner);
            if (held < 0) {
                MoneyLog.mismatch(table, stage + " left " + owner + " holding " + held);
            }
            for (Stake stake : ledger.stakes(owner)) {
                if (stake.unit() < 1 && stake.count() > 0) {
                    // A coin worth nothing is money that cannot be paid out or taken away.
                    MoneyLog.mismatch(table, stage + " left " + stake.count() + " worthless chips on "
                            + owner);
                }
                if (stake.count() < 0) {
                    MoneyLog.mismatch(table, stage + " left a stake counting " + stake.count()
                            + " on " + owner);
                }
            }
        }
    }

    /**
     * What a table read off disk against what the file said it held. A gap here means a stake was
     * dropped on load, which is worth knowing before anyone plays on it.
     */
    public static void checkLoaded(Table table, int expected) {
        if (table == null) {
            return;
        }
        int held = table.ledger().total();
        if (held != expected) {
            MoneyLog.mismatch(table, "loaded holding " + held + " but the file says " + expected);
        } else if (held > 0 && Cache.wagerAuditLog) {
            MoneyLog.note(table, held, "loaded from disk");
        }
    }
}
