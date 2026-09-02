package net.tfminecraft.games.wager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.games.table.PayoutFlight;
import net.tfminecraft.games.table.Table;

/**
 * The only way money moves on a table.
 *
 * <p>Games ask questions of this and start transactions through it. They do not touch the
 * ledger, a player's pockets, or a guild bank, because every bug worth fixing in this area came
 * from two of those being changed in sequence and the second one failing.
 */
public final class WagerEngine {

    private static WagerEngine instance;

    private final WagerHost host;

    private WagerEngine(WagerHost host) {
        this.host = host;
    }

    public static void init(WagerHost host) {
        instance = new WagerEngine(host);
    }

    public static WagerEngine get() {
        return instance;
    }

    /** Start a movement of money. Nothing happens until {@code commit()}. */
    public MoneyTx begin(Table table, String reason) {
        return new MoneyTx(host, table, reason);
    }

    // ---------------------------------------------------------------- reading

    /** Every denar this table is holding, tray included. */
    public int total(Table table) {
        return table == null ? 0 : table.ledger().total();
    }

    /** Money in play: everything except the house tray. */
    public int felt(Table table) {
        return table == null ? 0 : table.ledger().totalExcept(table.getId());
    }

    public int owned(Table table, UUID owner) {
        return table == null || owner == null ? 0 : table.ledger().total(owner);
    }

    public int owned(Table table, UUID owner, int street) {
        return table == null || owner == null ? 0 : table.ledger().total(owner, street);
    }

    public int tray(Table table) {
        return table == null ? 0 : table.ledger().total(table.getId());
    }

    /** Owner to denars, skipping one bucket. Used for pot levels. */
    public Map<UUID, Integer> totalsExcept(Table table, UUID skip) {
        return table == null ? Map.of() : table.ledger().totalsExcept(skip);
    }

    /** Everyone holding money, in the order they first staked. */
    public List<UUID> owners(Table table) {
        return table == null ? List.of() : new ArrayList<>(table.ledger().owners());
    }

    /** Everyone holding money except the tray. In poker this is the pot. */
    public List<UUID> potOwners(Table table) {
        List<UUID> out = new ArrayList<>();
        if (table == null) {
            return out;
        }
        for (UUID owner : table.ledger().owners()) {
            if (!owner.equals(table.getId())) {
                out.add(owner);
            }
        }
        return out;
    }

    /** The pot buckets as accounts, ready to pay a winner out of. */
    public List<MoneyAccount> potAccounts(Table table) {
        List<MoneyAccount> out = new ArrayList<>();
        for (UUID owner : potOwners(table)) {
            out.add(Accounts.bucket(table, owner));
        }
        return out;
    }

    /**
     * The smallest coin a bucket holds, so anything the house has to create is as divisible as
     * the money already on the table.
     */
    public ItemStack template(Table table, UUID owner) {
        if (table == null || owner == null) {
            return null;
        }
        ItemStack template = table.ledger().template(owner);
        if (template == null) {
            return null;
        }
        ItemStack one = template.clone();
        one.setAmount(1);
        return one;
    }

    /** Denars one of these items is worth. */
    public int unitOf(ItemStack stack) {
        return ChipItems.unitDenars(stack);
    }

    // ---------------------------------------------------------------- sweeping

    /**
     * The whole pot to one place, as one movement. For a hand that ends without a showdown, or
     * one there is nobody left to play for.
     */
    public TxResult sweepPot(Table table, Player dest, List<PayoutFlight> flights, String reason) {
        if (table == null) {
            return TxResult.nothing();
        }
        MoneyTx tx = begin(table, reason).animate(flights);
        for (UUID owner : potOwners(table)) {
            tx.moveAll(Accounts.bucket(table, owner), Accounts.payee(table, dest, owner));
        }
        return tx.commit();
    }

    /** Every stake back to whoever put it there, as one movement. */
    public TxResult returnStakes(Table table, List<PayoutFlight> flights, String reason) {
        if (table == null) {
            return TxResult.nothing();
        }
        MoneyTx tx = begin(table, reason).animate(flights);
        for (UUID owner : potOwners(table)) {
            tx.moveAll(Accounts.bucket(table, owner),
                    Accounts.payee(table, Accounts.online(owner), owner));
        }
        return tx.commit();
    }

