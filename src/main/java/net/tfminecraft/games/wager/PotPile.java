package net.tfminecraft.games.wager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.inventory.ItemStack;

/** One chip or valued-item pile on a table. */
public final class PotPile {

    private UUID ownerId;
    private final ItemStack item;
    private final String typeKey;
    private final int denars;
    private int count;
    private int pieces;
    private int streetId;
    private double x;
    private double z;
    private final List<UUID> tokens = new ArrayList<>();
    private final List<Float> layerYaws = new ArrayList<>();

    public PotPile(UUID ownerId, ItemStack item, String typeKey, int denars, int count, double x, double z) {
        this.ownerId = ownerId;
        this.item = item;
        this.typeKey = typeKey;
        this.denars = denars;
        this.count = count;
        this.pieces = Math.max(0, count);
        this.x = x;
        this.z = z;
    }

    public UUID ownerId() {
        return ownerId;
    }

    /** Null owner is the communal pot. */
    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public ItemStack item() {
        return item;
    }

    public String typeKey() {
        return typeKey;
    }

    public int denars() {
        return denars;
    }

    public int count() {
        return count;
    }

    public void addOne() {
        count++;
        pieces++;
    }

    public void setCount(int count) {
        this.count = Math.max(0, count);
    }

    public int pieces() {
        return pieces;
    }

    public void setPieces(int pieces) {
        this.pieces = Math.max(0, pieces);
    }

    public void addPieces(int amount) {
        if (amount > 0) {
            pieces += amount;
        }
    }

    public void addCount(int amount) {
        if (amount > 0) {
            count += amount;
        }
    }

    public int contribution() {
        return denars * count;
    }

    public int streetId() {
        return streetId;
    }

    public void setStreetId(int streetId) {
        this.streetId = streetId;
    }

    public double x() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double z() {
        return z;
    }

    public void setZ(double z) {
        this.z = z;
    }

    public List<UUID> tokens() {
        return tokens;
    }

    public List<Float> layerYaws() {
        return layerYaws;
    }
}
