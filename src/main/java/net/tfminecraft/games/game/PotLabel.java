package net.tfminecraft.games.game;

import java.util.Map;
import java.util.UUID;

import net.tfminecraft.games.Messages;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.voice.RpNames;
import net.tfminecraft.games.wager.WagerEngine;

/**
 * The pot as the hologram shows it: who put in what, then the total.
 */
final class PotLabel {

    private PotLabel() {}

    /**
     * One line per owner with money on the felt, then the total. The house tray is left out, and
     * an empty felt gives back nothing so the hologram does not carry a dead line.
     */
    static String lines(Table table) {
        if (table == null) {
            return "";
        }
        Map<UUID, Integer> totals = WagerEngine.get().totalsExcept(table, table.getId());
        StringBuilder text = new StringBuilder();
        int sum = 0;
        for (Map.Entry<UUID, Integer> entry : totals.entrySet()) {
            int denars = entry.getValue() != null ? entry.getValue() : 0;
            if (denars < 1) {
                continue;
            }
            sum += denars;
            if (text.length() > 0) {
                text.append("\n");
            }
            text.append(Messages.get("label.pot_seat",
                    "name", RpNames.of(entry.getKey()),
                    "n", String.valueOf(denars)));
        }
        if (sum < 1) {
            return "";
        }
        text.append("\n").append(Messages.get("label.pot", "n", String.valueOf(sum)));
        return text.toString();
    }
}
