package net.tfminecraft.games.wager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.games.table.PayoutFlight;
import net.tfminecraft.games.table.Table;

/**
 * One movement of money, checked in full before any of it happens.
 *
 * <p>Every leg is planned first. If any leg cannot be met the whole thing is abandoned and not
 * a single coin has moved, so a caller that gets a failure can refuse its action without
 * putting anything back. That is the difference from the old helpers, which mutated as they
 * went and then leaned on a refund that was allowed to quietly hand back less than it took.
 *
 * <p>Everything happens in one synchronous pass. Animations are drawn from what moved and
 * carry no value of their own.
 */
public final class MoneyTx {

    private enum Mode {
        /** All of it or nothing. */
        EXACT,
        /** As much as the source can hand over exactly, no more than asked. */
        UP_TO,
        /** Whatever the source is holding. */
        ALL
    }

    private static final class Leg {
        private MoneyAccount from;
        private MoneyAccount to;
        private int denars;
        private Mode mode;
        private ItemStack template;
        private Integer street;
        /** Shared remainder when several sources are covering one amount between them. */
        private int[] pool;
    }

    private record Planned(Leg leg, Withdrawal plan) {}

    private final WagerHost host;
    private final Table table;
    private final String reason;
    private final List<Leg> legs = new ArrayList<>();
    private List<PayoutFlight> flights;

    MoneyTx(WagerHost host, Table table, String reason) {
        this.host = host;
        this.table = table;
        this.reason = reason;
    }

    /** Exactly this much, or the transaction fails and nothing moves. */
    public MoneyTx move(MoneyAccount from, MoneyAccount to, int denars) {
        return move(from, to, denars, null);
    }

    /**
     * Exactly this much, shaped like {@code template} where the source holds a balance instead
     * of coins. The amount must be a whole number of those coins, which is checked while
     * planning, so a bank never pays out more than the felt can hold.
     */
    public MoneyTx move(MoneyAccount from, MoneyAccount to, int denars, ItemStack template) {
        if (from == null || to == null || denars < 1) {
            return this;
        }
        Leg leg = new Leg();
        leg.from = from;
        leg.to = to;
        leg.denars = denars;
        leg.mode = Mode.EXACT;
        leg.template = template;
        legs.add(leg);
        return this;
    }

    /** As much as the source can hand over exactly, up to this much. Never fails for being short. */
    public MoneyTx moveUpTo(MoneyAccount from, MoneyAccount to, int denars) {
        return moveUpTo(from, to, denars, null, null);
    }

    /** As above, shaped like {@code template} where the source holds a balance. */
    public MoneyTx moveUpTo(MoneyAccount from, MoneyAccount to, int denars, ItemStack template) {
        return moveUpTo(from, to, denars, template, null);
    }

    private MoneyTx moveUpTo(MoneyAccount from, MoneyAccount to, int denars, ItemStack template,
            int[] pool) {
        if (from == null || to == null || denars < 1) {
            return this;
        }
        Leg leg = new Leg();
        leg.from = from;
        leg.to = to;
        leg.denars = denars;
        leg.mode = Mode.UP_TO;
        leg.template = template;
        leg.pool = pool;
        legs.add(leg);
        return this;
    }

    /** Everything the source is holding. */
    public MoneyTx moveAll(MoneyAccount from, MoneyAccount to) {
        return moveStreet(from, to, null);
    }

    /** Everything the source staked on one betting street. */
    public MoneyTx moveStreet(MoneyAccount from, MoneyAccount to, Integer street) {
        if (from == null || to == null) {
            return this;
        }
        Leg leg = new Leg();
        leg.from = from;
        leg.to = to;
        leg.mode = Mode.ALL;
        leg.street = street;
        legs.add(leg);
        return this;
    }

    /**
     * Cover one amount from several sources in turn, each giving what it can. Used to pay a pot
     * out of the boxes that built it, and to top a payout up from the house when the tray
     * cannot make the exact figure on its own.
     */
    public MoneyTx spread(MoneyAccount to, int denars, List<MoneyAccount> sources) {
        return spread(to, denars, sources, null);
    }

    public MoneyTx spread(MoneyAccount to, int denars, List<MoneyAccount> sources, ItemStack template) {
        if (to == null || sources == null || denars < 1) {
            return this;
        }
        int[] pool = {denars};
        for (MoneyAccount source : sources) {
            moveUpTo(source, to, denars, template, pool);
        }
        return this;
    }

    /** Collect chip animations for {@code flushPiles} instead of moving the money silently. */
    public MoneyTx animate(List<PayoutFlight> into) {
        this.flights = into;
        return this;
    }

