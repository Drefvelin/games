package net.tfminecraft.games.game;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;

import net.tfminecraft.games.table.PayoutFlight;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.table.TableManager;
import net.tfminecraft.games.wager.PotPile;

/**
 * Leave settlement only. Streets, blinds, and dealing are later.
 */
public final class PokerGame implements Game {

    @Override
    public void onLeave(Table table, Player player) {
        TableManager manager = TableManager.get();
        UUID leaver = player.getUniqueId();
        table.actives().remove(leaver);
        int street = table.street();
        IdentityHashMap<PotPile, UUID> dests = new IdentityHashMap<>();
        for (PotPile pile : table.getPiles()) {
            if (leaver.equals(pile.ownerId()) && pile.streetId() == street) {
                dests.put(pile, leaver);
            }
        }
        UUID rest = null;
        if (table.actives().size() == 1) {
            rest = table.actives().iterator().next();
        } else if (table.actives().isEmpty()) {
            rest = leaver;
        }
        if (rest != null) {
            for (PotPile pile : table.getPiles()) {
                dests.putIfAbsent(pile, rest);
            }
        }
        List<PayoutFlight> flights = new ArrayList<>();
        for (Map.Entry<PotPile, UUID> entry : dests.entrySet()) {
            flights.add(new PayoutFlight(entry.getKey(), entry.getValue()));
        }
        manager.flushPiles(table, flights, null);
    }

    @Override
    public int denarsToMatch(Table table, Player player) {
        return 0;
    }

    @Override
    public void onStreetCommit(Table table, Player player, int streetValue, boolean folded) {}
}
