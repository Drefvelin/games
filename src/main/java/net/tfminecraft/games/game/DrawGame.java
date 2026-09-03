package net.tfminecraft.games.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.games.Messages;
import net.tfminecraft.games.card.Card;
import net.tfminecraft.games.table.HandCard;
import net.tfminecraft.games.table.PayoutFlight;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.table.TableManager;
import net.tfminecraft.games.voice.RpNames;
import net.tfminecraft.games.wager.WagerEngine;

/**
 * Five-Draw seats, deal, draw round, and showdown.
 */
public final class DrawGame implements Game {

    public static final String BET = "bet";
    public static final String DRAW = "draw";
    public static final String SHOWDOWN = "showdown";

    private static final class Street {
        int currentBet;
        final Set<UUID> folded = new HashSet<>();
        final Set<UUID> acted = new HashSet<>();
        final Set<UUID> capped = new HashSet<>();
        final Set<UUID> drawn = new HashSet<>();
    }

    private final Map<UUID, Street> streets = new HashMap<>();

    @Override
    public boolean showStockDealer() {
        return false;
    }

    @Override
    public boolean allowFreeDraw(Table table, Player player) {
        return table != null && !table.live();
    }

    @Override
    public boolean allowReturnSelected(Table table, Player player) {
        if (table == null || player == null) {
            return false;
        }
        if (!table.live()) {
            return true;
        }
        if (!DRAW.equals(table.phase()) || !player.getUniqueId().equals(table.actor())) {
            return false;
        }
        Street street = streets.get(table.getId());
        return street != null && !street.drawn.contains(player.getUniqueId());
    }

    @Override
    public boolean tryClaimDealer(Table table, Player player) {
        if (table == null || player == null || table.live()) {
            return false;
        }
        if (!table.actives().contains(player.getUniqueId())) {
            return false;
        }
        if (table.actives().size() < 2) {
            return false;
        }
        TableManager.get().beginSession(table);
        return true;
    }

    @Override
    public void onSessionStart(Table table) {
        if (table == null) {
            return;
        }
        List<Player> online = seatedOnline(table);
        if (online.size() < 2) {
            for (Player player : online) {
                player.sendMessage(Messages.get("draw.need_players"));
            }
            TableManager.get().endSession(table);
            return;
        }
        TableManager.get().reshuffleFull(table);
        table.setPhase(BET);
        table.setStreet(1);
        table.setActor(null);
        TableManager.get().refreshLabel(table);
        dealHands(table, dealQueue(table), 0);
    }

    @Override
    public void onSessionEnd(Table table) {
        if (table == null) {
            return;
        }
        streets.remove(table.getId());
        TableManager manager = TableManager.get();
        for (UUID id : new ArrayList<>(table.getHands().keySet())) {
            manager.muckPlayer(table, id);
        }
        manager.refreshLabel(table);
    }

    @Override
    public void onTableRemoved(Table table) {
        if (table != null) {
            streets.remove(table.getId());
        }
    }

    @Override
    public boolean allowPlayChat(Table table, Player player) {
        return table != null && player != null && table.live()
                && (BET.equals(table.phase()) || DRAW.equals(table.phase()))
                && player.getUniqueId().equals(table.actor());
    }

    @Override
    public void onPlayWord(Table table, Player player, String word) {
        if (table == null || player == null || word == null) {
            return;
        }
        Street street = streets.get(table.getId());
        if (street == null || !table.live()) {
            return;
        }
        UUID id = player.getUniqueId();
        if (street.folded.contains(id)) {
            return;
        }
        if (DRAW.equals(table.phase())) {
            if (!"draw".equals(word)) {
                return;
            }
            if (hasSelected(table, id)) {
                return;
            }
            street.drawn.add(id);
            player.sendMessage(Messages.get("draw.stood"));
            nextDrawActor(table, street, id);
            return;
        }
        int contrib = streetContrib(table, id);
        switch (word) {
            case "check" -> {
                if (contrib < street.currentBet) {
                    player.sendMessage(Messages.get("draw.cannot_check"));
                    return;
                }
                player.sendMessage(Messages.get("draw.checked"));
            }
            case "call" -> {
                if (contrib < street.currentBet) {
                    street.capped.add(id);
                } else {
                    street.capped.remove(id);
                }
                player.sendMessage(Messages.get("draw.called"));
            }
            case "raise" -> {
                if (contrib <= street.currentBet) {
                    player.sendMessage(Messages.get("draw.need_chips"));
                    return;
                }
                street.currentBet = contrib;
                street.acted.clear();
                street.capped.remove(id);
                player.sendMessage(Messages.get("draw.raised", "n", String.valueOf(street.currentBet)));
            }
            case "fold" -> {
                street.folded.add(id);
                TableManager.get().muckPlayer(table, id);
                player.sendMessage(Messages.get("draw.folded"));
            }
            default -> {
                return;
            }
        }
        street.acted.add(id);
        finishOrAdvance(table, true);
    }

