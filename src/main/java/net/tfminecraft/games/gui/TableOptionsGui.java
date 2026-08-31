package net.tfminecraft.games.gui;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.games.Messages;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.guild.GuildTables;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.table.ShufflePolicy;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.table.TableHouse;
import net.tfminecraft.games.table.TableManager;
import net.tfminecraft.games.utils.Keys;

public final class TableOptionsGui implements Listener {

    private static final int SIZE = 27;
    private static final int SLOT_AUTO = 10;
    private static final int SLOT_MINT = 11;
    private static final int SLOT_MIN = 12;
    private static final int SLOT_MAX = 13;
    private static final int SLOT_BOXES = 14;
    private static final int SLOT_SHUFFLE = 15;
    private static final int SLOT_CONFIRM = 16;
    private static final int SLOT_CANCEL = 22;

    private TableOptionsGui() {}

    public static final TableOptionsGui INSTANCE = new TableOptionsGui();

    public static void openPlace(Player player, boolean requireDeck, Location pending) {
        TableLayout layout = Cache.layoutOf("blackjack");
        TableHouse house = TableHouse.forPlace(player, layout);
        open(player, requireDeck, pending, null, house);
    }

    public static void openEdit(Player player, Table table) {
        if (player == null || table == null) {
            return;
        }
        open(player, false, null, table.getId(), TableHouse.from(table));
    }

    private static void open(Player player, boolean requireDeck, Location pending, java.util.UUID editId,
            TableHouse house) {
        TableOptionsHolder holder = new TableOptionsHolder(requireDeck, pending, editId, house);
        Inventory inventory = Bukkit.createInventory(holder, SIZE, Messages.get("place.options_title"));
        holder.setInventory(inventory);
        fill(player, holder);
        player.openInventory(inventory);
    }

    private static void fill(Player player, TableOptionsHolder holder) {
        Inventory inventory = holder.getInventory();
        TableHouse house = holder.house();
        boolean staff = player.hasPermission(TableHouse.STAFF_PERM);
        inventory.setItem(SLOT_AUTO, toggle(Material.LEVER, "auto", house.autoDealer(),
                Messages.get("place.options_auto"),
                Messages.get(house.autoDealer() ? "place.options_on" : "place.options_off")));
        if (staff) {
            inventory.setItem(SLOT_MINT, toggle(Material.GOLD_INGOT, "mint", house.staffMint(),
                    Messages.get("place.options_mint"),
                    Messages.get(house.staffMint() ? "place.options_on" : "place.options_off")));
        } else {
            inventory.setItem(SLOT_MINT, null);
        }
        inventory.setItem(SLOT_MIN, valueItem(Material.IRON_NUGGET, "min",
                Messages.get("place.options_min", "n", String.valueOf(house.minBet()))));
        inventory.setItem(SLOT_MAX, valueItem(Material.GOLD_NUGGET, "max",
                Messages.get("place.options_max", "n", String.valueOf(house.maxBet()))));
        inventory.setItem(SLOT_BOXES, valueItem(Material.PLAYER_HEAD, "boxes",
                Messages.get("place.options_boxes", "n", boxesLabel(house.maxBoxes()))));
        ShufflePolicy policy = house.shufflePolicy();
        inventory.setItem(SLOT_SHUFFLE, valueItem(Material.BOOK, "shuffle",
                Messages.get(policy == ShufflePolicy.ROUND ? "place.options_shuffle_round"
                        : "place.options_shuffle_shoe")));
        inventory.setItem(SLOT_CONFIRM, named(Material.LIME_WOOL, "confirm", Messages.get("place.options_confirm")));
        inventory.setItem(SLOT_CANCEL, named(Material.BARRIER, "cancel", Messages.get("place.options_cancel")));
    }

    private static String boxesLabel(int n) {
        return n < 1 ? Messages.get("place.options_boxes_open") : String.valueOf(n);
    }

