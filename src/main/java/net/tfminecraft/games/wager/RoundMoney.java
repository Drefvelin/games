package net.tfminecraft.games.wager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Coin money a player put on the felt this round and coin money paid back to them.
 * Loot stakes are ignored via {@link ChipItems#moneyValue}. Cleared when the session ends.
 */
public final class RoundMoney {

    private final Map<UUID, Integer> moneyIn = new HashMap<>();
    private final Map<UUID, Integer> moneyOut = new HashMap<>();

    /** Record one successful leg from {@link MoneyTx}. */
    public void recordLeg(MoneyAccount from, MoneyAccount to, List<Stake> coins) {
        int value = ChipItems.moneyValue(coins);
        if (value < 1) {
            return;
        }
        if (from instanceof PlayerAccount && to != null && to.onFelt()) {
            UUID owner = from.flightTarget();
            if (owner != null) {
                moneyIn.merge(owner, value, Integer::sum);
            }
        }
        if (to instanceof PlayerAccount) {
            UUID owner = to.flightTarget();
            if (owner != null) {
                moneyOut.merge(owner, value, Integer::sum);
            }
        }
    }

    public int moneyIn(UUID owner) {
        if (owner == null) {
            return 0;
        }
        return moneyIn.getOrDefault(owner, 0);
    }

    public int moneyOut(UUID owner) {
        if (owner == null) {
            return 0;
        }
        return moneyOut.getOrDefault(owner, 0);
    }

    /** Net coin profit this round: paid out minus staked, never negative. */
    public int moneyProfit(UUID owner) {
        return Math.max(0, moneyOut(owner) - moneyIn(owner));
    }

    /**
     * Coin profit in one payout after stake is returned. Uses running {@link #moneyIn} /
     * {@link #moneyOut} so multi-street pots only tax what is left after earlier payouts.
     */
    public int taxableProfit(UUID owner, int payout) {
        if (owner == null || payout < 1) {
            return 0;
        }
        int stakeBack = Math.min(payout, Math.max(0, moneyIn(owner) - moneyOut(owner)));
        return Math.max(0, payout - stakeBack);
    }

    public void clear() {
        moneyIn.clear();
        moneyOut.clear();
    }
}
