package net.tfminecraft.games.table;

import java.util.UUID;

import org.bukkit.entity.Player;

import net.tfminecraft.games.layout.TableLayout;

/**
 * Place / options snapshot. Guild id is filled in a later batch.
 */
public final class TableHouse {

    public static final String STAFF_PERM = "games.autodealer.staff";

    private UUID ownerPlayer;
    private String ownerGuildId;
    private boolean autoDealer;
    private boolean staffMint;
    private int minBet;
    private int maxBet;
    private int maxBoxes;
    private ShufflePolicy shufflePolicy = ShufflePolicy.SHOE;

    public static TableHouse forPlace(Player player, TableLayout layout) {
        TableHouse house = new TableHouse();
        if (player != null) {
            house.ownerPlayer = player.getUniqueId();
        }
        boolean staff = player != null && player.hasPermission(STAFF_PERM);
        boolean yamlAuto = layout != null && layout.autoDealer();
        if (staff && yamlAuto) {
            house.autoDealer = true;
            house.staffMint = true;
        }
        if (layout != null) {
            house.minBet = layout.minBet();
            house.maxBet = layout.maxBet();
            house.maxBoxes = layout.maxBoxes();
        }
        house.shufflePolicy = ShufflePolicy.SHOE;
        return house;
    }

    public static TableHouse from(Table table) {
        TableHouse house = new TableHouse();
        if (table == null) {
            return house;
        }
        house.ownerPlayer = table.ownerPlayer();
        house.ownerGuildId = table.ownerGuildId();
        house.autoDealer = table.autoDealer();
        house.staffMint = table.staffMint();
        house.minBet = table.minBet();
        house.maxBet = table.maxBet();
        house.maxBoxes = table.maxBoxes();
        house.shufflePolicy = table.shufflePolicy();
        return house;
    }

    public void apply(Table table) {
        if (table == null) {
            return;
        }
        table.setOwnerPlayer(ownerPlayer);
        table.setOwnerGuildId(ownerGuildId);
        table.setAutoDealer(autoDealer);
        table.setStaffMint(staffMint);
        table.setMinBet(minBet);
        table.setMaxBet(maxBet);
        table.setMaxBoxes(maxBoxes);
        table.setShufflePolicy(shufflePolicy);
    }

    public UUID ownerPlayer() {
        return ownerPlayer;
    }

    public void setOwnerPlayer(UUID ownerPlayer) {
        this.ownerPlayer = ownerPlayer;
    }

    public String ownerGuildId() {
        return ownerGuildId;
    }

    public void setOwnerGuildId(String ownerGuildId) {
        this.ownerGuildId = ownerGuildId;
    }

    public boolean autoDealer() {
        return autoDealer;
    }

    public void setAutoDealer(boolean autoDealer) {
        this.autoDealer = autoDealer;
        if (!autoDealer) {
            this.staffMint = false;
        }
    }

    public boolean staffMint() {
        return staffMint;
    }

    public void setStaffMint(boolean staffMint) {
        this.staffMint = staffMint;
        if (staffMint) {
            this.autoDealer = true;
        }
    }

    public int minBet() {
        return minBet;
    }

    public void setMinBet(int minBet) {
        this.minBet = Math.max(0, minBet);
    }

    public int maxBet() {
        return maxBet;
    }

    public void setMaxBet(int maxBet) {
        this.maxBet = Math.max(0, maxBet);
    }

    public int maxBoxes() {
        return maxBoxes;
    }

    public void setMaxBoxes(int maxBoxes) {
        this.maxBoxes = Math.max(0, maxBoxes);
    }

    public ShufflePolicy shufflePolicy() {
        return shufflePolicy != null ? shufflePolicy : ShufflePolicy.SHOE;
    }

    public void setShufflePolicy(ShufflePolicy shufflePolicy) {
        this.shufflePolicy = shufflePolicy != null ? shufflePolicy : ShufflePolicy.SHOE;
    }
}
