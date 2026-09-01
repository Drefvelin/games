package net.tfminecraft.games.guild;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import me.Plugins.SimpleFactions.Guild.Guild;
import me.Plugins.SimpleFactions.Managers.FactionManager;
import me.Plugins.SimpleFactions.Objects.Bank;
import me.Plugins.SimpleFactions.enums.GuildModifier;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.table.TableHouse;
import net.tfminecraft.games.table.TableManager;

/**
 * SimpleFactions guild auto-dealer caps. Isolated so blackjack/GUI do not import SF.
 */
public final class GuildTables {

    private static boolean loggedFail;

    private GuildTables() {}

    public static boolean counts(Table table) {
        return table != null && !table.staffMint()
                && table.ownerGuildId() != null && !table.ownerGuildId().isBlank();
    }

    public static boolean wouldCount(TableHouse house) {
        return house != null && !house.staffMint();
    }

    public static String guildId(Player player) {
        if (player == null) {
            return null;
        }
        try {
            if (!sfReady()) {
                return null;
            }
            Guild guild = FactionManager.getGuildByMember(player.getName());
            if (guild == null) {
                return null;
            }
            String id = guild.getId();
            return id == null || id.isBlank() ? null : id;
        } catch (RuntimeException | LinkageError ex) {
            warn(ex);
            return null;
        }
    }

    public static String displayName(String guildId) {
        if (guildId == null || guildId.isBlank()) {
            return null;
        }
        try {
            Guild guild = guild(guildId);
            if (guild == null) {
                return null;
            }
            String name = guild.getName();
            return name == null || name.isBlank() ? null : name;
        } catch (RuntimeException | LinkageError ex) {
            warn(ex);
            return null;
        }
    }

    public static int cap(String guildId) {
        if (guildId == null || guildId.isBlank()) {
            return 0;
        }
        try {
            if (!sfReady()) {
                return 0;
            }
            Guild guild = FactionManager.getGuildByString(guildId);
            if (guild == null) {
                return 0;
            }
            return Math.max(0, (int) guild.getModifier(GuildModifier.AUTO_DEALER_TABLES));
        } catch (RuntimeException | LinkageError ex) {
            warn(ex);
            return 0;
        }
    }

    public static int count(String guildId) {
        if (guildId == null || guildId.isBlank()) {
            return 0;
        }
        int n = 0;
        for (Table table : TableManager.get().tables()) {
            if (counts(table) && guildId.equals(table.ownerGuildId())) {
                n++;
            }
        }
        return n;
    }

    public static boolean canAddGuildAuto(String guildId) {
        return count(guildId) < cap(guildId);
    }

    public static boolean overCap(String guildId) {
        return guildId != null && !guildId.isBlank() && count(guildId) > cap(guildId);
    }

    public static boolean frozen(Table table) {
        return counts(table) && overCap(table.ownerGuildId());
    }

    public static boolean canStartGuildAutoRound(Table table) {
        if (table == null || table.live()) {
            return true;
        }
        return !frozen(table);
    }

    /**
     * Null if this house may be saved/placed. Otherwise a messages.yml key.
     */
    public static String refuseKey(Player player, TableHouse house, Table existing) {
        if (house != null && house.staffMint()) {
            return null;
        }
        if (!wouldCount(house)) {
            return null;
        }
        String id = house.ownerGuildId();
        if (id == null || id.isBlank()) {
            id = guildId(player);
            house.setOwnerGuildId(id);
        }
        if (id == null || id.isBlank()) {
            return "place.no_guild";
        }
        if (!isLeader(id, player)) {
            return "place.not_leader";
        }
        if (existing != null && counts(existing)) {
            return null;
        }
        if (!canAddGuildAuto(id)) {
            return "place.no_slots";
        }
        return null;
    }

    public static void tellRefuse(Player player, String key, TableHouse house) {
        if (player == null || key == null || key.isBlank()) {
            return;
        }
        if ("place.no_slots".equals(key)) {
            String id = house != null ? house.ownerGuildId() : null;
            player.sendMessage(Messages.get(key,
                    "used", String.valueOf(count(id)),
                    "cap", String.valueOf(cap(id))));
            return;
        }
        player.sendMessage(Messages.get(key));
    }

    public static void stampGuild(Player player, TableHouse house) {
        if (house == null || house.staffMint()) {
            return;
        }
        if (house.ownerGuildId() == null || house.ownerGuildId().isBlank()) {
            house.setOwnerGuildId(guildId(player));
        }
    }

    public static boolean mayDeal(Table table, Player player) {
        if (table == null || player == null) {
            return false;
        }
        if (player.hasPermission(TableHouse.STAFF_PERM) || player.hasPermission("games.admin")) {
            return true;
        }
        String guildId = table.ownerGuildId();
        if (guildId != null && !guildId.isBlank()) {
            return isMember(guildId, player);
        }
        return table.ownerPlayer() != null && table.ownerPlayer().equals(player.getUniqueId());
    }

    private static boolean isMember(String guildId, Player player) {
        try {
            Guild guild = guild(guildId);
            return guild != null && player != null && guild.isMember(player);
        } catch (RuntimeException | LinkageError ex) {
            warn(ex);
            return false;
        }
    }

    private static boolean isLeader(String guildId, Player player) {
        try {
            Guild guild = guild(guildId);
            if (guild == null || player == null) {
                return false;
            }
            String leader = guild.getLeader();
            return leader != null && leader.equalsIgnoreCase(player.getName());
        } catch (RuntimeException | LinkageError ex) {
            warn(ex);
            return false;
        }
    }

    public static boolean tryWithdraw(String guildId, int denars) {
        if (denars < 1) {
            return true;
        }
        try {
            Guild guild = guild(guildId);
            if (guild == null || guild.isBankrupt()) {
                return false;
            }
            Bank bank = guild.getBank();
            if (bank == null || bank.getWealth() == null || bank.getWealth() < denars) {
                return false;
            }
            bank.withdraw((double) denars);
            return true;
        } catch (RuntimeException | LinkageError ex) {
            warn(ex);
            return false;
        }
    }

    public static boolean guildExists(String guildId) {
        try {
            return guild(guildId) != null;
        } catch (RuntimeException | LinkageError ex) {
            warn(ex);
            return false;
        }
    }

    /**
     * True if the amount was banked (or there was nothing to bank). False if the guild or bank is gone.
     */
    public static boolean tryDeposit(String guildId, int denars) {
        if (denars < 1) {
            return true;
        }
        try {
            Guild guild = guild(guildId);
            if (guild == null) {
                return false;
            }
            Bank bank = guild.getBank();
            if (bank == null) {
                return false;
            }
            bank.deposit((double) denars);
            return true;
        } catch (RuntimeException | LinkageError ex) {
            warn(ex);
            return false;
        }
    }

    public static void deposit(String guildId, int denars) {
        tryDeposit(guildId, denars);
    }

    private static Guild guild(String guildId) {
        if (guildId == null || guildId.isBlank() || !sfReady()) {
            return null;
        }
        return FactionManager.getGuildByString(guildId);
    }

    private static boolean sfReady() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("SimpleFactions");
        return plugin != null && plugin.isEnabled();
    }

    private static void warn(Throwable ex) {
        if (!loggedFail && Games.plugin != null) {
            loggedFail = true;
            Games.plugin.getLogger().warning("[Games] SimpleFactions guild tables skipped: " + ex.getMessage());
        }
    }
}
