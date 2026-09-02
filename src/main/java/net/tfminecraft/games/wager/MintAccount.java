package net.tfminecraft.games.wager;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.inventory.ItemStack;

import net.tfminecraft.games.table.Table;

/**
 * Money from nothing, and money into nothing, for staff tables that have no guild behind them.
 * Refuses outright on a guild table, so a real economy can never be inflated by a bug here.
 */
public final class MintAccount implements MoneyAccount {

    private final Table table;
    private final int streetId;

    MintAccount(Table table, int streetId) {
        this.table = table;
        this.streetId = streetId;
    }

    @Override
    public String label() {
        return "mint";
    }

    @Override
    public int available() {
        return table.staffMint() ? Integer.MAX_VALUE : 0;
    }

    @Override
    public Withdrawal planTake(int denars, ItemStack template) {
        int unit = ChipItems.unitDenars(template);
        if (!table.staffMint() || denars < 1 || unit < 1 || denars % unit != 0) {
            return null;
        }
        ItemStack one = template.clone();
        one.setAmount(1);
        Stake made = new Stake(one, ChipItems.typeKey(one), unit, denars / unit, streetId);
        return new MintWithdrawal(made, denars);
    }

    @Override
    public Withdrawal planTakeAll(Integer street) {
        return null;
    }

    @Override
    public int largestTakeUpTo(int denars, ItemStack template) {
        int unit = ChipItems.unitDenars(template);
        if (!table.staffMint() || denars < 1 || unit < 1) {
            return 0;
        }
        return denars / unit * unit;
    }

    @Override
    public boolean canAccept(int denars) {
        return denars < 1 || table.staffMint();
    }

    @Override
    public int accept(List<Stake> stakes) {
        // Nothing to do: staff chips simply stop existing.
        return TableLedger.valueOf(stakes);
    }

    private record MintWithdrawal(Stake made, int denars) implements Withdrawal {

        @Override
        public List<Stake> preview() {
            return coins();
        }

        @Override
        public List<Stake> take() {
            return coins();
        }

        private List<Stake> coins() {
            List<Stake> out = new ArrayList<>();
            out.add(new Stake(made.item(), made.typeKey(), made.unit(), made.count(), made.streetId()));
            return out;
        }
    }
}
