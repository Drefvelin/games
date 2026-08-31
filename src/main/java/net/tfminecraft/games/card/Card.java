package net.tfminecraft.games.card;

/**
 * Catalog card. Poker ranking is not defined here.
 */
public final class Card {

    private final String id;
    private final String suit;
    private final int rank;
    private final boolean joker;
    private final String item;

    public Card(String id, String suit, int rank, boolean joker, String item) {
        this.id = id;
        this.suit = suit;
        this.rank = rank;
        this.joker = joker;
        this.item = item;
    }

    public String getId() {
        return id;
    }

    public String getSuit() {
        return suit;
    }

    public int getRank() {
        return rank;
    }

    public boolean isJoker() {
        return joker;
    }

    public String getItem() {
        return item;
    }
}
