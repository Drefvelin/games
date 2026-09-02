package net.tfminecraft.games.wager;

import org.bukkit.inventory.ItemStack;

/**
 * Real money held by a table: a number of one kind of coin or valued item.
 * The item is what gets handed back when this stake is paid out, so the value
 * can only ever move in whole units of it.
 */
public final class Stake {

    private final ItemStack item;
    private final String typeKey;
    private final int unit;
    private final int streetId;
    private int count;
    /** Where these chips are drawn, when somebody chose the spot rather than a layout. */
    private double x;
    private double z;
    private boolean placed;

    public Stake(ItemStack item, String typeKey, int unit, int count, int streetId) {
        this.item = item;
        this.typeKey = typeKey;
        this.unit = Math.max(0, unit);
        this.count = Math.max(0, count);
        this.streetId = streetId;
    }

    /** One of these, for spawning chips or giving items. */
    public ItemStack item() {
        return item;
    }

    public String typeKey() {
        return typeKey;
    }

    /** Denars per item. */
    public int unit() {
        return unit;
    }

    public int count() {
        return count;
    }

    public int streetId() {
        return streetId;
    }

    /** True when this stake was put somewhere on purpose, so it is drawn there and nowhere else. */
    public boolean placed() {
        return placed;
    }

    public double x() {
        return x;
    }

    public double z() {
        return z;
    }

    /**
     * Where to draw these chips. A stake owns its spot rather than the bucket owning one for
     * everything, so a player's second bet lands where they clicked and not on a spiral.
     */
    void setSpot(Double spotX, Double spotZ) {
        if (spotX == null || spotZ == null) {
            this.placed = false;
            this.x = 0;
            this.z = 0;
            return;
        }
        this.x = spotX;
        this.z = spotZ;
        this.placed = true;
    }

    /** How far this stake sits from a spot, squared. Only meaningful when it has one. */
    double distanceSq(double spotX, double spotZ) {
        double dx = x - spotX;
        double dz = z - spotZ;
        return dx * dx + dz * dz;
    }

    /** Denars held by this stake. */
    public int value() {
        return unit * count;
    }

    public void addCount(int amount) {
        if (amount > 0) {
            count += amount;
        }
    }

    void setCount(int count) {
        this.count = Math.max(0, count);
    }

    /**
     * Only stakes of the very same item merge, so a loot wager always comes back as the
     * item that was staked and not a lookalike of the same value.
     */
    boolean sameKind(ItemStack other, String otherType, int otherUnit, int otherStreet) {
        return unit == otherUnit && streetId == otherStreet
                && (typeKey == null ? otherType == null : typeKey.equals(otherType))
                && item != null && item.isSimilar(other);
    }

    Stake split(int takeCount) {
        int take = Math.min(Math.max(0, takeCount), count);
        count -= take;
        Stake part = new Stake(item, typeKey, unit, take, streetId);
        // Keeps the spot so a payout animation flies from the stack that actually paid.
        if (placed) {
            part.setSpot(x, z);
        }
        return part;
    }
}