    @Override
    public void onReturnedSelected(Table table, Player player, int count) {
        if (table == null || player == null || count < 1 || !table.live() || !DRAW.equals(table.phase())) {
            return;
        }
        Street street = streets.get(table.getId());
        if (street == null || street.drawn.contains(player.getUniqueId())) {
            return;
        }
        UUID id = player.getUniqueId();
        if (!id.equals(table.actor()) || street.folded.contains(id)) {
            return;
        }
        TableManager.get().dealToPlayer(table, player, count, () -> {
            Table still = TableManager.get().table(table.getId());
            if (still == null || !still.live() || !DRAW.equals(still.phase())) {
                return;
            }
            Street liveStreet = streets.get(still.getId());
            if (liveStreet == null) {
                return;
            }
            liveStreet.drawn.add(id);
            Player online = Bukkit.getPlayer(id);
            if (online != null && online.isOnline()) {
                online.sendMessage(Messages.get("draw.drew", "n", String.valueOf(count)));
            }
            nextDrawActor(still, liveStreet, id);
        });
    }

    @Override
    public void onFeltPilesChanged(Table table) {
        if (table != null) {
            TableManager.get().refreshLabel(table);
        }
    }

    @Override
    public void onChipIn(Table table, Player player) {
        if (table == null || player == null) {
            return;
        }
        if (table.dealerId() == null) {
            table.setDealerId(player.getUniqueId());
        }
        ensureButton(table);
        TableManager.get().refreshLabel(table);
    }

    @Override
    public void onChipIn(Table table, Player player, int denars, ItemStack item) {
        onChipIn(table, player);
    }

    @Override
    public void onTableReady(Table table) {
        ensureButton(table);
        TableManager.get().refreshLabel(table);
    }

