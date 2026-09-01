# Games - Implementation batches

Work in order. Each batch should compile and be testable before the next.

**Games (conceptually done):** blackjack is playable. Tenceur Hold'em (`poker`) and Five-Draw (`draw`) have all numbered batches checked; both still need in-game testing. Shuffle GUI for Five-Draw, Hold'em blind posting, burns, jacks-or-better, and ante stay later unless a lock says otherwise. Tables persist location and house settings; shutdown/crash boot resets each table to idle (no mid-hand resume).

See [SYSTEM.md](SYSTEM.md) for engines vs poker and packet ItemDisplay rules.
See [TEST_MATRIX.md](TEST_MATRIX.md) for the manual checklist (filled as batches land).

---

## Batch 1 - Scaffold

- [x] Maven project, plugin.yml, config/messages/cards/games yaml
- [x] Bootstrap class, ConfigLoader, GamesLoader, Cache, Messages
- [x] `/game reload`
- [x] Documentation (`SYSTEM.md`, this file, `TEST_MATRIX.md`)

**Test:** `mvn package`, plugin enables, `/game reload` succeeds.

---

## Batch 2 - ItemsAdder pack `tfmc_games`

- [x] `ItemsAdder/tfmc_games/contents/categories.yml` and `items.yml`: `deck`, `card_back`, `card_base`, 52 faces (cerrith/mitlan/oseni/seithr)
- [x] `PAPER`, `generate: true`, one texture each (same style as Magic artifacts)
- [x] Ace as `_1` (from source `_14`). Textures under `resourcepack/assets/tfmc_games/textures/item/cards/`
- [x] Fill `cards.yml`: id, suit, rank, IA id. Sets `french_52` and `french_54` are both 52 faces until joker textures exist

**Test:** Copy the pack into ItemsAdder; `/iaget tfmc_games:deck` and a face/back item exist. No jokers in the pack yet.

---

## Batch 3 - Deck engine (no world yet)

- [x] `card/` ids and composition from `cards.yml`
- [x] `deck/` create from a named set, shuffle, draw, remaining, return
- [x] Never yield a card outside the set
- [x] Poker stub composition is `french_52` (`games.yml` `poker.card-set`)

**Test:** `/game deck test` (or `/game deck test french_52`): shuffled 52, draw until empty, 0 jokers, return remaining 1.

---

## Batch 4 - Display engine

- [x] ProtocolLib bridge (same enable/disable idea as RPCharacters `ProtocolLibBridge`)
- [x] `FakeItemDisplayPackets`: spawn `ITEM_DISPLAY`, item + transform metadata, destroy
- [x] Shared token id for all viewers; `setItemFor` for faces
- [x] Transform interpolation only (no teleport for slides)
- [x] Viewer tracking: join, quit, chunk
- [x] Real Interaction helper for clickables
- [x] Real TextDisplay helper for the public table label

**Test:** `/game display test`. You see a face, others see `card_back`. Card slides via transform, then despawns. Right-click the Interaction to confirm the anchor.

---

## Batch 5 - Place table

- [x] `/game place poker` then place the deck item
- [x] Bind `gameId=poker`, persist Gson under `Data/tables/`
- [x] Visible stack: at most `stack-visible-max` backs, layers scale with remaining
- [x] TextDisplay label "Poker"
- [x] Break/pickup despawns displays, drops the deck item, removes save

**Test:** Place on a table, reload chunk and server, stack and label return; left-click the Interaction to pick up.

---

## Batch 6 - Draw to hand

- [x] Right-click deck Interaction: `deck.draw()`, lerp via transform into a fan
- [x] Fan between player and stack, Y locked to table height (standing still works)
- [x] Auto-sort in layout (suit then rank, jokers last)
- [x] Per-viewer faces; others see `card_back`
- [x] `games.yml` `leave-distance`: return cards, restack, `Game.onLeave`

**Test:** Two players; each sees own faces; walk away returns cards; stack layers drop as the shoe shrinks.

---

## Batch 7 - Card select and return

- [ ] Closest own-hand card on the look ray (furniture-style click point vs token)
- [ ] Valid hit swings the main hand and toggles select; selected cards use `hand.selected-distance`
- [ ] Right-click shoe Interaction with a selection returns those cards, no draw
- [ ] Empty selection still draws; leave-distance still returns the whole hand

**Test:** Toggle cards in the fan (they sit farther out); right-click the shoe to put them back; stack grows; no extra draw. Missed clicks do not swing.

---

