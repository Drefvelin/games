package net.tfminecraft.games.table;

import java.util.UUID;

import net.tfminecraft.games.wager.PotPile;

/**
 * One chip stack flying to one destination, for show only: the money it depicts already
 * moved in the ledger, and the stack is thrown away when it lands.
 */
public final class PayoutFlight {

    private final PotPile pile;
    private final UUID destId;
    private final boolean stayOnTray;

    public PayoutFlight(PotPile pile, UUID destId) {
        this(pile, destId, false);
    }

    private PayoutFlight(PotPile pile, UUID destId, boolean stayOnTray) {
        this.pile = pile;
        this.destId = destId;
        this.stayOnTray = stayOnTray;
    }

    public static PayoutFlight toTray(PotPile pile) {
        return new PayoutFlight(pile, null, true);
    }

    public PotPile pile() {
        return pile;
    }

    public UUID destId() {
        return destId;
    }

    public boolean stayOnTray() {
        return stayOnTray;
    }
}
