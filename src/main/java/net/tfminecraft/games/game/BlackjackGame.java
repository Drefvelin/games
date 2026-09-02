package net.tfminecraft.games.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.games.Games;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.card.Card;
import net.tfminecraft.games.guild.GuildTables;
import net.tfminecraft.games.display.DisplayPose;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.table.HandCard;
import net.tfminecraft.games.table.PayoutFlight;
import net.tfminecraft.games.table.ShufflePolicy;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.table.TableManager;
import net.tfminecraft.games.voice.RpNames;
import net.tfminecraft.games.voice.RpVoice;
import net.tfminecraft.games.wager.Accounts;
import net.tfminecraft.games.wager.MoneyAccount;
import net.tfminecraft.games.wager.MoneyLog;
import net.tfminecraft.games.wager.MoneyTx;
import net.tfminecraft.games.wager.TxResult;
import net.tfminecraft.games.wager.WagerEngine;

/**
 * Layout, claim, deal, 21, double/split, and settle. Engines stay dumb.
 */
public final class BlackjackGame implements Game {

    public static final String WAIT_DEAL = "wait_deal";
    public static final String DEAL = "deal";
    public static final String PLAY = "play";
    public static final String DEALER = "dealer";
    public static final String SETTLE = "settle";

    /** Slots are parked above this while a box is being renumbered, so shifts cannot collide. */
    private static final int SLOT_PARK = 100;

    private final Map<UUID, List<BjHand>> rounds = new HashMap<>();
    private final Map<UUID, BukkitTask> betTimers = new HashMap<>();
    private final Map<UUID, BukkitTask> lingerTimers = new HashMap<>();
    private final Map<UUID, Map<String, UUID>> pileHolos = new HashMap<>();
    private final Map<UUID, UUID> trayHolos = new HashMap<>();

    /**
     * Auto dealing is the house setting minus any human at the shoe. Claiming the shoe never
     * clears the setting, so unsetting or leaving hands the table straight back to auto.
     */
    static boolean auto(Table table) {
        return table != null && table.autoDealer() && table.dealerId() == null;
    }

    /**
     * Where the house money comes from, which is a separate question from who turns the cards.
     * A guild table is bank funded even with a human at the shoe, because a non-member cannot
     * deal it, so the tray is the guild's float and its winnings are the guild's. Only a table
     * with no guild behind it is stocked by its dealer.
     */
    private static boolean houseFunded(Table table) {
        return GuildTables.houseBacked(table);
    }

    private static void speak(Table table, Player player, String key) {
        if (table == null) {
            return;
        }
        RpVoice.say(player, Cache.layoutOf(table.getGameId()), key);
    }

    @Override
    public boolean tryClaimDealer(Table table, Player player) {
        if (table == null || player == null) {
            return false;
        }
        if (table.live()) {
            return true;
        }
        if (!inStandRange(table, player)) {
            if (table.dealerId() == null && !auto(table)) {
                player.sendMessage(Messages.get("dealer.none"));
            }
            return true;
        }
        if (auto(table) && table.staffMint()) {
            player.sendMessage(Messages.get("dealer.staff_table"));
            return true;
        }
        if (auto(table)) {
            if (!GuildTables.mayDeal(table, player)) {
                player.sendMessage(Messages.get("dealer.denied"));
                return true;
            }
            // The float in the tray belongs to the guild, not to whoever takes the shoe.
            TableManager.get().bankAutoTray(table);
            table.setDealerId(player.getUniqueId());
            TableManager.get().persistHouseChange(table);
            player.sendMessage(Messages.get("dealer.takeover"));
            return true;
        }
        UUID have = table.dealerId();
        if (have == null) {
            if (!GuildTables.mayDeal(table, player)) {
                player.sendMessage(Messages.get("dealer.denied"));
                return true;
            }
            table.setDealerId(player.getUniqueId());
            TableManager.get().persistHouseChange(table);
            player.sendMessage(Messages.get("dealer.claimed"));
            return true;
        }
        if (have.equals(player.getUniqueId())) {
            table.setDealerId(null);
            TableManager.get().persistHouseChange(table);
            player.sendMessage(Messages.get("dealer.unset"));
            return true;
        }
        player.sendMessage(Messages.get("dealer.taken"));
        return true;
    }

    private static boolean inStandRange(Table table, Player player) {
        TableLayout layout = Cache.layoutOf(table.getGameId());
        if (layout == null || layout.stand() == null) {
            return false;
        }
        Location stand = layout.standLocation(table);
        if (stand == null || stand.getWorld() == null || player.getWorld() == null
                || !stand.getWorld().equals(player.getWorld())) {
            return false;
        }
        double radius = layout.noBetRadius() > 0 ? layout.noBetRadius() : 0.5;
        Location feet = player.getLocation();
        return Math.hypot(feet.getX() - stand.getX(), feet.getZ() - stand.getZ()) <= radius;
    }

    @Override
    public boolean allowManualPotFlush(Table table, Player player) {
        return false;
    }

    @Override
    public String extraLabel(Table table) {
        if (table == null) {
            return "";
        }
        if (!table.live()) {
            return GuildTables.frozen(table) ? Messages.get("label.no_slots") : "";
        }
        StringBuilder text = new StringBuilder();
        if (PLAY.equals(table.phase()) && table.actor() != null) {
            text.append(Messages.get("label.turn", "name", RpNames.of(table.actor())));
        } else if (DEALER.equals(table.phase())) {
            text.append(Messages.get("label.turn", "name", "Dealer"));
        }
        int total = 0;
        List<BjHand> hands = rounds.getOrDefault(table.getId(), List.of());
        for (UUID box : table.boxes()) {
            int stake = 0;
            for (BjHand hand : hands) {
                if (box.equals(hand.owner)) {
                    stake += hand.bet;
                }
            }
            if (text.length() > 0) {
                text.append("\n");
            }
            text.append(Messages.get("label.box", "name", RpNames.of(box), "stake", String.valueOf(stake)));
            total += stake;
        }
        if (!table.boxes().isEmpty()) {
            if (text.length() > 0) {
                text.append("\n");
            }
            text.append(Messages.get("label.action", "total", String.valueOf(total)));
        }
        return text.toString();
    }

    @Override
    public boolean sortHeldCards() {
        return false;
    }

    @Override
    public boolean allowRevealToggle(Table table, Player player) {
        return false;
    }

    @Override
    public boolean showRevealDust(Table table) {
        return false;
    }

    @Override
    public void onTablePilesChanged(Table table) {
        syncPileHolos(table);
    }

    @Override
    public void onFeltPilesChanged(Table table) {
        syncTrayHolo(table);
    }

