package net.tfminecraft.games.wager;

/** What {@link WagerEngine#payWin} moved, and how much of it came off the tray. */
public record PayWinResult(int moved, int trayMoved, int owe) {

    public static final PayWinResult NONE = new PayWinResult(0, 0, 0);
}