    @Override
    public String extraLabel(Table table) {
        if (table == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        UUID button = table.dealerId();
        if (button != null) {
            text.append(Messages.get("label.button", "name", RpNames.of(button)));
        }
        if (table.live()) {
            if (text.length() > 0) {
                text.append("\n");
            }
            if (SHOWDOWN.equals(table.phase())) {
                text.append(Messages.get("label.draw_showdown"));
            } else if (DRAW.equals(table.phase())) {
                text.append(Messages.get("label.draw_draw"));
                UUID actor = table.actor();
                if (actor != null) {
                    text.append("\n").append(Messages.get("label.turn", "name", RpNames.of(actor)));
                }
            } else {
                text.append(Messages.get("label.draw_bet"));
                UUID actor = table.actor();
                if (actor != null) {
                    text.append("\n").append(Messages.get("label.turn", "name", RpNames.of(actor)));
                    Street street = streets.get(table.getId());
                    int toCall = 0;
                    if (street != null) {
                        toCall = Math.max(0, street.currentBet - streetContrib(table, actor));
                    }
                    text.append("\n").append(Messages.get("label.draw_tocall", "n", String.valueOf(toCall)));
                }
            }
        }
        String pot = PotLabel.lines(table);
        if (!pot.isEmpty()) {
            if (text.length() > 0) {
                text.append("\n");
            }
            text.append(pot);
        }
        return text.toString();
    }

    void passButton(Table table) {
        passButton(table, table != null ? table.dealerId() : null);
    }

    void passButton(Table table, UUID from) {
        if (table == null) {
            return;
        }
        List<UUID> seats = new ArrayList<>(table.actives());
        if (seats.isEmpty()) {
            table.setDealerId(null);
            return;
        }
        if (from != null && seats.contains(from)) {
            int i = seats.indexOf(from);
            table.setDealerId(seats.get((i + 1) % seats.size()));
            return;
        }
        table.setDealerId(seats.get(0));
    }

    private void ensureButton(Table table) {
        if (table == null) {
            return;
        }
        List<UUID> seats = new ArrayList<>(table.actives());
        if (seats.isEmpty()) {
            table.setDealerId(null);
            return;
        }
        UUID have = table.dealerId();
        if (have != null && seats.contains(have)) {
            return;
        }
        table.setDealerId(seats.get(0));
    }

    @Override
    public void onLeave(Table table, Player player) {
        TableManager manager = TableManager.get();
        UUID leaver = player.getUniqueId();
        List<UUID> before = new ArrayList<>(table.actives());
        boolean live = table.live();
        Street street = streets.get(table.getId());
        if (street != null) {
            street.folded.remove(leaver);
            street.acted.remove(leaver);
            street.capped.remove(leaver);
            street.drawn.add(leaver);
        }
        table.actives().remove(leaver);
        if (leaver.equals(table.dealerId())) {
            passAfterLeave(table, before, leaver);
        }
        TableManager.get().refreshLabel(table);
        int streetId = table.street();
        List<PayoutFlight> flights = new ArrayList<>();
        // A leaver gets this street's bet back; earlier streets stay in the pot.
        WagerEngine.get().refundStreet(table, leaver, streetId, flights, "player left");
        UUID rest = null;
        if (table.actives().size() == 1) {
            rest = table.actives().iterator().next();
        } else if (table.actives().isEmpty()) {
            rest = leaver;
        }
        if (rest != null) {
            // Nobody left to play for it, so the pot goes to the last seat.
            WagerEngine.get().sweepPot(table, Bukkit.getPlayer(rest), rest, flights, "hand abandoned");
        }
        boolean stop = live && table.actives().size() < 2;
        manager.flushPiles(table, flights, () -> {
            if (stop) {
                TableManager.get().endSession(table);
            } else {
                finishOrAdvance(table, false);
            }
        });
    }

    private static void passAfterLeave(Table table, List<UUID> before, UUID leaver) {
        if (before == null || before.isEmpty()) {
            table.setDealerId(null);
            return;
        }
        int i = before.indexOf(leaver);
        if (i < 0) {
            table.setDealerId(table.actives().isEmpty() ? null : table.actives().iterator().next());
            return;
        }
        UUID next = null;
        for (int step = 1; step <= before.size(); step++) {
            UUID candidate = before.get((i + step) % before.size());
            if (!candidate.equals(leaver) && table.actives().contains(candidate)) {
                next = candidate;
                break;
            }
        }
        table.setDealerId(next);
    }

    private static List<Player> seatedOnline(Table table) {
        List<Player> online = new ArrayList<>();
        for (UUID id : table.actives()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                online.add(player);
            }
        }
        return online;
    }

    private static List<Player> dealQueue(Table table) {
        List<UUID> seats = new ArrayList<>(table.actives());
        List<Player> queue = new ArrayList<>();
        if (seats.isEmpty()) {
            return queue;
        }
        int button = seats.indexOf(table.dealerId());
        if (button < 0) {
            button = seats.size() - 1;
        }
        for (int round = 0; round < 5; round++) {
            for (int step = 1; step <= seats.size(); step++) {
                UUID id = seats.get((button + step) % seats.size());
                Player player = Bukkit.getPlayer(id);
                if (player != null && player.isOnline()) {
                    queue.add(player);
                }
            }
        }
        return queue;
    }

    private void dealHands(Table table, List<Player> queue, int index) {
        if (table == null) {
            return;
        }
        Table still = TableManager.get().table(table.getId());
        if (still == null || !still.live()) {
            return;
        }
        if (index >= queue.size()) {
            startStreet(still);
            return;
        }
        Player player = queue.get(index);
        TableManager.get().dealToPlayer(still, player, 1, () -> dealHands(still, queue, index + 1));
    }