## Batch 8 - Wager engine + pot

- [ ] DenarEconomy softdepend then depend
- [ ] Escrow denars; item propose with claimed value; others must agree
- [ ] Named `pot` anchor in layout YAML (3x3 tweak later)
- [ ] Coin ItemDisplays when models exist; other items as their own displays
- [ ] Shift+right-click the shoe flushes the pot to the clicker (`Game.allowManualPotFlush`; poker will return false in-hand)
- [ ] No poker betting rules (blinds, call, raise)

**Test:** Put denars and an agreed item in the pot; cancel/leave refunds; winner payout path stubbed or admin settle.

---

## Batch 9 - Poker rules

- [ ] Turns, legal actions, hand ranking, showdown
- [ ] Blocks manual pot flush (`allowManualPotFlush`) while a hand is in progress
- [ ] Side pots only if required
- [ ] Uses deck, display, layout, table, wager, select only (no second display stack)

**Test:** Full hand with two+ players per TEST_MATRIX poker section.

---

## Phase 1 - Session and auto-deal

### Batch 1 - Session flags

- [x] In-memory `live`, `actor`, `phase` on Table (not persisted)
- [x] `startSession` / `clearSession`; clear on pickup and plugin unload
- [x] `/games session start` and `/games session stop` (nearest table)

**Test:** Place a table, start then stop; restart the server: table is idle (no live in JSON).

### Batch 2 - Locks while live

- [x] Default `allowFreeDraw` / `allowReturnSelected` / `allowManualPotFlush` are `!table.live()`
- [x] Live session: no free draw, no selected return, no shift-flush (`hand.locked` / `wager.no_flush`)

**Test:** `/games session start`: shoe draw, selected return, and shift-flush fail; `/games session stop`: sandbox works again.

### Batch 3 - dealToPlayer

- [x] `dealToPlayer(table, player, n)` sequential courier; skips live shoe lock and empty-hotbar
- [x] `/games deal [n]` (nearest table, default 1)

**Test:** `/games deal 2` while idle and after `/games session start`; holding an item does not block deal; shoe click stays locked while live.

### Batch 4 - Table-owned piles

- [x] Named `board` / `dealer` piles in front of the shoe (`table.board-offset`)
- [x] `dealToTable`; `/games deal table <board|dealer> [n] [back]`
- [x] Pickup and unload discard those cards; not persisted

**Test:** `/games deal table board 3` faces; `... 3 back` backs; pickup clears. Works while session live.

### Batch 5 - Muck and endSession

- [x] `muckPlayer` / `muckTable`: tokens to discard; player muck does not refund or drop actives
- [x] `endSession`: clear live/actor/phase; muck table piles only
- [x] `/games session stop` calls `endSession`

**Test:** Deal board + player cards; stop: board gone, player still holding; walk away: holes to discard.

### Batch 6 - Auto-start hook

- [x] `minActives()` default 0; poker does not override
- [x] `tryBeginSession` after chip-in; `beginSession` + `onSessionStart`
- [x] Live shoe click: `onShoeClick` (no free draw)

**Test:** Chip in does not start a session. `/games session start` works. `debug: true` logs `onSessionStart`. Live shoe click is silent.

### Per-game table layout

- [x] `games.yml` piles (forward/right) and felt (`ring` or `box`) per game
- [x] Blackjack stub: `/games place blackjack`, GUI icon `mitlan_7`, dealer pile left of the shoe

**Test:** Poker board/dealer and chip ring unchanged. Blackjack: `/games deal table dealer 2` left of shoe; chips on the forward box, not on the shoe.

---

## Phase 2 - Blackjack

Lock: [BLACKJACK.md](BLACKJACK.md). Vegas min/max, double/split, unbounded house. No Hold'em.

### Batch 1 - Claim and house layout

- [x] `stand` + `piles.tray` in `games.yml`; no-bet radius 0.5 on shoe and tray
- [x] Idle shoe click in stand range claims player dealer; logout/pickup clears; leave-distance does not
- [x] Label shows dealer name

**Test:** Claim at stand, walk away, still dealer; log out, claim free. Chips in the 0.5 shoe/tray radius are refused.

### Batch 2 - Bet window

- [x] `/games bet min|max|open|close` (`games.bet`, not admin)
- [x] Open: place on felt. Under min / over max: restore chips, no pile
- [x] Close: `beginSession`, no new boxes

**Test:** Max 100; 200 place cancelled. Close then chip place ignored.

### Batch 3 - Deal, hit, stand, settle

