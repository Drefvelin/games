package net.tfminecraft.games.wager;

import java.util.List;

import org.bukkit.inventory.ItemStack;

/**
 * Where withheld citizen tax chips go. They are destroyed rather than banked or left on the
 * felt, so the same denars are not taxed twice as guild gambling profit.
 */
final class TaxSink implements MoneyAccount {

    static final TaxSink INSTANCE = new TaxSink();

    private TaxSink() {}

    @Override
    public String label() {
        return "citizen tax";
    }

    @Override
    public int available() {
        return 0;
    }

    @Override
    public Withdrawal planTake(int denars, ItemStack template) {
        return null;
    }

    @Override
    public Withdrawal planTakeAll(Integer street) {
        return null;
    }

    @Override
    public int largestTakeUpTo(int denars, ItemStack template) {
        return 0;
    }

    @Override
    public boolean canAccept(int denars) {
        return true;
    }

    @Override
    public int accept(List<Stake> stakes) {
        // Chips stop existing. Nothing is banked and the felt total does not change.
        return TableLedger.valueOf(stakes);
    }
}