    private void startStreet(Table table) {
        if (table == null || !table.live()) {
            return;
        }
        Street street = new Street();
        streets.put(table.getId(), street);
        table.setActor(leftOfButton(table, street));
        TableManager.get().refreshLabel(table);
    }

    private static UUID leftOfButton(Table table, Street street) {
        List<UUID> seats = new ArrayList<>(table.actives());
        if (seats.isEmpty()) {
            return null;
        }
        int button = seats.indexOf(table.dealerId());
        if (button < 0) {
            button = seats.size() - 1;
        }
        for (int step = 1; step <= seats.size(); step++) {
            UUID id = seats.get((button + step) % seats.size());
            if (street == null || (!street.folded.contains(id) && !street.capped.contains(id))) {
                return id;
            }
        }
        return null;
    }

    private static int streetContrib(Table table, UUID owner) {
        if (table == null || owner == null) {
            return 0;
        }
        return WagerEngine.get().owned(table, owner, table.street());
    }

    private static List<UUID> liveSeats(Table table, Street street) {
        List<UUID> live = new ArrayList<>();
        for (UUID id : table.actives()) {
            if (!street.folded.contains(id)) {
                live.add(id);
            }
        }
        return live;
    }

    private static UUID nextLive(Table table, Street street, UUID from) {
        List<UUID> seats = new ArrayList<>(table.actives());
        if (seats.isEmpty()) {
            return null;
        }
        int i = seats.indexOf(from);
        for (int step = 1; step <= seats.size(); step++) {
            UUID id = seats.get((i + step) % seats.size());
            if (!street.folded.contains(id) && !street.capped.contains(id)) {
                return id;
            }
        }
        return null;
    }

    private static boolean streetComplete(Table table, Street street) {
        for (UUID id : liveSeats(table, street)) {
            if (street.capped.contains(id)) {
                continue;
            }
            if (!street.acted.contains(id) || streetContrib(table, id) < street.currentBet) {
                return false;
            }
        }
        return !liveSeats(table, street).isEmpty();
    }

    private void finishOrAdvance(Table table, boolean advance) {
        if (table == null || !table.live()) {
            return;
        }
        Street street = streets.get(table.getId());
        if (street == null) {
            TableManager.get().refreshLabel(table);
            return;
        }
        List<UUID> live = liveSeats(table, street);
        if (live.size() <= 1) {
            foldWin(table, live.isEmpty() ? null : live.get(0));
            return;
        }
        if (DRAW.equals(table.phase())) {
            nextDrawActor(table, street, table.actor());
            return;
        }
        if (streetComplete(table, street)) {
            if (table.street() >= 2) {
                showdown(table);
                return;
            }
            tellSeated(table, Messages.get("draw.street_done"));
            startDrawRound(table, street);
            return;
        }
        UUID actor = table.actor();
        if (advance) {
            table.setActor(nextLive(table, street, actor));
        } else if (actor != null
                && (street.folded.contains(actor) || street.capped.contains(actor)
                        || !table.actives().contains(actor))) {
            table.setActor(nextLive(table, street, actor));
        }
        TableManager.get().refreshLabel(table);
    }

    private void startDrawRound(Table table, Street street) {
        if (table == null || street == null || !table.live()) {
            return;
        }
        street.drawn.clear();
        table.setPhase(DRAW);
        table.setActor(leftOfButtonLive(table, street));
        TableManager.get().refreshLabel(table);
    }

    private void nextDrawActor(Table table, Street street, UUID from) {
        if (table == null || street == null || !table.live()) {
            return;
        }
        List<UUID> live = liveSeats(table, street);
        if (live.size() <= 1) {
            foldWin(table, live.isEmpty() ? null : live.get(0));
            return;
        }
        if (allDrawn(live, street)) {
            startStreetTwo(table, street);
            return;
        }
        table.setActor(nextDrawLive(table, street, from));
        TableManager.get().refreshLabel(table);
    }

    private void startStreetTwo(Table table, Street street) {
        street.currentBet = 0;
        street.acted.clear();
        street.capped.clear();
        street.drawn.clear();
        table.setStreet(2);
        table.setPhase(BET);
        table.setActor(leftOfButton(table, street));
        TableManager.get().refreshLabel(table);
    }