- [x] After close, dealer shoe click deals boxes then dealer (order in BLACKJACK.md)
- [x] Actor hit = shoe or `/games bet hit`; `/games bet stand`
- [x] S17 dealer; even money vs dealer; muck; `endSession`

**Test:** Two players, hit/stand, winner paid from tray or spawn stub, holes mucked.

### Batch 4 - 3:2, double, split

- [x] Natural 3:2; doubled hands even money
- [x] Double/split commands; player must place equal extra; DAS
- [x] Dealer cannot refuse; player cannot fund extra → action fails

**Test:** Pair split, double one hand, natural 3:2 on a two-card 21 only.

### Batch 5 - Auto dealer

- [x] `auto-dealer` + min/max + `bet-seconds` (10)
- [x] First legal bet starts countdown on label; then close + deal
- [x] Pays by spawning chips

**Test:** Auto table, one min bet, 10s, cards out, no claim.

### Batch 6 - Voice

- [x] Softdepend RPCharacters; `ChatManager.dispatch`; `voice.channel` + lines
- [x] Speech after success only; missing RPC/character skips speech

**Test:** Hit shows in `rp` like `/rp Hit.` without the player running `/rp`.

---

## Phase 2 polish - Blackjack playtest

New set, batches 1-5. Lock: [BLACKJACK.md](BLACKJACK.md). Phase 2 batches 1-6 stay done. Do not start Hold'em.

### Batch 1 - Min is box total

- [x] Place refused only if `have + chip > max`
- [x] 1-denar chips can build to min; after min, add 1s/5s until max
- [x] Auto window starts when a box first reaches min
- [x] Close: refund boxes still under min (not a legal box)

**Test:** Ten 1-gold clicks make a min-10 box. An 11th 1-gold after min stays. A 5-gold-only box is refunded at close.

### Batch 2 - Lose to tray

- [x] Auto lose: flush felt onto the tray; piles stay
- [x] Player dealer lose still flushes to the dealer
- [x] Stop void-at-shoe for auto losses

**Test:** Bust chips fly to the tray and sit there. Win extra still comes off the tray.

### Batch 3 - Shoe label

- [x] Turn: actor name, or `Dealer`, during play
- [x] Each box stake (`Name 10`) and total action
- [x] Not a poker pot; no payout math on the shoe

**Test:** During play the hologram shows whose turn and the box totals.

### Batch 4 - Public pad cards and split

- [x] Player cards are public table piles on that player's bet pad
- [x] Dealer hole stays down until the dealer plays
- [x] Split: second hand on the same pad, up-right, overlapping, slight Y lift
- [x] Stop shoe-row `split-<uuid>`

**Test:** Other players can read all faces. Split sits on the first hand, not at the shoe.

### Batch 5 - Card total holos

- [x] Holo over the dealer pile: upcard or `N + ?` while the hole is down; full total after (soft `7/17`); `Bust`
- [x] Same total over each player box once cards are public
- [x] No em dash

**Test:** Dealer total readable from standing. Player box shows 18.

---

## Guild house

New set. Lock: [GUILD_TABLES.md](GUILD_TABLES.md). Phase 2 blackjack stay done. Do not start Hold'em. No create / dealer / bank Bukkit events. SF does not depend on Games.

### Batch 1 - Per-table options + GUI

- [x] Table fields + Gson: `ownerPlayer`, `ownerGuildId`, `autoDealer`, `staffMint`, min/max, `maxBoxes`, shuffle policy
- [x] After blackjack in game-select, second GUI (auto, staff mint if `games.autodealer.staff`, min/max, boxes, shuffle)
- [x] Place uses those fields. Yaml `auto-dealer` pre-checks staff mint only; player default auto off
- [x] Sneak-click shoe while not live reopens options; live round refuses
- [x] Existing tables: keep current layout auto until picked up and re-placed

**Test:** Place BJ with auto off; min/max from GUI apply. Staff with perm can check mint. Reload chunk: fields persist.

### Batch 2 - SF cap (Games softdepend)

- [x] Games `pom` + `plugin.yml` softdepend SimpleFactions; isolated guild helper (no SF types in `BlackjackGame`)
- [x] SF: `GuildModifier.AUTO_DEALER_TABLES`; expansion in `upgrades.yml` for `guild` and `realm`
- [x] Cap = `Guild.getModifier(AUTO_DEALER_TABLES)`. Count guild-auto tables (`auto && !staffMint`) with that `ownerGuildId`
- [x] Place or toggle auto on: refuse if over cap, no guild, or SF missing (unless staff mint)
- [x] Toggle auto off / pickup guild-auto: slot frees. No table-id list in SF
- [x] Cap drop: freeze new rounds on all counted tables; do not stop a live round

