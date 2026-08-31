package net.tfminecraft.games.deck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.card.Card;
import net.tfminecraft.games.loader.CardLoader;

/**
 * One shoe from a named composition. Does not know poker.
 */
public final class Deck {

    private final String setName;
    private final Set<String> compositionIds;
    private final int compositionSize;
    private final List<Card> remaining;
    private final List<Card> discarded;

    private Deck(String setName, List<Card> cards) {
        this.setName = setName;
        this.compositionSize = cards.size();
        this.compositionIds = new HashSet<>();
        for (Card card : cards) {
            compositionIds.add(card.getId());
        }
        this.remaining = new ArrayList<>(cards);
        this.discarded = new ArrayList<>();
    }

    public static Optional<Deck> create(String setName) {
        if (!CardLoader.hasSet(setName)) {
            Games.plugin.getLogger().warning("[Games] Unknown card set: " + setName);
            return Optional.empty();
        }
        List<Card> cards = CardLoader.getSet(setName);
        if (cards.isEmpty()) {
            Games.plugin.getLogger().warning("[Games] Card set is empty: " + setName);
            return Optional.empty();
        }
        return Optional.of(new Deck(setName, cards));
    }

    public String getSetName() {
        return setName;
    }

    public void shuffle() {
        Collections.shuffle(remaining);
    }

    public Optional<Card> draw() {
        if (remaining.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(remaining.remove(0));
    }

    public int remaining() {
        return remaining.size();
    }

    public int discarded() {
        return discarded.size();
    }

    public int size() {
        return compositionSize;
    }

    public void discard(Card card) {
        if (card == null || !compositionIds.contains(card.getId())) {
            if (Cache.debug && Games.plugin != null) {
                Games.plugin.getLogger().info("[Games] Debug: ignored discard of card not in set "
                        + setName + ": " + (card == null ? "null" : card.getId()));
            }
            return;
        }
        discarded.add(card);
    }

    /**
     * Move discard onto the shoe and shuffle. Runs even if discard is empty.
     */
    public void reshuffleAll() {
        remaining.addAll(discarded);
        discarded.clear();
        Collections.shuffle(remaining);
    }

    /**
     * Move the discard pile onto the shoe and shuffle. No packets.
     */
    public void recycle() {
        if (discarded.isEmpty()) {
            return;
        }
        remaining.addAll(discarded);
        discarded.clear();
        Collections.shuffle(remaining);
    }

    public List<String> remainingIds() {
        return idsOf(remaining);
    }

    public List<String> discardedIds() {
        return idsOf(discarded);
    }

    private static List<String> idsOf(List<Card> cards) {
        List<String> ids = new ArrayList<>(cards.size());
        for (Card card : cards) {
            ids.add(card.getId());
        }
        return ids;
    }

    /**
     * Restore a shoe with a saved remaining and discard order. Unknown ids are skipped.
     */
    public static Optional<Deck> create(String setName, List<String> remainingIds) {
        return create(setName, remainingIds, null);
    }

    public static Optional<Deck> create(String setName, List<String> remainingIds, List<String> discardedIds) {
        Optional<Deck> created = create(setName);
        if (created.isEmpty()) {
            return Optional.empty();
        }
        Deck deck = created.get();
        if (remainingIds != null) {
            deck.remaining.clear();
            fill(deck, deck.remaining, remainingIds);
        }
        deck.discarded.clear();
        if (discardedIds != null) {
            fill(deck, deck.discarded, discardedIds);
        }
        return Optional.of(deck);
    }

    private static void fill(Deck deck, List<Card> into, List<String> ids) {
        for (String id : ids) {
            Card card = CardLoader.get(id);
            if (card != null && deck.compositionIds.contains(card.getId())) {
                into.add(card);
            }
        }
    }
}
