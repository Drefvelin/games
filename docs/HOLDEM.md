# Hold'em

Lock for Texas Hold'em (player-facing **Tenceur Hold'em**). Saved `gameId` stays **`poker`**. Implementation order: [IMPLEMENTATION_BATCHES.md](IMPLEMENTATION_BATCHES.md) phase **Hold'em**. Numbered batches are coded; awaiting in-game testing. Five-card draw is Phase 4 ([FIVEDRAW.md](FIVEDRAW.md)), id `draw`.

Games **owns rules**. Engines stay dumb: no Hold'em ranking on `Card`, no second display stack, no blackjack box/tray copy. Fill [PokerGame.java](../src/main/java/net/tfminecraft/games/game/PokerGame.java). Call `dealToPlayer`, `dealToTable`, `muck*`, `flushPiles`, session APIs.

Hold'em is a **player pot**, not a house game. Guild auto-dealer, staff mint, and tray float stay blackjack.

## Rules (locked)

- No-limit Hold'em. Stacks are chips on the felt.
- 2+ players to start a hand (seated in `actives`). Idle shoe click by a seated player deals.
- Button is `table.dealerId()`. First player to chip in is the button. When they leave or log out, the button passes to the next seated player in seat order (`actives` insertion order). After a finished dealt hand (fold-win), rotate around that circle.
- Blinds default from `games.yml` `poker.blinds.small` / `big`. Each table can change them in options. Displayed on the hologram; not posted.
- No straddles, bomb pots, or other variants.
- Optional burn cards: later.
- Side pots: a short call is in for that amount; extra chips from others make a side pot at showdown.

## Batch 1

Seats, button, blinds on the hologram. Sandbox shoe draw while idle. `minActives()` stays 0. No deal, no blind posting, no session auto-start.

## Batch 2

Seated player clicks the shoe with 2+ seats: `beginSession`, two hole cards each (left of button first, one around then another). Live: no sandbox draw or selected return. F still works. `/games session stop` mucks holes and does not rotate the button. Walking off to one player ends the session.

## Test (batch 2)

One seated: shoe still draws. Two seated, seated click: holes deal, shoe locked. Unseated click still draws. Session stop mucks. One of two leaves: session ends.

## Batch 3

Preflop chat: **check**, **call**, **fold**, **raise** (same path as blackjack hit/stand). Raise is chips already on this street above the call, then the word. No `/wager` commit for Hold'em. Last player standing wins without ranking: session ends, button moves. Still no blind posting.

## Batch 4

When a street matches, deal the next board automatically (flop 3, then turn 1, then river 1), all face-up on `board`. New street number so only this-street chips count. Same chat betting; folded stays folded. No burns.

## Batch 5

When river betting matches, remaining holes are shown, best 5-card Hold'em hand wins (ace-high, wheel straight). Even split among tied winners of a pot; leftover denars go left of the button among those winners. Then session end and button move. Fold-win still skips ranking.

## Batch 6

**Call** with fewer chips than the bet is allowed: they are in for that amount and skip the rest of the street. **Raise** still needs more than the bet. At showdown, pots are layers by total invested (all streets). Folded chips stay in the pots they paid into. No extra chat word. Still no blinds posted.

## Batch 7

Place and sneak-edit: small/big blinds and Shoe vs Round shuffle. Defaults from `games.yml`. Still not posted. `ROUND` reshuffles at hand start (already). Later: optional burns.

## Test (batch 7)

Place poker 5/10 Round: hologram blinds + Round. Edit 0/0: no blinds line. Old JSON without fields: yaml blinds. Non-owner sneak still flushes. Blackjack options unchanged.