    private static boolean allDrawn(List<UUID> live, Street street) {
        for (UUID id : live) {
            if (!street.drawn.contains(id)) {
                return false;
            }
        }
        return !live.isEmpty();
    }

    private static UUID leftOfButtonLive(Table table, Street street) {
        List<UUID> seats = new ArrayList<>(table.actives());
        if (seats.isEmpty()) {
            return null;
        }
        int button = seats.indexOf(table.dealerId());
        if (button < 0) {
            button = seats.size() - 1;
        }
        for (int step = 1; step <= seats.size(); step++) {
            UUID id = seats.get((button + step) % seats.size());
            if (street == null || !street.folded.contains(id)) {
                return id;
            }
        }
        return null;
    }

    private static UUID nextDrawLive(Table table, Street street, UUID from) {
        List<UUID> seats = new ArrayList<>(table.actives());
        if (seats.isEmpty()) {
            return null;
        }
        int i = seats.indexOf(from);
        for (int step = 1; step <= seats.size(); step++) {
            UUID id = seats.get((i + step) % seats.size());
            if (!street.folded.contains(id) && !street.drawn.contains(id)) {
                return id;
            }
        }
        return null;
    }

    private static boolean hasSelected(Table table, UUID id) {
        List<HandCard> hand = table.getHands().get(id);
        if (hand == null) {
            return false;
        }
        for (HandCard held : hand) {
            if (held.isSelected()) {
                return true;
            }
        }
        return false;
    }

