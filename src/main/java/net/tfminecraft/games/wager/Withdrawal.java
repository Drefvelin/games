package net.tfminecraft.games.wager;

import java.util.List;

/**
 * Money an account has agreed to hand over but has not handed over yet.
 *
 * <p>Planning and doing are separate so a transaction can check every leg first and then
 * apply them all, rather than applying as it goes and hoping it can walk the earlier ones back.
 */
public interface Withdrawal {

    /** Exact denars this will hand over. */
    int denars();

    /** The coin kinds and counts about to move, for valuing and for drawing the animation. */
    List<Stake> preview();

    /** Do it. Returns the coins removed, which is exactly what the destination receives. */
    List<Stake> take();
}
