package net.tfminecraft.games.wager;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.inventory.ItemStack;

import net.tfminecraft.games.table.Table;

/**
 * The guild bank behind an auto dealer table. It holds a balance rather than coins, so money
 * coming out of it has to be shaped like a coin the felt can hold: the amount is checked
 * against that coin before anything is withdrawn, which is why nothing ever has to be put back.
 */
public final class BankAccount implements MoneyAccount {

    private final Table table;
    private final int streetId;

    BankAccount(Table table, int streetId) {
        this.table = table;
        this.streetId = streetId;
    }

    @Override
    public String label() {
        return "guild bank";
    }

    @Override
    public int available() {
        return GuildBank.balance(table.ownerGuildId());
    }

    @Override
    public Withdrawal planTake(int denars, ItemStack template) {
        int unit = ChipItems.unitDenars(template);
        if (denars < 1 || unit < 1 || denars % unit != 0) {
            return null;
        }
        if (available() < denars) {
            return null;
        }
        return new BankWithdrawal(template, unit, denars / unit, denars);
    }

    @Override
    public Withdrawal planTakeAll(Integer street) {
        // A bank balance is not a pile you can sweep off the felt.
        return null;
    }

    @Override
    public int largestTakeUpTo(int denars, ItemStack template) {
        int unit = ChipItems.unitDenars(template);
        if (denars < 1 || unit < 1) {
            return 0;
        }
        return Math.min(denars, available()) / unit * unit;
    }

    @Override
    public boolean canAccept(int denars) {
        return denars < 1 || GuildBank.canHold(table.ownerGuildId());
    }

    @Override
    public int accept(List<Stake> stakes) {
        int value = TableLedger.valueOf(stakes);
        if (value < 1) {
            return 0;
        }
        if (!GuildBank.deposit(table.ownerGuildId(), value)) {
            return 0;
        }
        // The float comes home before anything is called a win, so a table that has been losing
        // has to earn its way back to level before the guild owes tax on any of it.
        int repaid = Math.min(value, table.houseFloat());
        table.setHouseFloat(table.houseFloat() - repaid);
        int profit = value - repaid;
        if (profit > 0) {
            GuildBank.declareProfit(table.ownerGuildId(), profit);
        }
        return value;
    }

    private final class BankWithdrawal implements Withdrawal {

        private final ItemStack template;
        private final int unit;
        private final int count;
        private final int denars;

        private BankWithdrawal(ItemStack template, int unit, int count, int denars) {
            this.template = template;
            this.unit = unit;
            this.count = count;
            this.denars = denars;
        }

        @Override
        public int denars() {
            return denars;
        }

        @Override
        public List<Stake> preview() {
            return coins();
        }

        @Override
        public List<Stake> take() {
            if (!GuildBank.withdraw(table.ownerGuildId(), denars)) {
                return new ArrayList<>();
            }
            table.setHouseFloat(table.houseFloat() + denars);
            return coins();
        }

        private List<Stake> coins() {
            ItemStack one = template.clone();
            one.setAmount(1);
            List<Stake> out = new ArrayList<>();
            out.add(new Stake(one, ChipItems.typeKey(one), unit, count, streetId));
            return out;
        }
    }
}