    private void showdown(Table table) {
        if (table == null || !table.live() || SHOWDOWN.equals(table.phase())) {
            return;
        }
        table.setPhase(SHOWDOWN);
        table.setActor(null);
        TableManager manager = TableManager.get();
        manager.refreshLabel(table);
        tellSeated(table, Messages.get("draw.showdown"));
        Street street = streets.get(table.getId());
        List<UUID> live = street != null ? liveSeats(table, street) : new ArrayList<>(table.actives());
        if (live.size() <= 1) {
            foldWin(table, live.isEmpty() ? null : live.get(0));
            return;
        }
        for (UUID id : live) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                manager.publishHand(table, player);
            }
        }
        for (String line : HandTalk.bestHand(table.getGameId(), live, id -> cardsOf(table.handOf(id)))) {
            tellSeated(table, line);
        }
        payPots(table, live, table.getGameId());
    }

    private void announceWinners(Table table, List<UUID> winners) {
        if (winners.size() == 1) {
            tellSeated(table, Messages.get("draw.win", "name", RpNames.of(winners.get(0))));
            return;
        }
        List<String> names = new ArrayList<>();
        for (UUID id : winners) {
            names.add(RpNames.of(id));
        }
        tellSeated(table, Messages.get("draw.chop", "names", String.join(", ", names)));
    }

    private void payPots(Table table, List<UUID> live, String gameId) {
        TableManager manager = TableManager.get();
        Map<UUID, Integer> invested = investedByOwner(table, manager);
        TreeSet<Integer> levels = new TreeSet<>();
        for (int put : invested.values()) {
            if (put > 0) {
                levels.add(put);
            }
        }
        if (levels.isEmpty()) {
            finishHand(table);
            return;
        }
        List<PayoutFlight> flights = new ArrayList<>();
        UUID leftover = null;
        List<UUID> liveOrder = seatOrderLeftOfButton(table, live);
        if (!liveOrder.isEmpty()) {
            leftover = liveOrder.get(0);
        } else if (!live.isEmpty()) {
            leftover = live.get(0);
        }
        int previous = 0;
        for (int level : levels) {
            int covered = 0;
            for (int put : invested.values()) {
                if (put >= level) {
                    covered++;
                }
            }
            int amount = (level - previous) * covered;
            previous = level;
            if (amount < 1) {
                continue;
            }
            List<UUID> contestants = new ArrayList<>();
            for (UUID id : live) {
                if (invested.getOrDefault(id, 0) >= level) {
                    contestants.add(id);
                }
            }
            if (contestants.isEmpty()) {
                continue;
            }
            List<UUID> winners = rankSeats(table, contestants, gameId);
            if (winners.isEmpty()) {
                continue;
            }
            leftover = winners.get(0);
            announceWinners(table, winners);
            payEven(table, manager, flights, winners, amount);
        }
        // Whatever the levels could not split in whole coins goes to one seat.
        if (leftover != null) {
            WagerEngine.get().sweepPot(table, Bukkit.getPlayer(leftover), leftover, flights, "pot remainder");
        }
        UUID tableId = table.getId();
        manager.flushPiles(table, flights, () -> {
            Table still = TableManager.get().table(tableId);
            if (still != null) {
                finishHand(still);
            }
        });
    }

    private static Map<UUID, Integer> investedByOwner(Table table, TableManager manager) {
        return WagerEngine.get().totalsExcept(table, table.getId());
    }

    private static List<UUID> rankSeats(Table table, List<UUID> contestants, String gameId) {
        if (contestants.size() == 1) {
            return new ArrayList<>(contestants);
        }
        HoldemRank.Score best = HoldemRank.Score.none();
        List<UUID> tied = new ArrayList<>();
        for (UUID id : contestants) {
            HoldemRank.Score score = HoldemRank.best(gameId, cardsOf(table.handOf(id)));
            if (tied.isEmpty() || score.compareTo(best) > 0) {
                best = score;
                tied.clear();
                tied.add(id);
            } else if (score.compareTo(best) == 0) {
                tied.add(id);
            }
        }
        return seatOrderLeftOfButton(table, tied);
    }

    /** Split one pot level between winners, as evenly as the coins on the felt allow. */
    private static void payEven(Table table, TableManager manager, List<PayoutFlight> flights,
            List<UUID> winners, int amount) {
        if (amount < 1 || winners.isEmpty()) {
            return;
        }
        int n = winners.size();
        int share = amount / n;
        int remnant = amount % n;
        for (UUID winner : winners) {
            int need = share;
            if (remnant > 0) {
                need++;
                remnant--;
            }
            if (need < 1) {
                continue;
            }
            WagerEngine.get().payFromPot(table, Bukkit.getPlayer(winner), winner, need, flights, "pot");
        }
    }

    private void finishHand(Table table) {
        TableManager.get().endSession(table);
        passButton(table);
        TableManager.get().refreshLabel(table);
    }

    private static List<UUID> seatOrderLeftOfButton(Table table, List<UUID> include) {
        List<UUID> ordered = new ArrayList<>();
        List<UUID> seats = new ArrayList<>(table.actives());
        if (seats.isEmpty() || include == null || include.isEmpty()) {
            return ordered;
        }
        Set<UUID> want = new HashSet<>(include);
        int button = seats.indexOf(table.dealerId());
        if (button < 0) {
            button = seats.size() - 1;
        }
        for (int step = 1; step <= seats.size(); step++) {
            UUID id = seats.get((button + step) % seats.size());
            if (want.contains(id)) {
                ordered.add(id);
            }
        }
        return ordered;
    }

    private static List<Card> cardsOf(List<HandCard> held) {
        List<Card> cards = new ArrayList<>();
        if (held == null) {
            return cards;
        }
        for (HandCard card : held) {
            if (card != null && card.card() != null) {
                cards.add(card.card());
            }
        }
        return cards;
    }

    /** Everyone else folded, so the whole pot is the last player's. */
    private void foldWin(Table table, UUID winner) {
        if (winner != null) {
            tellSeated(table, Messages.get("draw.win_fold", "name", RpNames.of(winner)));
        }
        TableManager manager = TableManager.get();
        List<PayoutFlight> flights = new ArrayList<>();
        Player dest = winner != null ? Bukkit.getPlayer(winner) : null;
        if (dest != null) {
            WagerEngine.get().sweepPot(table, dest, winner, flights, "fold win");
        } else {
            // With nobody left to win it, every stake goes back where it came from.
            WagerEngine.get().returnStakes(table, flights, "hand abandoned");
        }
        UUID tableId = table.getId();
        manager.flushPiles(table, flights, () -> {
            Table still = TableManager.get().table(tableId);
            if (still != null) {
                finishHand(still);
            }
        });
    }

    private static void tellSeated(Table table, String message) {
        for (Player player : seatedOnline(table)) {
            player.sendMessage(message);
        }
    }
}
