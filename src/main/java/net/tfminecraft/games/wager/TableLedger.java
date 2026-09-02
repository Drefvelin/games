package net.tfminecraft.games.wager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.bukkit.inventory.ItemStack;

import net.tfminecraft.games.cache.Cache;

/**
 * Every denar a table holds. One bucket per player, plus one keyed by the table id
 * for the house tray. Chips on the felt are a drawing of this and nothing more.
 *
 * <p>Anything that reads is public. Anything that changes the money is package private, so a
 * balance can only move through a {@link MoneyTx}, where it has to be planned first and where
 * its other half is guaranteed to happen too.
 */
public final class TableLedger {

    /** One owner's money, plus where their chips are drawn. */
    private static final class Bucket {
        private final List<Stake> stakes = new ArrayList<>();
        private double anchorX;
        private double anchorZ;
        private boolean anchored;
    }

    private final Map<UUID, Bucket> buckets = new LinkedHashMap<>();

    public boolean isEmpty() {
        return total() < 1;
    }

    /** Owners that hold money, in the order they first staked. */
    public Set<UUID> owners() {
        Set<UUID> out = new LinkedHashSet<>();
        for (Map.Entry<UUID, Bucket> entry : buckets.entrySet()) {
            if (value(entry.getValue(), null) > 0) {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    void add(UUID owner, ItemStack item, String typeKey, int unit, int count, int streetId) {
        add(owner, item, typeKey, unit, count, streetId, null, null);
    }

    /**
     * Money into a bucket, drawn at {@code spotX}/{@code spotZ} when a spot is given.
     *
     * <p>A spot is part of a stake's identity, so chips put down in two places stay in two
     * places. Coins arriving without one merge into any stack of the same kind, which is what
     * the bank, the mint and a load from disk want.
     */
    void add(UUID owner, ItemStack item, String typeKey, int unit, int count, int streetId,
            Double spotX, Double spotZ) {
        if (owner == null || item == null || unit < 1 || count < 1) {
            return;
        }
        Bucket bucket = buckets.computeIfAbsent(owner, key -> new Bucket());
        ItemStack one = item.clone();
        one.setAmount(1);
        boolean spotted = spotX != null && spotZ != null;
        double range = Math.max(0.0, Cache.wagerMergeRange);
        double rangeSq = range * range;
        for (Stake stake : bucket.stakes) {
            if (!stake.sameKind(one, typeKey, unit, streetId)) {
                continue;
            }
            if (!spotted) {
                stake.addCount(count);
                return;
            }
            // Only stacks close enough to be the same heap absorb a placed chip. A stake with no
            // spot of its own never does, so what was placed by hand is always drawn where it was.
            if (stake.placed() && stake.distanceSq(spotX, spotZ) <= rangeSq) {
                stake.addCount(count);
                return;
            }
        }
        Stake fresh = new Stake(one, typeKey, unit, count, streetId);
        fresh.setSpot(spotX, spotZ);
        bucket.stakes.add(fresh);
    }

    /**
     * Put a whole stake back, keeping its item and street, at the spot the destination chose.
     * A stake's spot describes where it sits in the bucket holding it, so the one it carried
     * out of its old bucket is not reused here.
     */
    void put(UUID owner, Stake stake, Double spotX, Double spotZ) {
        if (stake != null) {
            add(owner, stake.item(), stake.typeKey(), stake.unit(), stake.count(), stake.streetId(),
                    spotX, spotZ);
        }
    }

    public int total() {
        int sum = 0;
        for (Bucket bucket : buckets.values()) {
            sum += value(bucket, null);
        }
        return sum;
    }

    public int total(UUID owner) {
        return value(buckets.get(owner), null);
    }

    public int total(UUID owner, int streetId) {
        return value(buckets.get(owner), stake -> stake.streetId() == streetId);
    }

    /** Everything except one bucket, which is how the house reads the live action. */
    public int totalExcept(UUID owner) {
        int sum = 0;
        for (Map.Entry<UUID, Bucket> entry : buckets.entrySet()) {
            if (!entry.getKey().equals(owner)) {
                sum += value(entry.getValue(), null);
            }
        }
        return sum;
    }

    /** Owner to denars, skipping the given bucket. Used for pot levels. */
    public Map<UUID, Integer> totalsExcept(UUID skip) {
        Map<UUID, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, Bucket> entry : buckets.entrySet()) {
            if (entry.getKey().equals(skip)) {
                continue;
            }
            int value = value(entry.getValue(), null);
            if (value > 0) {
                out.put(entry.getKey(), value);
            }
        }
        return out;
    }

    public List<Stake> stakes(UUID owner) {
        Bucket bucket = buckets.get(owner);
        return bucket != null ? new ArrayList<>(bucket.stakes) : new ArrayList<>();
    }

    /**
     * The stakes themselves rather than a copy of the list, so an account can plan a take
     * against them and then split exactly those objects when the transaction commits.
     */
    List<Stake> liveStakes(UUID owner) {
        Bucket bucket = buckets.get(owner);
        return bucket != null ? bucket.stakes : List.of();
    }

    /** Drop stakes that have run out, and buckets that hold nothing and remember no spot. */
    void tidy() {
        prune();
    }

    /**
     * The smallest coin this owner holds, so anything spawned or paid from a template
     * is as divisible as possible.
     */
    public ItemStack template(UUID owner) {
        Bucket bucket = buckets.get(owner);
        if (bucket == null) {
            return null;
        }
        Stake best = null;
        for (Stake stake : bucket.stakes) {
            if (stake.count() < 1) {
                continue;
            }
            if (best == null || stake.unit() < best.unit()) {
                best = stake;
            }
        }
        return best != null ? best.item() : null;
    }

    /** Drop a bucket entirely, including its anchor. Only safe when it holds nothing. */
    void forget(UUID owner) {
        Bucket bucket = buckets.get(owner);
        if (bucket != null && value(bucket, null) < 1) {
            buckets.remove(owner);
        }
    }

    void setAnchor(UUID owner, double x, double z) {
        Bucket bucket = buckets.computeIfAbsent(owner, key -> new Bucket());
        bucket.anchorX = x;
        bucket.anchorZ = z;
        bucket.anchored = true;
    }

    public boolean hasAnchor(UUID owner) {
        Bucket bucket = buckets.get(owner);
        return bucket != null && bucket.anchored;
    }

    public double anchorX(UUID owner) {
        Bucket bucket = buckets.get(owner);
        return bucket != null ? bucket.anchorX : 0;
    }

    public double anchorZ(UUID owner) {
        Bucket bucket = buckets.get(owner);
        return bucket != null ? bucket.anchorZ : 0;
    }

    public static int valueOf(Collection<Stake> stakes) {
        int sum = 0;
        if (stakes != null) {
            for (Stake stake : stakes) {
                sum += stake.value();
            }
        }
        return sum;
    }

    private static int value(Bucket bucket, Predicate<Stake> filter) {
        if (bucket == null) {
            return 0;
        }
        int sum = 0;
        for (Stake stake : bucket.stakes) {
            if (filter == null || filter.test(stake)) {
                sum += stake.value();
            }
        }
        return sum;
    }

    private void prune() {
        for (Bucket bucket : buckets.values()) {
            bucket.stakes.removeIf(stake -> stake.count() < 1);
        }
        buckets.entrySet().removeIf(entry -> entry.getValue().stakes.isEmpty() && !entry.getValue().anchored);
    }
}