**Test:** Cap 1: first guild auto places, second refused. Staff mint does not consume the slot. Without SF, guild auto refused.

### Batch 3 - Guild bank float

- [x] Guild auto cover: withdraw `max(0, action - tray)` then spawn; bank short / bankrupt / no bank → refuse that chip-in (restore item)
- [x] Staff auto: mint only, no withdraw
- [x] Settle extra (3:2) uses the same withdraw-or-fail for tray shortfall
- [x] Pickup / teardown: deposit remaining tray denars to that guild bank, then despawn tray
- [x] Missing guild on cover: refuse auto cover (do not mint)

**Test:** Bank 50, bet 10: wealth 40, tray 10. Second bet 10 with tray 10: no withdraw. Pickup: tray returns to bank. Empty bank: bet restored, no pile.

### Batch 4 - Dealer guild claim

- [x] Not during a live round
- [x] Shoe claim: member of `ownerGuildId` (or staff); same player again unsets
- [x] Takeover guild auto between rounds: human dealer, auto off, tray stays, cap slot frees
- [x] Cannot takeover staff-mint tables
- [x] No guild on table: placer or staff only

**Test:** Non-member cannot claim. Dealer click unsets. Mid-round click ignored. Takeover auto: next round needs a human close; expansion slot usable on another table.

### Batch 5 - Shuffle policy

- [x] `ROUND`: reshuffle full shoe at round start. `SHOE`: current recycle-when-empty
- [x] Options GUI persists policy. Default `SHOE`

**Test:** Round policy: after muck the shoe is a full shuffled set. Shoe policy: burned cards stay out until recycle.

---

## Later

| Scope |
|-------|
| Chess (or other games) on the same table + display |
| 3D coin models for the pot |
| Seat furniture / sit binding |

Do not start a later batch while the previous is untested.

---

## Phase 3 - Hold'em

Lock: [HOLDEM.md](HOLDEM.md). Blackjack and guild house stay done. Engines stay dumb. Grow `PokerGame` only.

### Batch 1 - Seats, button, blinds

- [x] `poker.blinds.small` / `big` in `games.yml`; hologram `label.blinds` / `label.button`
- [x] Chip-in seats (`actives` insertion order). First seated player is the button (`dealerId`)
- [x] Button passes to the next seated player on leave or logout
- [x] No deal, no posting, `minActives` 0, idle shoe still draws

**Test:** Two chip-ins: first is Button. First leaves: button moves. Last leaves: no button line. Sandbox draw still works.

### Batch 2 - Hole cards and live lock

- [x] Seated shoe click with 2+ `actives` starts a session (idle click not consumed if fewer or unseated)
- [x] Deal one around then one around, left of the button first; `ROUND` reshuffle at start
- [x] Live: no free draw / selected return; F still works; session stop mucks holes, no button rotate
- [x] Live leave to fewer than 2 seated ends the session

**Test:** Two seats, click shoe: two hole cards each. One player: still draws. Stop: muck, idle draw. One walks: session ends.

### Batch 3 - Preflop betting

- [x] Chat `check` / `call` / `fold` / `raise` via play words; `Game.allowPlayChat` / `onPlayWord` (no `if poker` in TableManager)
- [x] Street `currentBet`, folded, acted; contrib is this-street owned piles; actor left of button after the hole deal
- [x] Fold-win: muck holes, `endSession`, `passButton`; all matched: actor null, `poker.street_done`; no flop, no blinds posted
- [x] Label turn + `label.holdem_tocall`; copy keys in `messages.yml`

**Test:** Two check: street done, live, no board. Raise then short call: need chips. Fold: win, button moves. Not-actor chat ignored.

### Batch 4 - Flop, turn, river

- [x] Street match auto-deals flop 3 / turn 1 / river 1 face-up via `dealToTable` `board`; bump `table.street()` before the deal
- [x] Keep `folded`; reset `currentBet` / `acted`; actor left of button (skip folded)
- [x] After river match: actor null, still live, no showdown, no burns, no blinds posted
- [x] Label `holdem_flop` / `holdem_turn` / `holdem_river`; copy `poker.flop` / `turn` / `river`

**Test:** Two check: flop 3. Check around to river: 5 cards, street done, live. Fold on flop: win, button moves. Flop call needs this-street chips only.

