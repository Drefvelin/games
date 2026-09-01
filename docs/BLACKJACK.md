# Blackjack (Phase 2) - locked rules

Engines stay dumb. [BlackjackGame](../src/main/java/net/tfminecraft/games/game/BlackjackGame.java) owns rules, turns, and which engine calls to make. Do not put 21 or house payout in `Deck`, `Display`, or `Wager`.

Hold'em is Phase 3. Chip **mountain / ring pack** (visual heaps) is later, not this phase.

Player-facing strings: no em dash (U+2014).

---

## House model (Vegas)

There is **no shared pot**. Each player bets in a box. The house pays wins and takes losses.

- **Table min / max** is the **box total**, not each click. A place is refused only if `have + this chip > max`. Building with 1-denar coins up to min is allowed. After the box is at min, 1s and 5s (coins only) may be added until max. A box under min at close is not a legal box (refund that felt, no deal to it). First place that brings a box to min starts the auto bet window.
- **Bets are coins only.** DenarEconomy coins and configured gold/silver coin items. No gold ingot, no `wager.items`, no `/wager` loot.
- **Double and split are allowed.** The dealer **cannot refuse** them. The **player** must put out an equal extra bet. If they cannot, the action fails (not a dealer no).
- **Doubled hands pay even money**, not 3:2. Naturals (two-card 21) pay **3:2**. Usual strip limits: split pairs, double after split (DAS), doubled/split hands are not naturals.
- **House bank is unbounded.** A player banker can lose a lot. Auto dealer covers each box bet 1:1 on the tray (same stored items) and mints any extra shortfall (naturals). Tray stacks spread around the tray centre (not one pile). A total holo sits over the tray.
- **Six boxes.** `max-boxes: 6`. A seventh player cannot place until a box is empty. Adding onto an existing box is still allowed.
- **Insurance / even money / surrender / dealer peek** are **out of Phase 2**.

Payouts: losing bets **flush to the house tray and stay** (player dealer: still inventory of the dealer; auto: piles land on the tray). Push and win stakes return to inventory as the **stored items**, never the gold display model. Win extra is the same item type. Never `/games payout` the whole felt like a poker pot.

---

## Layout (table-local, shoe at origin)

Same axes as [games.yml](../src/main/resources/games.yml): **forward** = table yaw, **right** = perpendicular.

| Name | Role |
|------|------|
| Shoe | Origin. Click: after close, **dealer deals**; during a player's turn, **hit**. |
| `piles.dealer` | House cards (`forward: 0.5`, `right: 0.55`). Each card yaw +180 so players can read it; hole stays on the dealer left. |
| `piles.tray` | House chips, further that same side (`right: 1.10`). |
| `stand` | Dealer **claim** point (behind and one block left of the shoe). Not a seat. |
| `bet-zone` | Disk in front of the player: centre at `hand.distance` + `radius` toward the shoe, sticky yaw. Default radius 0.5. |
| `felt` | Fallback table box if `bet-zone` is missing. |

**No-bet radius 0.5** around shoe origin **and** tray centre. Chip place in that radius is refused like min/max.

Seats are not enforced. Players stand wherever; bets go in a 0.5-radius disk centred 1 block toward the shoe (0.5 past the fan).

---

## Player dealer

1. Stand within **0.5** of `stand`. Right-click the **shoe** while idle (no live round) → **claim**. Message + label.
2. After claim, **position does not matter**. They may walk away for money. **Do not** run `onLeave` / drop claim from `leave-distance`.
3. **Logout or pickup** clears dealer.
4. Only the claimed dealer: `/games bet min|max|open|close` and **deal click** after close.
5. Two people at stand: first claim wins until they log out.

Commands (permission `games.bet`, default true, **not** admin-only). Nearest blackjack table.

| Command | When |
|---------|------|
| `/games bet min <n>` | Claimed, bets not open |
| `/games bet max <n>` | Claimed, bets not open |
| `/games bet open` | Claimed. Players may place legal bets |
| `/games bet close` | Claimed, at least one legal bet (or allow close empty = cancel window). **Round starts**: `beginSession`, no new boxes |
| `/games bet stand` | Current actor, after cards |
| `/games bet double` | Current actor; then equal chips on felt |
| `/games bet split` | Current actor, pair; then equal chips |

`/games session start|stop` is admin/sandbox. BJ round control is **bet open/close**, not session start.

---

## Auto dealer

`games.yml` `blackjack.auto-dealer` is the **staff-GUI default**, not "every placed table mints." Full house rules: [GUILD_TABLES.md](GUILD_TABLES.md).

Until that phase ships, current tables still:

- No claim. House is the table when layout auto is on.
- Min/max from config (`blackjack.min-bet`, `blackjack.max-bet`).
- First **legal** bet starts a **10s** window (`blackjack.bet-seconds`). Label under the name counts down.
- Timer end → close + deal (no shoe click).
- Each legal box bet (and double/split extra) adds to the house tray **only the shortfall** so tray denars cover action 1:1. If the tray already covers the new total, nothing is minted. Leave during the bet window peels cover only when the tray was short of that stake (leftover bank stays). Wins take extra from the tray first. Losses flush the player's felt onto the tray (visible) and leave the cover.

After guild house: **staff mint** keeps that spawn path. **Guild auto** withdraws the same shortfall from the owning guild bank (refuse the chip-in if the bank cannot cover). Human dealers stock the tray.

---

## Cards and turns

After close, deal **in order**: one card to each box, one to dealer, second to each box, second to dealer (hole). Player cards use the **hand engine** (follow and sit like free play). Faces are public. Do not use sandbox free draw. Dealer hole stays face-down until the dealer plays.

During a player's turn:

- **Hit:** shoe right-click, `/games bet hit`, or saying `hit` in chat (case insensitive). Same voice line.
- **Stand / double / split:** `/games bet …` or the same words in chat.

**Split:** second hand on the **same fan**, a bit toward the shoe and to the player's right, overlapping the first hand and slightly higher (card-layer gap).

Then next box. Dealer plays last (auto stand on 17, hit below; lock **S17**). Config `dealer-hits-soft-17: false` (S17).

Compare vs dealer; take/lose/push. After the last card, wait `result-delay-ticks` then send bust/win/push. Cards stay for `round-end-seconds` (label countdown), then muck and `endSession`.

---

## Voice (RPCharacters)

Softdepend. **Do not** `performCommand("rp ...")`.

After a **successful** action, `ChatManager.dispatch(player, channel, line, true)` with channel from config (`voice.channel: rp`). Lines: `hit`, `stand`, `double`, `split`.

If RPCharacters is missing, channel missing, or no active character: **skip speech**, action still counts. Do not spam "need a character" on every hit.

---

## Label

Shoe TextDisplay (gold title, aqua dealer, gray limits, green Open, gold countdown):

- `min-max`, dealer name or `Auto`, auto countdown while the bet window is open
- **Turn:** current actor name, or `Dealer`, during play
- **Action:** each box stake (`Name 10`) and a **total** of those stakes. Not a poker pot. Do not list payouts here.

**Card totals** sit on the **dealer** cards only (upcard or `6 + ?` while the hole is down; full / soft / bust after). Players read their own hands. No holo on box or split piles.

Turn, box stakes, and claimed dealer use the player's active RPCharacters display name (`%rpcharacters_display%`) when RPC is present.

No em dash in any of these strings.

---

## Out of Phase 2

Hold'em, insurance, mountain chip pack, enforced seats, player-dealer accept/refuse of doubles.
