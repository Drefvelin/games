package net.tfminecraft.games.gui;

import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Game-select chest. pendingHit is the precise click on a block, or null if armed from a command.
 */
public final class GameSelectHolder implements InventoryHolder {

    private final boolean requireDeck;
    private final Location pendingHit;
    private Inventory inventory;

    public GameSelectHolder(boolean requireDeck, Location pendingHit) {
        this.requireDeck = requireDeck;
        this.pendingHit = pendingHit != null ? pendingHit.clone() : null;
    }

    public boolean requireDeck() {
        return requireDeck;
    }

    public Location pendingHit() {
        return pendingHit;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }
}
