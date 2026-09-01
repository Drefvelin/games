package net.tfminecraft.games.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
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
import net.tfminecraft.games.wager.PotPile;

/**
 * Layout, claim, deal, 21, double/split, and settle. Engines stay dumb.
 */
public final class BlackjackGame implements Game {

    public static final String WAIT_DEAL = "wait_deal";
    public static final String DEAL = "deal";
    public static final String PLAY = "play";
    public static final String DEALER = "dealer";
    public static final String SETTLE = "settle";

    private final Map<UUID, List<BjHand>> rounds = new HashMap<>();
    private final Map<UUID, BukkitTask> betTimers = new HashMap<>();
    private final Map<UUID, BukkitTask> lingerTimers = new HashMap<>();
    private final Map<UUID, Map<String, UUID>> pileHolos = new HashMap<>();
    private final Map<UUID, UUID> trayHolos = new HashMap<>();

    static boolean auto(Table table) {
        return table != null && table.autoDealer();
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
            table.setAutoDealer(false);
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
        TableManager manager = TableManager.get();
        int total = 0;
        for (PotPile pile : table.getPiles()) {
            if (manager.isTrayPile(table, pile)) {
                total += pile.contribution();
            }
        }
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
            hands.add(new BjHand(box, 0, manager.ownedDenars(table, box)));
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
        prepareIdle(table);
        syncTrayHolo(table);
    }

    @Override
    public void onChipIn(Table table, Player player, int denars, ItemStack item) {
        if (!coverAutoTray(table, player, denars, item)) {
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
    }

    private static boolean coverAutoTray(Table table, Player player, int denars, ItemStack item) {
        if (!auto(table) || table == null) {
            return true;
        }
        TableLayout layout = Cache.layoutOf(table.getGameId());
        Location tray = layout != null ? layout.trayLocation(table) : null;
        int need = Math.max(0, actionTotal(table) - trayTotal(table));
        if (need < 1) {
            return true;
        }
        if (table.staffMint()) {
            if (denars < 1 || item == null || tray == null) {
                return true;
            }
            TableManager.get().spawnStoredPiles(table, table.getId(), item, need, tray);
            return true;
        }
        if (denars < 1 || item == null || tray == null
                || !GuildTables.tryWithdraw(table.ownerGuildId(), need)) {
            restoreChipIn(table, player, denars);
            if (player != null) {
                player.sendMessage(Messages.get("bet.bank_short"));
            }
            return false;
        }
        TableManager.get().spawnStoredPiles(table, table.getId(), item, need, tray);
        return true;
    }

    private static void restoreChipIn(Table table, Player player, int denars) {
        if (table == null || player == null || denars < 1) {
            return;
        }
        TableManager manager = TableManager.get();
        UUID owner = player.getUniqueId();
        List<PotPile> back = manager.detachDenars(table,
                pile -> owner.equals(pile.ownerId()) && !manager.isTrayPile(table, pile), denars);
        List<PayoutFlight> flights = new ArrayList<>();
        for (PotPile pile : back) {
            flights.add(new PayoutFlight(pile, owner));
        }
        manager.flushPiles(table, flights, null);
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
            TableManager.get().beginSession(table);
        } else if (auto(table) && !table.live()) {
            prepareIdle(table);
        }
    }

    private void refundUnderMinBoxes(Table table) {
        TableManager manager = TableManager.get();
        int min = table.minBet();
        UUID dealer = table.dealerId();
        UUID house = table.getId();
        LinkedHashSet<UUID> owners = new LinkedHashSet<>();
        for (PotPile pile : table.getPiles()) {
            UUID owner = pile.ownerId();
            if (owner == null || owner.equals(dealer) || owner.equals(house) || manager.isTrayPile(table, pile)) {
                continue;
            }
            owners.add(owner);
        }
        for (UUID owner : owners) {
            int felt = manager.ownedDenars(table, owner);
            if (felt >= min || felt < 1) {
                continue;
            }
            Player player = Bukkit.getPlayer(owner);
            if (player != null) {
                player.sendMessage(Messages.get("bet.under_min", "min", String.valueOf(min)));
                manager.refundPiles(table, player,
                        pile -> owner.equals(pile.ownerId()) && !manager.isTrayPile(table, pile));
            } else {
                List<PayoutFlight> flights = new ArrayList<>();
                for (PotPile pile : new ArrayList<>(table.getPiles())) {
                    if (owner.equals(pile.ownerId()) && !manager.isTrayPile(table, pile)) {
                        flights.add(new PayoutFlight(pile, owner));
                    }
                }
                manager.flushPiles(table, flights, null);
            }
            if (auto(table)) {
                peelAutoTray(table, felt);
            }
        }
    }

