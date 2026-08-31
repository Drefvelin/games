# Games - Manual test matrix

Fill in as batches land. Player-facing strings must not use an em dash (U+2014).

---

## Batch 1 - Scaffold

- [ ] `mvn package` succeeds
- [ ] Plugin enables with TLibs, ItemsAdder, ProtocolLib
- [ ] Default yaml copied to `plugins/Games/` (`config.yml`, `messages.yml`, `cards.yml`, `games.yml`)
- [ ] `/game` with no args shows usage
- [ ] `/game reload` as op succeeds
- [ ] No permission: denial from `messages.yml`
- [ ] `debug: true` logs cache values on load/reload

---

## Batch 2 - ItemsAdder

- [ ] Pack `tfmc_games` loads
- [ ] `deck`, `card_back`, `card_base`, and 52 faces obtainable (suits: cerrith, mitlan, oseni, seithr)
- [ ] Ace items use `_1` / rank 1, not 14 (e.g. `tfmc_games:cerrith_1`)
- [ ] No `_14` item ids
- [ ] Jokers are not in the pack yet; `french_54` lists the same 52 ids as `french_52`
- [ ] `card_base` is an IA item but not in either set

---

## Batch 3 - Deck engine

- [ ] `/game deck test` uses `poker.card-set` (`french_52`)
- [ ] Reports 52 cards, 0 jokers, remaining 0 after draw-all
- [ ] Empty draw does not yield a card
- [ ] After return, remaining is 1
- [ ] `/game deck test french_54` matches until jokers exist
- [ ] Unknown set name is rejected

---

## Batch 4 - Display

- [ ] `/game display test` (player, ProtocolLib up)
- [ ] Nearby players see a packet ItemDisplay
- [ ] Per-viewer item: command sender sees a face, others see the back
- [ ] Slide uses transform interpolation (not teleport)
- [ ] Rejoin while the token is live still shows it
- [ ] Right-click the Interaction reports a click; label "Display test" is visible
- [ ] Token, interaction, and label despawn after about 8 seconds

---

## Batch 5 - Place table

- [ ] `/game place poker`, hold deck, right-click a block
- [ ] Label reads Poker; cards lie flat (not standing)
- [ ] At most `stack-visible-max` backs
- [ ] Restart / chunk load restores the table
- [ ] Left-click the Interaction drops a deck item and removes entities / json

---

## Batch 6 - Hand

- [ ] Right-click draws one card into a sorted fan
- [ ] Fan sits at table height between player and stack
- [ ] Other players see backs
- [ ] Leave-distance returns cards and restacks
- [ ] Standing (no chair) still places the hand correctly

---

## Batch 7 - Select and return

- [ ] Right-click a fanned card toggles it (swing on hit only)
- [ ] Selected cards sit at `selected-distance`
- [ ] Right-click the shoe with a selection returns those cards without drawing
- [ ] Right-click the shoe with nothing selected still draws

---

## Batch 8 - Wager

- [ ] Denars escrow and refund
- [ ] Item proposal requires agreement
- [ ] Pot displays at the `pot` anchor
- [ ] Disconnect / table break refunds

- [ ] Shift+right-click the shoe pays the pot to you (engine). Poker will disable this during a hand.

---

## Batch 9 - Poker

- [ ] (Add variant rules, betting, showdown checks when implemented)

---

## Phase 2 - Blackjack

See [BLACKJACK.md](BLACKJACK.md).

- [ ] Claim dealer at stand; logout clears; walk-away does not
- [ ] Min/max/open/close; illegal bet restores chips
- [ ] Deal order, hit/stand, S17, even money settle
- [ ] 3:2 natural, double, split, DAS
- [ ] Auto: 10s after first min bet, spawn pays
- [ ] Voice via RPCharacters `dispatch`, not `/rp` command

---

## Guild house

See [GUILD_TABLES.md](GUILD_TABLES.md).

- [ ] BJ options GUI persists auto / mint / min-max / boxes / shuffle; chunk reload keeps them
- [ ] Guild auto consumes expansion cap; staff mint does not; no SF → guild auto refused
- [ ] Cap drop freezes new rounds on all counted tables; live rounds finish
- [ ] Guild cover withdraws shortfall only; empty bank restores the chip; pickup deposits tray
- [ ] Dealer: guild member set/unset; no mid-round; takeover auto frees slot; no takeover of staff mint
- [ ] Shuffle `ROUND` vs `SHOE` matches policy after a full round
