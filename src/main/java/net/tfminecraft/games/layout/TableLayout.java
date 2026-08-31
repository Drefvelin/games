package net.tfminecraft.games.layout;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Sound;

import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.table.Table;

/**
 * Per-game offsets in table space. Forward is table yaw; right is perpendicular.
 */
public final class TableLayout {

    public record PileSlot(double forward, double right) {}

    public record FeltRing(double minRange, double maxRange) {}

    public record FeltBox(double forward, double right, double width, double depth) {}

    /** Disk in front of the player: center at hand.distance + radius toward the shoe. */
    public record BetZone(double radius) {
        public boolean present() {
            return radius > 0;
        }
    }

    /** Null layout chipFx means use the global chip-sound from config.yml. Null sound inside means disabled. */
    public record SoundFx(Sound sound, float volume, float pitch) {}

    public record VoiceLines(String channel, String hit, String stand, String doubled, String split) {
        public static VoiceLines defaults() {
            return new VoiceLines("rp", "Hit.", "Stand.", "Double.", "Split.");
        }

        public String line(String key) {
            if (key == null) {
                return "";
            }
            return switch (key.toLowerCase(Locale.ROOT)) {
                case "hit" -> hit == null ? "" : hit;
                case "stand" -> stand == null ? "" : stand;
                case "double" -> doubled == null ? "" : doubled;
                case "split" -> split == null ? "" : split;
                default -> "";
            };
        }
    }

    private final String cardSet;
    private final String label;
    private final String icon;
    private final double leaveDistance;
    private final Map<String, PileSlot> piles;
    private final FeltRing ring;
    private final FeltBox box;
    private final PileSlot stand;
    private final double noBetRadius;
    private final boolean dealerHitsSoft17;
    private final boolean autoDealer;
    private final int minBet;
    private final int maxBet;
    private final int betSeconds;
    private final VoiceLines voice;
    private final BetZone betZone;
    private final int resultDelayTicks;
    private final int roundEndSeconds;
    private final SoundFx chipFx;
    private final int maxBoxes;

    public TableLayout(String cardSet, String label, String icon, double leaveDistance,
            Map<String, PileSlot> piles, FeltRing ring, FeltBox box, PileSlot stand, double noBetRadius) {
        this(cardSet, label, icon, leaveDistance, piles, ring, box, stand, noBetRadius, false);
    }

    public TableLayout(String cardSet, String label, String icon, double leaveDistance,
            Map<String, PileSlot> piles, FeltRing ring, FeltBox box, PileSlot stand, double noBetRadius,
            boolean dealerHitsSoft17) {
        this(cardSet, label, icon, leaveDistance, piles, ring, box, stand, noBetRadius, dealerHitsSoft17,
                false, 0, 0, 10);
    }

    public TableLayout(String cardSet, String label, String icon, double leaveDistance,
            Map<String, PileSlot> piles, FeltRing ring, FeltBox box, PileSlot stand, double noBetRadius,
            boolean dealerHitsSoft17, boolean autoDealer, int minBet, int maxBet, int betSeconds) {
        this(cardSet, label, icon, leaveDistance, piles, ring, box, stand, noBetRadius, dealerHitsSoft17,
                autoDealer, minBet, maxBet, betSeconds, VoiceLines.defaults());
    }

    public TableLayout(String cardSet, String label, String icon, double leaveDistance,
            Map<String, PileSlot> piles, FeltRing ring, FeltBox box, PileSlot stand, double noBetRadius,
            boolean dealerHitsSoft17, boolean autoDealer, int minBet, int maxBet, int betSeconds,
            VoiceLines voice) {
        this(cardSet, label, icon, leaveDistance, piles, ring, box, stand, noBetRadius, dealerHitsSoft17,
                autoDealer, minBet, maxBet, betSeconds, voice, null, 8, 10, null);
    }

    public TableLayout(String cardSet, String label, String icon, double leaveDistance,
            Map<String, PileSlot> piles, FeltRing ring, FeltBox box, PileSlot stand, double noBetRadius,
            boolean dealerHitsSoft17, boolean autoDealer, int minBet, int maxBet, int betSeconds,
            VoiceLines voice, BetZone betZone, int resultDelayTicks, int roundEndSeconds) {
        this(cardSet, label, icon, leaveDistance, piles, ring, box, stand, noBetRadius, dealerHitsSoft17,
                autoDealer, minBet, maxBet, betSeconds, voice, betZone, resultDelayTicks, roundEndSeconds, null);
    }