    private static ItemStack toggle(Material material, String action, boolean on, String name, String lore) {
        ItemStack item = named(material, action, name);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setLore(List.of(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack valueItem(Material material, String action, String name) {
        ItemStack item = named(material, action, name);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setLore(List.of(Messages.get("place.options_step")));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack named(Material material, String action, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.getPersistentDataContainer().set(Keys.guiAction(), PersistentDataType.STRING, action);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof TableOptionsHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        String action = clicked.getItemMeta().getPersistentDataContainer()
                .get(Keys.guiAction(), PersistentDataType.STRING);
        if (action == null) {
            return;
        }
        TableHouse house = holder.house();
        TableLayout layout = Cache.layoutOf("blackjack");
        boolean staff = player.hasPermission(TableHouse.STAFF_PERM);
        switch (action) {
            case "auto" -> house.setAutoDealer(!house.autoDealer());
            case "mint" -> {
                if (staff) {
                    house.setStaffMint(!house.staffMint());
                }
            }
            case "min" -> house.setMinBet(clampMin(house.minBet() + delta(event), staff, layout, house.maxBet()));
            case "max" -> house.setMaxBet(clampMax(house.maxBet() + delta(event), staff, layout, house.minBet()));
            case "boxes" -> house.setMaxBoxes(clampBoxes(house.maxBoxes() + delta(event), staff, layout));
            case "shuffle" -> house.setShufflePolicy(house.shufflePolicy().next());
            case "confirm" -> {
                confirm(player, holder);
                return;
            }
            case "cancel" -> {
                player.closeInventory();
                return;
            }
            default -> {
                return;
            }
        }
        fill(player, holder);
    }

    private static void confirm(Player player, TableOptionsHolder holder) {
        TableHouse house = holder.house();
        if (!player.hasPermission(TableHouse.STAFF_PERM)) {
            house.setStaffMint(false);
        }
        GuildTables.stampGuild(player, house);
        Table existing = holder.editTableId() != null ? TableManager.get().table(holder.editTableId()) : null;
        String refuse = GuildTables.refuseKey(player, house, existing);
        if (refuse != null) {
            player.sendMessage(Messages.get(refuse));
            return;
        }
        player.closeInventory();
        if (holder.editTableId() != null) {
            Table table = TableManager.get().table(holder.editTableId());
            if (table == null || table.live()) {
                player.sendMessage(Messages.get("place.options_live"));
                return;
            }
            if (!TableManager.get().canEditHouse(player, table)) {
                player.sendMessage(Messages.get("place.options_denied"));
                return;
            }
            TableManager.get().applyHouse(table, house);
            player.sendMessage(Messages.get("place.options_saved"));
            return;
        }
        house.setOwnerPlayer(player.getUniqueId());
        TableManager.get().armPlace(player, "blackjack", holder.requireDeck(), house);
        Location at = holder.pendingHit();
        if (at != null) {
            TableManager.get().tryPlace(player, at);
            return;
        }
        player.sendMessage(Messages.get(holder.requireDeck() ? "place.armed" : "place.armed_admin"));
    }

    private static int delta(InventoryClickEvent event) {
        int step = event.isShiftClick() ? 10 : 1;
        ClickType type = event.getClick();
        if (type == ClickType.RIGHT || type == ClickType.SHIFT_RIGHT) {
            return -step;
        }
        return step;
    }

    private static int clampMin(int value, boolean staff, TableLayout layout, int maxBet) {
        int floor = staff || layout == null ? 0 : layout.minBet();
        int ceil = maxBet > 0 ? maxBet : Integer.MAX_VALUE;
        if (!staff && layout != null && layout.maxBet() > 0) {
            ceil = Math.min(ceil, layout.maxBet());
        }
        return Math.max(floor, Math.min(ceil, Math.max(0, value)));
    }

    private static int clampMax(int value, boolean staff, TableLayout layout, int minBet) {
        int floor = Math.max(minBet, staff || layout == null ? 0 : layout.minBet());
        int ceil = Integer.MAX_VALUE;
        if (!staff && layout != null && layout.maxBet() > 0) {
            ceil = layout.maxBet();
        }
        return Math.max(floor, Math.min(ceil, Math.max(0, value)));
    }

    private static int clampBoxes(int value, boolean staff, TableLayout layout) {
        int yaml = layout != null ? layout.maxBoxes() : 0;
        if (value < 0) {
            value = 0;
        }
        if (!staff && yaml > 0) {
            return Math.min(yaml, Math.max(0, value));
        }
        return value;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof TableOptionsHolder) {
            event.setCancelled(true);
        }
    }
}