    private void refundOpenBoxes(Table table) {
        TableManager manager = TableManager.get();
        UUID dealer = table.dealerId();
        UUID house = table.getId();
        LinkedHashSet<UUID> owners = new LinkedHashSet<>();
        for (PotPile pile : table.getPiles()) {
            UUID owner = pile.ownerId();
            if (owner == null || owner.equals(dealer) || owner.equals(house) || manager.isTrayPile(table, pile)) {
                continue;
            }
            owners.add(owner);
        }
        for (UUID owner : owners) {
            int felt = manager.ownedDenars(table, owner);
            if (felt < 1) {
                continue;
            }
            Player player = Bukkit.getPlayer(owner);
            if (player != null) {
                player.sendMessage(Messages.get("bet.no_slots"));
                manager.refundPiles(table, player,
                        pile -> owner.equals(pile.ownerId()) && !manager.isTrayPile(table, pile));
            } else {
                List<PayoutFlight> flights = new ArrayList<>();
                for (PotPile pile : new ArrayList<>(table.getPiles())) {
                    if (owner.equals(pile.ownerId()) && !manager.isTrayPile(table, pile)) {
                        flights.add(new PayoutFlight(pile, owner));
                    }
                }
                manager.flushPiles(table, flights, null);
            }
            if (auto(table)) {
                peelAutoTray(table, felt);
            }
        }
    }

    private static void peelAutoTray(Table table, int released) {
        if (table == null || released < 1) {
            return;
        }
        int peel = Math.max(0, Math.min(released, actionTotal(table) + released - trayTotal(table)));
        if (peel < 1) {
            return;
        }
        TableManager manager = TableManager.get();
        List<PotPile> peeled = manager.detachDenars(table, pile -> manager.isTrayPile(table, pile), peel);
        List<PayoutFlight> flights = new ArrayList<>();
        for (PotPile pile : peeled) {
            flights.add(new PayoutFlight(pile, null));
        }
        manager.flushPiles(table, flights, null);
    }

    private static int trayTotal(Table table) {
        if (table == null) {
            return 0;
        }
        TableManager manager = TableManager.get();
        int total = 0;
        for (PotPile pile : table.getPiles()) {
            if (manager.isTrayPile(table, pile)) {
                total += pile.contribution();
            }
        }
        return total;
    }

    private static int actionTotal(Table table) {
        if (table == null) {
            return 0;
        }
        TableManager manager = TableManager.get();
        int total = 0;
        for (PotPile pile : table.getPiles()) {
            if (pile.ownerId() != null && !manager.isTrayPile(table, pile)) {
                total += pile.contribution();
            }
        }
        return total;
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
        if (!canAct(table, player, currentHand(table))) {
            return;
        }
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
        if (!stakeExtra(table, player, hand.bet)) {
            return;
        }
        hand.bet *= 2;
        hand.doubled = true;
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
        if (hand.slot != 0 || hand.fromSplit || boxHands.size() != 1 || cards.size() != 2 || !isPair(cards)) {
            player.sendMessage(Messages.get("bet.no_split"));
            return;
        }
        if (!stakeExtra(table, player, hand.bet)) {
            return;
        }
        boolean aces = isAce(cards.get(0)) && isAce(cards.get(1));
        hand.fromSplit = true;
        hand.splitAces = aces;
        BjHand second = new BjHand(hand.owner, 1, hand.bet);
        second.fromSplit = true;
        second.splitAces = aces;
        List<BjHand> round = rounds.get(table.getId());
        int at = round.indexOf(hand);
        round.add(at + 1, second);
        TableManager manager = TableManager.get();
        List<HandCard> held = table.handOf(hand.owner);
        if (held.size() > 1) {
            held.get(1).setSlot(1);
        }
        Player online = Bukkit.getPlayer(hand.owner);
        if (online != null && online.isOnline()) {
            manager.relayoutHand(table, online);
        }
        player.sendMessage(Messages.get("bet.split"));
        speak(table, player, "split");
        manager.refreshLabel(table);
        table.setPhase(DEAL);
        dealToBox(table, hand.owner, 0, () -> {
            Table still = TableManager.get().table(table.getId());
            if (still == null || !still.live()) {
                return;
            }
            dealToBox(still, hand.owner, 1, () -> {
                Table live = TableManager.get().table(still.getId());
                if (live == null || !live.live()) {
                    return;
                }
                live.setPhase(PLAY);
                live.setHandIndex(0);
                advancePlay(live);
            });
        });
    }

    private boolean stakeExtra(Table table, Player player, int bet) {
        if (bet < 1) {
            player.sendMessage(Messages.get("bet.need_chips"));
            return false;
        }
        if (!TableManager.get().placeChipsFromInventory(table, player, bet)) {
            player.sendMessage(Messages.get("bet.need_chips"));
            return false;
        }
        return true;
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
        int slot = hand.slot;
        afterResultDelay(table, () -> {
            Table live = TableManager.get().table(tableId);
            if (live == null || !live.live()) {
                return;
            }
            BjHand still = findHand(live, owner, slot);
            finishPlayerCard(live, still, fromDouble);
        });
    }

