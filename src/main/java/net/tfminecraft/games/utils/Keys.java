package net.tfminecraft.games.utils;

import org.bukkit.NamespacedKey;

import net.tfminecraft.games.Games;

public final class Keys {

    public static NamespacedKey anchorToken() {
        return new NamespacedKey(Games.plugin, "anchor_token");
    }

    public static NamespacedKey tableId() {
        return new NamespacedKey(Games.plugin, "table_id");
    }

    public static NamespacedKey guiGame() {
        return new NamespacedKey(Games.plugin, "gui_game");
    }

    public static NamespacedKey guiAction() {
        return new NamespacedKey(Games.plugin, "gui_action");
    }

    private Keys() {}
}