    private void syncPileHolos(Table table) {
        if (table == null) {
            return;
        }
        Map<String, UUID> holos = pileHolos.computeIfAbsent(table.getId(), id -> new HashMap<>());
        HashSet<String> keep = new HashSet<>();
        for (Map.Entry<String, List<HandCard>> entry : table.tablePiles().entrySet()) {
            String pile = entry.getKey();
            List<HandCard> cards = entry.getValue();
            if (!isHoloPile(pile) || cards == null || cards.isEmpty()) {
                continue;
            }
            keep.add(pile);
            upsertHolo(table, pile, cards, holos);
        }
        List<String> drop = new ArrayList<>();
        for (String pile : holos.keySet()) {
            if (!keep.contains(pile)) {
                drop.add(pile);
            }
        }
        for (String pile : drop) {
            WorldAnchors.remove(holos.remove(pile));
        }
        if (holos.isEmpty()) {
            pileHolos.remove(table.getId());
        }
    }

    private void syncTrayHolo(Table table) {
        if (table == null) {
            return;
        }
        int total = trayTotal(table);
        UUID id = trayHolos.get(table.getId());
        if (total < 1) {
            WorldAnchors.remove(id);
            trayHolos.remove(table.getId());
            return;
        }
        TableLayout layout = Cache.layoutOf(table.getGameId());
        Location at = layout != null ? layout.trayLocation(table) : null;
        if (at == null) {
            WorldAnchors.remove(id);
            trayHolos.remove(table.getId());
            return;
        }
        String text = Messages.get("label.tray_total", "n", String.valueOf(total));
        Entity entity = id != null ? Bukkit.getEntity(id) : null;
        if (entity == null || entity.isDead() || !(entity instanceof TextDisplay)) {
            WorldAnchors.remove(id);
            TextDisplay spawned = WorldAnchors.spawnLabel(at, text);
            if (spawned != null) {
                trayHolos.put(table.getId(), spawned.getUniqueId());
            } else {
                trayHolos.remove(table.getId());
            }
            return;
        }
        WorldAnchors.setText(id, text);
        WorldAnchors.move(id, at);
    }

    private static boolean isHoloPile(String pile) {
        if (pile == null) {
            return false;
        }
        String name = pile.toLowerCase(Locale.ROOT);
        return name.equals("dealer");
    }

    private void upsertHolo(Table table, String pile, List<HandCard> cards, Map<String, UUID> holos) {
        Location at = pileHoloLocation(table, pile, cards.size());
        String text = formatPileTotal(cards);
        UUID id = holos.get(pile);
        Entity entity = id != null ? Bukkit.getEntity(id) : null;
        if (entity == null || entity.isDead() || !(entity instanceof TextDisplay)) {
            WorldAnchors.remove(id);
            TextDisplay spawned = WorldAnchors.spawnLabel(at, text);
            if (spawned != null) {
                holos.put(pile, spawned.getUniqueId());
            } else {
                holos.remove(pile);
            }
            return;
        }
        WorldAnchors.setText(id, text);
        WorldAnchors.move(id, at);
    }

    private Location pileHoloLocation(Table table, String pile, int count) {
        Location origin = table.getOrigin().clone();
        int n = Math.max(1, count);
        int mid = (n - 1) / 2;
        DisplayPose pose = tablePileSlot(table, pile, mid, n, true);
        origin.add(pose.translation().x, 0, pose.translation().z);
        return origin;
    }