    public TableLayout(String cardSet, String label, String icon, double leaveDistance,
            Map<String, PileSlot> piles, FeltRing ring, FeltBox box, PileSlot stand, double noBetRadius,
            boolean dealerHitsSoft17, boolean autoDealer, int minBet, int maxBet, int betSeconds,
            VoiceLines voice, BetZone betZone, int resultDelayTicks, int roundEndSeconds, SoundFx chipFx) {
        this(cardSet, label, icon, leaveDistance, piles, ring, box, stand, noBetRadius, dealerHitsSoft17,
                autoDealer, minBet, maxBet, betSeconds, voice, betZone, resultDelayTicks, roundEndSeconds, chipFx, 0);
    }

    public TableLayout(String cardSet, String label, String icon, double leaveDistance,
            Map<String, PileSlot> piles, FeltRing ring, FeltBox box, PileSlot stand, double noBetRadius,
            boolean dealerHitsSoft17, boolean autoDealer, int minBet, int maxBet, int betSeconds,
            VoiceLines voice, BetZone betZone, int resultDelayTicks, int roundEndSeconds, SoundFx chipFx,
            int maxBoxes) {
        this.cardSet = cardSet;
        this.label = label;
        this.icon = icon;
        this.leaveDistance = leaveDistance;
        this.piles = piles == null ? Map.of() : Map.copyOf(piles);
        this.ring = ring;
        this.box = box;
        this.stand = stand;
        this.noBetRadius = noBetRadius;
        this.dealerHitsSoft17 = dealerHitsSoft17;
        this.autoDealer = autoDealer;
        this.minBet = Math.max(0, minBet);
        this.maxBet = Math.max(0, maxBet);
        this.betSeconds = Math.max(1, betSeconds);
        this.voice = voice == null ? VoiceLines.defaults() : voice;
        this.betZone = betZone;
        this.resultDelayTicks = Math.max(0, resultDelayTicks);
        this.roundEndSeconds = Math.max(1, roundEndSeconds);
        this.chipFx = chipFx;
        this.maxBoxes = Math.max(0, maxBoxes);
    }

    public String cardSet() {
        return cardSet;
    }

    public String label() {
        return label;
    }

    public String icon() {
        return icon;
    }

    public double leaveDistance() {
        return leaveDistance;
    }

    public PileSlot pile(String name) {
        if (name == null) {
            return null;
        }
        return piles.get(name.toLowerCase(Locale.ROOT));
    }

    public Map<String, PileSlot> piles() {
        return Collections.unmodifiableMap(piles);
    }

    public PileSlot stand() {
        return stand;
    }

    public double noBetRadius() {
        return noBetRadius;
    }

    /** False is S17: dealer stands on all 17, including soft. */
    public boolean dealerHitsSoft17() {
        return dealerHitsSoft17;
    }

    public boolean autoDealer() {
        return autoDealer;
    }

    public int minBet() {
        return minBet;
    }

    public int maxBet() {
        return maxBet;
    }

    public int betSeconds() {
        return betSeconds;
    }

    public VoiceLines voice() {
        return voice;
    }

    public BetZone betZone() {
        return betZone;
    }

    public int resultDelayTicks() {
        return resultDelayTicks;
    }

    public int roundEndSeconds() {
        return roundEndSeconds;
    }

    public SoundFx chipFx() {
        return chipFx;
    }

    /** 0 means no cap. */
    public int maxBoxes() {
        return maxBoxes;
    }

    public Location standLocation(Table table) {
        if (stand == null) {
            return null;
        }
        return fromLocal(table, stand.forward(), stand.right());
    }

    public Location trayLocation(Table table) {
        PileSlot tray = pile("tray");
        if (tray == null) {
            return null;
        }
        return fromLocal(table, tray.forward(), tray.right());
    }

    public boolean inShoeZone(Table table, Location hit) {
        if (noBetRadius <= 0 || table == null || hit == null) {
            return false;
        }
        Location origin = table.getOrigin();
        return origin.getWorld() != null && hit.getWorld() != null && origin.getWorld().equals(hit.getWorld())
                && Math.hypot(hit.getX() - origin.getX(), hit.getZ() - origin.getZ()) < noBetRadius;
    }

