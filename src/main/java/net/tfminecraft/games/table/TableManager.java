package net.tfminecraft.games.table;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.joml.Vector3f;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import me.Plugins.TLibs.TLibs;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.card.Card;
import net.tfminecraft.games.card.CardNames;
import net.tfminecraft.games.deck.Deck;
import net.tfminecraft.games.display.DisplayManager;
import net.tfminecraft.games.display.DisplayPose;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.gui.GameSelectGui;
import net.tfminecraft.games.gui.GuiSounds;
import net.tfminecraft.games.game.Game;
import net.tfminecraft.games.game.GamesRegistry;
import net.tfminecraft.games.guild.GuildTables;
import net.tfminecraft.games.select.CardSelector;
import net.tfminecraft.games.layout.HandAnchor;
import net.tfminecraft.games.layout.HandLayout;
import net.tfminecraft.games.layout.RevealLayout;
import net.tfminecraft.games.layout.StackLayout;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.layout.TablePileLayout;
import net.tfminecraft.games.loader.CardLoader;
import net.tfminecraft.games.utils.BodyYaw;
import net.tfminecraft.games.wager.ChipItems;
import net.tfminecraft.games.wager.PotLayout;
import net.tfminecraft.games.wager.PotPile;
import net.tfminecraft.games.wager.WagerChat;
import net.tfminecraft.games.wager.WagerPileStyle;
import net.tfminecraft.games.wager.WagerVote;
import net.tfminecraft.games.voice.RpNames;

public final class TableManager implements Listener {

    private static final TableManager INSTANCE = new TableManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long PLACE_TIMEOUT_MS = 30_000L;
    private static final double MIN_DISTANCE = 1.0;
    private static final double FELT_REACH = 5.0;
    private static final double TABLE_Y_SLOP = 3.0;
    private static final double PLACE_TOP_MIN = 0.9;

    private static final long SELECT_COOLDOWN_MS = 200L;
    private static final float INSPECT_BUMP = 0.22f;
    private static final long INSPECT_TICKS = 5L;

    private final Map<UUID, Table> tables = new HashMap<>();
    private final Map<UUID, PlaceArm> arms = new HashMap<>();
    private final Map<UUID, LootArm> lootArms = new HashMap<>();
    private final Map<UUID, Long> selectCooldown = new HashMap<>();
    private final Map<UUID, HandLock> handLocks = new HashMap<>();
    private final Map<UUID, Long> layoutHoldUntil = new HashMap<>();
    private final Map<UUID, Integer> selectAnimGen = new HashMap<>();
    private final Set<UUID> revealedHands = new HashSet<>();
    private final Set<UUID> revealedCardTokens = new HashSet<>();
    private final Set<UUID> revealBusy = new HashSet<>();
    private final Map<UUID, Integer> revealGen = new HashMap<>();
    private final Map<UUID, List<UUID>> revealExtras = new HashMap<>();
    private final Map<UUID, Integer> dealGen = new HashMap<>();
    private final Map<UUID, List<UUID>> dealCouriers = new HashMap<>();
    private final Map<UUID, List<Card>> dealPendingCards = new HashMap<>();
    private final Set<UUID> tableDealing = new HashSet<>();
    private BukkitTask handClock;
    private int handTicks;

    private TableManager() {}

    public static TableManager get() {
        return INSTANCE;
    }

    public void startClock() {
        stopClock();
        if (Games.plugin == null) {
            return;
        }
        handClock = Bukkit.getScheduler().runTaskTimer(Games.plugin, this::tickHands, 0L, 1L);
    }

    public void stopClock() {
        if (handClock != null) {
            handClock.cancel();
            handClock = null;
        }
    }

    private void tickHands() {
        handTicks++;
        for (Table table : tables.values()) {
            Map<UUID, List<HandCard>> hands = table.getHands();
            if (hands.isEmpty()) {
                continue;
            }
            for (UUID playerId : new ArrayList<>(hands.keySet())) {
                List<HandCard> hand = hands.get(playerId);
                if (hand == null || hand.isEmpty()) {
                    continue;
                }
                Player player = Bukkit.getPlayer(playerId);
                if (player == null || !player.isOnline()) {
                    continue;
                }
                leaveIfAtTable(player, false);
                List<HandCard> still = table.getHands().get(playerId);
                if (still == null || still.isEmpty()) {
                    continue;
                }
                if (handTicks % 3 == 0 && anyPublic(still) && showRevealDust(table)) {
                    spawnRevealDust(player, still);
                }
                if (layoutHeld(playerId)) {
                    continue;
                }
                layoutHand(table, player, Cache.handFollowTicks, false, null);
            }
        }
    }

    private void spawnRevealDust(Player owner, List<HandCard> hand) {
        DisplayManager displays = DisplayManager.get();
        HandLock lock = handLocks.get(owner.getUniqueId());
        double yawRad = lock != null ? Math.toRadians(lock.placeYaw()) : Math.toRadians(BodyYaw.of(owner));
        double aheadX = -Math.sin(yawRad) * Cache.handRevealDust;
        double aheadZ = Math.cos(yawRad) * Cache.handRevealDust;
        for (HandCard held : hand) {
            if (!revealedCardTokens.contains(held.tokenId())) {
                continue;
            }
            Location at = displays.worldLocation(held.tokenId());
            if (at == null || at.getWorld() == null || !at.getWorld().equals(owner.getWorld())) {
                continue;
            }
            if (Cache.handRevealDust > 0f) {
                at.add(aheadX, 0, aheadZ);
            }
            Particle.DustOptions dust = new Particle.DustOptions(CardNames.suitDust(held.card()), 0.8f);
            owner.spawnParticle(Particle.DUST, at.getX(), at.getY(), at.getZ(), 1, 0, 0, 0, 0, dust);
        }
    }

    private void clearRevealed(UUID playerId, List<HandCard> hand) {
        revealedHands.remove(playerId);
        if (hand == null) {
            return;
        }
        for (HandCard held : hand) {
            revealedCardTokens.remove(held.tokenId());
        }
    }

    public void armPlace(Player player, String gameId) {
        armPlace(player, gameId, true);
    }

    public void armPlace(Player player, String gameId, boolean requireDeck) {
        armPlace(player, gameId, requireDeck, null);
    }

    public void armPlace(Player player, String gameId, boolean requireDeck, TableHouse house) {
        arms.put(player.getUniqueId(),
                new PlaceArm(gameId, System.currentTimeMillis() + PLACE_TIMEOUT_MS, requireDeck, house));
    }

    public void selectGame(Player player, String gameId, Location at, boolean requireDeck) {
        armPlace(player, gameId, requireDeck);
        if (at != null) {
            tryPlace(player, at);
            return;
        }
        player.sendMessage(Messages.get(requireDeck ? "place.armed" : "place.armed_admin"));
    }