    static String formatPileTotal(List<HandCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return "";
        }
        boolean hole = false;
        List<HandCard> up = new ArrayList<>();
        for (HandCard held : cards) {
            if (held.faceUp()) {
                up.add(held);
            } else {
                hole = true;
            }
        }
        if (hole) {
            return Messages.get("label.total_hole", "up", String.valueOf(bestTotal(up)));
        }
        int best = bestTotal(cards);
        if (best > 21) {
            return Messages.get("label.total_bust");
        }
        int hard = hardTotal(cards);
        if (best != hard) {
            return Messages.get("label.total_soft", "hard", String.valueOf(hard), "soft", String.valueOf(best));
        }
        return Messages.get("label.total", "n", String.valueOf(best));
    }

    private void clearPileHolos(Table table) {
        if (table == null) {
            return;
        }
        Map<String, UUID> holos = pileHolos.remove(table.getId());
        if (holos != null) {
            for (UUID id : holos.values()) {
                WorldAnchors.remove(id);
            }
        }
    }

    private void clearTrayHolo(Table table) {
        if (table == null) {
            return;
        }
        WorldAnchors.remove(trayHolos.remove(table.getId()));
    }

    @Override
    public void onSessionStart(Table table) {
        if (table == null) {
            return;
        }
        if (table.shufflePolicy() == ShufflePolicy.ROUND) {
            TableManager.get().reshuffleFull(table);
        }
        snapshotBoxes(table);
        table.setPhase(WAIT_DEAL);
        table.setActor(null);
        table.setBoxIndex(0);
        table.setHandIndex(0);
        List<BjHand> hands = new ArrayList<>();
        TableManager manager = TableManager.get();
        for (UUID box : table.boxes()) {
            hands.add(new BjHand(box, hands.size(), 0, manager.ownedDenars(table, box)));
        }
        rounds.put(table.getId(), hands);
        TableManager.get().refreshLabel(table);
        if (auto(table)) {
            startDeal(table);
        }
    }

    @Override
    public void onTableReady(Table table) {
        prepareIdle(table);
        syncTrayHolo(table);
    }

    @Override
    public void onTableRemoved(Table table) {
        cancelBetTimer(table);
        cancelLinger(table);
        clearPileHolos(table);
        clearTrayHolo(table);
    }

    @Override
    public void onSessionEnd(Table table) {
        cancelLinger(table);
        cancelBetTimer(table);
        clearPileHolos(table);
        // Covers both exits: a settled round, and one abandoned when every box walked away.
        drainAutoTray(table);
        prepareIdle(table);
        syncTrayHolo(table);
    }

    @Override
    public void onChipIn(Table table, Player player, int denars, ItemStack item) {
        if (!coverAutoTray(table, item)) {
            // The house cannot back this bet, so it goes back rather than sitting on the felt
            // looking covered. The chip that just landed is its own coin, so this is exact.
            if (player != null && denars > 0) {
                List<PayoutFlight> flights = new ArrayList<>();
                WagerEngine.get().refund(table, player.getUniqueId(), player, denars, flights,
                        "bet not covered");
                TableManager.get().flushPiles(table, flights, null);
                player.sendMessage(Messages.get("bet.bank_short"));
            }
            return;
        }
        onChipIn(table, player);
    }

    @Override
    public void onChipIn(Table table, Player player) {
        if (!auto(table) || table == null || table.live() || !table.betOpen()) {
            return;
        }
        if (!TableManager.get().hasLegalBlackjackBox(table)) {
            cancelBetTimer(table);
            table.setAutoCountdown(0);
            TableManager.get().refreshLabel(table);
            return;
        }
        if (betTimers.containsKey(table.getId())) {
            return;
        }
        startBetTimer(table);
    }

    @Override
    public void onLeave(Table table, Player player) {
        TableManager manager = TableManager.get();
        int felt = 0;
        boolean peel = auto(table) && table != null && player != null && !table.live() && table.betOpen();
        if (peel) {
            felt = manager.ownedDenars(table, player.getUniqueId());
        }
        Game.super.onLeave(table, player);
        if (peel && felt > 0) {
            peelAutoTray(table, felt);
        }
        onChipIn(table, player);
        skipGone(table, player);
    }

    @Override
    public void onDealerGone(Table table) {
        if (table == null || !table.live()) {
            onTableReady(table);
            return;
        }
        continueWithout(table, null, true);
    }

    private void skipGone(Table table, Player player) {
        if (table == null || player == null || !table.live()) {
            return;
        }
        continueWithout(table, player.getUniqueId(), player.getUniqueId().equals(table.dealerId()));
    }

    private void continueWithout(Table table, UUID gone, boolean dealerLeft) {
        if (table == null || !table.live()) {
            return;
        }
        boolean currentBox = false;
        if (gone != null) {
            int idx = table.boxes().indexOf(gone);
            currentBox = idx >= 0 && idx == table.boxIndex();
            if (idx >= 0) {
                table.boxes().remove(idx);
                if (idx < table.boxIndex()) {
                    table.setBoxIndex(table.boxIndex() - 1);
                }
            }
        }
        String phase = table.phase();
        if (table.boxes().isEmpty() && (WAIT_DEAL.equals(phase) || DEAL.equals(phase) || PLAY.equals(phase))) {
            TableManager.get().endSession(table);
            return;
        }
        if (WAIT_DEAL.equals(phase) && (dealerLeft || table.dealerId() == null)) {
            startDeal(table);
            return;
        }
        if (PLAY.equals(phase) && (currentBox || (gone != null && gone.equals(table.actor())))) {
            table.setActor(null);
            table.setHandIndex(0);
            advancePlay(table);
        }
    }

    /**
     * Top the tray up so it can pay every bet on the felt. Says whether the house is good for
     * the action, and never touches the bet that prompted it: the caller decides what to do about
     * a shortfall while its own stake is still safely where the player put it.
     */
    private static boolean coverAutoTray(Table table, ItemStack item) {
        if (table == null || !houseFunded(table)) {
            return true;
        }
        int need = Math.max(0, actionTotal(table) - trayTotal(table));
        if (need < 1) {
            return true;
        }
        TableManager manager = TableManager.get();
        ItemStack template = manager.chipUnitDenars(item) > 0 ? item : houseTemplate(table, null);
        int unit = manager.chipUnitDenars(template);
        // Nothing here the house could pay in, such as a loot wager on an empty table. The round
        // reserve at closeBets is the real gate, and it refunds everyone if the bank is short.
        if (unit < 1) {
            return true;
        }
        // The tray only holds whole coins, so a gap smaller than one coin waits for settle.
        int fund = (need / unit) * unit;
        if (fund < 1) {
            return true;
        }
        TableLayout layout = Cache.layoutOf(table.getGameId());
        Location tray = layout != null ? layout.trayLocation(table) : null;
        TxResult result = WagerEngine.get().fundFromHouse(table, table.getId(), template, fund, tray,
                "tray cover");
        return result.ok();
    }

    /**
     * After the bet window closes: refund under-min boxes, then session if a legal box remains.
     */
    public void closeBets(Table table) {
        if (table == null) {
            return;
        }
        refundUnderMinBoxes(table);
        if (TableManager.get().hasLegalBlackjackBox(table)) {
            if (!GuildTables.canStartGuildAutoRound(table)) {
                refundOpenBoxes(table);
                prepareIdle(table);
                return;
            }
            if (houseFunded(table) && !reserveRound(table)) {
                messageBoxes(table, Messages.get("bet.house_short"));
                refundOpenBoxes(table);
                drainAutoTray(table);
                prepareIdle(table);
                return;
            }
            TableManager.get().beginSession(table);
        } else if (auto(table) && !table.live()) {
            prepareIdle(table);
        }
    }

    private static void messageBoxes(Table table, String message) {
        for (UUID owner : TableManager.get().boxOwners(table)) {
            Player player = Bukkit.getPlayer(owner);
            if (player != null && player.isOnline()) {
                player.sendMessage(message);
            }
        }
    }

    /**
     * The most the house can be asked for this round: every box split to the cap, every hand
     * doubled, every one of them won. A win pays 1:1, so this is also what the tray must hold.
     */
    private static int roundLiability(Table table) {
        if (table == null) {
            return 0;
        }
        TableManager manager = TableManager.get();
        int hands = maxHandsPerBox(table);
        int liability = 0;
        for (UUID owner : manager.boxOwners(table)) {
            liability += manager.ownedDenars(table, owner) * hands * 2;
        }
        return liability;
    }

    /**
     * Put the whole worst case in the tray before the first card, so no split or double later in
     * the round has to ask the bank for anything. What is not won goes back at round end.
     */
    private static boolean reserveRound(Table table) {
        TableManager manager = TableManager.get();
        int need = roundLiability(table) - trayTotal(table);
        if (need < 1) {
            return true;
        }
        ItemStack template = manager.feltItem(table, table.getId());
        if (template == null) {
            for (UUID owner : manager.boxOwners(table)) {
                template = manager.feltItem(table, owner);
                if (template != null) {
                    break;
                }
            }
        }
        int unit = manager.chipUnitDenars(template);
        if (template == null || unit < 1) {
            return false;
        }
        // Round up: over-reserving is banked again at round end, under-reserving is the hole we are closing.
        int fund = ((need + unit - 1) / unit) * unit;
        TableLayout layout = Cache.layoutOf(table.getGameId());
        Location tray = layout != null ? layout.trayLocation(table) : null;
        TxResult result = WagerEngine.get().fundFromHouse(table, table.getId(), template, fund, tray,
                "round reserve");
        return result.ok() && result.moved() >= need;
    }

    /**
     * Between rounds nothing is staked, so the whole float goes back to the bank. This takes the
     * pickup path rather than a peel, because that one drops the coins at the table if the owning
     * guild has gone away instead of destroying them.
     */
    private static void drainAutoTray(Table table) {
        if (table == null || !houseFunded(table) || trayTotal(table) < 1) {
            return;
        }
        TableManager.get().bankAutoTray(table);
    }

    /**
     * True if the house can take {@code extra} more action. The round reserve normally covers it
     * outright, so this only bites if the reserve came up short. A table stocked by its own dealer
     * is their problem, not the tray's.
     */
    private static boolean houseCanCover(Table table, int extra) {
        if (!houseFunded(table)) {
            return true;
        }
        return trayTotal(table) >= actionTotal(table) + extra;
    }

    private void refundUnderMinBoxes(Table table) {
        TableManager manager = TableManager.get();
        int min = table.minBet();
        List<PayoutFlight> flights = new ArrayList<>();
        int released = 0;
        for (UUID owner : manager.boxOwners(table)) {
            int felt = manager.ownedDenars(table, owner);
            if (felt >= min || felt < 1) {
                continue;
            }
            Player player = Bukkit.getPlayer(owner);
            if (player != null) {
                player.sendMessage(Messages.get("bet.under_min", "min", String.valueOf(min)));
            }
            released += WagerEngine.get().refund(table, owner, player, 0, flights, "under min refund")
                    .moved();
        }
        refundAndPeel(table, flights, released);
    }

    /** One wave for every owner, then one peel, so no refund is dropped mid-flight. */
    private void refundAndPeel(Table table, List<PayoutFlight> flights, int released) {
        if (released < 1 && flights.isEmpty()) {
            return;
        }
        TableManager.get().flushPiles(table, flights, null);
        if (houseFunded(table)) {
            peelAutoTray(table, released);
        }
    }

    private void refundOpenBoxes(Table table) {
        TableManager manager = TableManager.get();
        List<PayoutFlight> flights = new ArrayList<>();
        int released = 0;
        for (UUID owner : manager.boxOwners(table)) {
            if (manager.ownedDenars(table, owner) < 1) {
                continue;
            }
            Player player = Bukkit.getPlayer(owner);
            if (player != null) {
                player.sendMessage(Messages.get("bet.no_slots"));
            }
            released += WagerEngine.get().refund(table, owner, player, 0, flights, "box refund")
                    .moved();
        }
        refundAndPeel(table, flights, released);
    }

    /**
     * The action shrank by {@code released}, so give the tray excess back to the bank.
     * Callers refund first, so the piles are already gone from the felt here.
     */
    private static void peelAutoTray(Table table, int released) {
        if (table == null || released < 1) {
            return;
        }
        int peel = Math.max(0, Math.min(released, trayTotal(table) - actionTotal(table)));
        if (peel < 1) {
            return;
        }
        WagerEngine.get().peelToHouse(table, peel, "peel");
    }

    private static int trayTotal(Table table) {
        return TableManager.get().trayDenars(table);
    }

    /** Every denar on the felt: the whole ledger minus the tray. */
    private static int actionTotal(Table table) {
        return WagerEngine.get().felt(table);
    }

    private void prepareIdle(Table table) {
        if (table == null || table.live()) {
            return;
        }
        if (!auto(table) || GuildTables.frozen(table)) {
            cancelBetTimer(table);
            if (GuildTables.frozen(table) && table.betOpen()) {
                refundUnderMinBoxes(table);
                refundOpenBoxes(table);
            }
            table.setBetOpen(false);
            table.setAutoCountdown(0);
            TableManager.get().refreshLabel(table);
            syncTrayHolo(table);
            return;
        }
        table.setBetOpen(true);
        table.setAutoCountdown(0);
        TableManager.get().refreshLabel(table);
        syncTrayHolo(table);
    }

    private void startBetTimer(Table table) {
        TableLayout layout = Cache.layoutOf(table.getGameId());
        int seconds = layout != null ? layout.betSeconds() : 10;
        table.setAutoCountdown(seconds);
        TableManager.get().refreshLabel(table);
        UUID tableId = table.getId();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(Games.plugin, () -> {
            Table still = TableManager.get().table(tableId);
            if (still == null || still.live() || !still.betOpen()) {
                cancelBetTimer(still != null ? still : table);
                return;
            }
            int left = still.autoCountdown() - 1;
            still.setAutoCountdown(left);
            if (left > 0) {
                TableManager.get().refreshLabel(still);
                return;
            }
            cancelBetTimer(still);
            still.setBetOpen(false);
            TableManager.get().refreshLabel(still);
            closeBets(still);
        }, 20L, 20L);
        betTimers.put(tableId, task);
    }

    private void cancelLinger(Table table) {
        if (table == null) {
            return;
        }
        BukkitTask task = lingerTimers.remove(table.getId());
        if (task != null) {
            task.cancel();
        }
    }

    private static int resultDelay(Table table) {
        TableLayout layout = table != null ? Cache.layoutOf(table.getGameId()) : null;
        int cfg = layout != null ? layout.resultDelayTicks() : 8;
        return Math.max(Cache.interpolationTicks, Math.max(0, cfg));
    }

    private void afterResultDelay(Table table, Runnable then) {
        if (then == null) {
            return;
        }
        if (table == null) {
            then.run();
            return;
        }
        int ticks = resultDelay(table);
        if (ticks <= 0) {
            then.run();
            return;
        }
        UUID tableId = table.getId();
        Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
            Table live = TableManager.get().table(tableId);
            if (live == null || !live.live()) {
                return;
            }
            then.run();
        }, ticks);
    }

    private void cancelBetTimer(Table table) {
        if (table == null) {
            return;
        }
        BukkitTask task = betTimers.remove(table.getId());
        if (task != null) {
            task.cancel();
        }
        table.setAutoCountdown(0);
    }

    @Override
    public void onShoeClick(Table table, Player player) {
        if (table == null || player == null || !table.live()) {
            return;
        }
        String phase = table.phase();
        if (WAIT_DEAL.equals(phase)) {
            if (!player.getUniqueId().equals(table.dealerId())) {
                return;
            }
            startDeal(table);
            return;
        }
        if (PLAY.equals(phase) && player.getUniqueId().equals(table.actor())) {
            hit(table, player);
        }
    }

    @Override
    public void onBetHit(Table table, Player player) {
        hit(table, player);
    }

    @Override
    public void onBetStand(Table table, Player player) {
        stand(table, player);
    }

    @Override
    public void onBetDouble(Table table, Player player) {
        doubleDown(table, player);
    }

    @Override
    public void onBetSplit(Table table, Player player) {
        split(table, player);
    }

    void hit(Table table, Player player) {
        BjHand hand = currentHand(table);
        if (!canAct(table, player, hand) || hand.doubled || hand.splitAces) {
            return;
        }
        speak(table, player, "hit");
        dealToHand(table, player, hand, false);
    }

    void stand(Table table, Player player) {
        BjHand hand = currentHand(table);
        if (!canAct(table, player, hand)) {
            return;
        }
        hand.stood = true;
        speak(table, player, "stand");
        nextHand(table);
    }

    void doubleDown(Table table, Player player) {
        BjHand hand = currentHand(table);
        if (!canAct(table, player, hand)) {
            return;
        }
        List<HandCard> cards = cardsOf(table, hand);
        if (hand.doubled || hand.splitAces || cards.size() != 2) {
            player.sendMessage(Messages.get("bet.no_double"));
            return;
        }
        // Before the chips move, not after: a refused cover used to leave the bet doubled anyway.
        if (!houseCanCover(table, hand.bet)) {
            player.sendMessage(Messages.get("bet.bank_short"));
            return;
        }
        if (!stakeExtra(table, player, hand.bet)) {
            return;
        }
        hand.bet *= 2;
        hand.doubled = true;
        checkBoxBets(table, "double");
        player.sendMessage(Messages.get("bet.doubled"));
        speak(table, player, "double");
        TableManager.get().refreshLabel(table);
        dealToHand(table, player, hand, true);
    }

    void split(Table table, Player player) {
        BjHand hand = currentHand(table);
        if (!canAct(table, player, hand)) {
            return;
        }
        List<BjHand> boxHands = boxHands(table);
        List<HandCard> cards = cardsOf(table, hand);
        if (boxHands.size() >= maxHandsPerBox(table) || cards.size() != 2 || !isPair(cards)
                || (hand.splitAces && !resplitAces(table))) {
            player.sendMessage(Messages.get("bet.no_split"));
            return;
        }
        if (!houseCanCover(table, hand.bet)) {
            player.sendMessage(Messages.get("bet.bank_short"));
            return;
        }
        if (!stakeExtra(table, player, hand.bet)) {
            return;
        }
        boolean aces = isAce(cards.get(0)) && isAce(cards.get(1));
        hand.fromSplit = true;
        hand.splitAces = aces;
        List<BjHand> round = rounds.get(table.getId());
        // A free slot until renumberBox assigns the real one, so no two hands claim the same cards.
        BjHand second = new BjHand(hand.owner, round.size(), nextFreeSlot(table, hand.owner), hand.bet);
        second.fromSplit = true;
        second.splitAces = aces;
        int at = round.indexOf(hand);
        round.add(at + 1, second);
        TableManager manager = TableManager.get();
        // The card that leaves belongs to this hand, which on a resplit is not the box's second card.
        List<HandCard> moving = cardsOf(table, hand);
        renumberBox(table, hand.owner);
        if (moving.size() > 1) {
            moving.get(1).setSlot(second.slot);
        }
        Player online = Bukkit.getPlayer(hand.owner);
        if (online != null && online.isOnline()) {
            manager.relayoutHand(table, online);
        }
        checkBoxBets(table, "split");
        player.sendMessage(Messages.get("bet.split"));
        speak(table, player, "split");
        manager.refreshLabel(table);
        table.setPhase(DEAL);
        int first = hand.slot;
        int next = second.slot;
        dealToBox(table, hand.owner, first, () -> {
            Table still = TableManager.get().table(table.getId());
            if (still == null || !still.live()) {
                return;
            }
            dealToBox(still, hand.owner, next, () -> {
                Table live = TableManager.get().table(still.getId());
                if (live == null || !live.live()) {
                    return;
                }
                live.setPhase(PLAY);
                // Resume on the hand that was split, never back at the box's first hand.
                live.setHandIndex(first);
                advancePlay(live);
            });
        });
    }

    /**
     * Give a box's hands slots 0..n-1 in play order, moving their cards with them. The new hand
     * from a split already sits next to its parent in the round list, so it lands next to it in
     * the fan too. Parking cards out of range first keeps a shift from landing on a slot that has
     * not moved yet.
     */
    private void renumberBox(Table table, UUID owner) {
        List<BjHand> hands = handsOf(table, owner);
        if (hands.isEmpty()) {
            return;
        }
        List<HandCard> held = table.handOf(owner);
        Map<Integer, Integer> moves = new HashMap<>();
        for (int i = 0; i < hands.size(); i++) {
            moves.put(hands.get(i).slot, i);
        }
        for (HandCard card : held) {
            Integer to = moves.get(card.slot());
            if (to != null) {
                card.setSlot(SLOT_PARK + to);
            }
        }
        for (int i = 0; i < hands.size(); i++) {
            hands.get(i).slot = i;
        }
        for (HandCard card : held) {
            if (card.slot() >= SLOT_PARK) {
                card.setSlot(card.slot() - SLOT_PARK);
            }
        }
    }

    private int nextFreeSlot(Table table, UUID owner) {
        int max = -1;
        for (BjHand hand : handsOf(table, owner)) {
            max = Math.max(max, hand.slot);
        }
        return max + 1;
    }

    /** Every hand this owner holds, in play order. */
    private List<BjHand> handsOf(Table table, UUID owner) {
        List<BjHand> out = new ArrayList<>();
        if (table == null || owner == null) {
            return out;
        }
        for (BjHand hand : rounds.getOrDefault(table.getId(), List.of())) {
            if (owner.equals(hand.owner)) {
                out.add(hand);
            }
        }
        return out;
    }

    private static int maxHandsPerBox(Table table) {
        TableLayout layout = table != null ? Cache.layoutOf(table.getGameId()) : null;
        return layout != null ? layout.maxHandsPerBox() : 4;
    }

    private static boolean resplitAces(Table table) {
        TableLayout layout = table != null ? Cache.layoutOf(table.getGameId()) : null;
        return layout != null && layout.resplitAces();
    }

    /**
     * The extra bet a double or a split needs, out of the player's coins. Either the whole bet is
     * on the felt when this returns true, or nothing left their pockets and it returns false.
     * There is no third outcome, which is what the old version had.
     */
    private boolean stakeExtra(Table table, Player player, int bet) {
        if (bet < 1) {
            player.sendMessage(Messages.get("bet.need_chips"));
            return false;
        }
        TableManager manager = TableManager.get();
        UUID owner = player.getUniqueId();
        MoneyTx tx = WagerEngine.get().begin(table, "extra bet");
        // Placed on the box, so a double grows the heap already sitting there instead of
        // starting a second one on top of it.
        tx.move(Accounts.coins(table, player),
                Accounts.bucket(table, owner).placedAt(manager.boxLocation(table, owner)), bet);
        // The house has to be good for the bigger action in the same breath, so a bank that comes
        // up short leaves the bet in the player's pocket rather than on the felt.
        if (houseFunded(table)) {
            int need = Math.max(0, actionTotal(table) + bet - trayTotal(table));
            if (need > 0) {
                ItemStack template = houseTemplate(table, owner);
                int unit = manager.chipUnitDenars(template);
                if (unit < 1) {
                    player.sendMessage(Messages.get("bet.bank_short"));
                    return false;
                }
                TableLayout layout = Cache.layoutOf(table.getGameId());
                Location tray = layout != null ? layout.trayLocation(table) : null;
                // The tray holds whole coins, so a gap under one coin waits for settle.
                tx.move(Accounts.house(table), Accounts.tray(table).at(tray),
                        (need / unit) * unit, template);
            }
        }
        TxResult result = tx.commit();
        if (!result.ok()) {
            player.sendMessage(refusal(result, bet));
            return false;
        }
        manager.playChipSound(table, manager.boxLocation(table, owner));
        return true;
    }

    /**
     * A box should hold exactly what its hands are betting, nothing more and nothing less.
     *
     * <p>This is the check that would have caught the double-down bug the instant it happened:
     * the hand's bet doubled while the felt still held the original, because the extra stake had
     * been handed back by a step whose answer nobody looked at.
     */
    private void checkBoxBets(Table table, String stage) {
        if (table == null || !Cache.wagerAuditLog) {
            return;
        }
        Map<UUID, Integer> betting = new LinkedHashMap<>();
        for (BjHand hand : rounds.getOrDefault(table.getId(), List.of())) {
            betting.merge(hand.owner, hand.bet, Integer::sum);
        }
        for (Map.Entry<UUID, Integer> entry : betting.entrySet()) {
            int held = WagerEngine.get().owned(table, entry.getKey());
            if (held != entry.getValue()) {
                MoneyLog.mismatch(table, stage + " box " + entry.getKey() + " is betting "
                        + entry.getValue() + " but holds " + held);
            }
        }
    }

    /** A coin the house can pay in: whatever is already on this table, smallest first. */
    private static ItemStack houseTemplate(Table table, UUID prefer) {
        TableManager manager = TableManager.get();
        ItemStack template = manager.feltItem(table, table.getId());
        if (template == null && prefer != null) {
            template = manager.feltItem(table, prefer);
        }
        if (template == null) {
            for (UUID owner : manager.boxOwners(table)) {
                template = manager.feltItem(table, owner);
                if (template != null) {
                    break;
                }
            }
        }
        return template;
    }

    /** What to tell a player whose bet was refused, naming the actual reason. */
    private static String refusal(TxResult result, int bet) {
        if (result == null) {
            return Messages.get("bet.need_chips");
        }
        if (result.reason() == TxResult.Reason.NO_CHANGE) {
            return Messages.get("bet.no_change", "amount", String.valueOf(bet), "best",
                    String.valueOf(result.best()));
        }
        return Messages.get(result.messageKey() != null ? result.messageKey() : "bet.need_chips");
    }

    private void dealToHand(Table table, Player player, BjHand hand, boolean fromDouble) {
        table.setPhase(DEAL);
        Runnable after = () -> {
            Table still = TableManager.get().table(table.getId());
            if (still == null || !still.live()) {
                return;
            }
            still.setPhase(PLAY);
            afterPlayerCard(still, currentHand(still), fromDouble);
        };
        dealToBox(table, player.getUniqueId(), hand.slot, after);
    }

    private void startDeal(Table table) {
        if (!WAIT_DEAL.equals(table.phase())) {
            return;
        }
        table.setPhase(DEAL);
        List<DealStep> steps = new ArrayList<>();
        for (UUID box : table.boxes()) {
            steps.add(new DealStep(box, false, true));
        }
        steps.add(new DealStep(null, true, true));
        for (UUID box : table.boxes()) {
            steps.add(new DealStep(box, false, true));
        }
        steps.add(new DealStep(null, true, false));
        runDeal(table, steps, 0);
    }

    private void runDeal(Table table, List<DealStep> steps, int index) {
        Table live = TableManager.get().table(table.getId());
        if (live == null || !live.live()) {
            return;
        }
        if (index >= steps.size()) {
            live.setBoxIndex(0);
            live.setHandIndex(0);
            advancePlay(live);
            return;
        }
        DealStep step = steps.get(index);
        Runnable next = () -> runDeal(live, steps, index + 1);
        if (step.dealer()) {
            TableManager.get().dealToTable(live, "dealer", 1, step.faceUp(), next);
            return;
        }
        dealToBox(live, step.box(), 0, next);
    }

    private void dealToBox(Table table, UUID box, int slot, Runnable after) {
        Player player = box != null ? Bukkit.getPlayer(box) : null;
        if (player == null || !player.isOnline()) {
            if (after != null) {
                after.run();
            }
            return;
        }
        UUID tableId = table.getId();
        UUID playerId = player.getUniqueId();
        TableManager.get().dealToPlayer(table, player, 1, slot, () -> {
            Table still = TableManager.get().table(tableId);
            Player online = Bukkit.getPlayer(playerId);
            if (still != null && online != null) {
                TableManager.get().publishHand(still, online);
            }
            if (after != null) {
                after.run();
            }
        });
    }

    private void afterPlayerCard(Table table, BjHand hand, boolean fromDouble) {
        if (hand == null) {
            nextHand(table);
            return;
        }
        UUID tableId = table.getId();
        UUID owner = hand.owner;
        int id = hand.id;
        afterResultDelay(table, () -> {
            Table live = TableManager.get().table(tableId);
            if (live == null || !live.live()) {
                return;
            }
            BjHand still = findHand(live, owner, id);
            finishPlayerCard(live, still, fromDouble);
        });
    }

    /** By id, not slot: a split between the deal and the delay renumbers every slot in the box. */
    private BjHand findHand(Table table, UUID owner, int id) {
        for (BjHand hand : rounds.getOrDefault(table.getId(), List.of())) {
            if (hand.owner.equals(owner) && hand.id == id) {
                return hand;
            }
        }
        return null;
    }

    private void finishPlayerCard(Table table, BjHand hand, boolean fromDouble) {
        if (hand == null) {
            nextHand(table);
            return;
        }
        List<HandCard> cards = cardsOf(table, hand);
        int total = bestTotal(cards);
        Player player = Bukkit.getPlayer(hand.owner);
        if (total > 21) {
            if (player != null && player.isOnline()) {
                player.sendMessage(Messages.get("bet.bust"));
            }
            nextHand(table);
            return;
        }
        if (fromDouble || hand.doubled || total == 21 || (hand.splitAces && cards.size() >= 2)) {
            nextHand(table);
            return;
        }
        table.setActor(hand.owner);
        table.setPhase(PLAY);
        TableManager.get().refreshLabel(table);
    }

    private void advancePlay(Table table) {
        while (table.boxIndex() < table.boxes().size()) {
            List<BjHand> boxHands = boxHands(table);
            while (table.handIndex() < boxHands.size()) {
                BjHand hand = boxHands.get(table.handIndex());
                Player player = Bukkit.getPlayer(hand.owner);
                List<HandCard> cards = cardsOf(table, hand);
                if (player == null || !player.isOnline() || cards.isEmpty()) {
                    table.setHandIndex(table.handIndex() + 1);
                    continue;
                }
                int total = bestTotal(cards);
                boolean done = total >= 21 || hand.doubled || hand.stood
                        || (hand.splitAces && cards.size() >= 2);
                if (done) {
                    UUID tableId = table.getId();
                    int box = table.boxIndex();
                    int handAt = table.handIndex();
                    boolean bust = total > 21;
                    UUID playerId = player.getUniqueId();
                    afterResultDelay(table, () -> {
                        Table live = TableManager.get().table(tableId);
                        if (live == null || !live.live()) {
                            return;
                        }
                        live.setBoxIndex(box);
                        live.setHandIndex(handAt);
                        Player still = Bukkit.getPlayer(playerId);
                        if (bust && still != null && still.isOnline()) {
                            still.sendMessage(Messages.get("bet.bust"));
                        }
                        live.setHandIndex(handAt + 1);
                        advancePlay(live);
                    });
                    return;
                }
                table.setActor(hand.owner);
                table.setPhase(PLAY);
                TableManager.get().refreshLabel(table);
                return;
            }
            table.setBoxIndex(table.boxIndex() + 1);
            table.setHandIndex(0);
        }
        startDealer(table);
    }

    private void nextHand(Table table) {
        table.setActor(null);
        table.setHandIndex(table.handIndex() + 1);
        advancePlay(table);
    }

    private void startDealer(Table table) {
        table.setPhase(DEALER);
        table.setActor(null);
        TableManager.get().refreshLabel(table);
        TableManager.get().revealTablePile(table, "dealer");
        afterResultDelay(table, () -> {
            Table live = TableManager.get().table(table.getId());
            if (live == null || !live.live()) {
                return;
            }
            if (allBusted(live)) {
                settle(live);
                return;
            }
            dealerHit(live);
        });
    }

    private void dealerHit(Table table) {
        Table live = TableManager.get().table(table.getId());
        if (live == null || !live.live()) {
            return;
        }
        TableLayout layout = Cache.layoutOf(live.getGameId());
        boolean hitSoft = layout != null && layout.dealerHitsSoft17();
        if (!shouldDealerHit(live.tablePile("dealer"), hitSoft)) {
            afterResultDelay(live, () -> settle(TableManager.get().table(live.getId())));
            return;
        }
        TableManager.get().dealToTable(live, "dealer", 1, true, () -> dealerHit(live));
    }

    private void settle(Table table) {
        if (table == null || SETTLE.equals(table.phase())) {
            return;
        }
        table.setPhase(SETTLE);
        checkBoxBets(table, "settle");
        TableManager manager = TableManager.get();
        int dealerTotal = bestTotal(table.tablePile("dealer"));
        boolean dealerBust = dealerTotal > 21;
        UUID dealerId = table.dealerId();
        Player dealer = dealerId != null ? Bukkit.getPlayer(dealerId) : null;
        Map<UUID, Integer> collect = new HashMap<>();
        Map<UUID, Integer> pay = new HashMap<>();
        Map<UUID, ItemStack> templates = new HashMap<>();
        List<BjHand> hands = rounds.getOrDefault(table.getId(), List.of());
        for (BjHand hand : hands) {
            templates.putIfAbsent(hand.owner, manager.feltItem(table, hand.owner));
            List<HandCard> cards = cardsOf(table, hand);
            int playerTotal = bestTotal(cards);
            boolean bust = playerTotal > 21 || cards.isEmpty();
            boolean natural = isNatural(hand, cards);
            Player player = Bukkit.getPlayer(hand.owner);
            if (bust || (!dealerBust && playerTotal < dealerTotal)) {
                collect.merge(hand.owner, hand.bet, Integer::sum);
                if (player != null && player.isOnline()) {
                    player.sendMessage(Messages.get("bet.lose"));
                }
                continue;
            }
            if (!dealerBust && playerTotal == dealerTotal) {
                if (player != null && player.isOnline()) {
                    player.sendMessage(Messages.get("bet.push"));
                }
                continue;
            }
            int amount = natural ? (hand.bet * 3) / 2 : hand.bet;
            pay.merge(hand.owner, amount, Integer::sum);
            if (player != null && player.isOnline()) {
                player.sendMessage(Messages.get(natural ? "bet.natural" : "bet.win"));
            }
        }
        // Who is behind the house here. On a table with no guild that is the dealer out of their
        // own pocket; on a guild table it is the bank, whether or not a human is turning the cards.
        boolean dealerBacked = !houseFunded(table);
        UUID trayId = table.getId();
        List<PayoutFlight> flights = new ArrayList<>();
        int held = trayTotal(table) + actionTotal(table);
        // Nothing comes into the table during a settle. A dealer or a bank covering a win pays the
        // winner directly, so that money is never table money for even an instant.
        int wentOut = 0;
        // Losing bets leave the box: a private dealer takes them, the house tray keeps them.
        for (Map.Entry<UUID, Integer> entry : collect.entrySet()) {
            UUID owner = entry.getKey();
            if (dealerBacked) {
                wentOut += WagerEngine.get()
                        .refund(table, owner, dealer, entry.getValue(), flights, "loss to dealer")
                        .moved();
            } else {
                WagerEngine.get().toTray(table, owner, entry.getValue(), flights, "loss");
            }
        }
        for (Map.Entry<UUID, Integer> entry : pay.entrySet()) {
            UUID owner = entry.getKey();
            int amount = entry.getValue();
            Player winner = Bukkit.getPlayer(owner);
            // One movement: the tray pays what it can make, and whoever backs the table covers
            // the rest directly. Nothing sits half paid waiting for a second step to work.
            int fromTray = Math.min(amount, WagerEngine.get().tray(table));
            fromTray = Accounts.tray(table).largestTakeUpTo(fromTray, null);
            MoneyTx tx = WagerEngine.get().begin(table, "win payout").animate(flights);
            MoneyAccount to = Accounts.payee(table, winner, owner);
            tx.moveUpTo(Accounts.tray(table), to, amount);
            int rest = amount - fromTray;
            if (rest > 0) {
                if (dealerBacked) {
                    if (dealer != null && dealer.isOnline()) {
                        tx.moveUpTo(Accounts.pockets(table, dealer), to, rest);
                    }
                } else {
                    // The round reserve is sized to make this unreachable, so say so if it happens.
                    if (Cache.wagerAuditLog) {
                        MoneyLog.mismatch(table, "tray short " + rest + " paying " + owner
                                + ", the round reserve should have covered it");
                    }
                    ItemStack template = templates.get(owner);
                    if (template == null) {
                        template = houseTemplate(table, owner);
                    }
                    tx.moveUpTo(Accounts.house(table), to, rest, template);
                }
            }
            int paid = tx.commit().moved();
            // Only the tray's share came off this table. The rest was never table money.
            wentOut += Math.min(paid, fromTray);
            int owe = amount - paid;
            if (owe > 0) {
                Player tell = dealerBacked ? dealer : winner;
                if (tell != null && tell.isOnline()) {
                    tell.sendMessage(Messages.get("bet.owe", "amount", String.valueOf(owe)));
                }
            }
        }
        // Pushes and anything a winner still has on the felt goes straight back to them.
        for (UUID owner : new ArrayList<>(manager.boxOwners(table))) {
            Player back = Bukkit.getPlayer(owner);
            wentOut += WagerEngine.get().refund(table, owner, back, 0, flights, "bet returned")
                    .moved();
        }
        manager.checkFeltEmpty(table, "settle");
        int expected = held - wentOut;
        int now = trayTotal(table) + actionTotal(table);
        if (Cache.wagerAuditLog && now != expected) {
            MoneyLog.mismatch(table, "settle held " + held + " paid out " + wentOut
                    + " so it should hold " + expected + " but holds " + now);
        }
        manager.flushPiles(table, flights, () -> startLinger(table));
    }

    private void startLinger(Table table) {
        if (table == null) {
            return;
        }
        cancelLinger(table);
        TableLayout layout = Cache.layoutOf(table.getGameId());
        int seconds = layout != null ? layout.roundEndSeconds() : 10;
        table.setPhase(SETTLE);
        table.setAutoCountdown(seconds);
        TableManager.get().refreshLabel(table);
        String msg = Messages.get("bet.round_end", "seconds", String.valueOf(seconds));
        for (UUID box : table.boxes()) {
            Player player = Bukkit.getPlayer(box);
            if (player != null && player.isOnline()) {
                player.sendMessage(msg);
            }
        }
        UUID tableId = table.getId();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(Games.plugin, () -> {
            Table still = TableManager.get().table(tableId);
            if (still == null || !still.live() || !SETTLE.equals(still.phase())) {
                cancelLinger(still != null ? still : table);
                return;
            }
            int left = still.autoCountdown() - 1;
            still.setAutoCountdown(left);
            if (left > 0) {
                TableManager.get().refreshLabel(still);
                return;
            }
            cancelLinger(still);
            finishRound(still);
        }, 20L, 20L);
        lingerTimers.put(tableId, task);
    }

    private void finishRound(Table table) {
        if (table == null || !table.live()) {
            return;
        }
        TableManager manager = TableManager.get();
        for (UUID box : new ArrayList<>(table.boxes())) {
            manager.muckPlayer(table, box);
        }
        manager.muckTable(table, "dealer");
        rounds.remove(table.getId());
        manager.endSession(table);
    }

    /** Boxes play left to right, ordered by where each bucket sits on the felt. */
    private void snapshotBoxes(Table table) {
        table.boxes().clear();
        TableManager manager = TableManager.get();
        List<UUID> order = new ArrayList<>(manager.boxOwners(table));
        Map<UUID, double[]> spots = new HashMap<>();
        for (UUID owner : order) {
            Location at = manager.boxLocation(table, owner);
            spots.put(owner, new double[] {TableLayout.localRight(table, at), TableLayout.localForward(table, at)});
        }
        order.sort(Comparator.comparingDouble((UUID id) -> spots.get(id)[0])
                .thenComparingDouble(id -> spots.get(id)[1])
                .thenComparing(UUID::toString));
        table.boxes().addAll(order);
    }

    private boolean canAct(Table table, Player player, BjHand hand) {
        return table != null && player != null && PLAY.equals(table.phase())
                && player.getUniqueId().equals(table.actor()) && hand != null
                && hand.owner.equals(player.getUniqueId());
    }

    private BjHand currentHand(Table table) {
        List<BjHand> boxHands = boxHands(table);
        int index = table.handIndex();
        if (index < 0 || index >= boxHands.size()) {
            return null;
        }
        return boxHands.get(index);
    }

    private List<BjHand> boxHands(Table table) {
        if (table == null || table.boxIndex() < 0 || table.boxIndex() >= table.boxes().size()) {
            return List.of();
        }
        UUID owner = table.boxes().get(table.boxIndex());
        List<BjHand> out = new ArrayList<>();
        for (BjHand hand : rounds.getOrDefault(table.getId(), List.of())) {
            if (hand.owner.equals(owner)) {
                out.add(hand);
            }
        }
        return out;
    }

    private List<HandCard> cardsOf(Table table, BjHand hand) {
        if (hand == null || table == null) {
            return List.of();
        }
        List<HandCard> all = table.handOf(hand.owner);
        List<HandCard> out = new ArrayList<>();
        for (HandCard held : all) {
            if (held.slot() == hand.slot) {
                out.add(held);
            }
        }
        return out;
    }

    private boolean allBusted(Table table) {
        for (BjHand hand : rounds.getOrDefault(table.getId(), List.of())) {
            if (bestTotal(cardsOf(table, hand)) <= 21) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNatural(BjHand hand, List<HandCard> cards) {
        return hand != null && !hand.fromSplit && !hand.doubled && cards != null && cards.size() == 2
                && bestTotal(cards) == 21;
    }

    private static boolean isPair(List<HandCard> cards) {
        if (cards == null || cards.size() != 2) {
            return false;
        }
        Card a = cards.get(0).card();
        Card b = cards.get(1).card();
        if (a == null || b == null) {
            return false;
        }
        if (a.getRank() == b.getRank()) {
            return true;
        }
        return tenValue(a) && tenValue(b);
    }

    private static boolean isAce(HandCard held) {
        return held != null && held.card() != null && held.card().getRank() == 1;
    }

    private static boolean tenValue(Card card) {
        return card != null && !card.isJoker() && card.getRank() >= 10;
    }

    static int bestTotal(List<HandCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return 0;
        }
        int total = 0;
        int aces = 0;
        for (HandCard held : cards) {
            Card card = held.card();
            if (card == null || card.isJoker()) {
                continue;
            }
            int rank = card.getRank();
            if (rank == 1) {
                aces++;
                total += 1;
            } else if (rank >= 11) {
                total += 10;
            } else {
                total += rank;
            }
        }
        while (aces > 0 && total + 10 <= 21) {
            total += 10;
            aces--;
        }
        return total;
    }

    static int hardTotal(List<HandCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (HandCard held : cards) {
            Card card = held.card();
            if (card == null || card.isJoker()) {
                continue;
            }
            int rank = card.getRank();
            if (rank == 1) {
                total += 1;
            } else if (rank >= 11) {
                total += 10;
            } else {
                total += rank;
            }
        }
        return total;
    }

    static boolean isSoft17(List<HandCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return false;
        }
        int hard = 0;
        int aces = 0;
        for (HandCard held : cards) {
            Card card = held.card();
            if (card == null || card.isJoker()) {
                continue;
            }
            int rank = card.getRank();
            if (rank == 1) {
                aces++;
                hard += 1;
            } else if (rank >= 11) {
                hard += 10;
            } else {
                hard += rank;
            }
        }
        int total = hard;
        int soft = 0;
        int left = aces;
        while (left > 0 && total + 10 <= 21) {
            total += 10;
            left--;
            soft++;
        }
        return total == 17 && soft > 0;
    }

    static boolean shouldDealerHit(List<HandCard> cards, boolean hitSoft17) {
        int total = bestTotal(cards);
        if (total < 17) {
            return true;
        }
        return hitSoft17 && isSoft17(cards);
    }

    private record DealStep(UUID box, boolean dealer, boolean faceUp) {}

    private static final class BjHand {
        private final UUID owner;
        /** Stable for the whole round, so a hand survives being renumbered mid-animation. */
        private final int id;
        /** Play order inside the box, and the card fan group. Renumbered on every split. */
        private int slot;
        private int bet;
        private boolean doubled;
        private boolean fromSplit;
        private boolean splitAces;
        private boolean stood;

        private BjHand(UUID owner, int id, int slot, int bet) {
            this.owner = owner;
            this.id = id;
            this.slot = slot;
            this.bet = Math.max(0, bet);
        }
    }
}
