package net.tfminecraft.games.wager;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/** In-memory loot proposal. Not saved with the table. */
public final class WagerVote {

    private final UUID proposerId;
    private final ItemStack item;
    private final int denars;
    private final Set<UUID> eligible = new HashSet<>();
    private final Set<UUID> yes = new HashSet<>();
    private final Set<UUID> no = new HashSet<>();
    private BukkitTask expireTask;

    public WagerVote(UUID proposerId, ItemStack item, int denars) {
        this.proposerId = proposerId;
        this.item = item;
        this.denars = denars;
    }

    public UUID proposerId() {
        return proposerId;
    }

    public ItemStack item() {
        return item;
    }

    public int denars() {
        return denars;
    }

    public Set<UUID> eligible() {
        return eligible;
    }

    public Set<UUID> yes() {
        return yes;
    }

    public Set<UUID> no() {
        return no;
    }

    public BukkitTask expireTask() {
        return expireTask;
    }

    public void setExpireTask(BukkitTask expireTask) {
        this.expireTask = expireTask;
    }

    public void cancelExpire() {
        if (expireTask != null) {
            expireTask.cancel();
            expireTask = null;
        }
    }

    public boolean majorityYes() {
        return yes.size() * 2 > eligible.size();
    }

    public boolean allVoted() {
        return yes.size() + no.size() >= eligible.size();
    }
}