    public void loadAll() {
        File folder = tablesFolder();
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
                TableData data = GSON.fromJson(reader, TableData.class);
                if (data == null || data.id == null) {
                    continue;
                }
                Table table = fromData(data);
                if (table == null) {
                    continue;
                }
                tables.put(table.getId(), table);
                try {
                    boolean stale = (data.actives != null && !data.actives.isEmpty())
                            || (data.piles != null && !data.piles.isEmpty());
                    if (stale) {
                        resetTableToIdle(table, table.getOrigin());
                    }
                    spawnWorld(table);
                } catch (RuntimeException ex) {
                    despawnWorld(table);
                    tables.remove(table.getId());
                    Games.plugin.getLogger().warning("[Games] Failed to spawn loaded table "
                            + file.getName() + ": " + ex.getMessage());
                }
            } catch (IOException ex) {
                Games.plugin.getLogger().warning("[Games] Failed to load table " + file.getName() + ": " + ex.getMessage());
            }
        }
        if (Cache.debug) {
            Games.plugin.getLogger().info("[Games] Debug: loaded tables=" + tables.size());
        }
    }

    public void despawnWorldAll() {
        for (Table table : tables.values()) {
            resetTableToIdle(table, table.getOrigin());
            despawnWorld(table);
        }
    }

    /**
     * End the session, settle money, muck cards, full idle deck. Table file stays.
     */
    private void resetTableToIdle(Table table, Location dropAt) {
        if (table == null) {
            return;
        }
        cancelVote(table, null);
        cancelLootArmsForTable(table.getId());
        table.bumpRecycleGen();
        table.bumpPayoutGen();
        table.clearSession();
        Game removed = gameOf(table);
        if (removed != null) {
            removed.onTableRemoved(table);
        }
        for (UUID playerId : new ArrayList<>(table.getHands().keySet())) {
            discardPlayerCards(table, playerId);
        }
        despawnTablePiles(table, true);
        settleAutoTray(table, dropAt);
        clearFeltNow(table, null);
        table.actives().clear();
        table.setDealerId(null);
        table.setStreet(1);
        if (table.getDeck() != null) {
            table.getDeck().reshuffleAll();
        }
        save(table);
    }

    public void rebuildAllStacks() {
        for (Table table : tables.values()) {
            try {
                rebuildCardStacks(table);
            } catch (RuntimeException ex) {
                Games.plugin.getLogger().warning("[Games] Failed to rebuild table stack: " + ex.getMessage());
            }
        }
    }

    /** Despawn in-hand displays and put those cards on the discard pile. Hands are not persisted. */
    public void wipeHands() {
        for (Table table : tables.values()) {
            for (UUID playerId : new ArrayList<>(table.getHands().keySet())) {
                stopDeal(playerId, table);
            }
            DisplayManager displays = DisplayManager.get();
            for (List<HandCard> hand : new ArrayList<>(table.getHands().values())) {
                for (HandCard held : hand) {
                    table.getDeck().discard(held.card());
                    displays.despawn(held.tokenId());
                }
            }
            table.getHands().clear();
            despawnTablePiles(table, true);
            save(table);
            try {
                rebuildCardStacks(table);
                recycleIfNeeded(table, null);
            } catch (RuntimeException ex) {
                Games.plugin.getLogger().warning("[Games] Failed to rebuild table stack: " + ex.getMessage());
            }
        }
        revealedHands.clear();
        revealedCardTokens.clear();
        revealBusy.clear();
        dealGen.clear();
        for (List<UUID> extras : revealExtras.values()) {
            for (UUID extra : extras) {
                DisplayManager.get().despawn(extra);
            }
        }
        revealExtras.clear();
    }

    public boolean tryPlace(Player player, Location at) {
        PlaceArm arm = arms.get(player.getUniqueId());
        if (arm == null) {
            return false;
        }
        if (at == null || at.getWorld() == null) {
            return false;
        }
        if (System.currentTimeMillis() > arm.expiresAt) {
            arms.remove(player.getUniqueId());
            player.sendMessage(Messages.get("place.expired"));
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (arm.requireDeck) {
            if (!TLibs.getItemAPI().getChecker().checkItemWithPath(held, CardLoader.getDeckItem())) {
                player.sendMessage(Messages.get("place.need_deck"));
                return true;
            }
        }
        Location origin = at.clone();
        origin.add(0, Cache.tableYOffset, 0);
        origin.setYaw(player.getLocation().getYaw());
        origin.setPitch(0f);
        if (tooClose(origin)) {
            player.sendMessage(Messages.get("place.too_close"));
            return true;
        }
        Optional<Deck> created = Deck.create(Cache.cardSetOf(arm.gameId));
        if (created.isEmpty()) {
            player.sendMessage(Messages.get("place.no_deck"));
            return true;
        }
        Deck deck = created.get();
        deck.shuffle();
        Table table = new Table(UUID.randomUUID(), arm.gameId, origin, player.getLocation().getYaw(), deck);
        TableHouse house = arm.house;
        if (house == null && "blackjack".equalsIgnoreCase(arm.gameId)) {
            house = TableHouse.forPlace(player, Cache.layoutOf("blackjack"));
            GuildTables.stampGuild(player, house);
        }
        if (house != null) {
            if ("blackjack".equalsIgnoreCase(arm.gameId)) {
                String refuse = GuildTables.refuseKey(player, house, null);
                if (refuse != null) {
                    arms.remove(player.getUniqueId());
                    GuiSounds.deny(player);
                    GuildTables.tellRefuse(player, refuse, house);
                    return true;
                }
            }
            house.apply(table);
        } else {
            table.setOwnerPlayer(player.getUniqueId());
            TableLayout layout = Cache.layoutOf(arm.gameId);
            if (layout != null) {
                table.setSmallBlind(layout.smallBlind());
                table.setBigBlind(layout.bigBlind());
            }
        }
        try {
            spawnWorld(table);
        } catch (RuntimeException ex) {
            despawnWorld(table);
            Games.plugin.getLogger().warning("[Games] Failed to spawn table: " + ex.getMessage());
            player.sendMessage(Messages.get("place.spawn_failed"));
            return true;
        }
        tables.put(table.getId(), table);
        save(table);
        if (arm.requireDeck) {
            consumeOne(held, player);
        }
        arms.remove(player.getUniqueId());
        player.sendMessage(Messages.get("place.done", "game", table.getGameId()));
        return true;
    }

    @EventHandler
    public void onInteractBlock(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            if (tryInspectCard(player)) {
                event.setCancelled(true);
            }
            return;
        }
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) {
            return;
        }
        Location click = clickHit(event);
        if (arms.containsKey(player.getUniqueId())) {
            if (action != Action.RIGHT_CLICK_BLOCK) {
                return;
            }
            Location origin = tablePlaceOrigin(event);
            if (origin == null) {
                player.sendMessage(Messages.get("place.need_surface"));
                return;
            }
            event.setCancelled(true);
            tryPlace(player, origin);
            return;
        }
        if (trySelectCard(player)) {
            event.setCancelled(true);
            return;
        }
        if (tryLootPlace(player, click)) {
            event.setCancelled(true);
            return;
        }
        if (tryPlaceChip(player, click)) {
            event.setCancelled(true);
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!TLibs.getItemAPI().getChecker().checkItemWithPath(held, CardLoader.getDeckItem())) {
            return;
        }
        if (tableHolding(player.getUniqueId()) != null) {
            return;
        }
        Location origin = tablePlaceOrigin(event);
        if (origin == null) {
            return;
        }
        event.setCancelled(true);
        GameSelectGui.open(player, true, origin);
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return;
        }
        if (arms.remove(event.getPlayer().getUniqueId()) != null) {
            event.getPlayer().sendMessage(Messages.get("place.cancelled"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        arms.remove(event.getPlayer().getUniqueId());
        clearLootArm(event.getPlayer().getUniqueId(), false);
        onWagerQuit(event.getPlayer());
        leaveIfAtTable(event.getPlayer(), true);
        clearDealer(event.getPlayer());
        selectCooldown.remove(event.getPlayer().getUniqueId());
        handLocks.remove(event.getPlayer().getUniqueId());
        layoutHoldUntil.remove(event.getPlayer().getUniqueId());
        selectAnimGen.remove(event.getPlayer().getUniqueId());
        clearRevealed(event.getPlayer().getUniqueId(), null);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        Table table = tableHolding(player.getUniqueId());
        if (table == null) {
            return;
        }
        List<HandCard> hand = table.getHands().get(player.getUniqueId());
        if (hand == null || hand.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        if (!allowRevealToggle(table, player)) {
            return;
        }
        UUID id = player.getUniqueId();
        if (revealBusy.contains(id)) {
            return;
        }
        playCardSound(player);
        List<HandCard> band = revealBand(hand);
        boolean show = !anyPublic(band);
        startRevealSequence(table, player, hand, band, show);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Table table = tableFrom(event.getRightClicked());
        if (table == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (arms.containsKey(player.getUniqueId())) {
            return;
        }
        markSelectCooldown(player);
        if (player.isSneaking()) {
            if (tryOpenHouseOptions(table, player)) {
                return;
            }
            tryManualFlush(table, player);
            return;
        }
        if (table.live()) {
            Game game = gameOf(table);
            if (game != null && allowReturnSelected(table, player)) {
                int n = countSelected(table, player);
                if (n > 0 && tryReturnSelected(table, player)) {
                    game.onReturnedSelected(table, player, n);
                    return;
                }
            }
            if (game != null) {
                game.onShoeClick(table, player);
            }
            return;
        }
        Game idleGame = gameOf(table);
        if (idleGame != null && idleGame.tryClaimDealer(table, player)) {
            return;
        }
        if (!allowReturnSelected(table, player) && !allowFreeDraw(table, player)) {
            return;
        }
        if (allowReturnSelected(table, player) && tryReturnSelected(table, player)) {
            return;
        }
        if (!allowFreeDraw(table, player)) {
            return;
        }
        tryDraw(player, table);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayChat(AsyncPlayerChatEvent event) {
        String action = playWord(event.getMessage());
        if (action == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!isPlayActor(player)) {
            return;
        }
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(Games.plugin, () -> applyPlayCall(player, action));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHitEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        Table table = tableFrom(event.getEntity());
        if (table == null) {
            return;
        }
        event.setCancelled(true);
        pickup(player, table);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        int cx = event.getChunk().getX();
        int cz = event.getChunk().getZ();
        for (Table table : tables.values()) {
            Location origin = table.getOrigin();
            if (origin.getWorld() == null || !origin.getWorld().equals(world)) {
                continue;
            }
            if ((origin.getBlockX() >> 4) != cx || (origin.getBlockZ() >> 4) != cz) {
                continue;
            }
            ensureAnchors(table);
            rebuildCardStacks(table);
            rebuildTablePiles(table);
            rebuildPiles(table);
        }
    }

    private void pickup(Player player, Table table) {
        cancelVote(table, "wager.cancelled");
        table.bumpRecycleGen();
        cancelLootArmsForTable(table.getId());
        Location dropAt = table.getOrigin().clone();
        settleAutoTray(table, dropAt);
        clearFeltNow(table, player);
        returnAllHands(table, false, false);
        table.actives().clear();
        table.clearSession();
        table.setDealerId(null);
        Game removed = gameOf(table);
        if (removed != null) {
            removed.onTableRemoved(table);
        }
        String guildId = table.ownerGuildId();
        despawnWorld(table);
        tables.remove(table.getId());
        deleteFile(table.getId());
        ItemStack deckItem = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getDeckItem());
        if (deckItem != null && dropAt.getWorld() != null) {
            dropAt.getWorld().dropItemNaturally(dropAt, deckItem);
        }
        player.sendMessage(Messages.get("place.picked_up"));
        refreshGuildAutoIdle(guildId);
    }

    private void spawnWorld(Table table) {
        Location origin = table.getOrigin();
        if (origin.getWorld() == null) {
            return;
        }
        rebuildCardStacks(table);
        rebuildTablePiles(table);
        rebuildPiles(table);
        ensureAnchors(table);
        Game ready = gameOf(table);
        if (ready != null) {
            ready.onTableReady(table);
        }
    }

    private void ensureAnchors(Table table) {
        Location origin = table.getOrigin();
        if (origin.getWorld() == null) {
            return;
        }
        Entity interaction = table.getInteractionId() != null ? Bukkit.getEntity(table.getInteractionId()) : null;
        if (interaction == null || interaction.isDead()) {
            var spawned = WorldAnchors.spawnInteraction(
                    origin.clone(), 0.55f, 0.2f, table.getId().toString(), table.getId().toString());
            table.setInteractionId(spawned != null ? spawned.getUniqueId() : null);
        }
        Entity label = table.getLabelId() != null ? Bukkit.getEntity(table.getLabelId()) : null;
        if (label == null || label.isDead()) {
            var spawned = WorldAnchors.spawnLabel(origin.clone().add(0, 0.25, 0), tableLabel(table));
            table.setLabelId(spawned != null ? spawned.getUniqueId() : null);
        } else {
            WorldAnchors.setText(table.getLabelId(), tableLabel(table));
        }
    }

    public void refreshLabel(Table table) {
        if (table == null) {
            return;
        }
        if (table.getLabelId() == null) {
            ensureAnchors(table);
            return;
        }
        WorldAnchors.setText(table.getLabelId(), tableLabel(table));
    }

    private static String tableLabel(Table table) {
        String base = Cache.labelOf(table.getGameId());
        StringBuilder text = new StringBuilder(Messages.get("label.title", "name", base));
        boolean auto = table.autoDealer();
        UUID dealer = table.dealerId();
        if ("blackjack".equalsIgnoreCase(table.getGameId())) {
            text.append("\n").append(Messages.get(table.shufflePolicy() == ShufflePolicy.ROUND
                    ? "label.shuffle_round" : "label.shuffle_shoe"));
        }
        String guildName = GuildTables.displayName(table.ownerGuildId());
        if (guildName != null) {
            text.append("\n").append(Messages.get("label.owner", "name", guildName));
        }
        Game game = gameOf(table);
        boolean stockDealer = game == null || game.showStockDealer();
        if (stockDealer) {
            if (auto) {
                text.append("\n").append(Messages.get("label.dealer", "name", "Auto"));
            } else if (dealer != null) {
                text.append("\n").append(Messages.get("label.dealer", "name", RpNames.of(dealer)));
            }
        }
        if (table.minBet() > 0 || table.maxBet() > 0) {
            String min = table.minBet() > 0 ? String.valueOf(table.minBet()) : "-";
            String max = table.maxBet() > 0 ? String.valueOf(table.maxBet()) : "-";
            text.append("\n").append(Messages.get("label.limits", "min", min, "max", max));
        }
        if (table.betOpen()) {
            text.append("\n").append(Messages.get("label.open"));
        }
        if (table.autoCountdown() > 0) {
            String seconds = String.valueOf(table.autoCountdown());
            String key = "label.countdown";
            if (table.live()) {
                key = "label.countdown_round";
            } else if (table.betOpen()) {
                key = "label.countdown_bets";
            }
            text.append("\n").append(Messages.get(key, "seconds", seconds));
        }
        if (game != null) {
            String extra = game.extraLabel(table);
            if (extra != null && !extra.isBlank()) {
                text.append("\n").append(extra);
            }
        }
        return text.toString();
    }

    private void clearDealer(Player player) {
        UUID id = player.getUniqueId();
        for (Table table : tables.values()) {
            if (id.equals(table.dealerId())) {
                table.setDealerId(null);
                Game game = gameOf(table);
                if (game != null) {
                    game.onTableReady(table);
                } else {
                    refreshLabel(table);
                }
            }
        }
    }

    void rebuildCardStacks(Table table) {
        rebuildShoeStack(table);
        rebuildDiscardStack(table);
    }

    void rebuildStack(Table table) {
        rebuildCardStacks(table);
    }

    private void rebuildShoeStack(Table table) {
        DisplayManager displays = DisplayManager.get();
        for (UUID token : table.getStackTokens()) {
            displays.despawn(token);
        }
        table.getStackTokens().clear();
        ItemStack back = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getBackItem());
        if (back == null) {
            return;
        }
        int layers = StackLayout.visibleLayers(table.getDeck().remaining(), table.getDeck().size());
        Location origin = table.getOrigin();
        DisplayPose pose = DisplayPose.flatOnTable(Cache.cardScale, 0f, table.getYaw());
        for (int i = 0; i < layers; i++) {
            UUID tokenId = stackTokenId(table.getId(), i);
            Location layerOrigin = origin.clone().add(0, i * Cache.stackLayerGap, 0);
            if (!displays.spawn(tokenId, layerOrigin, back, pose)) {
                throw new IllegalStateException("Failed to spawn stack layer " + i);
            }
            table.getStackTokens().add(tokenId);
        }
    }

    private void rebuildDiscardStack(Table table) {
        DisplayManager displays = DisplayManager.get();
        for (UUID token : table.getDiscardTokens()) {
            displays.despawn(token);
        }
        table.getDiscardTokens().clear();
        ItemStack back = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getBackItem());
        if (back == null || table.getDeck().discarded() <= 0) {
            return;
        }
        int layers = StackLayout.visibleLayers(table.getDeck().discarded(), table.getDeck().size());
        Location origin = discardOrigin(table);
        DisplayPose pose = DisplayPose.flatOnTable(Cache.cardScale, 0f, table.getYaw());
        for (int i = 0; i < layers; i++) {
            UUID tokenId = discardTokenId(table.getId(), i);
            Location layerOrigin = origin.clone().add(0, i * Cache.stackLayerGap, 0);
            if (!displays.spawn(tokenId, layerOrigin, back, pose)) {
                throw new IllegalStateException("Failed to spawn discard layer " + i);
            }
            table.getDiscardTokens().add(tokenId);
        }
    }

    private void rebuildTablePiles(Table table) {
        DisplayManager displays = DisplayManager.get();
        ItemStack back = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getBackItem());
        Location origin = table.getOrigin();
        for (Map.Entry<String, List<HandCard>> entry : table.tablePiles().entrySet()) {
            List<HandCard> pile = entry.getValue();
            if (pile == null || pile.isEmpty()) {
                continue;
            }
            int n = pile.size();
            for (int i = 0; i < n; i++) {
                HandCard held = pile.get(i);
                DisplayPose pose = pileSlot(table, entry.getKey(), i, n, held.faceUp());
                ItemStack item = back;
                if (held.faceUp()) {
                    ItemStack face = TLibs.getItemAPI().getCreator().getItemFromPath(held.card().getItem());
                    if (face != null) {
                        item = face;
                    }
                }
                if (displays.worldLocation(held.tokenId()) == null) {
                    if (item == null || !displays.spawn(held.tokenId(), origin, item, pose)) {
                        continue;
                    }
                } else {
                    displays.setTransform(held.tokenId(), pose, 0);
                    if (item != null) {
                        displays.setItem(held.tokenId(), item);
                    }
                }
            }
        }
        notifyTablePiles(table);
    }

    private void layoutTablePile(Table table, String pile, int durationTicks) {
        List<HandCard> cards = table.tablePile(pile);
        int n = cards.size();
        DisplayManager displays = DisplayManager.get();
        for (int i = 0; i < n; i++) {
            HandCard held = cards.get(i);
            displays.setTransform(held.tokenId(), pileSlot(table, pile, i, n, held.faceUp()),
                    durationTicks);
        }
        notifyTablePiles(table);
    }

    private void despawnTablePiles(Table table, boolean discardCards) {
        table.bumpTableDealGen();
        DisplayManager displays = DisplayManager.get();
        for (List<HandCard> pile : table.tablePiles().values()) {
            if (pile == null) {
                continue;
            }
            for (HandCard held : pile) {
                displays.despawn(held.tokenId());
                if (discardCards) {
                    table.getDeck().discard(held.card());
                }
            }
        }
        table.tablePiles().clear();
        tableDealing.remove(table.getId());
        notifyTablePiles(table);
    }

    private boolean drawOneToTable(Table table, String pile, boolean faceUp, Runnable after) {
        ItemStack back = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getBackItem());
        if (back == null) {
            return false;
        }
        if (table.getDeck().remaining() == 0) {
            if (table.getDeck().discarded() == 0) {
                return false;
            }
            if (table.isRecycling()) {
                return false;
            }
            UUID tableId = table.getId();
            int gen = table.tableDealGen();
            recycleIfNeeded(table, () -> {
                Table live = tables.get(tableId);
                if (live == null || live.tableDealGen() != gen) {
                    tableDealing.remove(tableId);
                    return;
                }
                drawOneToTable(live, pile, faceUp, after);
            });
            return true;
        }
        int dealGen = table.tableDealGen();
        Optional<Card> drawn = table.getDeck().draw();
        if (drawn.isEmpty()) {
            return false;
        }
        Card card = drawn.get();
        ItemStack face = TLibs.getItemAPI().getCreator().getItemFromPath(card.getItem());
        List<HandCard> cards = table.tablePile(pile);
        int destIndex = cards.size();
        int destCount = destIndex + 1;
        rebuildCardStacks(table);
        save(table);
        DisplayPose end = pileSlot(table, pile, destIndex, destCount, faceUp);
        ItemStack show = faceUp && face != null ? face : back;
        if (Cache.handDealTicks <= 0) {
            if (table.tableDealGen() != dealGen) {
                table.getDeck().discard(card);
                rebuildCardStacks(table);
                save(table);
                return false;
            }
            UUID tokenId = UUID.randomUUID();
            if (show == null || !DisplayManager.get().spawn(tokenId, table.getOrigin(), show, end)) {
                table.getDeck().discard(card);
                rebuildCardStacks(table);
                save(table);
                return false;
            }
            HandCard held = new HandCard(card, tokenId, faceUp);
            cards.add(held);
            layoutTablePile(table, pile, 0);
            save(table);
            playCardSound(table.getOrigin());
            if (after != null) {
                Bukkit.getScheduler().runTaskLater(Games.plugin, after, 1L);
            }
            return true;
        }
        float stackTopY = stackTopOffset(StackLayout.visibleLayers(table.getDeck().remaining(),
                table.getDeck().size()));
        Location courierOrigin = table.getOrigin().clone().add(0, stackTopY, 0);
        DisplayPose start = DisplayPose.flatOnTable(Cache.cardScale, 0f, table.getYaw());
        UUID courierId = UUID.randomUUID();
        if (!DisplayManager.get().spawn(courierId, courierOrigin, show != null ? show : back, start)) {
            table.getDeck().discard(card);
            rebuildCardStacks(table);
            save(table);
            return false;
        }
        playCardSound(table.getOrigin());
        DisplayPose endFromShoe = poseOnStackOrigin(end, stackTopY);
        UUID tableId = table.getId();
        flyTableCourier(tableId, courierId, start, endFromShoe, () -> {
            Table still = tables.get(tableId);
            DisplayManager.get().despawn(courierId);
            if (still == null) {
                tableDealing.remove(tableId);
                return;
            }
            if (still.tableDealGen() != dealGen) {
                still.getDeck().discard(card);
                tableDealing.remove(tableId);
                rebuildCardStacks(still);
                save(still);
                return;
            }
            UUID tokenId = UUID.randomUUID();
            if (show == null || !DisplayManager.get().spawn(tokenId, still.getOrigin(), show, end)) {
                still.getDeck().discard(card);
                rebuildCardStacks(still);
                save(still);
                return;
            }
            still.tablePile(pile).add(new HandCard(card, tokenId, faceUp));
            layoutTablePile(still, pile, 0);
            save(still);
            if (after != null) {
                after.run();
            }
        });
        return true;
    }

    private void flyTableCourier(UUID tableId, UUID courierId, DisplayPose start, DisplayPose end, Runnable onArrive) {
        DisplayManager displays = DisplayManager.get();
        displays.setTransform(courierId, start, 0);
        int ticks = Math.max(1, Cache.handDealTicks);
        Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
            if (tables.get(tableId) == null) {
                displays.despawn(courierId);
                return;
            }
            for (int step = 1; step <= ticks; step++) {
                final int s = step;
                Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
                    if (tables.get(tableId) == null) {
                        displays.despawn(courierId);
                        return;
                    }
                    float t = s / (float) ticks;
                    float ease = 1f - (1f - t) * (1f - t);
                    displays.setTransform(courierId, lerpPose(start, end, ease), 1);
                    if (s == ticks) {
                        onArrive.run();
                    }
                }, s);
            }
        }, 1L);
    }

    static Location discardOrigin(Table table) {
        Location origin = table.getOrigin().clone();
        double yawRad = Math.toRadians(table.getYaw());
        double fx = -Math.sin(yawRad);
        double fz = Math.cos(yawRad);
        double rx = fz;
        double rz = -fx;
        origin.add(rx * Cache.tableDiscardOffset, 0, rz * Cache.tableDiscardOffset);
        return origin;
    }

    public void reshuffleFull(Table table) {
        if (table == null || table.getDeck() == null) {
            return;
        }
        table.getDeck().reshuffleAll();
        rebuildCardStacks(table);
        save(table);
    }

    private void recycleIfNeeded(Table table, Runnable after) {
        if (table == null) {
            return;
        }
        boolean blackjack = "blackjack".equalsIgnoreCase(table.getGameId());
        if (blackjack && table.shufflePolicy() == ShufflePolicy.ROUND) {
            if (after != null) {
                after.run();
            }
            return;
        }
        boolean emptyShoe = table.getDeck().remaining() == 0;
        boolean idle = table.getHands().isEmpty() && table.tablePilesEmpty();
        boolean should = table.getDeck().discarded() > 0 && emptyShoe;
        if (blackjack && table.shufflePolicy() == ShufflePolicy.SHOE) {
            if (!should) {
                if (after != null) {
                    after.run();
                }
                return;
            }
            startRecycle(table, after);
            return;
        }
        if (table.getDeck().discarded() == 0 || (!emptyShoe && !idle)) {
            if (after != null) {
                after.run();
            }
            return;
        }
        startRecycle(table, after);
    }

    private void startRecycle(Table table, Runnable after) {
        if (table.isRecycling()) {
            return;
        }
        table.setRecycling(true);
        int gen = table.bumpRecycleGen();
        UUID tableId = table.getId();
        int ticks = Cache.tableRecycleTicks;
        List<UUID> tokens = new ArrayList<>(table.getDiscardTokens());
        if (ticks <= 0 || tokens.isEmpty()) {
            finishRecycle(table, gen, after);
            return;
        }
        Location shoe = table.getOrigin();
        Location discard = discardOrigin(table);
        float dx = (float) (shoe.getX() - discard.getX());
        float dz = (float) (shoe.getZ() - discard.getZ());
        DisplayPose start = DisplayPose.flatOnTable(Cache.cardScale, 0f, table.getYaw());
        DisplayPose end = start.withTranslation(dx, 0f, dz);
        DisplayManager displays = DisplayManager.get();
        UUID last = tokens.get(tokens.size() - 1);
        for (UUID token : tokens) {
            displays.setTransform(token, start, 0);
        }
        Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
            Table still = tables.get(tableId);
            if (still == null || still.recycleGen() != gen) {
                return;
            }
            for (int step = 1; step <= ticks; step++) {
                final int s = step;
                Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
                    Table live = tables.get(tableId);
                    if (live == null || live.recycleGen() != gen) {
                        return;
                    }
                    float t = s / (float) ticks;
                    float ease = 1f - (1f - t) * (1f - t);
                    DisplayPose pose = lerpPose(start, end, ease);
                    for (UUID token : tokens) {
                        displays.setTransform(token, pose, 1);
                    }
                    if (s == ticks) {
                        displays.setTransform(last, pose, 1);
                        finishRecycle(live, gen, after);
                    }
                }, s);
            }
        }, 1L);
    }

    private void finishRecycle(Table table, int gen, Runnable after) {
        if (table == null || table.recycleGen() != gen) {
            return;
        }
        table.setRecycling(false);
        table.getDeck().recycle();
        rebuildCardStacks(table);
        save(table);
        if (after != null) {
            after.run();
        }
    }

    private void despawnWorld(Table table) {
        table.bumpRecycleGen();
        table.setRecycling(false);
        despawnHands(table);
        despawnTablePiles(table, true);
        despawnPiles(table);
        for (UUID token : table.getStackTokens()) {
            DisplayManager.get().despawn(token);
        }
        table.getStackTokens().clear();
        for (UUID token : table.getDiscardTokens()) {
            DisplayManager.get().despawn(token);
        }
        table.getDiscardTokens().clear();
        WorldAnchors.remove(table.getInteractionId());
        WorldAnchors.remove(table.getLabelId());
        table.setInteractionId(null);
        table.setLabelId(null);
        tableDealing.remove(table.getId());
    }

    public void proposeLoot(Player player, int denars) {
        Table table = tableWhereActive(player.getUniqueId());
        if (table == null) {
            player.sendMessage(Messages.get("wager.no_table"));
            return;
        }
        if (table.getVote() != null || lootArms.containsKey(player.getUniqueId())) {
            player.sendMessage(Messages.get("wager.busy"));
            return;
        }
        if (table.isPaying()) {
            player.sendMessage(Messages.get("wager.paying"));
            return;
        }
        if ("blackjack".equalsIgnoreCase(table.getGameId())) {
            player.sendMessage(Messages.get("wager.coins_only"));
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType() == org.bukkit.Material.AIR || held.getAmount() <= 0) {
            player.sendMessage(Messages.get("wager.need_item"));
            return;
        }
        if (ChipItems.isChip(held) || (ChipItems.isChipKind(held) && !ChipItems.needsDeclaredValue(held))) {
            player.sendMessage(Messages.get("wager.use_click"));
            return;
        }
        if (Cache.wagerMinPlayers >= 2 && table.actives().size() < Cache.wagerMinPlayers) {
            player.sendMessage(Messages.get("wager.need_players"));
            return;
        }
        ItemStack snapshot = held.clone();
        Set<UUID> eligible = eligibleVoters(table, player.getUniqueId());
        if (eligible.isEmpty()) {
            messageActives(table, Messages.get("wager.accepted", "player", player.getName()));
            armLootPlace(player, table, snapshot, denars);
            return;
        }
        WagerVote vote = new WagerVote(player.getUniqueId(), snapshot, denars);
        vote.eligible().addAll(eligible);
        table.setVote(vote);
        UUID tableId = table.getId();
        vote.setExpireTask(Bukkit.getScheduler().runTaskLater(Games.plugin, () -> expireVote(tableId),
                Cache.wagerVoteSeconds * 20L));
        broadcastProposed(table, player.getName(), snapshot, denars);
    }

    public void commitStreet(Player player) {
        Table table = tableWhereActive(player.getUniqueId());
        if (table == null) {
            player.sendMessage(Messages.get("wager.no_table"));
            return;
        }
        if (table.getVote() != null) {
            player.sendMessage(Messages.get("wager.busy"));
            return;
        }
        if (table.isPaying()) {
            player.sendMessage(Messages.get("wager.paying"));
            return;
        }
        UUID owner = player.getUniqueId();
        int street = table.street();
        int value = 0;
        for (PotPile pile : table.getPiles()) {
            if (owner.equals(pile.ownerId()) && pile.streetId() == street) {
                value += pile.contribution();
            }
        }
        Game game = GamesRegistry.of(table.getGameId());
        int need = game != null ? Math.max(0, game.denarsToMatch(table, player)) : 0;
        if (value < need) {
            refundStreet(table, player, street);
            save(table);
            if (game != null) {
                game.onStreetCommit(table, player, value, true);
            }
            player.sendMessage(Messages.get("wager.folded"));
            return;
        }
        if (game != null) {
            game.onStreetCommit(table, player, value, false);
        }
        player.sendMessage(Messages.get("wager.committed",
                "value", String.valueOf(value),
                "need", String.valueOf(need)));
    }

    public Table tableNear(Player player) {
        FeltHit felt = findFelt(player, null);
        return felt != null ? felt.table() : null;
    }

    /** Felt look, else nearest shoe origin within that game's leave-distance. */
    public Table tableNearby(Player player) {
        Table felt = tableNear(player);
        if (felt != null) {
            return felt;
        }
        if (player == null || player.getWorld() == null) {
            return null;
        }
        Location loc = player.getLocation();
        Table best = null;
        double bestDist = Double.MAX_VALUE;
        for (Table table : tables.values()) {
            if (!atTable(player, table)) {
                continue;
            }
            double dist = table.getOrigin().distance(loc);
            if (dist < bestDist) {
                best = table;
                bestDist = dist;
            }
        }
        return best;
    }

    public Table table(UUID id) {
        return id == null ? null : tables.get(id);
    }

    public Collection<Table> tables() {
        return Collections.unmodifiableCollection(tables.values());
    }

    public boolean hasNonDealerOwnedPile(Table table) {
        if (table == null) {
            return false;
        }
        UUID dealer = table.dealerId();
        for (PotPile pile : table.getPiles()) {
            UUID owner = pile.ownerId();
            if (owner == null || owner.equals(dealer)) {
                continue;
            }
            if (pile.pieces() > 0 || pile.count() > 0) {
                return true;
            }
        }
        return false;
    }

    /** True if a player box (non-tray) has at least minBet on the felt. */
    public boolean hasLegalBlackjackBox(Table table) {
        if (table == null) {
            return false;
        }
        int min = table.minBet();
        UUID dealer = table.dealerId();
        UUID house = table.getId();
        HashSet<UUID> seen = new HashSet<>();
        for (PotPile pile : table.getPiles()) {
            UUID owner = pile.ownerId();
            if (owner == null || owner.equals(dealer) || owner.equals(house) || isTrayPile(table, pile)) {
                continue;
            }
            if (!seen.add(owner)) {
                continue;
            }
            if (ownedDenars(table, owner) >= min) {
                return true;
            }
        }
        return false;
    }

    public int ownedDenars(Table table, UUID owner) {
        if (table == null || owner == null) {
            return 0;
        }
        int sum = 0;
        for (PotPile pile : table.getPiles()) {
            if (owner.equals(pile.ownerId()) && !isTrayPile(table, pile)) {
                sum += pile.contribution();
            }
        }
        return sum;
    }

    public Location boxLocation(Table table, UUID owner) {
        if (table == null) {
            return null;
        }
        TableLayout layout = Cache.layoutOf(table.getGameId());
        Location fallback = layout != null ? layout.feltCenter(table) : table.getOrigin().clone();
        if (owner == null) {
            return fallback;
        }
        double x = 0;
        double z = 0;
        int n = 0;
        for (PotPile pile : table.getPiles()) {
            if (!owner.equals(pile.ownerId()) || isTrayPile(table, pile)) {
                continue;
            }
            x += pile.x();
            z += pile.z();
            n++;
        }
        if (n < 1) {
            Player online = owner != null ? Bukkit.getPlayer(owner) : null;
            if (online != null && online.isOnline()) {
                Location pad = betPadCenter(table, online);
                if (pad != null) {
                    return pad;
                }
            }
            return fallback;
        }
        Location at = table.getOrigin().clone();
        at.setX(x / n);
        at.setZ(z / n);
        return at;
    }

    public List<PotPile> detachDenars(Table table, Predicate<PotPile> filter, int need) {
        List<PotPile> detached = new ArrayList<>();
        if (table == null || filter == null || need < 1) {
            return detached;
        }
        int taken = 0;
        List<PotPile> candidates = new ArrayList<>();
        for (PotPile pile : table.getPiles()) {
            if (filter.test(pile) && pile.denars() > 0 && pile.count() > 0) {
                candidates.add(pile);
            }
        }
        candidates.sort((a, b) -> Integer.compare(b.denars(), a.denars()));
        for (PotPile pile : candidates) {
            int peel = 0;
            int peelPieces = 0;
            while (pile.count() > 0 && pile.denars() <= need - taken) {
                int per = Math.max(1, pile.pieces() / Math.max(1, pile.count()));
                pile.setCount(pile.count() - 1);
                pile.setPieces(Math.max(0, pile.pieces() - per));
                peel++;
                peelPieces += per;
                taken += pile.denars();
            }
            if (peel < 1) {
                continue;
            }
            if (pile.count() < 1) {
                pile.setCount(peel);
                pile.setPieces(peelPieces);
                rebuildPile(table, pile);
                detached.add(pile);
            } else {
                rebuildPile(table, pile);
                ItemStack one = pile.item() != null ? pile.item().clone() : null;
                if (one != null) {
                    one.setAmount(1);
                }
                PotPile split = new PotPile(pile.ownerId(), one, pile.typeKey(), pile.denars(), peel, pile.x(),
                        pile.z());
                split.setPieces(peelPieces);
                split.setStreetId(pile.streetId());
                if (rebuildPile(table, split)) {
                    table.getPiles().add(split);
                    detached.add(split);
                }
            }
            if (taken >= need) {
                break;
            }
        }
        save(table);
        return detached;
    }

    public List<PotPile> spawnStoredPiles(Table table, UUID owner, ItemStack template, int denars, Location at) {
        List<PotPile> spawned = new ArrayList<>();
        if (table == null || owner == null || template == null || denars < 1) {
            return spawned;
        }
        int unit = chipDenars(template);
        if (unit < 1) {
            return spawned;
        }
        int n = denars / unit;
        if (n < 1) {
            return spawned;
        }
        ItemStack one = template.clone();
        one.setAmount(1);
        Location hit = at != null ? at.clone() : table.getOrigin().clone();
        hit.setY(table.getOrigin().getY());
        int piece = Math.max(1, chipPieces(one));
        String type = ChipItems.typeKey(one);
        if (inTrayZone(table, hit)) {
            spawned.addAll(placeTrayChips(table, owner, one, type, unit, n, piece));
            save(table);
            notifyFeltPiles(table);
            return spawned;
        }
        PotPile pile = new PotPile(owner, one.clone(), type, unit, n, hit.getX(), hit.getZ());
        pile.setPieces(n * piece);
        pile.setStreetId(table.street());
        if (rebuildPile(table, pile)) {
            table.getPiles().add(pile);
            spawned.add(pile);
            playChipSound(table, hit);
        }
        save(table);
        notifyFeltPiles(table);
        return spawned;
    }

    private List<PotPile> placeTrayChips(Table table, UUID owner, ItemStack one, String type, int unit, int count,
            int piece) {
        List<PotPile> spawned = new ArrayList<>();
        if (count < 1) {
            return spawned;
        }
        WagerPileStyle style = ChipItems.pileStyle(one);
        int left = count;
        int leftPieces = count * Math.max(1, piece);
        int per = Math.max(1, piece);
        while (left > 0) {
            PotPile merge = trayMerge(table, owner, type, unit, style);
            if (merge != null) {
                int room = PotLayout.room(merge.pieces(), style);
                if (room < 1) {
                    break;
                }
                int addPieces = Math.min(room, leftPieces);
                int addCount = Math.min(left, Math.max(1, addPieces / per));
                addPieces = addCount * per;
                merge.addCount(addCount);
                merge.addPieces(addPieces);
                rebuildPile(table, merge);
                left -= addCount;
                leftPieces -= addPieces;
                spawned.add(merge);
                continue;
            }
            Location slot = nextTraySlot(table);
            if (slot == null) {
                break;
            }
            int room = Math.max(1, PotLayout.room(0, style));
            int addPieces = Math.min(room, leftPieces);
            int addCount = Math.min(left, Math.max(1, addPieces / per));
            addPieces = addCount * per;
            PotPile pile = new PotPile(owner, one.clone(), type, unit, addCount, slot.getX(), slot.getZ());
            pile.setPieces(addPieces);
            pile.setStreetId(table.street());
            if (!rebuildPile(table, pile)) {
                despawnPile(pile);
                break;
            }
            table.getPiles().add(pile);
            spawned.add(pile);
            playChipSound(table, slot);
            left -= addCount;
            leftPieces -= addPieces;
        }
        return spawned;
    }

    private PotPile trayMerge(Table table, UUID owner, String type, int unit, WagerPileStyle style) {
        PotPile best = null;
        int bestRoom = 0;
        for (PotPile pile : table.getPiles()) {
            if (!isTrayPile(table, pile) || !owner.equals(pile.ownerId())) {
                continue;
            }
            if (!type.equals(pile.typeKey()) || pile.denars() != unit) {
                continue;
            }
            int room = PotLayout.room(pile.pieces(), style);
            if (room > bestRoom) {
                best = pile;
                bestRoom = room;
            }
        }
        return best;
    }

    private Location nextTraySlot(Table table) {
        TableLayout layout = Cache.layoutOf(table.getGameId());
        Location tray = layout != null ? layout.trayLocation(table) : null;
        if (tray == null) {
            return table.getOrigin().clone();
        }
        for (int i = 0; i < 80; i++) {
            Location slot = trayGridAt(table, layout, tray, i);
            if (slot == null || !inTrayZone(table, slot)) {
                continue;
            }
            if (!traySlotTaken(table, slot)) {
                return slot;
            }
        }
        return tray.clone();
    }

    private static Location trayGridAt(Table table, TableLayout layout, Location tray, int index) {
        if (index <= 0) {
            return tray.clone();
        }
        TableLayout.PileSlot origin = layout.pile("tray");
        if (origin == null) {
            return tray.clone();
        }
        double step = Math.max(0.12, Cache.wagerMergeRange);
        int x = 0;
        int z = 0;
        int dx = 0;
        int dz = -1;
        for (int n = 0; n < index; n++) {
            if (x == z || (x < 0 && x == -z) || (x > 0 && x == 1 - z)) {
                int t = dx;
                dx = -dz;
                dz = t;
            }
            x += dx;
            z += dz;
        }
        return TableLayout.fromLocal(table, origin.forward() + z * step, origin.right() + x * step);
    }

    private boolean traySlotTaken(Table table, Location slot) {
        double max = Cache.wagerMergeRange;
        double maxSq = max * max;
        for (PotPile pile : table.getPiles()) {
            if (!isTrayPile(table, pile)) {
                continue;
            }
            double dx = pile.x() - slot.getX();
            double dz = pile.z() - slot.getZ();
            if (dx * dx + dz * dz <= maxSq) {
                return true;
            }
        }
        return false;
    }

    public ItemStack feltItem(Table table, UUID owner) {
        if (table == null || owner == null) {
            return null;
        }
        for (PotPile pile : table.getPiles()) {
            if (owner.equals(pile.ownerId()) && !isTrayPile(table, pile) && pile.item() != null && pile.count() > 0) {
                ItemStack one = pile.item().clone();
                one.setAmount(1);
                return one;
            }
        }
        return null;
    }

    public boolean placeChipsFromInventory(Table table, Player player, int need) {
        if (table == null || player == null || !player.isOnline() || need < 1) {
            return false;
        }
        if (inventoryChipDenars(player) < need) {
            return false;
        }
        Location at = boxLocation(table, player.getUniqueId());
        if (at == null) {
            return false;
        }
        int taken = 0;
        ItemStack template = null;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            while (stack != null && stack.getAmount() > 0 && taken < need) {
                int value = chipDenars(stack);
                if (value < 1 || value > need - taken) {
                    break;
                }
                ItemStack one = stack.clone();
                one.setAmount(1);
                int pieces = chipPieces(stack);
                if (!depositPieces(table, at, player.getUniqueId(), one, ChipItems.typeKey(one),
                        ChipItems.pileStyle(one), value, 1, pieces)) {
                    break;
                }
                if (template == null) {
                    template = one;
                }
                taken += value;
                playChipSound(table, at);
                if (stack.getAmount() <= 1) {
                    player.getInventory().setItem(i, null);
                } else {
                    stack.setAmount(stack.getAmount() - 1);
                }
                stack = player.getInventory().getItem(i);
            }
            if (taken >= need) {
                break;
            }
        }
        save(table);
        if (taken >= need) {
            notifyChipIn(table, player, taken, template);
            return true;
        }
        return false;
    }

    private static int inventoryChipDenars(Player player) {
        int sum = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            int value = chipDenars(stack);
            if (value > 0 && stack != null) {
                sum += value * stack.getAmount();
            }
        }
        return sum;
    }

    private static int chipDenars(ItemStack stack) {
        if (stack == null) {
            return 0;
        }
        ChipItems.DecoChips deco = ChipItems.decoChips(stack);
        if (deco != null) {
            return deco.denars();
        }
        OptionalInt whole = ChipItems.integerDenars(stack);
        return whole.isPresent() ? whole.getAsInt() : 0;
    }

    private static int chipPieces(ItemStack stack) {
        ChipItems.DecoChips deco = ChipItems.decoChips(stack);
        if (deco != null) {
            return Math.max(1, deco.pieces());
        }
        return 1;
    }

    public int takeDenarsFromInventory(Player player, int need) {
        if (player == null || !player.isOnline() || need < 1) {
            return 0;
        }
        int taken = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            OptionalInt value = ChipItems.integerDenars(stack);
            if (value.isEmpty() || stack.getAmount() < 1) {
                continue;
            }
            int each = value.getAsInt();
            if (each < 1) {
                continue;
            }
            int can = stack.getAmount();
            while (can > 0 && each <= need - taken) {
                can--;
                taken += each;
            }
            if (can <= 0) {
                player.getInventory().setItem(i, null);
            } else if (can != stack.getAmount()) {
                stack.setAmount(can);
            }
            if (taken >= need) {
                break;
            }
        }
        return taken;
    }

    public boolean spawnPayChips(Table table, UUID owner, Location at, int denars) {
        if (table == null || owner == null || denars < 1) {
            return true;
        }
        ItemStack one = stubChipItem();
        if (one == null) {
            Player dest = Bukkit.getPlayer(owner);
            return dest != null && dest.isOnline() && giveStubToInventory(dest, denars);
        }
        OptionalInt each = ChipItems.integerDenars(one);
        int unit = each.isPresent() && each.getAsInt() > 0 ? each.getAsInt() : 1;
        int count = denars / unit;
        int leftover = denars - count * unit;
        Location hit = at != null ? at : table.getOrigin().clone();
        hit.setY(table.getOrigin().getY());
        boolean ok = true;
        if (count > 0) {
            String type = ChipItems.typeKey(one);
            PotPile pile = new PotPile(owner, one.clone(), type, unit, count, hit.getX(), hit.getZ());
            pile.setPieces(count);
            pile.setStreetId(table.street());
            if (rebuildPile(table, pile)) {
                table.getPiles().add(pile);
            } else {
                ok = false;
                Player dest = Bukkit.getPlayer(owner);
                if (dest != null && dest.isOnline()) {
                    ItemStack give = one.clone();
                    give.setAmount(count);
                    dest.getInventory().addItem(give);
                    ok = true;
                }
            }
        }
        save(table);
        if (leftover > 0) {
            Player dest = Bukkit.getPlayer(owner);
            if (dest == null || !dest.isOnline() || !giveStubToInventory(dest, leftover)) {
                ok = false;
            }
        }
        return ok;
    }

    private static ItemStack stubChipItem() {
        if (Cache.wagerGold == null || Cache.wagerGold.item() == null || Cache.wagerGold.item().isBlank()) {
            return null;
        }
        ItemStack item = TLibs.getItemAPI().getCreator().getItemFromPath(Cache.wagerGold.item());
        if (item == null) {
            return null;
        }
        ItemStack one = item.clone();
        one.setAmount(1);
        return one;
    }

    private static boolean giveStubToInventory(Player dest, int denars) {
        ItemStack one = stubChipItem();
        if (one == null || denars < 1) {
            return false;
        }
        OptionalInt each = ChipItems.integerDenars(one);
        int unit = each.isPresent() && each.getAsInt() > 0 ? each.getAsInt() : 1;
        int count = Math.max(1, denars / unit);
        ItemStack give = one.clone();
        give.setAmount(count);
        dest.getInventory().addItem(give);
        return true;
    }

    public void beginSession(Table table) {
        if (table == null || table.live()) {
            return;
        }
        if (!GuildTables.canStartGuildAutoRound(table)) {
            return;
        }
        table.startSession();
        Game game = gameOf(table);
        if (game != null) {
            game.onSessionStart(table);
        }
    }

    public void tryBeginSession(Table table) {
        if (table == null || table.live()) {
            return;
        }
        Game game = gameOf(table);
        int min = game != null ? game.minActives() : 0;
        if (min < 1 || table.actives().size() < min) {
            return;
        }
        beginSession(table);
    }

    private static void notifyChipIn(Table table, Player player) {
        notifyChipIn(table, player, 0, null);
    }

    private static void notifyChipIn(Table table, Player player, int denars, ItemStack item) {
        Game game = gameOf(table);
        if (game != null) {
            game.onChipIn(table, player, denars, item);
        }
    }

    public void dealToPlayer(Table table, Player player, int n) {
        dealToPlayer(table, player, n, 0, null);
    }

    public void dealToPlayer(Table table, Player player, int n, Runnable after) {
        dealToPlayer(table, player, n, 0, after);
    }

    public void dealToPlayer(Table table, Player player, int n, int slot, Runnable after) {
        if (table == null || player == null || n < 1) {
            if (after != null) {
                after.run();
            }
            return;
        }
        dealRemaining(table, player, n, Math.max(0, slot), after);
    }

    public void dealToTable(Table table, String name, int n, boolean faceUp) {
        dealToTable(table, name, n, faceUp, null);
    }

    public void dealToTable(Table table, String name, int n, boolean faceUp, Runnable after) {
        if (table == null || name == null || name.isBlank() || n < 1) {
            if (after != null) {
                after.run();
            }
            return;
        }
        String pile = name.toLowerCase(java.util.Locale.ROOT);
        UUID tableId = table.getId();
        int gen = table.tableDealGen();
        if (tableDealing.contains(tableId)) {
            Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
                Table still = tables.get(tableId);
                if (still != null && still.tableDealGen() == gen) {
                    dealToTable(still, pile, n, faceUp, after);
                } else if (after != null) {
                    after.run();
                }
            }, 2L);
            return;
        }
        dealTableRemaining(table, pile, n, faceUp, gen, after);
    }

    public void revealTablePile(Table table, String name) {
        if (table == null || name == null || name.isBlank()) {
            return;
        }
        String pile = name.toLowerCase(java.util.Locale.ROOT);
        List<HandCard> cards = table.tablePiles().get(pile);
        if (cards == null) {
            return;
        }
        for (HandCard held : cards) {
            held.setFaceUp(true);
        }
        rebuildTablePiles(table);
        notifyTablePiles(table);
    }

    private void dealTableRemaining(Table table, String pile, int left, boolean faceUp, int gen, Runnable after) {
        if (table == null || table.tableDealGen() != gen) {
            if (table != null) {
                tableDealing.remove(table.getId());
            }
            if (after != null) {
                after.run();
            }
            return;
        }
        UUID tableId = table.getId();
        if (left < 1) {
            tableDealing.remove(tableId);
            if (after != null) {
                after.run();
            }
            return;
        }
        tableDealing.add(tableId);
        boolean started = drawOneToTable(table, pile, faceUp, () -> {
            Table still = tables.get(tableId);
            if (still != null && still.tableDealGen() == gen) {
                dealTableRemaining(still, pile, left - 1, faceUp, gen, after);
            } else {
                tableDealing.remove(tableId);
                if (after != null) {
                    after.run();
                }
            }
        });
        if (!started) {
            tableDealing.remove(tableId);
            if (after != null) {
                after.run();
            }
        }
    }

    public boolean moveHandCardToTablePile(Table table, Player player, int cardIndex, String pileName, boolean faceUp) {
        if (table == null || player == null || pileName == null || pileName.isBlank()) {
            return false;
        }
        List<HandCard> hand = table.getHands().get(player.getUniqueId());
        if (hand == null || cardIndex < 0 || cardIndex >= hand.size()) {
            return false;
        }
        HandCard held = hand.remove(cardIndex);
        held.setSelected(false);
        held.setFaceUp(faceUp);
        String pile = pileName.toLowerCase(java.util.Locale.ROOT);
        table.tablePile(pile).add(held);
        DisplayManager displays = DisplayManager.get();
        displays.setLayoutOwner(held.tokenId(), null);
        displays.clearItemFor(held.tokenId(), player);
        ItemStack back = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getBackItem());
        ItemStack face = TLibs.getItemAPI().getCreator().getItemFromPath(held.card().getItem());
        ItemStack show = faceUp && face != null ? face : back;
        if (show != null) {
            displays.setItem(held.tokenId(), show);
        }
        layoutTablePile(table, pile, Cache.interpolationTicks);
        layoutHand(table, player, Cache.interpolationTicks, true, null);
        save(table);
        return true;
    }

    public boolean moveTablePileCard(Table table, String fromName, int cardIndex, String toName, boolean faceUp) {
        if (table == null || fromName == null || fromName.isBlank() || toName == null || toName.isBlank()) {
            return false;
        }
        String fromPile = fromName.toLowerCase(java.util.Locale.ROOT);
        String toPile = toName.toLowerCase(java.util.Locale.ROOT);
        List<HandCard> from = table.tablePiles().get(fromPile);
        if (from == null || cardIndex < 0 || cardIndex >= from.size()) {
            return false;
        }
        HandCard held = from.remove(cardIndex);
        held.setSelected(false);
        held.setFaceUp(faceUp);
        table.tablePile(toPile).add(held);
        DisplayManager displays = DisplayManager.get();
        displays.setLayoutOwner(held.tokenId(), null);
        ItemStack back = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getBackItem());
        ItemStack face = TLibs.getItemAPI().getCreator().getItemFromPath(held.card().getItem());
        ItemStack show = faceUp && face != null ? face : back;
        if (show != null) {
            displays.setItem(held.tokenId(), show);
        }
        layoutTablePile(table, fromPile, Cache.interpolationTicks);
        layoutTablePile(table, toPile, Cache.interpolationTicks);
        save(table);
        notifyTablePiles(table);
        return true;
    }

    public void relayoutHand(Table table, Player player) {
        if (table == null || player == null) {
            return;
        }
        layoutHand(table, player, Cache.interpolationTicks, true, null);
    }

    public void publishHand(Table table, Player player) {
        if (table == null || player == null) {
            return;
        }
        DisplayManager displays = DisplayManager.get();
        for (HandCard held : table.handOf(player.getUniqueId())) {
            ItemStack face = TLibs.getItemAPI().getCreator().getItemFromPath(held.card().getItem());
            revealedCardTokens.add(held.tokenId());
            held.setFaceUp(true);
            if (face != null) {
                displays.setItem(held.tokenId(), face);
            }
            displays.clearItemFor(held.tokenId(), player);
        }
        layoutHand(table, player, Cache.handFollowTicks, false, null);
        syncRevealedHands(player.getUniqueId(), table.handOf(player.getUniqueId()));
    }

    public void muckPlayer(Table table, Player player) {
        if (table == null || player == null) {
            return;
        }
        muckPlayer(table, player.getUniqueId());
    }

    public void muckPlayer(Table table, UUID playerId) {
        if (table == null || playerId == null) {
            return;
        }
        discardPlayerCards(table, playerId);
        rebuildCardStacks(table);
        save(table);
        recycleIfNeeded(table, null);
    }

    public void muckTable(Table table, String name) {
        if (table == null || name == null || name.isBlank()) {
            return;
        }
        String pile = name.toLowerCase(java.util.Locale.ROOT);
        List<HandCard> cards = table.tablePiles().get(pile);
        table.bumpTableDealGen();
        tableDealing.remove(table.getId());
        if (cards != null) {
            DisplayManager displays = DisplayManager.get();
            for (HandCard held : cards) {
                displays.despawn(held.tokenId());
                table.getDeck().discard(held.card());
            }
            table.tablePiles().remove(pile);
        }
        rebuildCardStacks(table);
        save(table);
        notifyTablePiles(table);
        recycleIfNeeded(table, null);
    }

    public void endSession(Table table) {
        if (table == null) {
            return;
        }
        table.clearSession();
        despawnTablePiles(table, true);
        rebuildCardStacks(table);
        save(table);
        recycleIfNeeded(table, null);
        Game game = gameOf(table);
        if (game != null) {
            game.onSessionEnd(table);
        }
    }

    private void dealRemaining(Table table, Player player, int left, int slot, Runnable after) {
        if (left < 1 || table == null || player == null || !player.isOnline()) {
            if (after != null) {
                after.run();
            }
            return;
        }
        UUID tableId = table.getId();
        UUID playerId = player.getUniqueId();
        if (revealBusy.contains(playerId)) {
            Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
                Table still = tables.get(tableId);
                Player online = Bukkit.getPlayer(playerId);
                if (still != null && online != null) {
                    dealRemaining(still, online, left, slot, after);
                } else if (after != null) {
                    after.run();
                }
            }, 1L);
            return;
        }
        boolean started = drawOneToPlayer(table, player, slot, () -> {
            Table still = tables.get(tableId);
            Player online = Bukkit.getPlayer(playerId);
            if (still != null && online != null) {
                dealRemaining(still, online, left - 1, slot, after);
            } else if (after != null) {
                after.run();
            }
        });
        if (!started && after != null) {
            after.run();
        }
    }

    public boolean canEditHouse(Player player, Table table) {
        if (player == null || table == null) {
            return false;
        }
        if (player.hasPermission(TableHouse.STAFF_PERM) || player.hasPermission("games.admin")) {
            return true;
        }
        UUID owner = table.ownerPlayer();
        return owner != null && owner.equals(player.getUniqueId());
    }

    private void refreshGuildAutoIdle(String guildId) {
        if (guildId == null || guildId.isBlank()) {
            return;
        }
        for (Table table : tables.values()) {
            if (table.live() || !guildId.equals(table.ownerGuildId())) {
                continue;
            }
            Game game = gameOf(table);
            if (game != null) {
                game.onTableReady(table);
            } else {
                refreshLabel(table);
            }
        }
    }

    public void persistHouseChange(Table table) {
        if (table == null) {
            return;
        }
        save(table);
        refreshLabel(table);
        Game game = gameOf(table);
        if (game != null) {
            game.onTableReady(table);
        }
        refreshGuildAutoIdle(table.ownerGuildId());
    }

    public void applyHouse(Table table, TableHouse house) {
        if (table == null || house == null) {
            return;
        }
        house.apply(table);
        persistHouseChange(table);
    }

    private boolean tryOpenHouseOptions(Table table, Player player) {
        if (table == null || player == null) {
            return false;
        }
        String gameId = table.getGameId();
        boolean blackjack = "blackjack".equalsIgnoreCase(gameId);
        boolean poker = "poker".equalsIgnoreCase(gameId);
        if (!blackjack && !poker) {
            return false;
        }
        if (table.live()) {
            player.sendMessage(Messages.get("place.options_live"));
            return true;
        }
        if (!canEditHouse(player, table)) {
            if (poker) {
                return false;
            }
            player.sendMessage(Messages.get("place.options_denied"));
            return true;
        }
        net.tfminecraft.games.gui.TableOptionsGui.openEdit(player, table);
        return true;
    }

    private static void applyHouseData(Table table, TableData data) {
        TableLayout layout = Cache.layoutOf(table.getGameId());
        if (data.ownerPlayer != null) {
            try {
                table.setOwnerPlayer(UUID.fromString(data.ownerPlayer));
            } catch (IllegalArgumentException ignored) {
                // keep null
            }
        }
        table.setOwnerGuildId(data.ownerGuildId);
        if (data.autoDealer == null) {
            boolean yaml = layout != null && layout.autoDealer();
            table.setAutoDealer(yaml);
            table.setStaffMint(yaml);
            if (layout != null) {
                table.setMinBet(layout.minBet());
                table.setMaxBet(layout.maxBet());
                table.setMaxBoxes(layout.maxBoxes());
            }
        } else {
            table.setAutoDealer(data.autoDealer);
            table.setStaffMint(Boolean.TRUE.equals(data.staffMint));
            table.setMinBet(data.minBet);
            table.setMaxBet(data.maxBet);
            table.setMaxBoxes(data.maxBoxes);
        }
        table.setShufflePolicy(ShufflePolicy.parse(data.shufflePolicy));
        if (data.smallBlind == null && data.bigBlind == null) {
            if (layout != null) {
                table.setSmallBlind(layout.smallBlind());
                table.setBigBlind(layout.bigBlind());
            }
        } else {
            table.setSmallBlind(data.smallBlind != null ? data.smallBlind : 0);
            table.setBigBlind(data.bigBlind != null ? data.bigBlind : 0);
        }
    }

    private void tryManualFlush(Table table, Player player) {
        if (!allowManualPotFlush(table, player)) {
            player.sendMessage(Messages.get("wager.no_flush"));
            return;
        }
        payout(table, player);
    }

    private DisplayPose pileSlot(Table table, String pile, int index, int count, boolean faceUp) {
        Game game = gameOf(table);
        if (game != null) {
            return game.tablePileSlot(table, pile, index, count, faceUp);
        }
        return TablePileLayout.slot(table, pile, index, count, faceUp);
    }

    private void notifyTablePiles(Table table) {
        Game game = gameOf(table);
        if (game != null) {
            game.onTablePilesChanged(table);
        }
    }

    private void notifyFeltPiles(Table table) {
        Game game = gameOf(table);
        if (game != null) {
            game.onFeltPilesChanged(table);
        }
    }

    private static Game gameOf(Table table) {
        return table == null ? null : GamesRegistry.of(table.getGameId());
    }

    private static String playWord(String message) {
        if (message == null) {
            return null;
        }
        String raw = message.strip();
        if (raw.endsWith(".") || raw.endsWith("!")) {
            raw = raw.substring(0, raw.length() - 1).strip();
        }
        if (raw.isEmpty()) {
            return null;
        }
        String key = raw.toLowerCase(Locale.ROOT);
        if (key.equals("hit") || key.equals("stand") || key.equals("double") || key.equals("split")
                || key.equals("check") || key.equals("call") || key.equals("fold") || key.equals("raise")
                || key.equals("draw")) {
            return key;
        }
        return null;
    }

    private boolean isPlayActor(Player player) {
        if (player == null) {
            return false;
        }
        Table table = tableNearby(player);
        if (table == null || !table.live() || !player.getUniqueId().equals(table.actor())) {
            return false;
        }
        Game game = gameOf(table);
        return game != null && game.allowPlayChat(table, player);
    }

    public void applyPlayCall(Player player, String action) {
        if (player == null || action == null || !isPlayActor(player)) {
            return;
        }
        Table table = tableNearby(player);
        Game game = gameOf(table);
        if (game == null) {
            return;
        }
        switch (action) {
            case "hit" -> game.onBetHit(table, player);
            case "stand" -> game.onBetStand(table, player);
            case "double" -> game.onBetDouble(table, player);
            case "split" -> game.onBetSplit(table, player);
            default -> game.onPlayWord(table, player, action);
        }
    }

    private static boolean allowManualPotFlush(Table table, Player player) {
        Game game = gameOf(table);
        return game != null ? game.allowManualPotFlush(table, player) : !table.live();
    }

    private static boolean allowFreeDraw(Table table, Player player) {
        Game game = gameOf(table);
        return game != null ? game.allowFreeDraw(table, player) : !table.live();
    }

    private static boolean allowReturnSelected(Table table, Player player) {
        Game game = gameOf(table);
        return game != null ? game.allowReturnSelected(table, player) : !table.live();
    }

    private static boolean allowRevealToggle(Table table, Player player) {
        Game game = gameOf(table);
        return game == null || game.allowRevealToggle(table, player);
    }

    private static boolean showRevealDust(Table table) {
        Game game = gameOf(table);
        return game == null || game.showRevealDust(table);
    }

    public void payout(Table table, Player winner) {
        if (table == null) {
            return;
        }
        cancelVote(table, "wager.cancelled");
        cancelLootArmsForTable(table.getId());
        if (table.isPaying()) {
            if (winner != null && winner.isOnline()) {
                winner.sendMessage(Messages.get("wager.paying"));
            }
            return;
        }
        if (table.getPiles().isEmpty()) {
            messageActives(table, Messages.get("wager.empty"));
            return;
        }
        table.actives().clear();
        payoutPiles(table, winner, pile -> true);
    }

    public void payoutPiles(Table table, Player winner, Predicate<PotPile> filter) {
        if (table == null || filter == null) {
            return;
        }
        UUID dest = winner != null ? winner.getUniqueId() : null;
        List<PayoutFlight> assignments = new ArrayList<>();
        for (PotPile pile : table.getPiles()) {
            if (filter.test(pile)) {
                assignments.add(new PayoutFlight(pile, dest));
            }
        }
        flushPiles(table, assignments, null);
    }

    public void refundOwnedPiles(Table table, Player player) {
        if (player == null) {
            return;
        }
        UUID owner = player.getUniqueId();
        refundPiles(table, player, pile -> owner.equals(pile.ownerId()));
    }

    public void refundPiles(Table table, Player player, Predicate<PotPile> filter) {
        if (table == null || player == null || filter == null) {
            return;
        }
        UUID dest = player.getUniqueId();
        List<PayoutFlight> assignments = new ArrayList<>();
        for (PotPile pile : table.getPiles()) {
            if (filter.test(pile)) {
                assignments.add(new PayoutFlight(pile, dest));
            }
        }
        flushPiles(table, assignments, null);
    }

    public void flushPiles(Table table, List<PayoutFlight> assignments, Runnable onDone) {
        if (table == null) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        cancelVote(table, "wager.cancelled");
        cancelLootArmsForTable(table.getId());
        if (assignments == null || assignments.isEmpty()) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        if (table.isPaying()) {
            Set<UUID> told = new HashSet<>();
            for (PayoutFlight flight : assignments) {
                if (flight == null || flight.destId() == null || !told.add(flight.destId())) {
                    continue;
                }
                Player dest = Bukkit.getPlayer(flight.destId());
                if (dest != null && dest.isOnline()) {
                    dest.sendMessage(Messages.get("wager.paying"));
                }
            }
            return;
        }
        IdentityHashMap<PotPile, PayoutFlight> dests = new IdentityHashMap<>();
        for (PayoutFlight flight : assignments) {
            if (flight == null || flight.pile() == null) {
                continue;
            }
            dests.put(flight.pile(), flight);
        }
        if (dests.isEmpty()) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        List<PotPile> keep = new ArrayList<>();
        for (PotPile pile : table.getPiles()) {
            if (!dests.containsKey(pile)) {
                keep.add(pile);
            }
        }
        table.getPiles().clear();
        table.getPiles().addAll(keep);
        save(table);
        startPayoutFlight(table, new ArrayList<>(dests.values()), onDone);
    }

    public void setPilesCommunal(Table table, Predicate<PotPile> filter) {
        if (table == null || filter == null) {
            return;
        }
        for (PotPile pile : table.getPiles()) {
            if (filter.test(pile)) {
                pile.setOwnerId(null);
            }
        }
        save(table);
    }

    private void startPayoutFlight(Table table, List<PayoutFlight> snapshot, Runnable onDone) {
        int gen = table.beginPayout(snapshot, onDone);
        int ticks = Cache.wagerPayoutTicks;
        UUID tableId = table.getId();
        int delay = 0;
        for (PayoutFlight flight : new ArrayList<>(snapshot)) {
            PotPile pile = flight.pile();
            UUID destId = flight.destId();
            if (destId != null && !flight.stayOnTray()) {
                Player dest = Bukkit.getPlayer(destId);
                if (dest == null || !dest.isOnline()) {
                    finishPayoutPile(table, gen, pile);
                    continue;
                }
            }
            if (ticks <= 0) {
                finishPayoutPile(table, gen, pile);
                continue;
            }
            final int d = delay++;
            Bukkit.getScheduler().runTaskLater(Games.plugin, () -> flyPayoutPile(tableId, gen, pile, ticks), d);
        }
        if (table.payoutFlying().isEmpty()) {
            finishPayoutWave(table, gen);
        }
    }

    private void flyPayoutPile(UUID tableId, int gen, PotPile pile, int ticks) {
        Table table = tables.get(tableId);
        if (!payoutActive(table, gen) || pile == null) {
            return;
        }
        PayoutFlight flight = flightOf(table, pile);
        if (flight == null) {
            return;
        }
        Location dest = destLocation(table, flight);
        Location origin = table.getOrigin();
        WagerPileStyle style = ChipItems.pileStyle(pile.item());
        List<UUID> tokens = new ArrayList<>(pile.tokens());
        if (tokens.isEmpty()) {
            finishPayoutPile(table, gen, pile);
            return;
        }
        DisplayManager displays = DisplayManager.get();
        for (int layer = 0; layer < tokens.size(); layer++) {
            UUID token = tokens.get(layer);
            float yaw = layer < pile.layerYaws().size() ? pile.layerYaws().get(layer) : table.getYaw();
            DisplayPose start = chipPose(style, yaw);
            double layerY = origin.getY() + layer * style.layerGap();
            float dx = (float) (dest.getX() - pile.x());
            float dy = (float) (dest.getY() + 1.0 - layerY);
            float dz = (float) (dest.getZ() - pile.z());
            DisplayPose end = start.withTranslation(dx, dy, dz);
            displays.setTransform(token, start, 0);
            final int last = tokens.size() - 1;
            final int layerIndex = layer;
            Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
                if (!payoutActive(tables.get(tableId), gen)) {
                    return;
                }
                for (int step = 1; step <= ticks; step++) {
                    final int s = step;
                    Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
                        Table still = tables.get(tableId);
                        if (!payoutActive(still, gen)) {
                            return;
                        }
                        float t = s / (float) ticks;
                        float ease = 1f - (1f - t) * (1f - t);
                        displays.setTransform(token, lerpPose(start, end, ease), 1);
                        if (s == ticks && layerIndex == last) {
                            finishPayoutPile(still, gen, pile);
                        }
                    }, s);
                }
            }, 1L);
        }
    }

    private static Location destLocation(Table table, PayoutFlight flight) {
        if (flight.stayOnTray()) {
            TableLayout layout = Cache.layoutOf(table.getGameId());
            Location tray = layout != null ? layout.trayLocation(table) : null;
            if (tray != null) {
                return tray.clone();
            }
        }
        if (flight.destId() != null) {
            Player dest = Bukkit.getPlayer(flight.destId());
            if (dest != null && dest.isOnline()) {
                return dest.getLocation();
            }
        }
        return table.getOrigin();
    }

    private static PayoutFlight flightOf(Table table, PotPile pile) {
        for (PayoutFlight flight : table.payoutFlying()) {
            if (flight.pile() == pile) {
                return flight;
            }
        }
        return null;
    }

    private static boolean removeFlying(Table table, PotPile pile) {
        List<PayoutFlight> flying = table.payoutFlying();
        for (int i = 0; i < flying.size(); i++) {
            if (flying.get(i).pile() == pile) {
                flying.remove(i);
                return true;
            }
        }
        return false;
    }

    private void finishPayoutPile(Table table, int gen, PotPile pile) {
        if (!payoutActive(table, gen)) {
            return;
        }
        PayoutFlight flight = flightOf(table, pile);
        if (flight == null || !removeFlying(table, pile)) {
            return;
        }
        settleFlight(table, flight);
        if (table.payoutFlying().isEmpty()) {
            finishPayoutWave(table, gen);
        }
    }

    private void settleFlight(Table table, PayoutFlight flight) {
        PotPile pile = flight.pile();
        if (flight.stayOnTray()) {
            despawnPile(pile);
            ItemStack one = pile.item() != null ? pile.item().clone() : null;
            if (one != null) {
                one.setAmount(1);
                int piece = Math.max(1, pile.count() > 0 ? Math.max(1, pile.pieces() / pile.count()) : 1);
                int count = pile.count() > 0 ? pile.count() : pile.pieces();
                if (count > 0) {
                    placeTrayChips(table, table.getId(), one, pile.typeKey(), pile.denars(), count, piece);
                }
            }
            save(table);
            notifyFeltPiles(table);
            Location tray = destLocation(table, flight);
            playChipSound(table, tray);
            return;
        }
        UUID destId = flight.destId();
        Player dest = destId != null ? Bukkit.getPlayer(destId) : null;
        Location dropAt = dest != null && dest.isOnline() ? dest.getLocation() : table.getOrigin();
        despawnPile(pile);
        playChipSound(table, dropAt);
        if (destId == null) {
            return;
        }
        givePileItems(pile, dest, dropAt);
    }

    private void finishPayoutWave(Table table, int gen) {
        if (!payoutActive(table, gen)) {
            return;
        }
        Runnable onDone = table.takePayoutOnDone();
        LinkedHashSet<UUID> dests = new LinkedHashSet<>(table.payoutDests());
        table.endPayout();
        if (onDone == null) {
            for (UUID destId : dests) {
                Player dest = Bukkit.getPlayer(destId);
                if (dest != null && dest.isOnline()) {
                    dest.sendMessage(Messages.get("wager.paid", "player", dest.getName()));
                }
            }
        }
        if (onDone != null) {
            onDone.run();
        }
        notifyFeltPiles(table);
    }

    private static boolean payoutActive(Table table, int gen) {
        return table != null && table.isPaying() && table.payoutGen() == gen;
    }

    private void abortPayout(Table table) {
        if (!table.isPaying()) {
            return;
        }
        table.bumpPayoutGen();
        table.clearPayoutOnDone();
        for (PayoutFlight flight : new ArrayList<>(table.payoutFlying())) {
            settleFlight(table, flight);
        }
        table.endPayout();
    }

    public void voteWager(Player player, boolean accept) {
        Table table = tableForVote(player);
        if (table == null) {
            player.sendMessage(Messages.get("wager.no_vote"));
            return;
        }
        if (table.isPaying()) {
            player.sendMessage(Messages.get("wager.paying"));
            return;
        }
        WagerVote vote = table.getVote();
        UUID id = player.getUniqueId();
        if (id.equals(vote.proposerId())) {
            if (!accept) {
                cancelVote(table, "wager.cancelled");
            } else {
                player.sendMessage(Messages.get("wager.not_eligible"));
            }
            return;
        }
        if (!vote.eligible().contains(id)) {
            player.sendMessage(Messages.get("wager.not_eligible"));
            return;
        }
        if (vote.yes().contains(id) || vote.no().contains(id)) {
            player.sendMessage(Messages.get("wager.already_voted"));
            return;
        }
        if (accept) {
            vote.yes().add(id);
        } else {
            vote.no().add(id);
        }
        tryResolveVote(table);
    }

    private void onWagerQuit(Player player) {
        UUID id = player.getUniqueId();
        for (Table table : tables.values()) {
            WagerVote vote = table.getVote();
            if (vote == null) {
                continue;
            }
            if (id.equals(vote.proposerId())) {
                cancelVote(table, "wager.cancelled");
                continue;
            }
            if (vote.eligible().contains(id) && !vote.yes().contains(id) && !vote.no().contains(id)) {
                vote.no().add(id);
                tryResolveVote(table);
            }
        }
    }

    private void expireVote(UUID tableId) {
        Table table = tables.get(tableId);
        if (table == null) {
            return;
        }
        WagerVote vote = table.getVote();
        if (vote == null) {
            return;
        }
        failVote(table, "wager.expired");
    }

    private void tryResolveVote(Table table) {
        WagerVote vote = table.getVote();
        if (vote == null) {
            return;
        }
        if (vote.majorityYes()) {
            passVote(table);
            return;
        }
        if (vote.allVoted()) {
            failVote(table, "wager.declined");
        }
    }

    private void passVote(Table table) {
        WagerVote vote = table.getVote();
        if (vote == null) {
            return;
        }
        vote.cancelExpire();
        table.setVote(null);
        Player proposer = Bukkit.getPlayer(vote.proposerId());
        String name = proposer != null ? proposer.getName() : "Someone";
        if (proposer == null || !proposer.isOnline()) {
            messageActives(table, Messages.get("wager.declined", "player", name));
            return;
        }
        messageActives(table, Messages.get("wager.accepted", "player", name));
        armLootPlace(proposer, table, vote.item(), vote.denars());
    }

    private void failVote(Table table, String messageKey) {
        WagerVote vote = table.getVote();
        if (vote == null) {
            return;
        }
        vote.cancelExpire();
        table.setVote(null);
        Player proposer = Bukkit.getPlayer(vote.proposerId());
        String name = proposer != null ? proposer.getName() : "Someone";
        messageActives(table, Messages.get(messageKey, "player", name));
    }

    private void cancelVote(Table table, String messageKey) {
        WagerVote vote = table.getVote();
        if (vote == null) {
            return;
        }
        vote.cancelExpire();
        table.setVote(null);
        if (messageKey != null) {
            Player proposer = Bukkit.getPlayer(vote.proposerId());
            String name = proposer != null ? proposer.getName() : "Someone";
            messageActives(table, Messages.get(messageKey, "player", name));
        }
    }

    private void armLootPlace(Player player, Table table, ItemStack snapshot, int denars) {
        clearLootArm(player.getUniqueId(), false);
        ItemStack copy = snapshot.clone();
        UUID playerId = player.getUniqueId();
        UUID tableId = table.getId();
        int seconds = Math.max(1, Cache.wagerPlaceSeconds);
        BukkitTask expire = Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
            LootArm arm = lootArms.get(playerId);
            if (arm == null || !arm.tableId.equals(tableId)) {
                return;
            }
            clearLootArm(playerId, false);
            Player still = Bukkit.getPlayer(playerId);
            if (still != null && still.isOnline()) {
                still.sendMessage(Messages.get("wager.place_timeout"));
            }
        }, seconds * 20L);
        lootArms.put(playerId, new LootArm(tableId, copy, denars, expire));
        player.sendMessage(Messages.get("wager.place_click", "seconds", String.valueOf(seconds)));
    }

    private boolean tryLootPlace(Player player, Location click) {
        LootArm arm = lootArms.get(player.getUniqueId());
        if (arm == null) {
            return false;
        }
        Table table = tables.get(arm.tableId);
        if (table == null) {
            clearLootArm(player.getUniqueId(), false);
            return true;
        }
        FeltHit felt = findFelt(player, click);
        if (felt == null || !felt.table().getId().equals(arm.tableId)) {
            return false;
        }
        if ("blackjack".equalsIgnoreCase(table.getGameId())) {
            clearLootArm(player.getUniqueId(), false);
            player.sendMessage(Messages.get("wager.coins_only"));
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || !held.isSimilar(arm.item) || held.getAmount() < arm.item.getAmount()) {
            player.sendMessage(Messages.get("wager.wrong_item"));
            return true;
        }
        if (!dumpLoot(player, table, arm.item, arm.denars, felt.hit())) {
            return true;
        }
        clearLootArm(player.getUniqueId(), false);
        return true;
    }

    private void cancelLootArmsForTable(UUID tableId) {
        for (UUID playerId : new ArrayList<>(lootArms.keySet())) {
            LootArm arm = lootArms.get(playerId);
            if (arm != null && arm.tableId.equals(tableId)) {
                clearLootArm(playerId, false);
            }
        }
    }

    private void clearLootArm(UUID playerId, boolean timeoutMessage) {
        LootArm arm = lootArms.remove(playerId);
        if (arm == null) {
            return;
        }
        if (arm.expireTask != null) {
            arm.expireTask.cancel();
        }
        if (timeoutMessage) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(Messages.get("wager.place_timeout"));
            }
        }
    }

    private boolean dumpLoot(Player player, Table table, ItemStack snapshot, int denars, Location hit) {
        ItemStack held = player.getInventory().getItemInMainHand();
        int need = snapshot.getAmount();
        if (held == null || !held.isSimilar(snapshot) || held.getAmount() < need) {
            player.sendMessage(Messages.get("wager.gone"));
            return false;
        }
        consumeAmount(held, player, need);
        Location at = hit;
        if (at == null || at.getWorld() == null
                || (!onPlayArea(table, player, at) && !isDealerTrayPlace(table, player.getUniqueId(), at))) {
            FeltHit felt = findFelt(player, null);
            at = felt != null && felt.table() == table ? felt.hit() : fallbackFelt(table, player);
        }
        if (at == null || at.getWorld() == null) {
            restoreItems(player, snapshot, need);
            player.sendMessage(Messages.get("wager.spawn_failed"));
            return false;
        }
        if (inShoeZone(table, at)
                || (inTrayZone(table, at) && !isDealerTrayPlace(table, player.getUniqueId(), at))) {
            restoreItems(player, snapshot, need);
            player.sendMessage(Messages.get("wager.no_bet_zone"));
            return false;
        }
        boolean dealerTray = isDealerTrayPlace(table, player.getUniqueId(), at);
        int placeDenars = denars * need;
        if (!dealerTray && refuseBlackjackPlace(player, table, player.getUniqueId(), placeDenars)) {
            restoreItems(player, snapshot, need);
            return false;
        }
        ItemStack one = snapshot.clone();
        one.setAmount(1);
        String type = ChipItems.typeKey(one);
        WagerPileStyle style = ChipItems.pileStyle(one);
        int remaining = need;
        while (remaining > 0) {
            PotPile merge = nearestMerge(table, type, style, at, denars, player.getUniqueId(), table.street());
            if (merge != null) {
                merge.addOne();
                if (!rebuildPile(table, merge)) {
                    merge.setCount(merge.count() - 1);
                    rebuildPile(table, merge);
                    restoreItems(player, snapshot, remaining);
                    player.sendMessage(Messages.get("wager.spawn_failed"));
                    return false;
                }
            } else {
                PotPile pile = new PotPile(player.getUniqueId(), one.clone(), type, denars, 1, at.getX(), at.getZ());
                pile.setStreetId(table.street());
                if (!rebuildPile(table, pile)) {
                    restoreItems(player, snapshot, remaining);
                    player.sendMessage(Messages.get("wager.spawn_failed"));
                    return false;
                }
                table.getPiles().add(pile);
            }
            remaining--;
        }
        playChipSound(table, at);
        if (!dealerTray) {
            table.actives().add(player.getUniqueId());
            save(table);
            tryBeginSession(table);
            notifyChipIn(table, player, need, one);
        } else {
            save(table);
        }
        return true;
    }

    private static void restoreItems(Player player, ItemStack snapshot, int amount) {
        if (amount <= 0) {
            return;
        }
        ItemStack back = snapshot.clone();
        back.setAmount(amount);
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(back);
        Location loc = player.getLocation();
        if (loc.getWorld() == null) {
            return;
        }
        for (ItemStack extra : leftover.values()) {
            loc.getWorld().dropItemNaturally(loc, extra);
        }
    }

    private static void consumeAmount(ItemStack held, Player player, int amount) {
        int have = held.getAmount();
        if (have <= amount) {
            player.getInventory().setItemInMainHand(null);
        } else {
            held.setAmount(have - amount);
        }
    }

    private Set<UUID> eligibleVoters(Table table, UUID proposerId) {
        Set<UUID> out = new HashSet<>();
        for (UUID id : table.actives()) {
            if (id.equals(proposerId)) {
                continue;
            }
            Player other = Bukkit.getPlayer(id);
            if (other != null && other.isOnline()) {
                out.add(id);
            }
        }
        return out;
    }

    private void broadcastProposed(Table table, String playerName, ItemStack item, int denars) {
        Set<UUID> ids = new HashSet<>(table.actives());
        if (table.getVote() != null) {
            ids.add(table.getVote().proposerId());
            ids.addAll(table.getVote().eligible());
        }
        for (UUID id : ids) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer != null && viewer.isOnline()) {
                WagerChat.sendProposed(viewer, playerName, item, denars);
            }
        }
    }

    private void messageActives(Table table, String message) {
        Set<UUID> ids = new HashSet<>(table.actives());
        if (table.getVote() != null) {
            ids.add(table.getVote().proposerId());
            ids.addAll(table.getVote().eligible());
        }
        for (UUID id : ids) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer != null && viewer.isOnline()) {
                viewer.sendMessage(message);
            }
        }
    }

    private Table tableWhereActive(UUID playerId) {
        Table best = null;
        for (Table table : tables.values()) {
            if (table.actives().contains(playerId)) {
                best = table;
            }
        }
        return best;
    }

    private Table tableForVote(Player player) {
        UUID id = player.getUniqueId();
        for (Table table : tables.values()) {
            WagerVote vote = table.getVote();
            if (vote == null) {
                continue;
            }
            if (id.equals(vote.proposerId()) || vote.eligible().contains(id)) {
                return table;
            }
        }
        return null;
    }

    private boolean tryPlaceChip(Player player, Location click) {
        FeltHit felt = findFelt(player, click);
        if (felt == null) {
            return false;
        }
        Table table = felt.table();
        Location hit = felt.hit();
        ItemStack held = player.getInventory().getItemInMainHand();
        boolean empty = held == null || held.getType() == org.bukkit.Material.AIR || held.getAmount() <= 0;
        if (!empty && "blackjack".equalsIgnoreCase(table.getGameId()) && !ChipItems.isMoneyCoin(held)
                && !wagerLike(held)) {
            return false;
        }
        if (inShoeZone(table, hit)) {
            player.sendMessage(Messages.get("wager.no_bet_zone"));
            return true;
        }
        boolean dealerTray = isDealerTrayPlace(table, player.getUniqueId(), hit);
        if (inTrayZone(table, hit) && !dealerTray) {
            player.sendMessage(Messages.get("wager.no_bet_zone"));
            return true;
        }
        if (!dealerTray && "blackjack".equalsIgnoreCase(table.getGameId()) && (!table.betOpen() || table.live())) {
            player.sendMessage(Messages.get("bet.closed"));
            return true;
        }
        if (table.isPaying()) {
            return true;
        }
        if (empty) {
            return true;
        }
        if ("blackjack".equalsIgnoreCase(table.getGameId()) && !ChipItems.isMoneyCoin(held)) {
            player.sendMessage(Messages.get("wager.coins_only"));
            return true;
        }
        if (ChipItems.needsDeclaredValue(held)) {
            return true;
        }
        ItemStack one = held.clone();
        one.setAmount(1);
        String type = ChipItems.typeKey(one);
        WagerPileStyle style;
        int denars;
        int pieceAdd;
        ChipItems.DecoChips deco = ChipItems.decoChips(held);
        if (deco != null) {
            style = deco.style();
            denars = deco.denars();
            pieceAdd = deco.pieces();
        } else {
            OptionalInt whole = ChipItems.integerDenars(held);
            if (whole.isEmpty()) {
                if (ChipItems.isChipKind(held)) {
                    player.sendMessage(Messages.get("wager.not_whole"));
                    return true;
                }
                return false;
            }
            style = ChipItems.pileStyle(one);
            denars = whole.getAsInt();
            pieceAdd = 1;
        }
        if (!dealerTray && refuseBlackjackPlace(player, table, player.getUniqueId(), denars)) {
            return true;
        }
        if (!depositPieces(table, hit, player.getUniqueId(), one, type, style, denars, 1, pieceAdd)) {
            player.sendMessage(Messages.get("wager.spawn_failed"));
            return true;
        }
        playChipSound(table, hit);
        consumeOne(held, player);
        if (!dealerTray) {
            table.actives().add(player.getUniqueId());
            save(table);
            tryBeginSession(table);
            notifyChipIn(table, player, denars, one);
        } else {
            save(table);
        }
        lockHand(table, player, false);
        markSelectCooldown(player);
        return true;
    }

    private static boolean wagerLike(ItemStack held) {
        return ChipItems.isChipKind(held) || ChipItems.decoChips(held) != null
                || ChipItems.integerDenars(held).isPresent();
    }

    private FeltHit findFelt(Player player, Location click) {
        Location eye = player.getEyeLocation();
        if (eye.getWorld() == null) {
            return null;
        }
        List<Table> nearby = new ArrayList<>();
        Table closest = null;
        double closestPlayer = Double.MAX_VALUE;
        Location feet = player.getLocation();
        for (Table table : tables.values()) {
            if (!atTable(player, table)) {
                continue;
            }
            nearby.add(table);
            double d = table.getOrigin().distance(feet);
            if (d < closestPlayer) {
                closestPlayer = d;
                closest = table;
            }
        }
        FeltHit best = null;
        double bestDist = Double.MAX_VALUE;
        for (Table table : nearby) {
            Location origin = table.getOrigin();
            Location hit;
            if (click != null && click.getWorld() != null && click.getWorld().equals(origin.getWorld())) {
                hit = origin.clone();
                hit.setX(click.getX());
                hit.setZ(click.getZ());
            } else {
                hit = rayFelt(eye, origin);
            }
            if (hit == null) {
                continue;
            }
            boolean onPlay = onPlayArea(table, player, hit, table == closest);
            boolean onTray = inTrayZone(table, hit);
            if (!onPlay && !onTray) {
                continue;
            }
            double dist = horizontalDistance(origin, hit);
            if (dist < bestDist) {
                best = new FeltHit(table, hit);
                bestDist = dist;
            }
        }
        return best;
    }

    private static Location tablePlaceOrigin(PlayerInteractEvent event) {
        if (event == null || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return null;
        }
        if (event.getBlockFace() != BlockFace.UP) {
            return null;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return null;
        }
        BoundingBox box = block.getBoundingBox();
        if (box.getMaxX() - box.getMinX() < PLACE_TOP_MIN
                || box.getMaxZ() - box.getMinZ() < PLACE_TOP_MIN) {
            return null;
        }
        Location origin = block.getLocation();
        origin.setX(block.getX() + 0.5);
        origin.setZ(block.getZ() + 0.5);
        origin.setY(box.getMaxY());
        return origin;
    }

    private static Location clickHit(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        var ray = player.rayTraceBlocks(FELT_REACH);
        if (ray == null || ray.getHitPosition() == null || player.getWorld() == null) {
            return null;
        }
        return ray.getHitPosition().toLocation(player.getWorld());
    }

    private static Location rayFelt(Location eye, Location origin) {
        Vector dir = eye.getDirection();
        if (Math.abs(dir.getY()) < 1e-4) {
            return null;
        }
        double t = (origin.getY() - eye.getY()) / dir.getY();
        if (t < 0.05 || t > FELT_REACH) {
            return null;
        }
        Location hit = eye.clone().add(dir.clone().multiply(t));
        hit.setY(origin.getY());
        return hit;
    }

    private static boolean inShoeZone(Table table, Location hit) {
        TableLayout layout = Cache.layoutOf(table.getGameId());
        return layout != null && layout.inShoeZone(table, hit);
    }

    private static boolean inTrayZone(Table table, Location hit) {
        TableLayout layout = Cache.layoutOf(table.getGameId());
        return layout != null && layout.inTrayZone(table, hit);
    }

    public boolean isTrayPile(Table table, PotPile pile) {
        if (table == null || pile == null) {
            return false;
        }
        Location at = table.getOrigin().clone();
        at.setX(pile.x());
        at.setZ(pile.z());
        return inTrayZone(table, at);
    }

    private static boolean isDealerTrayPlace(Table table, UUID ownerId, Location hit) {
        return table != null && ownerId != null && ownerId.equals(table.dealerId()) && inTrayZone(table, hit);
    }

    private static boolean inNoBetZone(Table table, Location hit) {
        TableLayout layout = Cache.layoutOf(table.getGameId());
        return layout != null && layout.inNoBetZone(table, hit);
    }

    private static boolean onFelt(Table table, Location hit) {
        TableLayout layout = Cache.layoutOf(table.getGameId());
        if (layout != null) {
            return layout.onFelt(table, hit);
        }
        Location origin = table.getOrigin();
        double dist = Math.hypot(hit.getX() - origin.getX(), hit.getZ() - origin.getZ());
        return dist >= Cache.wagerMinRange && dist <= Cache.wagerMaxRange;
    }

    private boolean onPlayArea(Table table, Player player, Location hit) {
        return onPlayArea(table, player, hit, true);
    }

    private boolean onPlayArea(Table table, Player player, Location hit, boolean allowBetPad) {
        if (table == null || hit == null) {
            return false;
        }
        if (onFelt(table, hit)) {
            return true;
        }
        if (!allowBetPad || player == null) {
            return false;
        }
        TableLayout layout = Cache.layoutOf(table.getGameId());
        if (layout == null || layout.betZone() == null || !layout.betZone().present()) {
            return false;
        }
        HandLock lock = peekHandLock(table, player, false);
        return layout.inBetZone(hit, lock.x(), lock.z(), lock.placeYaw());
    }

    private Location betPadCenter(Table table, Player player) {
        TableLayout layout = Cache.layoutOf(table.getGameId());
        if (layout == null || layout.betZone() == null || !layout.betZone().present() || player == null) {
            return null;
        }
        HandLock lock = peekHandLock(table, player, false);
        return layout.betPadCenter(table, lock.x(), lock.z(), lock.placeYaw());
    }

    private Location fallbackFelt(Table table, Player player) {
        TableLayout layout = Cache.layoutOf(table.getGameId());
        if (layout != null && layout.betZone() != null && layout.betZone().present() && player != null) {
            Location pad = betPadCenter(table, player);
            if (pad != null) {
                return pad;
            }
        }
        if (layout != null) {
            Location center = layout.feltCenter(table);
            if (layout.onFelt(table, center)) {
                return center;
            }
        }
        Location origin = table.getOrigin();
        Location loc = player.getLocation();
        double dx = loc.getX() - origin.getX();
        double dz = loc.getZ() - origin.getZ();
        double dist = Math.hypot(dx, dz);
        double ring = (Cache.wagerMinRange + Cache.wagerMaxRange) * 0.5;
        Location hit = origin.clone();
        if (dist < 1e-6) {
            hit.setX(origin.getX() + ring);
            hit.setZ(origin.getZ());
        } else {
            double s = ring / dist;
            hit.setX(origin.getX() + dx * s);
            hit.setZ(origin.getZ() + dz * s);
        }
        hit.setY(origin.getY());
        return hit;
    }

    private record FeltHit(Table table, Location hit) {}

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private static PotPile nearestMerge(Table table, String type, WagerPileStyle style, Location hit, int denars,
            UUID ownerId, int streetId) {
        PotPile best = null;
        double bestDist = Double.MAX_VALUE;
        double max = Cache.wagerMergeRange;
        double maxSq = max * max;
        for (PotPile pile : table.getPiles()) {
            if (!type.equals(pile.typeKey()) || pile.denars() != denars) {
                continue;
            }
            if (!Objects.equals(ownerId, pile.ownerId()) || pile.streetId() != streetId) {
                continue;
            }
            if (!PotLayout.canAdd(pile.pieces(), style)) {
                continue;
            }
            double dx = pile.x() - hit.getX();
            double dz = pile.z() - hit.getZ();
            double distSq = dx * dx + dz * dz;
            if (distSq > maxSq || distSq >= bestDist) {
                continue;
            }
            best = pile;
            bestDist = distSq;
        }
        return best;
    }

    private boolean refuseBlackjackPlace(Player player, Table table, UUID ownerId, int placeDenars) {
        if (table == null || player == null || !"blackjack".equalsIgnoreCase(table.getGameId())) {
            return false;
        }
        if (!table.betOpen() || table.live()) {
            player.sendMessage(Messages.get("bet.closed"));
            return true;
        }
        int max = table.maxBet();
        int have = ownedDenars(table, ownerId);
        if (have + placeDenars > max) {
            player.sendMessage(Messages.get("bet.over_max", "max", String.valueOf(max)));
            return true;
        }
        TableLayout layout = Cache.layoutOf(table.getGameId());
        int cap = table.maxBoxes();
        if (cap < 1) {
            cap = layout != null ? layout.maxBoxes() : 0;
        }
        if (cap > 0 && have < 1 && countBlackjackBoxes(table) >= cap) {
            player.sendMessage(Messages.get("bet.table_full", "max", String.valueOf(cap)));
            return true;
        }
        return false;
    }

    private int countBlackjackBoxes(Table table) {
        UUID dealer = table.dealerId();
        UUID house = table.getId();
        HashSet<UUID> seen = new HashSet<>();
        for (PotPile pile : table.getPiles()) {
            UUID owner = pile.ownerId();
            if (owner == null || owner.equals(dealer) || owner.equals(house) || isTrayPile(table, pile)) {
                continue;
            }
            if (ownedDenars(table, owner) > 0) {
                seen.add(owner);
            }
        }
        return seen.size();
    }

    private boolean depositPieces(Table table, Location hit, UUID ownerId, ItemStack one, String type,
            WagerPileStyle style, int denars, int physical, int pieceAdd) {
        if (pieceAdd < 1) {
            return false;
        }
        if (inShoeZone(table, hit)
                || (inTrayZone(table, hit) && !isDealerTrayPlace(table, ownerId, hit))) {
            return false;
        }
        List<PotPile> created = new ArrayList<>();
        Map<PotPile, int[]> snap = new HashMap<>();
        int left = pieceAdd;
        boolean physLeft = physical > 0;
        Location at = hit.clone();
        at.setY(table.getOrigin().getY());
        PotPile last = null;
        while (left > 0) {
            PotPile merge = nearestMerge(table, type, style, at, denars, ownerId, table.street());
            if (merge != null) {
                snap.putIfAbsent(merge, new int[] {merge.count(), merge.pieces()});
                int room = PotLayout.room(merge.pieces(), style);
                if (room < 1) {
                    return revertDeposit(table, created, snap);
                }
                int add = Math.min(room, left);
                merge.addPieces(add);
                if (physLeft) {
                    merge.addCount(1);
                    physLeft = false;
                }
                if (!rebuildPile(table, merge)) {
                    return revertDeposit(table, created, snap);
                }
                left -= add;
                last = merge;
                at = pileAt(table, merge);
                continue;
            }
            Location spawnAt = last == null ? at : overflowAway(table, last);
            Player owner = ownerId != null ? Bukkit.getPlayer(ownerId) : null;
            boolean onPad = owner != null && owner.isOnline()
                    ? onPlayArea(table, owner, spawnAt)
                    : onFelt(table, spawnAt);
            if (spawnAt == null || (!onPad && !inTrayZone(table, spawnAt))) {
                if (last != null) {
                    last.addPieces(left);
                    if (!rebuildPile(table, last)) {
                        return revertDeposit(table, created, snap);
                    }
                    return true;
                }
                return revertDeposit(table, created, snap);
            }
            int room = PotLayout.room(0, style);
            int add = Math.min(Math.max(1, room), left);
            int phys = physLeft ? 1 : 0;
            PotPile pile = new PotPile(ownerId, one.clone(), type, denars, phys, spawnAt.getX(), spawnAt.getZ());
            pile.setPieces(add);
            pile.setStreetId(table.street());
            if (!rebuildPile(table, pile)) {
                despawnPile(pile);
                return revertDeposit(table, created, snap);
            }
            table.getPiles().add(pile);
            created.add(pile);
            physLeft = false;
            left -= add;
            last = pile;
            at = spawnAt;
        }
        return true;
    }

    private boolean revertDeposit(Table table, List<PotPile> created, Map<PotPile, int[]> snap) {
        for (PotPile pile : created) {
            despawnPile(pile);
            table.getPiles().remove(pile);
        }
        for (Map.Entry<PotPile, int[]> entry : snap.entrySet()) {
            PotPile pile = entry.getKey();
            if (created.contains(pile)) {
                continue;
            }
            pile.setCount(entry.getValue()[0]);
            pile.setPieces(entry.getValue()[1]);
            rebuildPile(table, pile);
        }
        return false;
    }

    private static Location pileAt(Table table, PotPile pile) {
        Location at = table.getOrigin().clone();
        at.setX(pile.x());
        at.setZ(pile.z());
        return at;
    }

    private static Location overflowAway(Table table, PotPile from) {
        Location origin = table.getOrigin();
        double dx = from.x() - origin.getX();
        double dz = from.z() - origin.getZ();
        double dist = Math.hypot(dx, dz);
        double step = Cache.wagerMergeRange;
        Location hit = origin.clone();
        if (dist < 1e-6) {
            hit.setX(origin.getX() + Cache.wagerMinRange + step);
            hit.setZ(origin.getZ());
        } else {
            double s = (dist + step) / dist;
            hit.setX(origin.getX() + dx * s);
            hit.setZ(origin.getZ() + dz * s);
        }
        hit.setY(origin.getY());
        return clampOntoRing(table, hit);
    }

    private static Location clampOntoRing(Table table, Location loc) {
        Location origin = table.getOrigin();
        double dx = loc.getX() - origin.getX();
        double dz = loc.getZ() - origin.getZ();
        double dist = Math.hypot(dx, dz);
        double min = Cache.wagerMinRange;
        double max = Cache.wagerMaxRange;
        Location hit = origin.clone();
        if (dist < 1e-6) {
            hit.setX(origin.getX() + min);
            hit.setZ(origin.getZ());
        } else if (dist < min) {
            double s = min / dist;
            hit.setX(origin.getX() + dx * s);
            hit.setZ(origin.getZ() + dz * s);
        } else if (dist > max) {
            double s = max / dist;
            hit.setX(origin.getX() + dx * s);
            hit.setZ(origin.getZ() + dz * s);
        } else {
            hit.setX(loc.getX());
            hit.setZ(loc.getZ());
        }
        hit.setY(origin.getY());
        return hit;
    }

    private void rebuildPiles(Table table) {
        for (PotPile pile : table.getPiles()) {
            rebuildPile(table, pile);
        }
    }

    private boolean rebuildPile(Table table, PotPile pile) {
        DisplayManager displays = DisplayManager.get();
        for (UUID token : pile.tokens()) {
            displays.despawn(token);
        }
        pile.tokens().clear();
        ItemStack visual = pileDisplayItem(pile);
        if (visual == null) {
            return false;
        }
        WagerPileStyle style = ChipItems.pileStyle(pile.item());
        int layers = PotLayout.visibleLayers(pile.pieces(), style);
        Location origin = table.getOrigin();
        World world = origin.getWorld();
        if (world == null) {
            return false;
        }
        while (pile.layerYaws().size() < layers) {
            float yaw = style.randomYaw()
                    ? table.getYaw() + ThreadLocalRandom.current().nextFloat() * 360f
                    : table.getYaw();
            pile.layerYaws().add(yaw);
        }
        for (int i = 0; i < layers; i++) {
            UUID token = UUID.randomUUID();
            float yaw = pile.layerYaws().get(i);
            DisplayPose layerPose = chipPose(style, yaw);
            Location at = new Location(world, pile.x(), origin.getY() + i * style.layerGap(), pile.z(), 0f, 0f);
            if (!displays.spawn(token, at, visual, layerPose)) {
                for (UUID spawned : pile.tokens()) {
                    displays.despawn(spawned);
                }
                pile.tokens().clear();
                return false;
            }
            pile.tokens().add(token);
        }
        return true;
    }

    /**
     * Same {@link DisplayPose#flatOnTable} recipe as cards. Pitch from wager config.
     * Y offset is pose translation (world Y, entity pitch/yaw stay 0), not spawn Location,
     * so values like -0.05 vs -0.2 are not lost to block-snapped spawn Y.
     */
    private static DisplayPose chipPose(WagerPileStyle style, float yaw) {
        return DisplayPose.flatOnTable(style.scale(), (float) style.yOffset(), yaw,
                style.pitch() - Cache.tableCardPitch);
    }

    private static ItemStack pileDisplayItem(PotPile pile) {
        WagerPileStyle style = ChipItems.pileStyle(pile.item());
        if (style.model() != null && !style.model().isBlank()) {
            ItemStack fromPath = TLibs.getItemAPI().getCreator().getItemFromPath(style.model());
            if (fromPath != null) {
                fromPath.setAmount(1);
                return fromPath;
            }
        }
        return pile.item() != null ? pile.item().clone() : null;
    }

    private void despawnPile(PotPile pile) {
        DisplayManager displays = DisplayManager.get();
        for (UUID token : pile.tokens()) {
            displays.despawn(token);
        }
        pile.tokens().clear();
    }

    private void despawnPiles(Table table) {
        for (PotPile pile : table.getPiles()) {
            despawnPile(pile);
        }
    }

    private void refundStreet(Table table, Player player, int street) {
        UUID owner = player.getUniqueId();
        refundPiles(table, player, pile -> owner.equals(pile.ownerId()) && pile.streetId() == street);
    }

    private void givePileItems(PotPile pile, Player dest, Location dropAt) {
        if (pile.count() <= 0 || pile.item() == null) {
            return;
        }
        ItemStack give = pile.item().clone();
        give.setAmount(pile.count());
        World world = dropAt.getWorld();
        if (dest != null && dest.isOnline()) {
            HashMap<Integer, ItemStack> leftover = dest.getInventory().addItem(give);
            if (world != null) {
                for (ItemStack extra : leftover.values()) {
                    world.dropItemNaturally(dropAt, extra);
                }
            }
        } else if (world != null) {
            world.dropItemNaturally(dropAt, give);
        }
    }

    private void givePiles(Table table, Player picker, Location dropAt) {
        despawnPiles(table);
        for (PotPile pile : table.getPiles()) {
            Player dest = pile.ownerId() != null ? Bukkit.getPlayer(pile.ownerId()) : null;
            if (dest == null || !dest.isOnline()) {
                dest = picker != null && picker.isOnline() ? picker : null;
            }
            givePileItems(pile, dest, dropAt);
        }
        table.getPiles().clear();
    }

    private void clearFeltNow(Table table, Player fallback) {
        if (table == null) {
            return;
        }
        table.bumpPayoutGen();
        table.clearPayoutOnDone();
        IdentityHashMap<PotPile, Boolean> seen = new IdentityHashMap<>();
        List<PotPile> all = new ArrayList<>();
        for (PotPile pile : table.getPiles()) {
            if (pile != null && seen.put(pile, Boolean.TRUE) == null) {
                all.add(pile);
            }
        }
        for (PayoutFlight flight : new ArrayList<>(table.payoutFlying())) {
            PotPile pile = flight != null ? flight.pile() : null;
            if (pile != null && seen.put(pile, Boolean.TRUE) == null) {
                all.add(pile);
            }
        }
        Location dropAt = table.getOrigin() != null ? table.getOrigin().clone() : null;
        for (PotPile pile : all) {
            despawnPile(pile);
            Player dest = pile.ownerId() != null ? Bukkit.getPlayer(pile.ownerId()) : null;
            if (dest == null || !dest.isOnline()) {
                dest = fallback != null && fallback.isOnline() ? fallback : null;
            }
            if (dropAt == null && dest != null) {
                dropAt = dest.getLocation();
            }
            if (dropAt != null) {
                givePileItems(pile, dest, dropAt);
            }
        }
        table.getPiles().clear();
        table.endPayout();
        notifyFeltPiles(table);
    }

    /**
     * Auto-dealer tray: staff mint is deleted; guild auto banks, or drops if the guild is gone.
     */
    private void settleAutoTray(Table table, Location dropAt) {
        if (table == null || !table.autoDealer()) {
            return;
        }
        List<PotPile> piles = new ArrayList<>();
        int tray = 0;
        for (PotPile pile : new ArrayList<>(table.getPiles())) {
            if (!isTrayPile(table, pile)) {
                continue;
            }
            tray += pile.contribution();
            piles.add(pile);
        }
        if (piles.isEmpty()) {
            return;
        }
        boolean drop = false;
        if (!table.staffMint()) {
            drop = !GuildTables.tryDeposit(table.ownerGuildId(), tray);
        }
        Location at = dropAt != null ? dropAt : (table.getOrigin() != null ? table.getOrigin().clone() : null);
        for (PotPile pile : piles) {
            despawnPile(pile);
            if (drop && at != null) {
                givePileItems(pile, null, at);
            }
            table.getPiles().remove(pile);
        }
        notifyFeltPiles(table);
    }

    private boolean trySelectCard(Player player) {
        if (revealBusy.contains(player.getUniqueId())) {
            return false;
        }
        HandHit hit = hitOwnCard(player);
        if (hit == null) {
            return false;
        }
        List<HandCard> hand = hit.table.getHands().get(player.getUniqueId());
        if (anyPublic(hand)) {
            return true;
        }
        markSelectCooldown(player);
        hit.card.setSelected(!hit.card.isSelected());
        player.swingMainHand();
        pushSelectedCard(hit.table, player, hit.card);
        playCardSound(player);
        return true;
    }

    private boolean tryInspectCard(Player player) {
        if (revealBusy.contains(player.getUniqueId())) {
            return false;
        }
        HandHit hit = hitOwnCard(player);
        if (hit == null) {
            return false;
        }
        markSelectCooldown(player);
        player.swingMainHand();
        playCardSound(player);
        Card card = hit.card.card();
        player.sendMessage(Messages.get("card.info",
                "suit", CardNames.suitLabel(card.getSuit()),
                "rank", CardNames.rankLabel(card)));
        UUID tokenId = hit.card.tokenId();
        layoutHand(hit.table, player, 4, false, tokenId);
        holdLayout(player.getUniqueId(), (int) INSPECT_TICKS + 4);
        Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
            if (!player.isOnline() || tableHolding(player.getUniqueId()) != hit.table) {
                return;
            }
            layoutHand(hit.table, player, 4, false, null);
        }, INSPECT_TICKS);
        return true;
    }

    private HandHit hitOwnCard(Player player) {
        if (onSelectCooldown(player)) {
            return null;
        }
        var ray = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                Cache.handSelectRange,
                entity -> tableFrom(entity) != null);
        if (ray != null && ray.getHitEntity() != null) {
            return null;
        }
        Table table = tableHolding(player.getUniqueId());
        if (table == null) {
            return null;
        }
        List<HandCard> hand = table.getHands().get(player.getUniqueId());
        if (hand == null || hand.isEmpty()) {
            return null;
        }
        HandCard card = CardSelector.closestOnRay(player, hand);
        if (card == null) {
            return null;
        }
        return new HandHit(table, card);
    }

    private record HandHit(Table table, HandCard card) {}

    private static int countSelected(Table table, Player player) {
        if (table == null || player == null) {
            return 0;
        }
        List<HandCard> hand = table.getHands().get(player.getUniqueId());
        if (hand == null) {
            return 0;
        }
        int n = 0;
        for (HandCard held : hand) {
            if (held.isSelected()) {
                n++;
            }
        }
        return n;
    }

    private boolean tryReturnSelected(Table table, Player player) {
        if (revealBusy.contains(player.getUniqueId())) {
            return false;
        }
        List<HandCard> hand = table.getHands().get(player.getUniqueId());
        if (hand == null || hand.isEmpty()) {
            return false;
        }
        List<HandCard> selected = new ArrayList<>();
        for (HandCard held : hand) {
            if (held.isSelected()) {
                selected.add(held);
            }
        }
        if (selected.isEmpty()) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        DisplayManager displays = DisplayManager.get();
        List<Card> flying = new ArrayList<>();
        List<DisplayPose> fromOwner = new ArrayList<>();
        List<DisplayPose> fromOther = new ArrayList<>();
        for (HandCard held : selected) {
            DisplayPose ownerPose = displays.poseOf(held.tokenId());
            if (ownerPose == null) {
                continue;
            }
            DisplayPose otherPose = displays.otherPoseOf(held.tokenId());
            flying.add(held.card());
            fromOwner.add(ownerPose);
            fromOther.add(otherPose != null ? otherPose : ownerPose);
            revealedCardTokens.remove(held.tokenId());
            displays.despawn(held.tokenId());
            hand.remove(held);
        }
        if (flying.isEmpty()) {
            return false;
        }
        revealBusy.add(playerId);
        int gen = dealGen.merge(playerId, 1, Integer::sum);
        selectAnimGen.merge(playerId, 1, Integer::sum);
        dealPendingCards.computeIfAbsent(playerId, key -> new ArrayList<>()).addAll(flying);
        if (!hand.isEmpty()) {
            layoutHand(table, player, Cache.interpolationTicks, true, null);
        }
        int stagger = Math.max(0, Cache.handRevealStagger);
        int deal = Math.max(0, Cache.handDealTicks);
        holdLayout(playerId, stagger * Math.max(0, flying.size() - 1) + deal + 3);
        playCardSound(player);
        if (player.isOnline()) {
            player.sendMessage(Messages.get("hand.returned_selected"));
        }
        UUID tableId = table.getId();
        for (int i = 0; i < flying.size(); i++) {
            final Card card = flying.get(i);
            final DisplayPose ownerFrom = fromOwner.get(i);
            final DisplayPose otherFrom = fromOther.get(i);
            final int step = i;
            Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
                if (!dealActive(playerId, gen, tableId)) {
                    return;
                }
                startReturnCourier(table, player, card, ownerFrom, otherFrom, gen);
            }, (long) step * stagger);
        }
        return true;
    }

    private boolean onSelectCooldown(Player player) {
        Long until = selectCooldown.get(player.getUniqueId());
        return until != null && System.currentTimeMillis() < until;
    }

    private void markSelectCooldown(Player player) {
        selectCooldown.put(player.getUniqueId(), System.currentTimeMillis() + SELECT_COOLDOWN_MS);
    }

    private static List<HandCard> revealBand(List<HandCard> hand) {
        List<HandCard> selected = new ArrayList<>();
        for (HandCard held : hand) {
            if (held.isSelected()) {
                selected.add(held);
            }
        }
        return selected.isEmpty() ? hand : selected;
    }

    private boolean anyPublic(List<HandCard> cards) {
        if (cards == null) {
            return false;
        }
        for (HandCard held : cards) {
            if (revealedCardTokens.contains(held.tokenId())) {
                return true;
            }
        }
        return false;
    }

    private boolean othersAllPublic(List<HandCard> hand, UUID exceptToken) {
        boolean any = false;
        for (HandCard held : hand) {
            if (exceptToken != null && exceptToken.equals(held.tokenId())) {
                continue;
            }
            any = true;
            if (!revealedCardTokens.contains(held.tokenId())) {
                return false;
            }
        }
        return any;
    }

    private void syncRevealedHands(UUID playerId, List<HandCard> hand) {
        if (anyPublic(hand)) {
            revealedHands.add(playerId);
        } else {
            revealedHands.remove(playerId);
        }
    }

    private List<DisplayPose> fanSlots(Table table, Player player, List<HandCard> order, float extraPitch) {
        HandLock lock = lockHand(table, player, false);
        Location origin = table.getOrigin();
        Location anchor = lockLocation(player, lock);
        int n = order.size();
        List<DisplayPose> slots = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            HandCard held = order.get(i);
            int groupSize = countInSlot(order, held.slot());
            int groupIndex = indexInSlot(order, held);
            slots.add(playerFanPose(origin, anchor, lock, held.slot(), groupIndex, groupSize,
                    held.isSelected(), 0f, extraPitch));
        }
        return slots;
    }

    private void startRevealSequence(Table table, Player player, List<HandCard> hand, List<HandCard> band,
            boolean show) {
        if (band == null || band.isEmpty()) {
            return;
        }
        List<HandCard> order = handOrder(table, hand);
        List<Integer> indices = new ArrayList<>();
        List<HandCard> changing = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            HandCard held = order.get(i);
            if (!band.contains(held)) {
                continue;
            }
            boolean publicCard = revealedCardTokens.contains(held.tokenId());
            if (show == publicCard) {
                continue;
            }
            indices.add(i);
            changing.add(held);
        }
        if (indices.isEmpty()) {
            return;
        }
        UUID id = player.getUniqueId();
        stopRevealExtras(id);
        revealBusy.add(id);
        int gen = revealGen.merge(id, 1, Integer::sum);
        selectAnimGen.merge(id, 1, Integer::sum);
        HandLock lock = lockHand(table, player, false);
        List<DisplayPose> up = fanSlots(table, player, order, HandLayout.FACE_UP_PITCH);
        List<DisplayPose> down = new ArrayList<>(up.size());
        for (DisplayPose slot : up) {
            down.add(RevealLayout.withPitch(slot, lock.placeYaw(), HandLayout.FACE_DOWN_PITCH));
        }
        hideAllBacks(player, changing);
        DisplayManager displays = DisplayManager.get();
        for (int index : indices) {
            displays.setTransform(order.get(index).tokenId(), show ? down.get(index) : up.get(index), 0);
        }
        int steps = indices.size();
        int stagger = Math.max(0, Cache.handRevealStagger);
        int flip = Math.max(1, Cache.handRevealFlip);
        holdLayout(id, stagger * Math.max(0, steps - 1) + flip + 3);
        UUID tableId = table.getId();
        for (int i = 0; i < steps; i++) {
            final int step = i;
            Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
                if (!Integer.valueOf(gen).equals(revealGen.get(id))) {
                    return;
                }
                if (!player.isOnline() || tableHolding(id) == null
                        || !tableHolding(id).getId().equals(tableId)) {
                    return;
                }
                int index = indices.get(show ? step : (steps - 1 - step));
                flipSandwich(table, player, order, index, down, up, show, gen);
            }, (long) step * stagger);
        }
        Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
            if (!Integer.valueOf(gen).equals(revealGen.get(id))) {
                return;
            }
            List<HandCard> live = table.getHands().get(id);
            if (!show) {
                restorePrivateFaces(player, changing);
            }
            syncRevealedHands(id, live);
            if (player.isOnline()) {
                Table still = tableHolding(id);
                if (still != null && still.getId().equals(table.getId())) {
                    layoutHand(table, player, Cache.handFollowTicks, false, null);
                }
            }
            revealBusy.remove(id);
        }, (long) stagger * Math.max(0, steps - 1) + flip + 2);
    }

    private void hideAllBacks(Player owner, List<HandCard> hand) {
        DisplayManager displays = DisplayManager.get();
        ItemStack back = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getBackItem());
        for (HandCard held : hand) {
            if (back != null) {
                displays.setItem(held.tokenId(), back);
            }
            displays.clearItemFor(held.tokenId(), owner);
        }
    }

    private void restorePrivateFaces(Player owner, List<HandCard> hand) {
        DisplayManager displays = DisplayManager.get();
        ItemStack back = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getBackItem());
        for (HandCard held : hand) {
            if (back != null) {
                displays.setItem(held.tokenId(), back);
            }
            ItemStack face = TLibs.getItemAPI().getCreator().getItemFromPath(held.card().getItem());
            if (face != null) {
                displays.setItemFor(held.tokenId(), owner, face);
            }
        }
    }

    private void flipSandwich(Table table, Player player, List<HandCard> hand, int index,
            List<DisplayPose> down, List<DisplayPose> up, boolean toFace, int gen) {
        if (index < 0 || index >= hand.size() || index >= down.size() || index >= up.size()) {
            return;
        }
        HandCard held = hand.get(index);
        ItemStack face = TLibs.getItemAPI().getCreator().getItemFromPath(held.card().getItem());
        ItemStack back = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getBackItem());
        DisplayPose from = toFace ? down.get(index) : up.get(index);
        DisplayPose to = toFace ? up.get(index) : down.get(index);
        DisplayManager displays = DisplayManager.get();
        UUID token = held.tokenId();
        int flip = Math.max(1, Cache.handRevealFlip);
        if (!toFace) {
            revealedCardTokens.remove(token);
        }
        if (back != null) {
            displays.setItem(token, back);
        }
        displays.clearItemFor(token, player);
        displays.setTransform(token, from, 0);
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
            if (!Integer.valueOf(gen).equals(revealGen.get(playerId))) {
                return;
            }
            displays.setTransform(token, to, flip);
        }, 1L);
        Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
            if (!Integer.valueOf(gen).equals(revealGen.get(playerId))) {
                return;
            }
            if (toFace) {
                if (face != null) {
                    displays.setItem(token, face);
                    displays.setItemFor(token, player, face);
                }
        displays.setTransform(token, to, 0);
                revealedCardTokens.add(token);
                return;
            }
            revealedCardTokens.remove(token);
            respawnPrivateCard(table, player, held.tokenId(), up.get(index), back, face);
        }, 1L + flip);
    }

    private void respawnPrivateCard(Table table, Player player, UUID oldToken, DisplayPose pose,
            ItemStack back, ItemStack face) {
        List<HandCard> live = table.handOf(player.getUniqueId());
        int liveIndex = -1;
        for (int i = 0; i < live.size(); i++) {
            if (live.get(i).tokenId().equals(oldToken)) {
                liveIndex = i;
                break;
            }
        }
        if (liveIndex < 0) {
            return;
        }
        HandCard old = live.get(liveIndex);
        DisplayManager displays = DisplayManager.get();
        revealedCardTokens.remove(old.tokenId());
        displays.despawn(old.tokenId());
        UUID neu = UUID.randomUUID();
        ItemStack spawnItem = back != null ? back : face;
        if (spawnItem == null || !displays.spawn(neu, table.getOrigin(), spawnItem, pose)) {
            return;
        }
        displays.setLayoutOwner(neu, player.getUniqueId());
        if (back != null) {
            displays.setItem(neu, back);
        }
        if (face != null) {
            displays.setItemFor(neu, player, face);
        }
        HandCard next = new HandCard(old.card(), neu);
        next.setSelected(old.isSelected());
        next.setSlot(old.slot());
        next.setFaceUp(old.faceUp());
        live.set(liveIndex, next);
    }

    private void startSingleRevealFlip(Table table, Player player, UUID tokenId) {
        List<HandCard> hand = table.handOf(player.getUniqueId());
        List<HandCard> order = handOrder(table, hand);
        int index = -1;
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).tokenId().equals(tokenId)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return;
        }
        UUID id = player.getUniqueId();
        revealBusy.add(id);
        int gen = revealGen.merge(id, 1, Integer::sum);
        HandLock lock = lockHand(table, player, false);
        List<DisplayPose> up = fanSlots(table, player, order, HandLayout.FACE_UP_PITCH);
        List<DisplayPose> down = new ArrayList<>(up.size());
        for (DisplayPose slot : up) {
            down.add(RevealLayout.withPitch(slot, lock.placeYaw(), HandLayout.FACE_DOWN_PITCH));
        }
        int flip = Math.max(1, Cache.handRevealFlip);
        holdLayout(id, flip + 3);
        DisplayManager.get().clearItemFor(tokenId, player);
        ItemStack back = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getBackItem());
        if (back != null) {
            DisplayManager.get().setItem(tokenId, back);
        }
        DisplayManager.get().setTransform(tokenId, down.get(index), 0);
        flipSandwich(table, player, order, index, down, up, true, gen);
        Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
            if (Integer.valueOf(gen).equals(revealGen.get(id))) {
                syncRevealedHands(id, table.getHands().get(id));
                revealBusy.remove(id);
            }
        }, flip + 2);
    }

    private void stopRevealExtras(UUID playerId) {
        revealBusy.remove(playerId);
        revealGen.merge(playerId, 1, Integer::sum);
        List<UUID> extras = revealExtras.remove(playerId);
        if (extras == null) {
            return;
        }
        DisplayManager displays = DisplayManager.get();
        for (UUID extra : extras) {
            displays.despawn(extra);
        }
    }

    private void playCardSound(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        playCardSound(player.getLocation());
    }

    private void playCardSound(Location at) {
        if (Cache.cardSound == null || Cache.cardSoundVolume <= 0f || at == null || at.getWorld() == null) {
            return;
        }
        at.getWorld().playSound(at, Cache.cardSound, Cache.cardSoundVolume, Cache.cardSoundPitch);
    }

    private void playChipSound(Table table, Location at) {
        if (at == null || at.getWorld() == null) {
            return;
        }
        Sound sound = Cache.chipSound;
        float volume = Cache.chipSoundVolume;
        float pitch = Cache.chipSoundPitch;
        TableLayout layout = table != null ? Cache.layoutOf(table.getGameId()) : null;
        if (layout != null && layout.chipFx() != null) {
            sound = layout.chipFx().sound();
            volume = layout.chipFx().volume();
            pitch = layout.chipFx().pitch();
        }
        if (sound == null || volume <= 0f) {
            return;
        }
        at.getWorld().playSound(at, sound, volume, pitch);
    }

    private boolean layoutHeld(UUID playerId) {
        Long until = layoutHoldUntil.get(playerId);
        return until != null && System.currentTimeMillis() < until;
    }

    private void holdLayout(UUID playerId, int ticks) {
        if (ticks <= 0) {
            return;
        }
        layoutHoldUntil.put(playerId, System.currentTimeMillis() + ticks * 50L + 50L);
    }

    private void pushSelectedCard(Table table, Player player, HandCard card) {
        List<HandCard> hand = table.handOf(player.getUniqueId());
        int dealIndex = -1;
        for (int i = 0; i < hand.size(); i++) {
            if (hand.get(i).tokenId().equals(card.tokenId())) {
                dealIndex = i;
                break;
            }
        }
        if (dealIndex < 0) {
            return;
        }
        int n = countInSlot(hand, card.slot());
        int sortIndex = indexInSlot(handOrder(table, hand), card);
        if (sortIndex < 0) {
            sortIndex = indexInSlot(hand, card);
        }
        HandLock lock = lockHand(table, player, false);
        Location origin = table.getOrigin();
        Location anchor = lockLocation(player, lock);
        DisplayPose ownerEnd = playerFanPose(origin, anchor, lock, card.slot(), sortIndex, n,
                card.isSelected(), 0f, HandLayout.FACE_UP_PITCH);
        DisplayPose otherEnd = revealedCardTokens.contains(card.tokenId())
                ? ownerEnd
                : playerFanPose(origin, anchor, lock, card.slot(), indexInSlot(hand, card), n,
                        card.isSelected(), 0f, HandLayout.FACE_UP_PITCH);
        DisplayPose startOwner = DisplayManager.get().poseOf(card.tokenId());
        DisplayPose startOther = DisplayManager.get().otherPoseOf(card.tokenId());
        if (startOther == null) {
            startOther = startOwner;
        }
        int ticks = Cache.handSelectTicks;
        DisplayManager.get().setLayoutOwner(card.tokenId(), player.getUniqueId());
        if (ticks <= 0 || startOwner == null) {
            if (ticks > 0) {
                holdLayout(player.getUniqueId(), ticks);
            }
            DisplayManager.get().setTransformSplit(card.tokenId(), ownerEnd, otherEnd, Math.max(0, ticks));
            return;
        }
        holdLayout(player.getUniqueId(), ticks);
        int gen = selectAnimGen.merge(player.getUniqueId(), 1, Integer::sum);
        UUID playerId = player.getUniqueId();
        UUID tokenId = card.tokenId();
        UUID tableId = table.getId();
        DisplayPose fromOwner = startOwner;
        DisplayPose fromOther = startOther;
        for (int step = 1; step <= ticks; step++) {
            final int s = step;
            Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
                if (!Integer.valueOf(gen).equals(selectAnimGen.get(playerId))) {
                    return;
                }
                Table still = tableHolding(playerId);
                if (!player.isOnline() || still == null || !still.getId().equals(tableId)) {
                    return;
                }
                float t = s / (float) ticks;
                float ease = 1f - (1f - t) * (1f - t);
                DisplayManager.get().setTransformSplit(tokenId,
                        lerpPose(fromOwner, ownerEnd, ease),
                        lerpPose(fromOther, otherEnd, ease),
                        1);
            }, s);
        }
    }

    private void tryDraw(Player player, Table table) {
        if (revealBusy.contains(player.getUniqueId())) {
            return;
        }
        if (!allowFreeDraw(table, player)) {
            player.sendMessage(Messages.get("hand.locked"));
            return;
        }
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main != null && main.getType() != org.bukkit.Material.AIR && main.getAmount() > 0) {
            player.sendMessage(Messages.get("hand.need_empty"));
            return;
        }
        drawOneToPlayer(table, player, 0, null);
    }

    private boolean drawOneToPlayer(Table table, Player player, Runnable after) {
        return drawOneToPlayer(table, player, 0, after);
    }

    private boolean drawOneToPlayer(Table table, Player player, int slot, Runnable after) {
        if (revealBusy.contains(player.getUniqueId())) {
            return false;
        }
        Table other = tableHolding(player.getUniqueId());
        if (other != null && !other.getId().equals(table.getId())) {
            player.sendMessage(Messages.get("hand.busy"));
            return false;
        }
        ItemStack back = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getBackItem());
        if (back == null) {
            player.sendMessage(Messages.get("place.spawn_failed"));
            return false;
        }
        if (table.getDeck().remaining() == 0) {
            if (table.getDeck().discarded() == 0) {
                player.sendMessage(Messages.get("hand.empty"));
                return false;
            }
            if (table.isRecycling()) {
                return false;
            }
            UUID playerId = player.getUniqueId();
            UUID tableId = table.getId();
            recycleIfNeeded(table, () -> {
                Player still = Bukkit.getPlayer(playerId);
                Table live = tables.get(tableId);
                if (still == null || !still.isOnline() || live == null) {
                    return;
                }
                drawOneToPlayer(live, still, slot, after);
            });
            return true;
        }
        int layersBefore = StackLayout.visibleLayers(table.getDeck().remaining(), table.getDeck().size());
        float stackTopY = stackTopOffset(layersBefore);
        Optional<Card> drawn = table.getDeck().draw();
        if (drawn.isEmpty()) {
            player.sendMessage(Messages.get("hand.empty"));
            return false;
        }
        Card card = drawn.get();
        ItemStack face = TLibs.getItemAPI().getCreator().getItemFromPath(card.getItem());
        UUID playerId = player.getUniqueId();
        List<HandCard> live = table.handOf(playerId);
        int destSlot = Math.max(0, slot);
        int destIndex;
        int destCount;
        if (sortHeld(table)) {
            List<Card> preview = new ArrayList<>();
            for (HandCard held : live) {
                preview.add(held.card());
            }
            preview.add(card);
            preview.sort(HandLayout.orderFor(table.getGameId()));
            destIndex = preview.indexOf(card);
            destCount = preview.size();
        } else {
            destIndex = 0;
            for (HandCard held : live) {
                if (held.slot() == destSlot) {
                    destIndex++;
                }
            }
            destCount = destIndex + 1;
        }
        rebuildCardStacks(table);
        save(table);
        HandLock lock = lockHand(table, player, true);
        Location anchor = lockLocation(player, lock);
        DisplayPose fan = playerFanPose(table.getOrigin(), anchor, lock, destSlot, destIndex, destCount,
                false, 0f, HandLayout.FACE_UP_PITCH);
        DisplayPose otherFan = sortHeld(table)
                ? HandLayout.fanSlot(live.size(), destCount, table.getOrigin(), anchor, lock.placeYaw(),
                        lock.sitting(), false, 0f)
                : fan;
        boolean autoReveal = othersAllPublic(live, null);
        if (autoReveal) {
            otherFan = fan;
        }
        if (Cache.handDealTicks <= 0) {
            UUID tokenId = spawnPrivateHandCard(table, player, card, face, back, fan, destSlot);
            layoutHand(table, player, Cache.interpolationTicks, true, null);
            holdLayout(playerId, Cache.interpolationTicks);
            playCardSound(player);
            if (tokenId != null && autoReveal) {
                startSingleRevealFlip(table, player, tokenId);
            }
            runAfterDraw(player, after);
            return true;
        }
        Location courierOrigin = table.getOrigin().clone().add(0, stackTopY, 0);
        DisplayPose start = DisplayPose.flatOnTable(Cache.cardScale, 0f, table.getYaw());
        DisplayPose endOwner = poseOnStackOrigin(fan, stackTopY);
        DisplayPose endOther = poseOnStackOrigin(otherFan, stackTopY);
        UUID courierId = UUID.randomUUID();
        if (!DisplayManager.get().spawn(courierId, courierOrigin, back, start)) {
            table.getDeck().discard(card);
            rebuildCardStacks(table);
            save(table);
            player.sendMessage(Messages.get("place.spawn_failed"));
            return false;
        }
        DisplayManager.get().setLayoutOwner(courierId, playerId);
        revealBusy.add(playerId);
        int gen = dealGen.merge(playerId, 1, Integer::sum);
        selectAnimGen.merge(playerId, 1, Integer::sum);
        trackDealCourier(playerId, courierId);
        dealPendingCards.computeIfAbsent(playerId, key -> new ArrayList<>()).add(card);
        layoutHand(table, player, Cache.interpolationTicks, true, null, destIndex, 1, destSlot);
        holdLayout(playerId, Cache.handDealTicks + 3);
        playCardSound(player);
        flyCourier(table, player, courierId, start, start, endOwner, endOther, gen, () -> {
            finishDrawCourier(table, player, card, face, back, fan, courierId, gen, destSlot);
            runAfterDraw(player, after);
        });
        return true;
    }

    private void runAfterDraw(Player player, Runnable after) {
        if (after == null || player == null) {
            return;
        }
        UUID id = player.getUniqueId();
        long delay = revealBusy.contains(id) ? Math.max(1, Cache.handRevealFlip) + 3L : 1L;
        Bukkit.getScheduler().runTaskLater(Games.plugin, after, delay);
    }

    private UUID spawnPrivateHandCard(Table table, Player player, Card card, ItemStack face, ItemStack back,
            DisplayPose fan, int slot) {
        UUID tokenId = UUID.randomUUID();
        ItemStack spawnItem = back != null ? back : face;
        if (spawnItem == null || !DisplayManager.get().spawn(tokenId, table.getOrigin(), spawnItem, fan)) {
            table.getDeck().discard(card);
            rebuildCardStacks(table);
            save(table);
            return null;
        }
        DisplayManager.get().setLayoutOwner(tokenId, player.getUniqueId());
        if (back != null) {
            DisplayManager.get().setItem(tokenId, back);
        }
        if (face != null) {
            DisplayManager.get().setItemFor(tokenId, player, face);
        }
        HandCard held = new HandCard(card, tokenId);
        held.setSlot(slot);
        table.handOf(player.getUniqueId()).add(held);
        save(table);
        return tokenId;
    }

    private void finishDrawCourier(Table table, Player player, Card card, ItemStack face, ItemStack back,
            DisplayPose fan, UUID courierId, int gen, int slot) {
        UUID playerId = player.getUniqueId();
        if (!dealActive(playerId, gen, table.getId())) {
            return;
        }
        untrackDealCourier(playerId, courierId);
        DisplayManager.get().despawn(courierId);
        removePendingCard(playerId, card);
        UUID tokenId = spawnPrivateHandCard(table, player, card, face, back, fan, slot);
        layoutHand(table, player, 0, false, null);
        if (tokenId != null && othersAllPublic(table.handOf(playerId), tokenId)) {
            startSingleRevealFlip(table, player, tokenId);
            return;
        }
        revealBusy.remove(playerId);
    }

    private void startReturnCourier(Table table, Player player, Card card, DisplayPose fromOwner, DisplayPose fromOther,
            int gen) {
        UUID playerId = player.getUniqueId();
        Location discard = discardOrigin(table);
        float stackTopY = stackTopOffset(StackLayout.visibleLayers(table.getDeck().discarded(), table.getDeck().size()));
        Location courierOrigin = discard.clone().add(0, stackTopY, 0);
        DisplayPose startOwner = poseRelativeTo(fromOwner, table.getOrigin(), courierOrigin);
        DisplayPose startOther = poseRelativeTo(fromOther != null ? fromOther : fromOwner, table.getOrigin(),
                courierOrigin);
        DisplayPose end = DisplayPose.flatOnTable(Cache.cardScale, 0f, table.getYaw());
        UUID courierId = UUID.randomUUID();
        ItemStack back = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getBackItem());
        if (back == null || Cache.handDealTicks <= 0
                || !DisplayManager.get().spawn(courierId, courierOrigin, back, startOwner)) {
            finishReturnCard(table, player, card, null, gen);
            return;
        }
        DisplayManager.get().setLayoutOwner(courierId, playerId);
        DisplayManager.get().setTransformSplit(courierId, startOwner, startOther, 0);
        trackDealCourier(playerId, courierId);
        flyCourier(table, player, courierId, startOwner, startOther, end, end, gen,
                () -> finishReturnCard(table, player, card, courierId, gen));
    }

    private void finishReturnCard(Table table, Player player, Card card, UUID courierId, int gen) {
        UUID playerId = player.getUniqueId();
        if (!dealActive(playerId, gen, table.getId())) {
            return;
        }
        if (courierId != null) {
            untrackDealCourier(playerId, courierId);
            DisplayManager.get().despawn(courierId);
        }
        removePendingCard(playerId, card);
        table.getDeck().discard(card);
        rebuildCardStacks(table);
        save(table);
        if (dealStillFlying(playerId)) {
            return;
        }
        revealBusy.remove(playerId);
        List<HandCard> hand = table.getHands().get(playerId);
        if (hand == null || hand.isEmpty()) {
            table.getHands().remove(playerId);
            handLocks.remove(playerId);
            clearRevealed(playerId, hand);
            if (table.getHands().isEmpty()) {
                recycleIfNeeded(table, null);
            }
            return;
        }
        layoutHand(table, player, Cache.handFollowTicks, false, null);
    }

    private void flyCourier(Table table, Player player, UUID courierId, DisplayPose startOwner, DisplayPose startOther,
            DisplayPose endOwner, DisplayPose endOther, int gen, Runnable onArrive) {
        DisplayManager displays = DisplayManager.get();
        displays.setLayoutOwner(courierId, player.getUniqueId());
        displays.setTransformSplit(courierId, startOwner, startOther != null ? startOther : startOwner, 0);
        UUID playerId = player.getUniqueId();
        UUID tableId = table.getId();
        int ticks = Math.max(1, Cache.handDealTicks);
        DisplayPose fromOther = startOther != null ? startOther : startOwner;
        DisplayPose toOther = endOther != null ? endOther : endOwner;
        Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
            if (!dealActive(playerId, gen, tableId)) {
                return;
            }
            for (int step = 1; step <= ticks; step++) {
                final int s = step;
                Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
                    if (!dealActive(playerId, gen, tableId)) {
                        return;
                    }
                    float t = s / (float) ticks;
                    float ease = 1f - (1f - t) * (1f - t);
                    displays.setTransformSplit(courierId,
                            lerpPose(startOwner, endOwner, ease),
                            lerpPose(fromOther, toOther, ease),
                            1);
                    if (s == ticks) {
                        onArrive.run();
                    }
                }, s);
            }
        }, 1L);
    }

    private static DisplayPose lerpPose(DisplayPose start, DisplayPose end, float ease) {
        Vector3f a = start.translation();
        Vector3f b = end.translation();
        return end.withTranslation(
                a.x + (b.x - a.x) * ease,
                a.y + (b.y - a.y) * ease,
                a.z + (b.z - a.z) * ease);
    }

    private static List<HandCard> sortedCopy(List<HandCard> hand, String gameId) {
        List<HandCard> copy = new ArrayList<>(hand);
        copy.sort(java.util.Comparator.comparing(HandCard::card, HandLayout.orderFor(gameId)));
        return copy;
    }

    private static boolean sortHeld(Table table) {
        Game game = gameOf(table);
        return game == null || game.sortHeldCards();
    }

    private static List<HandCard> handOrder(Table table, List<HandCard> hand) {
        if (sortHeld(table)) {
            return sortedCopy(hand, table.getGameId());
        }
        return new ArrayList<>(hand);
    }

    private static int countInSlot(List<HandCard> hand, int slot) {
        int n = 0;
        for (HandCard held : hand) {
            if (held.slot() == slot) {
                n++;
            }
        }
        return n;
    }

    private static int indexInSlot(List<HandCard> hand, HandCard card) {
        if (card == null) {
            return -1;
        }
        int index = 0;
        for (HandCard held : hand) {
            if (held.slot() != card.slot()) {
                continue;
            }
            if (held == card || held.tokenId().equals(card.tokenId())) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static DisplayPose playerFanPose(Location origin, Location anchor, HandLock lock, int slot, int index,
            int count, boolean selected, float extraBump, float extraPitch) {
        float extra = extraBump + (slot == 1 ? 0.1f : 0f);
        DisplayPose pose = HandLayout.fanSlot(index, Math.max(1, count), origin, anchor, lock.placeYaw(),
                lock.sitting(), selected, extra, extraPitch);
        if (slot != 1) {
            return pose;
        }
        var t = pose.translation();
        double yawRad = Math.toRadians(lock.placeYaw());
        double fx = -Math.sin(yawRad);
        double fz = Math.cos(yawRad);
        double rx = fz;
        double rz = -fx;
        return pose.withTranslation(
                t.x + (float) (rx * 0.1),
                t.y + Cache.stackLayerGap,
                t.z + (float) (rz * 0.1));
    }

    private static float stackTopOffset(int layers) {
        if (layers <= 0) {
            return 0f;
        }
        return (layers - 1) * Cache.stackLayerGap;
    }

    private static DisplayPose poseOnStackOrigin(DisplayPose tablePose, float stackTopY) {
        Vector3f t = tablePose.translation();
        return tablePose.withTranslation(t.x, t.y - stackTopY, t.z);
    }

    private static DisplayPose poseRelativeTo(DisplayPose tableRelative, Location tableOrigin, Location spawn) {
        Vector3f t = tableRelative.translation();
        float dx = (float) (tableOrigin.getX() - spawn.getX());
        float dy = (float) (tableOrigin.getY() - spawn.getY());
        float dz = (float) (tableOrigin.getZ() - spawn.getZ());
        return tableRelative.withTranslation(t.x + dx, t.y + dy, t.z + dz);
    }

    private boolean dealActive(UUID playerId, int gen, UUID tableId) {
        if (!Integer.valueOf(gen).equals(dealGen.get(playerId))) {
            return false;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return false;
        }
        Table still = tableHolding(playerId);
        return still != null && still.getId().equals(tableId);
    }

    private boolean dealStillFlying(UUID playerId) {
        List<UUID> couriers = dealCouriers.get(playerId);
        if (couriers != null && !couriers.isEmpty()) {
            return true;
        }
        List<Card> pending = dealPendingCards.get(playerId);
        return pending != null && !pending.isEmpty();
    }

    private void trackDealCourier(UUID playerId, UUID courierId) {
        dealCouriers.computeIfAbsent(playerId, key -> new ArrayList<>()).add(courierId);
    }

    private void untrackDealCourier(UUID playerId, UUID courierId) {
        List<UUID> couriers = dealCouriers.get(playerId);
        if (couriers == null) {
            return;
        }
        couriers.remove(courierId);
        if (couriers.isEmpty()) {
            dealCouriers.remove(playerId);
        }
    }

    private void removePendingCard(UUID playerId, Card card) {
        List<Card> pending = dealPendingCards.get(playerId);
        if (pending == null) {
            return;
        }
        pending.remove(card);
        if (pending.isEmpty()) {
            dealPendingCards.remove(playerId);
        }
    }

    private void stopDeal(UUID playerId, Table table) {
        dealGen.merge(playerId, 1, Integer::sum);
        List<UUID> couriers = dealCouriers.remove(playerId);
        if (couriers != null) {
            for (UUID courierId : couriers) {
                DisplayManager.get().despawn(courierId);
            }
        }
        List<Card> pending = dealPendingCards.remove(playerId);
        if (table != null && pending != null) {
            for (Card card : pending) {
                table.getDeck().discard(card);
            }
        }
        revealBusy.remove(playerId);
    }

    private void layoutHand(Table table, Player player, int durationTicks, boolean forceHeading,
            UUID pulseToken) {
        layoutHand(table, player, durationTicks, forceHeading, pulseToken, -1, 0, 0);
    }

    private void layoutHand(Table table, Player player, int durationTicks, boolean forceHeading,
            UUID pulseToken, int gapIndex, int extraSlots) {
        layoutHand(table, player, durationTicks, forceHeading, pulseToken, gapIndex, extraSlots, 0);
    }

    private void layoutHand(Table table, Player player, int durationTicks, boolean forceHeading,
            UUID pulseToken, int gapIndex, int extraSlots, int extraOnSlot) {
        List<HandCard> hand = table.handOf(player.getUniqueId());
        Location origin = table.getOrigin();
        HandLock lock = lockHand(table, player, forceHeading);
        Location anchor = lockLocation(player, lock);
        List<HandCard> order = handOrder(table, hand);
        Map<UUID, DisplayPose> ownerPoses = new HashMap<>();
        for (HandCard held : order) {
            int groupSlot = held.slot();
            int n = countInSlot(order, groupSlot);
            int index = indexInSlot(order, held);
            if (extraSlots > 0 && groupSlot == extraOnSlot) {
                n += extraSlots;
                if (gapIndex >= 0 && index >= gapIndex) {
                    index++;
                }
            }
            float extra = pulseToken != null && pulseToken.equals(held.tokenId()) ? INSPECT_BUMP : 0f;
            ownerPoses.put(held.tokenId(), playerFanPose(origin, anchor, lock, groupSlot, index, n,
                    held.isSelected(), extra, HandLayout.FACE_UP_PITCH));
        }
        Map<UUID, DisplayPose> otherPoses = new HashMap<>();
        int otherIndex = 0;
        int otherCount = hand.size() + extraSlots;
        int otherGap = extraSlots > 0 ? hand.size() : -1;
        for (HandCard held : hand) {
            if (otherGap >= 0 && otherIndex == otherGap) {
                otherIndex++;
            }
            float extra = pulseToken != null && pulseToken.equals(held.tokenId()) ? INSPECT_BUMP : 0f;
            if (held.slot() != 0 || revealedCardTokens.contains(held.tokenId())) {
                otherPoses.put(held.tokenId(), ownerPoses.get(held.tokenId()));
            } else {
                otherPoses.put(held.tokenId(), HandLayout.fanSlot(
                        otherIndex, otherCount, origin, anchor, lock.placeYaw(), lock.sitting(),
                        held.isSelected(), extra));
            }
            otherIndex++;
        }
        DisplayManager displays = DisplayManager.get();
        UUID ownerId = player.getUniqueId();
        for (HandCard held : hand) {
            DisplayPose ownerPose = ownerPoses.get(held.tokenId());
            DisplayPose otherPose = revealedCardTokens.contains(held.tokenId()) || held.slot() != 0
                    ? ownerPose
                    : otherPoses.get(held.tokenId());
            displays.setLayoutOwner(held.tokenId(), ownerId);
            displays.setTransformSplit(held.tokenId(), ownerPose, otherPose, durationTicks);
        }
    }

    private HandLock lockHand(Table table, Player player, boolean force) {
        HandLock next = peekHandLock(table, player, force);
        handLocks.put(player.getUniqueId(), next);
        return next;
    }

    private HandLock peekHandLock(Table table, Player player, boolean force) {
        Location origin = table.getOrigin();
        HandAnchor.Raw raw = HandAnchor.resolve(player, origin);
        UUID id = player.getUniqueId();
        HandLock last = handLocks.get(id);
        boolean sitting = raw.sitting();
        double x = raw.location().getX();
        double z = raw.location().getZ();
        double y = raw.location().getY();
        boolean holdPos = !force && last != null && last.sitting() == sitting && Cache.handPosStick > 0
                && Math.hypot(x - last.x(), z - last.z()) < Cache.handPosStick;
        if (holdPos) {
            x = last.x();
            z = last.z();
            if (sitting) {
                y = last.y();
            }
        }
        if (!sitting) {
            y = player.getLocation().getY() + Cache.handLift;
        }
        float yaw;
        if (sitting) {
            yaw = holdPos ? last.placeYaw() : raw.placeYaw();
        } else {
            float candidateYaw = HandLayout.candidatePlaceYaw(
                    origin, player.getLocation(), table.getYaw(), BodyYaw.of(player));
            yaw = candidateYaw;
            if (!force && last != null && !last.sitting() && Cache.handYawStick > 0f
                    && Math.abs(HandLayout.wrapDegrees(candidateYaw - last.placeYaw())) < Cache.handYawStick) {
                yaw = last.placeYaw();
            }
        }
        return new HandLock(yaw, x, z, y, sitting);
    }

    private static boolean atTable(Player player, Table table) {
        if (player == null || table == null) {
            return false;
        }
        Location origin = table.getOrigin();
        Location loc = player.getLocation();
        if (origin == null || origin.getWorld() == null || loc.getWorld() == null
                || !origin.getWorld().equals(loc.getWorld())) {
            return false;
        }
        double leave = Cache.leaveDistanceOf(table.getGameId());
        if (leave <= 0) {
            return false;
        }
        if (Math.abs(loc.getY() - origin.getY()) > TABLE_Y_SLOP) {
            return false;
        }
        return origin.distance(loc) <= leave;
    }

    private static Location lockLocation(Player player, HandLock lock) {
        return new Location(player.getWorld(), lock.x(), lock.y(), lock.z());
    }

    private record HandLock(float placeYaw, double x, double z, double y, boolean sitting) {}

    private Table tableHolding(UUID playerId) {
        for (Table table : tables.values()) {
            List<HandCard> hand = table.getHands().get(playerId);
            if (hand != null) {
                return table;
            }
        }
        return null;
    }

    private void leaveIfAtTable(Player player, boolean force) {
        Table table = tableHolding(player.getUniqueId());
        if (table != null) {
            Location origin = table.getOrigin();
            if (!force && origin.getWorld() != null && player.getWorld().equals(origin.getWorld())
                    && origin.distance(player.getLocation()) <= Cache.leaveDistanceOf(table.getGameId())) {
                return;
            }
            returnHand(table, player, !force, true);
            return;
        }
        Table felt = tableWhereActive(player.getUniqueId());
        if (felt == null) {
            return;
        }
        Location origin = felt.getOrigin();
        if (!force && origin.getWorld() != null && player.getWorld().equals(origin.getWorld())
                && origin.distance(player.getLocation()) <= Cache.leaveDistanceOf(felt.getGameId())) {
            return;
        }
        Game game = GamesRegistry.of(felt.getGameId());
        if (game != null) {
            game.onLeave(felt, player);
            game.onChipIn(felt, player);
        } else {
            refundOwnedPiles(felt, player);
            felt.actives().remove(player.getUniqueId());
        }
        if (felt.getHands().isEmpty()) {
            recycleIfNeeded(felt, null);
        }
        save(felt);
    }

    private void returnHand(Table table, Player player, boolean notify, boolean refundChips) {
        muckPlayer(table, player);
        if (refundChips) {
            Game game = GamesRegistry.of(table.getGameId());
            if (game != null) {
                game.onLeave(table, player);
                game.onChipIn(table, player);
            } else {
                refundOwnedPiles(table, player);
                table.actives().remove(player.getUniqueId());
            }
        }
        if (notify && player.isOnline()) {
            player.sendMessage(Messages.get("hand.returned"));
        }
    }

    private void discardPlayerCards(Table table, UUID playerId) {
        List<HandCard> hand = table.getHands().remove(playerId);
        handLocks.remove(playerId);
        layoutHoldUntil.remove(playerId);
        selectAnimGen.merge(playerId, 1, Integer::sum);
        stopDeal(playerId, table);
        stopRevealExtras(playerId);
        clearRevealed(playerId, hand);
        if (hand != null) {
            DisplayManager displays = DisplayManager.get();
            for (HandCard held : hand) {
                table.getDeck().discard(held.card());
                displays.despawn(held.tokenId());
            }
        }
    }

    private void returnAllHands(Table table, boolean notify, boolean refundChips) {
        for (UUID playerId : new ArrayList<>(table.getHands().keySet())) {
            Player owner = Bukkit.getPlayer(playerId);
            if (owner != null) {
                returnHand(table, owner, notify, refundChips);
            } else {
                discardPlayerCards(table, playerId);
            }
        }
        rebuildCardStacks(table);
    }

    private void despawnHands(Table table) {
        for (UUID playerId : new ArrayList<>(table.getHands().keySet())) {
            stopDeal(playerId, table);
        }
        DisplayManager displays = DisplayManager.get();
        for (List<HandCard> hand : table.getHands().values()) {
            for (HandCard held : hand) {
                displays.despawn(held.tokenId());
            }
        }
        table.getHands().clear();
    }

    private boolean tooClose(Location origin) {
        double minSq = MIN_DISTANCE * MIN_DISTANCE;
        for (Table table : tables.values()) {
            Location other = table.getOrigin();
            if (other.getWorld() != null && other.getWorld().equals(origin.getWorld())
                    && other.distanceSquared(origin) < minSq) {
                return true;
            }
        }
        return false;
    }

    private Table tableFrom(Entity entity) {
        String raw = WorldAnchors.tableId(entity);
        if (raw == null) {
            return null;
        }
        try {
            return tables.get(UUID.fromString(raw));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void save(Table table) {
        File file = tableFile(table.getId());
        TableData data = toData(table);
        try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(data, writer);
        } catch (IOException ex) {
            Games.plugin.getLogger().warning("[Games] Failed to save table " + table.getId() + ": " + ex.getMessage());
        }
    }

    private void deleteFile(UUID id) {
        File file = tableFile(id);
        if (file.exists() && !file.delete()) {
            Games.plugin.getLogger().warning("[Games] Could not delete table file " + file.getName());
        }
    }

    private static File tablesFolder() {
        File folder = new File(Games.plugin.getDataFolder(), "Data/tables");
        if (!folder.exists()) {
            folder.mkdirs();
        }
        return folder;
    }

    private static File tableFile(UUID id) {
        return new File(tablesFolder(), id + ".json");
    }

    private static TableData toData(Table table) {
        TableData data = new TableData();
        data.id = table.getId().toString();
        data.gameId = table.getGameId();
        Location origin = table.getOrigin();
        data.world = origin.getWorld() != null ? origin.getWorld().getName() : "";
        data.x = origin.getX();
        data.y = origin.getY();
        data.z = origin.getZ();
        data.yaw = table.getYaw();
        data.setName = table.getDeck().getSetName();
        data.remaining = table.getDeck().remainingIds();
        data.discarded = table.getDeck().discardedIds();
        data.street = table.street();
        data.actives = new ArrayList<>();
        for (UUID id : table.actives()) {
            data.actives.add(id.toString());
        }
        data.piles = new ArrayList<>();
        for (PotPile pile : table.getPiles()) {
            PileData raw = toPileData(pile);
            if (raw != null) {
                data.piles.add(raw);
            }
        }
        data.ownerPlayer = table.ownerPlayer() != null ? table.ownerPlayer().toString() : null;
        data.ownerGuildId = table.ownerGuildId();
        data.autoDealer = table.autoDealer();
        data.staffMint = table.staffMint();
        data.minBet = table.minBet();
        data.maxBet = table.maxBet();
        data.maxBoxes = table.maxBoxes();
        data.shufflePolicy = table.shufflePolicy().name();
        data.smallBlind = table.smallBlind();
        data.bigBlind = table.bigBlind();
        return data;
    }

    private static Table fromData(TableData data) {
        World world = Bukkit.getWorld(data.world);
        if (world == null) {
            Games.plugin.getLogger().warning("[Games] Table world missing: " + data.world);
            return null;
        }
        Optional<Deck> deck = Deck.create(data.setName != null ? data.setName : Cache.cardSetOf(data.gameId), data.remaining,
                data.discarded);
        if (deck.isEmpty()) {
            return null;
        }
        Location origin = new Location(world, data.x, data.y, data.z, data.yaw, 0f);
        Table table = new Table(UUID.fromString(data.id), data.gameId, origin, data.yaw, deck.get());
        table.setStreet(data.street > 0 ? data.street : 1);
        if (data.actives != null) {
            for (String raw : data.actives) {
                try {
                    table.actives().add(UUID.fromString(raw));
                } catch (IllegalArgumentException ignored) {
                    // skip
                }
            }
        }
        if (data.piles != null) {
            for (PileData raw : data.piles) {
                PotPile pile = fromPileData(raw, table.street());
                if (pile != null) {
                    table.getPiles().add(pile);
                }
            }
        }
        applyHouseData(table, data);
        return table;
    }

    private static PileData toPileData(PotPile pile) {
        String item = encodeItem(pile.item());
        if (item == null) {
            return null;
        }
        PileData data = new PileData();
        data.owner = pile.ownerId() != null ? pile.ownerId().toString() : null;
        data.item = item;
        data.typeKey = pile.typeKey();
        data.denars = pile.denars();
        data.count = pile.count();
        data.pieces = pile.pieces();
        data.streetId = pile.streetId();
        data.x = pile.x();
        data.z = pile.z();
        data.layerYaws = new ArrayList<>();
        for (float yaw : pile.layerYaws()) {
            data.layerYaws.add((double) yaw);
        }
        return data;
    }

    private static PotPile fromPileData(PileData data, int tableStreet) {
        if (data == null || data.item == null) {
            return null;
        }
        int count = Math.max(0, data.count);
        int pieces = data.pieces > 0 ? data.pieces : count;
        if (count <= 0 && pieces <= 0) {
            return null;
        }
        ItemStack item = decodeItem(data.item);
        if (item == null) {
            return null;
        }
        item.setAmount(1);
        UUID owner = null;
        if (data.owner != null) {
            try {
                owner = UUID.fromString(data.owner);
            } catch (IllegalArgumentException ignored) {
                owner = null;
            }
        }
        String type = data.typeKey != null ? data.typeKey : ChipItems.typeKey(item);
        PotPile pile = new PotPile(owner, item, type, data.denars, count, data.x, data.z);
        pile.setPieces(pieces);
        pile.setStreetId(data.streetId > 0 ? data.streetId : tableStreet);
        if (data.layerYaws != null) {
            for (Double yaw : data.layerYaws) {
                if (yaw != null) {
                    pile.layerYaws().add(yaw.floatValue());
                }
            }
        }
        return pile;
    }

    private static String encodeItem(ItemStack item) {
        if (item == null) {
            return null;
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeObject(item);
            out.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException ex) {
            Games.plugin.getLogger().warning("[Games] Failed to encode pot item: " + ex.getMessage());
            return null;
        }
    }

    private static ItemStack decodeItem(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try (ByteArrayInputStream bytes = new ByteArrayInputStream(Base64.getDecoder().decode(raw));
                BukkitObjectInputStream in = new BukkitObjectInputStream(bytes)) {
            Object value = in.readObject();
            return value instanceof ItemStack stack ? stack : null;
        } catch (IOException | ClassNotFoundException | IllegalArgumentException ex) {
            Games.plugin.getLogger().warning("[Games] Failed to decode pot item: " + ex.getMessage());
            return null;
        }
    }

    private static UUID stackTokenId(UUID tableId, int layer) {
        return UUID.nameUUIDFromBytes(("games-stack-" + tableId + "-" + layer).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID discardTokenId(UUID tableId, int layer) {
        return UUID.nameUUIDFromBytes(("games-discard-" + tableId + "-" + layer).getBytes(StandardCharsets.UTF_8));
    }

    private static void consumeOne(ItemStack held, Player player) {
        int amount = held.getAmount();
        if (amount <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            held.setAmount(amount - 1);
        }
    }

    private static final class PlaceArm {
        private final String gameId;
        private final long expiresAt;
        private final boolean requireDeck;
        private final TableHouse house;

        private PlaceArm(String gameId, long expiresAt, boolean requireDeck, TableHouse house) {
            this.gameId = gameId;
            this.expiresAt = expiresAt;
            this.requireDeck = requireDeck;
            this.house = house;
        }
    }

    private static final class LootArm {
        private final UUID tableId;
        private final ItemStack item;
        private final int denars;
        private final BukkitTask expireTask;

        private LootArm(UUID tableId, ItemStack item, int denars, BukkitTask expireTask) {
            this.tableId = tableId;
            this.item = item;
            this.denars = denars;
            this.expireTask = expireTask;
        }
    }

    static final class TableData {
        String id;
        String gameId;
        String world;
        double x;
        double y;
        double z;
        float yaw;
        String setName;
        List<String> remaining = new ArrayList<>();
        List<String> discarded = new ArrayList<>();
        int street = 1;
        List<String> actives = new ArrayList<>();
        List<PileData> piles = new ArrayList<>();
        String ownerPlayer;
        String ownerGuildId;
        Boolean autoDealer;
        Boolean staffMint;
        int minBet;
        int maxBet;
        int maxBoxes;
        String shufflePolicy;
        Integer smallBlind;
        Integer bigBlind;
    }

    static final class PileData {
        String owner;
        String item;
        String typeKey;
        int denars;
        int count;
        int pieces;
        int streetId;
        double x;
        double z;
        List<Double> layerYaws = new ArrayList<>();
    }
}