    /** Plan every leg, then carry them all out. Nothing moves unless all of it can. */
    public TxResult commit() {
        if (table == null || host == null) {
            return TxResult.failed(TxResult.Reason.NO_TABLE, 0, 0);
        }
        if (legs.isEmpty()) {
            return TxResult.nothing();
        }
        List<Planned> planned = new ArrayList<>();
        for (Leg leg : legs) {
            int want = wanted(leg);
            Withdrawal plan = want > 0 ? plan(leg, want) : null;
            if (plan == null) {
                if (leg.mode == Mode.EXACT) {
                    return TxResult.failed(refusal(leg, want), want,
                            leg.from.largestTakeUpTo(want, leg.template));
                }
                continue;
            }
            if (!leg.to.canAccept(plan.denars())) {
                return TxResult.failed(TxResult.Reason.BANK_SHORT, want, 0);
            }
            if (leg.pool != null) {
                leg.pool[0] -= plan.denars();
            }
            planned.add(new Planned(leg, plan));
        }
        if (planned.isEmpty()) {
            return TxResult.nothing();
        }
        return apply(planned);
    }

    private int wanted(Leg leg) {
        if (leg.mode == Mode.ALL) {
            return Integer.MAX_VALUE;
        }
        int asked = leg.pool != null ? Math.min(leg.denars, leg.pool[0]) : leg.denars;
        if (asked < 1) {
            return 0;
        }
        return leg.mode == Mode.UP_TO ? leg.from.largestTakeUpTo(asked, leg.template) : asked;
    }

    private Withdrawal plan(Leg leg, int want) {
        if (leg.mode == Mode.ALL) {
            return leg.from.planTakeAll(leg.street);
        }
        return leg.from.planTake(want, leg.template);
    }

    /** Why an exact leg could not be met, so the player gets told the real reason. */
    private TxResult.Reason refusal(Leg leg, int want) {
        MoneyAccount from = leg.from;
        if ((from instanceof BankAccount || from instanceof MintAccount)
                && ChipItems.unitDenars(leg.template) < 1) {
            return TxResult.Reason.NO_TEMPLATE;
        }
        if (from instanceof MintAccount) {
            return TxResult.Reason.MINT_REFUSED;
        }
        boolean short0 = from.available() < want;
        if (from instanceof BankAccount) {
            return short0 ? TxResult.Reason.BANK_SHORT : TxResult.Reason.NO_CHANGE;
        }
        if (from instanceof PlayerAccount) {
            return short0 ? TxResult.Reason.PLAYER_SHORT : TxResult.Reason.NO_CHANGE;
        }
        return short0 ? TxResult.Reason.FELT_SHORT : TxResult.Reason.NO_CHANGE;
    }

    private TxResult apply(List<Planned> planned) {
        int feltBefore = table.ledger().total();
        int expected = 0;
        int moved = 0;
        Set<UUID> touched = new LinkedHashSet<>();
        for (Planned step : planned) {
            Leg leg = step.leg();
            // Read the spot before the money leaves, since emptying a bucket can drop its anchor.
            Location from = host.anchorFor(table, leg.from.feltOwner());
            List<Stake> coins = step.plan().take();
            int value = TableLedger.valueOf(coins);
            if (value != step.plan().denars()) {
                MoneyLog.mismatch(table, "planned " + step.plan().denars() + " out of "
                        + leg.from.label() + " but moved " + value + " (" + reason + ")");
            }
            if (value < 1) {
                continue;
            }
            int absorbed = leg.to.accept(coins);
            if (absorbed < value) {
                // Nothing sensible to do but hand it back where it came from, and shout.
                MoneyLog.mismatch(table, leg.to.label() + " only took " + absorbed + " of " + value
                        + ", returning it to " + leg.from.label() + " (" + reason + ")");
                leg.from.accept(coins);
                continue;
            }
            MoneyLog.move(table, leg.from.label(), leg.to.label(), value, reason);
            if (flights != null && from != null) {
                flights.addAll(host.flights(table, coins, from, leg.to.flightTarget(),
                        leg.to.isTray()));
            }
            if (leg.from.onFelt()) {
                touched.add(leg.from.feltOwner());
                expected -= value;
            }
            if (leg.to.onFelt()) {
                touched.add(leg.to.feltOwner());
                expected += value;
            }
            moved += value;
        }
        host.moneyMoved(table, touched);
        int delta = table.ledger().total() - feltBefore;
        if (delta != expected) {
            MoneyLog.mismatch(table, "felt moved by " + delta + " but the legs add up to "
                    + expected + " (" + reason + ")");
        }
        LedgerAudit.check(table, reason);
        return TxResult.done(moved, flights);
    }
}
