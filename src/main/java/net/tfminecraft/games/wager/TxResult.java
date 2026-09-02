package net.tfminecraft.games.wager;

import java.util.ArrayList;
import java.util.List;

import net.tfminecraft.games.table.PayoutFlight;

/**
 * What a {@link MoneyTx} did, or why it did nothing.
 *
 * <p>A failed transaction has moved no money at all, so a caller that sees {@code ok() == false}
 * can refuse its action without cleaning anything up.
 */
public final class TxResult {

    public enum Reason {
        /** Everything moved. */
        OK,
        /** Nothing was asked for. Treated as a success with zero moved. */
        NOTHING,
        /** The coins exist but cannot make the exact amount. */
        NO_CHANGE,
        /** A player does not hold enough to cover this. */
        PLAYER_SHORT,
        /** The felt bucket does not hold enough to cover this. */
        FELT_SHORT,
        /** The guild bank does not hold enough, or the guild is gone. */
        BANK_SHORT,
        /** Money had to be shaped like a coin and no coin was named. */
        NO_TEMPLATE,
        /** Only staff tables may make money from nothing. */
        MINT_REFUSED,
        /** The table went away. */
        NO_TABLE
    }

    private final Reason reason;
    private final int moved;
    private final int wanted;
    private final int best;
    private final List<PayoutFlight> flights;

    private TxResult(Reason reason, int moved, int wanted, int best, List<PayoutFlight> flights) {
        this.reason = reason;
        this.moved = moved;
        this.wanted = wanted;
        this.best = best;
        this.flights = flights == null ? new ArrayList<>() : flights;
    }

    static TxResult done(int moved, List<PayoutFlight> flights) {
        return new TxResult(Reason.OK, moved, moved, moved, flights);
    }

    static TxResult nothing() {
        return new TxResult(Reason.NOTHING, 0, 0, 0, null);
    }

    static TxResult failed(Reason reason, int wanted, int best) {
        return new TxResult(reason, 0, wanted, best, null);
    }

    /** True when the money moved, or when there was nothing to move in the first place. */
    public boolean ok() {
        return reason == Reason.OK || reason == Reason.NOTHING;
    }

    public Reason reason() {
        return reason;
    }

    /** Denars that actually moved. Always zero on a failure. */
    public int moved() {
        return moved;
    }

    /** Denars the caller asked for. */
    public int wanted() {
        return wanted;
    }

    /** The most that could have been made, so a message can say how far short it fell. */
    public int best() {
        return best;
    }

    public int shortfall() {
        return Math.max(0, wanted - best);
    }

    /** Chip animations to hand to {@code flushPiles}. Empty unless the caller asked for them. */
    public List<PayoutFlight> flights() {
        return flights;
    }

    /** The messages.yml key that explains this to a player. */
    public String messageKey() {
        return switch (reason) {
            case OK, NOTHING -> null;
            case NO_CHANGE -> "bet.no_change";
            case PLAYER_SHORT, FELT_SHORT -> "bet.need_chips";
            case BANK_SHORT, NO_TEMPLATE, MINT_REFUSED, NO_TABLE -> "bet.bank_short";
        };
    }
}
