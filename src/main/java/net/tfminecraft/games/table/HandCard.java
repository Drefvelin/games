package net.tfminecraft.games.table;

import java.util.UUID;

import net.tfminecraft.games.card.Card;

/** One card in a player's in-memory hand. */
public final class HandCard {

    private final Card card;
    private final UUID tokenId;
    private boolean selected;
    private boolean faceUp;
    private int slot;

    public HandCard(Card card, UUID tokenId) {
        this(card, tokenId, false);
    }

    public HandCard(Card card, UUID tokenId, boolean faceUp) {
        this.card = card;
        this.tokenId = tokenId;
        this.faceUp = faceUp;
    }

    public Card card() {
        return card;
    }

    public UUID tokenId() {
        return tokenId;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean faceUp() {
        return faceUp;
    }

    public void setFaceUp(boolean faceUp) {
        this.faceUp = faceUp;
    }

    public int slot() {
        return slot;
    }

    public void setSlot(int slot) {
        this.slot = Math.max(0, slot);
    }
}