    private BjHand findHand(Table table, UUID owner, int slot) {
        for (BjHand hand : rounds.getOrDefault(table.getId(), List.of())) {
            if (hand.owner.equals(owner) && hand.slot == slot) {
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
                boolean done = total >= 21 || hand.doubled
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
        boolean house = !auto(table);
        IdentityHashMap<PotPile, PayoutFlight> dests = new IdentityHashMap<>();
        for (Map.Entry<UUID, Integer> entry : collect.entrySet()) {
            UUID owner = entry.getKey();
            List<PotPile> lost = manager.detachDenars(table, pile -> owner.equals(pile.ownerId())
                    && !manager.isTrayPile(table, pile), entry.getValue());
            for (PotPile pile : lost) {
                dests.put(pile, house ? new PayoutFlight(pile, dealerId) : PayoutFlight.toTray(pile));
            }
        }
        for (Map.Entry<UUID, Integer> entry : pay.entrySet()) {
            UUID owner = entry.getKey();
            int amount = entry.getValue();
            ItemStack template = templates.get(owner);
            int covered = 0;
            List<PotPile> fromTray = manager.detachDenars(table, pile -> manager.isTrayPile(table, pile),
                    amount);
            for (PotPile pile : fromTray) {
                dests.put(pile, new PayoutFlight(pile, owner));
                covered += pile.contribution();
            }
            int shortfall = amount - covered;
            if (house) {
                if (shortfall > 0 && dealer != null && dealer.isOnline()) {
                    int fromInv = manager.takeDenarsFromInventory(dealer, shortfall);
                    if (fromInv > 0 && template != null) {
                        Location at = manager.boxLocation(table, owner);
                        for (PotPile pile : manager.spawnStoredPiles(table, owner, template, fromInv, at)) {
                            dests.put(pile, new PayoutFlight(pile, owner));
                            covered += pile.contribution();
                        }
                    }
                }
                int owe = amount - covered;
                if (owe > 0 && dealer != null && dealer.isOnline()) {
                    dealer.sendMessage(Messages.get("bet.owe", "amount", String.valueOf(owe)));
                }
            } else if (shortfall > 0 && template != null) {
                int spawn = shortfall;
                if (!table.staffMint()) {
                    if (!GuildTables.tryWithdraw(table.ownerGuildId(), shortfall)) {
                        spawn = 0;
                        Player winner = Bukkit.getPlayer(owner);
                        if (winner != null && winner.isOnline()) {
                            winner.sendMessage(Messages.get("bet.owe", "amount", String.valueOf(shortfall)));
                        }
                    }
                }
                if (spawn > 0) {
                    TableLayout layout = Cache.layoutOf(table.getGameId());
                    Location tray = layout != null ? layout.trayLocation(table) : table.getOrigin();
                    for (PotPile pile : manager.spawnStoredPiles(table, table.getId(), template, spawn, tray)) {
                        dests.put(pile, new PayoutFlight(pile, owner));
                    }
                }
            }
        }
        for (PotPile pile : table.getPiles()) {
            if (dests.containsKey(pile) || manager.isTrayPile(table, pile)) {
                continue;
            }
            UUID owner = pile.ownerId();
            if (owner != null) {
                dests.put(pile, new PayoutFlight(pile, owner));
            }
        }
        manager.flushPiles(table, new ArrayList<>(dests.values()), () -> startLinger(table));
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

    private void snapshotBoxes(Table table) {
        table.boxes().clear();
        UUID dealer = table.dealerId();
        Map<UUID, double[]> sums = new HashMap<>();
        for (PotPile pile : table.getPiles()) {
            UUID owner = pile.ownerId();
            if (owner == null || owner.equals(dealer) || TableManager.get().isTrayPile(table, pile)) {
                continue;
            }
            Location at = table.getOrigin().clone();
            at.setX(pile.x());
            at.setZ(pile.z());
            double right = TableLayout.localRight(table, at);
            double forward = TableLayout.localForward(table, at);
            double[] acc = sums.computeIfAbsent(owner, id -> new double[3]);
            acc[0] += right;
            acc[1] += forward;
            acc[2] += 1;
        }
        List<UUID> order = new ArrayList<>(sums.keySet());
        order.sort(Comparator.comparingDouble((UUID id) -> sums.get(id)[0] / sums.get(id)[2])
                .thenComparingDouble(id -> sums.get(id)[1] / sums.get(id)[2])
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
        private final int slot;
        private int bet;
        private boolean doubled;
        private boolean fromSplit;
        private boolean splitAces;

        private BjHand(UUID owner, int slot, int bet) {
            this.owner = owner;
            this.slot = slot;
            this.bet = Math.max(0, bet);
        }
    }
}
