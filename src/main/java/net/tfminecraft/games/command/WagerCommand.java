package net.tfminecraft.games.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.tfminecraft.games.Messages;
import net.tfminecraft.games.table.TableManager;

public final class WagerCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.get("wager.players_only"));
            return true;
        }
        if (args.length == 0) {
            TableManager.get().commitStreet(player);
            return true;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("accept")) {
            TableManager.get().voteWager(player, true);
            return true;
        }
        if (first.equals("decline")) {
            TableManager.get().voteWager(player, false);
            return true;
        }
        int denars;
        try {
            denars = Integer.parseInt(args[0]);
        } catch (NumberFormatException ex) {
            player.sendMessage(Messages.get("wager.usage"));
            return true;
        }
        if (denars < 1) {
            player.sendMessage(Messages.get("wager.need_amount"));
            return true;
        }
        TableManager.get().proposeLoot(player, denars);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length != 1) {
            return Collections.emptyList();
        }
        String lower = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : List.of("accept", "decline")) {
            if (option.startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}