### Batch 5 - Showdown even pots

- [x] River match auto-showdown: `publishHand`, rank in `HoldemRank` (not on `Card`), ace-high + wheel
- [x] Even denar split; leftover 1s left of the button among winners; leftover unsplittable chips to first of those
- [x] `flushPiles` then `endSession` + `passButton`; fold-win still skips ranking; no side pots
- [x] `label.holdem_showdown`; `poker.showdown` / `win` / `chop`

**Test:** Check to river: reveal, winner takes pot, button moves. Chop splits evenly. Fold before river: no ranking.

### Batch 6 - Side pots

- [x] Short **call** allowed (`capped` skip rest of street); raise still needs more than `currentBet`; no new play word
- [x] Showdown pots by total invested; even split per layer; leftover 1s left of button among that layer’s winners
- [x] Folded chips stay in pots; fold-win skips ranking; no blinds posted

**Test:** Short call then raise: street ends, board runs. Side pot to who put more. Equal stacks: one pot. Folder does not win.

### Batch 7 - Blinds GUI and shuffle

- [x] Per-table `smallBlind` / `bigBlind` persist; missing JSON uses `games.yml`; hologram from table, not layout
- [x] Poker options GUI: small, big, shuffle; place from game-select; sneak-edit for owner/staff; no guild auto refuse
- [x] Shuffle on poker label; still no blind posting

**Test:** Place 5/10 Round. Edit 0/0 hides blinds. Old tables use yaml. Non-owner sneak flushes. BJ GUI unchanged.

---

## Phase 4 - Five-Draw

Lock: [FIVEDRAW.md](FIVEDRAW.md). Hold'em (`poker`) and blackjack stay done. Engines stay dumb. New `DrawGame` only. Saved id **`draw`**. Do not start until Phase 3 is tested.

### Batch 1 - Place and seats

- [x] `games.yml` `draw`: `label` Five-Draw, `icon` `ia.tfmc_games:seithr_5`, felt ring, no blinds
- [x] `GamesRegistry`, select GUI fourth slot, `place.gui_draw`, `/games place draw`
- [x] `DrawGame`: chip-in seats, button, idle sandbox, `minActives` 0
- [x] Hold'em `poker.label` Tenceur Hold'em (id stays `poker`)

**Test:** Place Five-Draw and Hold'em. Labels and icons distinct. Sandbox draw on idle draw table.

### Batch 2 - Deal five

- [x] Seated shoe click with 2+ `actives` starts a session
- [x] Five cards each, left of button first; `ROUND` reshuffle at start
- [x] Live: no free draw; F still works; session stop mucks, no button rotate; leave to fewer than 2 seated ends session

**Test:** Two seats, click: five each. One seat: still sandbox. Stop: muck, idle draw.

### Batch 3 - First betting street

- [x] `allowPlayChat` / `onPlayWord` on draw bet phase (engine already has check/call/fold/raise)
- [x] Street state in `DrawGame` (folded, acted, capped, currentBet); fold-win `passButton`
- [x] Matched: go to draw phase, not showdown

**Test:** Two check: draw phase. Fold: win, button moves. Unseated chat ignored.

### Batch 4 - Draw

- [x] Actor only; F / selected return discards then deal that many; `playWord` **draw** stands pat / commits
- [x] Next live seat; all drawn: street 2 reset, actor left of button

**Test:** Stand pat with `draw`. Discard two, dealt two. Folded skipped.

### Batch 5 - Second street and showdown

- [x] Same betting; then `publishHand`, `HoldemRank` on five cards, Hold'em-style side pots
- [x] `endSession` + `passButton`

**Test:** Check/check after draw: showdown, pot, button moves. Short call: side pot. Equal stacks: one pot.

---

## Phase 5 - Shutdown and boot reset

No cross-session play. Tables stay in `Data/tables/`. A stop or crash boot leaves each table **idle**.

### Batch 1 - Idle reset

- [x] `resetTableToIdle`: muck hands and board, settle felt, clear seats, reshuffle, save
- [x] Shutdown (`despawnWorldAll`) uses that path then despawns displays
- [x] Boot: stale JSON (`actives` or `piles`) is reset before spawn
- [x] Guild auto tray banks; missing guild drops tray items; staff mint tray is deleted, not dropped

**Test:** Stop mid-hand: table still there, idle, full deck, no piles. Kill JVM, restart: same. Guild gone: tray drops. Staff mint: no tray items.

Do not start a later Five-Draw batch while the previous is untested.
