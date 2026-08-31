package net.tfminecraft.games.gui;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import me.Plugins.TLibs.TLibs;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.gui.TableOptionsGui;
import net.tfminecraft.games.table.TableManager;
import net.tfminecraft.games.utils.Keys;

public final class GameSelectGui implements Listener {

    private static final int SIZE = 27;
    private static final int POKER_SLOT = 11;
    private static final int FREEPLAY_SLOT = 13;
    private static final int BLACKJACK_SLOT = 15;

    private GameSelectGui() {}

    public static final GameSelectGui INSTANCE = new GameSelectGui();

    public static void open(Player player, boolean requireDeck, Location pending) {
        GameSelectHolder holder = new GameSelectHolder(requireDeck, pending);
        Inventory inventory = Bukkit.createInventory(holder, SIZE, Messages.get("place.gui_title"));
        holder.setInventory(inventory);
        inventory.setItem(POKER_SLOT, gameIcon("poker", "place.gui_poker"));
        inventory.setItem(FREEPLAY_SLOT, gameIcon("freeplay", "place.gui_freeplay"));
        inventory.setItem(BLACKJACK_SLOT, gameIcon("blackjack", "place.gui_blackjack"));
        player.openInventory(inventory);
    }

    private static ItemStack gameIcon(String gameId, String nameKey) {
        ItemStack item = TLibs.getItemAPI().getCreator().getItemFromPath(Cache.iconOf(gameId));
        if (item == null) {
            item = new ItemStack(Material.PAPER);
        } else {
            item = item.clone();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Messages.get(nameKey));
            meta.getPersistentDataContainer().set(Keys.guiGame(), PersistentDataType.STRING, gameId);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof GameSelectHolder holder)) {
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
        String gameId = clicked.getItemMeta().getPersistentDataContainer()
                .get(Keys.guiGame(), PersistentDataType.STRING);
        if (gameId == null || gameId.isBlank()) {
            return;
        }
        player.closeInventory();
        if ("blackjack".equalsIgnoreCase(gameId)) {
            TableOptionsGui.openPlace(player, holder.requireDeck(), holder.pendingHit());
            return;
        }
        TableManager.get().selectGame(player, gameId, holder.pendingHit(), holder.requireDeck());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof GameSelectHolder) {
            event.setCancelled(true);
        }
    }
}