    /**
     * One bucket back to a player. Pass {@code denars} below 1 to hand back the whole bucket,
     * which is always exact; a set amount is limited to what the coins there can make.
     */
    public TxResult refund(Table table, UUID owner, Player dest, int denars,
            List<PayoutFlight> flights, String reason) {
        if (table == null || owner == null) {
            return TxResult.nothing();
        }
        MoneyTx tx = begin(table, reason).animate(flights);
        MoneyAccount from = Accounts.bucket(table, owner);
        MoneyAccount to = Accounts.payee(table, dest, owner);
        if (denars > 0) {
            tx.moveUpTo(from, to, denars);
        } else {
            tx.moveAll(from, to);
        }
        return tx.commit();
    }

    /**
     * House money onto the felt, shaped like {@code template}. The amount has to be a whole
     * number of those coins or the transaction refuses, which is what used to need a compensating
     * deposit afterwards.
     */
    public TxResult fundFromHouse(Table table, UUID owner, ItemStack template, int denars,
            Location anchor, String reason) {
        if (table == null || owner == null) {
            return TxResult.nothing();
        }
        return begin(table, reason)
                .move(Accounts.house(table), Accounts.bucket(table, owner).at(anchor), denars, template)
                .commit();
    }

    /**
     * Tray money back to the house, as much of {@code denars} as the tray can make in whole
     * coins. One movement, so the tray and the bank can never disagree about it.
     */
    public TxResult peelToHouse(Table table, int denars, String reason) {
        if (table == null) {
            return TxResult.nothing();
        }
        return begin(table, reason)
                .moveUpTo(Accounts.tray(table), Accounts.house(table), denars)
                .commit();
    }

    /** A losing bet off the felt and into the house tray. */
    public TxResult toTray(Table table, UUID owner, int denars, List<PayoutFlight> flights,
            String reason) {
        if (table == null || owner == null) {
            return TxResult.nothing();
        }
        MoneyTx tx = begin(table, reason).animate(flights);
        MoneyAccount from = Accounts.bucket(table, owner);
        if (denars > 0) {
            tx.moveUpTo(from, Accounts.tray(table), denars);
        } else {
            tx.moveAll(from, Accounts.tray(table));
        }
        return tx.commit();
    }

    /** Pay a share of the pot, drawing from the buckets that built it so the chips fly from there. */
    public TxResult payFromPot(Table table, Player dest, int denars, List<PayoutFlight> flights,
            String reason) {
        if (table == null || denars < 1) {
            return TxResult.nothing();
        }
        return begin(table, reason).animate(flights)
                .spread(Accounts.payee(table, dest, null), denars, potAccounts(table))
                .commit();
    }

    /** One player's bet on the current street back to them, leaving earlier streets in the pot. */
    public TxResult refundStreet(Table table, UUID owner, int street, List<PayoutFlight> flights,
            String reason) {
        if (table == null || owner == null) {
            return TxResult.nothing();
        }
        return begin(table, reason).animate(flights)
                .moveStreet(Accounts.bucket(table, owner),
                        Accounts.payee(table, Accounts.online(owner), owner), street)
                .commit();
    }

    /**
     * Put money back on a table as it was saved. Loading is not a transfer of anything, so no
     * transaction, but it still comes through here so the ledger only has one door.
     */
    public void restore(Table table, UUID owner, ItemStack item, String typeKey, int unit, int count,
            int street, Double anchorX, Double anchorZ) {
        restore(table, owner, item, typeKey, unit, count, street, anchorX, anchorZ, null, null);
    }

    /** As above, keeping the spot a heap was put down on so chips come back where they were. */
    public void restore(Table table, UUID owner, ItemStack item, String typeKey, int unit, int count,
            int street, Double anchorX, Double anchorZ, Double spotX, Double spotZ) {
        if (table == null || owner == null || item == null || unit < 1 || count < 1) {
            return;
        }
        ItemStack one = item.clone();
        one.setAmount(1);
        table.ledger().add(owner, one, typeKey != null ? typeKey : ChipItems.typeKey(one), unit, count,
                street > 0 ? street : table.street(), spotX, spotZ);
        if (anchorX != null && anchorZ != null) {
            table.ledger().setAnchor(owner, anchorX, anchorZ);
        }
    }

    /** Forget an emptied bucket, including the spot its chips were drawn on. */
    public void forget(Table table, UUID owner) {
        if (table != null && owner != null) {
            table.ledger().forget(owner);
        }
    }
}