    public boolean inTrayZone(Table table, Location hit) {
        if (noBetRadius <= 0 || table == null || hit == null) {
            return false;
        }
        Location tray = trayLocation(table);
        return tray != null && tray.getWorld() != null && hit.getWorld() != null && tray.getWorld().equals(hit.getWorld())
                && Math.hypot(hit.getX() - tray.getX(), hit.getZ() - tray.getZ()) < noBetRadius;
    }

    public boolean inNoBetZone(Table table, Location hit) {
        return inShoeZone(table, hit) || inTrayZone(table, hit);
    }

    public boolean onFelt(Table table, Location hit) {
        if (box != null && box.width() > 0 && box.depth() > 0) {
            double df = localForward(table, hit) - box.forward();
            double dr = localRight(table, hit) - box.right();
            return Math.abs(df) <= box.depth() * 0.5 && Math.abs(dr) <= box.width() * 0.5;
        }
        double min = ring != null ? ring.minRange() : Cache.wagerMinRange;
        double max = ring != null ? ring.maxRange() : Cache.wagerMaxRange;
        Location origin = table.getOrigin();
        double dist = Math.hypot(hit.getX() - origin.getX(), hit.getZ() - origin.getZ());
        return dist >= min && dist <= max;
    }

    public boolean inBetZone(Location hit, double lockX, double lockZ, float placeYaw) {
        if (betZone == null || !betZone.present() || hit == null) {
            return false;
        }
        double yawRad = Math.toRadians(placeYaw);
        double fx = -Math.sin(yawRad);
        double fz = Math.cos(yawRad);
        double reach = Cache.handDistance + betZone.radius();
        double cx = lockX + fx * reach;
        double cz = lockZ + fz * reach;
        return Math.hypot(hit.getX() - cx, hit.getZ() - cz) <= betZone.radius();
    }

    public Location betPadCenter(Table table, double lockX, double lockZ, float placeYaw) {
        if (table == null) {
            return null;
        }
        double yawRad = Math.toRadians(placeYaw);
        double fx = -Math.sin(yawRad);
        double fz = Math.cos(yawRad);
        double reach = Cache.handDistance + (betZone != null && betZone.present() ? betZone.radius() : 0);
        Location at = table.getOrigin().clone();
        at.setX(lockX + fx * reach);
        at.setZ(lockZ + fz * reach);
        at.setY(table.getOrigin().getY());
        return at;
    }

    public Location feltCenter(Table table) {
        Location origin = table.getOrigin().clone();
        if (box != null) {
            return fromLocal(table, box.forward(), box.right());
        }
        double min = ring != null ? ring.minRange() : Cache.wagerMinRange;
        double max = ring != null ? ring.maxRange() : Cache.wagerMaxRange;
        double mid = (min + max) * 0.5;
        return fromLocal(table, mid, 0);
    }

    public static double localForward(Table table, Location hit) {
        Location origin = table.getOrigin();
        double yawRad = Math.toRadians(table.getYaw());
        double fx = -Math.sin(yawRad);
        double fz = Math.cos(yawRad);
        double dx = hit.getX() - origin.getX();
        double dz = hit.getZ() - origin.getZ();
        return dx * fx + dz * fz;
    }

    public static double localRight(Table table, Location hit) {
        Location origin = table.getOrigin();
        double yawRad = Math.toRadians(table.getYaw());
        double fx = -Math.sin(yawRad);
        double fz = Math.cos(yawRad);
        double rx = fz;
        double rz = -fx;
        double dx = hit.getX() - origin.getX();
        double dz = hit.getZ() - origin.getZ();
        return dx * rx + dz * rz;
    }

    public static Location fromLocal(Table table, double forward, double right) {
        Location origin = table.getOrigin().clone();
        double yawRad = Math.toRadians(table.getYaw());
        double fx = -Math.sin(yawRad);
        double fz = Math.cos(yawRad);
        double rx = fz;
        double rz = -fx;
        origin.add(fx * forward + rx * right, 0, fz * forward + rz * right);
        origin.setY(table.getOrigin().getY());
        return origin;
    }
}
