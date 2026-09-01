# Games - System Overview

Tabletop plugin. Engines are dumb: they cannot tell poker from blackjack. Games only call engines.

Phase 1 (session + auto-deal + per-game layout) is done. **Three games:** blackjack ([BLACKJACK.md](BLACKJACK.md)), Tenceur Hold'em ([HOLDEM.md](HOLDEM.md), id `poker`), Five-Draw ([FIVEDRAW.md](FIVEDRAW.md), id `draw`). Hold'em and Five-Draw numbered batches are coded; both await in-game testing. Plugin stop and crash boot reset tables to idle (location and house settings persist; no mid-hand resume).

Batch 1 shipped config shells, `/games reload`, and documentation. Nothing is placed in the world yet.

## Config folder layout

All paths under `plugins/Games/` on the server.

| Path | Purpose |
|------|---------|
| `config.yml` | `debug`, stack visual max, card scale, interpolation ticks, table Y offset |
| `messages.yml` | Player-facing chat strings |
| `cards.yml` | Card catalog and named sets (`french_54`, `french_52`). Schema only until Batch 2/3 |
| `games.yml` | Per-game rules, layout, blackjack min/max / auto-dealer defaults (Phase 2). Live auto/mint/shuffle live on the table ([GUILD_TABLES.md](GUILD_TABLES.md)) |
| `Data/tables/` | Gson for placed tables (Batch 5) |

ItemsAdder pack lives in the repo at `games/ItemsAdder/tfmc_games/` (Batch 2). Namespace: `tfmc_games`.

## Target source tree

Packages are added when a batch needs them. Do not pre-create empty classes.

```
games/
  docs/
  ItemsAdder/tfmc_games/          # batch 2
  src/main/java/net/tfminecraft/games/
    card/                         # CardId, composition
    deck/                         # shoe shuffle draw
    display/                      # ProtocolLib ItemDisplay + motion
    layout/                       # stack, fan, pot offsets
    table/                        # placed deck, seats, persist
    select/                       # closest token on look ray
    wager/                        # denars + item agree (late)
    game/                         # Game interface, poker stub then rules
    command/ loader/ cache/ database/ utils/
    Games.java
```

## Engines vs poker

| Piece | Owns | Must not own |
|-------|------|----------------|
| Card model | id, suit, rank, joker flag | Poker Ace-high ranking |
| Deck | composition, shuffle, draw, discard, recycle | ItemDisplays, who holds a card |
| Display | packet ItemDisplays, per-viewer item, transform interpolation | Poker, seats, pot math |
| Layout | stack / fan / pot transforms from named anchors | Packets, DenarEconomy |
| Table | placed deck, yaw, who is in range, persist | Hand ranking |
| Selector | ray to closest token | Discard vs play meaning |
| Wager | piles tagged by owner (null = communal) and street; refund/payout filters; manual shoe flush | Blinds, 21, house vs pot |
| Poker (Hold'em, id `poker`) | blinds display, streets, showdown, side pots | Packets, IA ids, ray math |
| Five-Draw (id `draw`) | five-card draw, discard round, showdown | Packets, IA ids, ray math |
| Blackjack | claim, bet window, 21, double/split, house pay (Phase 2) | Packets, shuffle |

A class is an engine if it cannot tell whether the game is poker or blackjack.

## Display: packet ItemDisplays

Same spawn / metadata / destroy idea as RPCharacters speech bubbles and clues (`FakeTextDisplayPackets`), but `EntityType.ITEM_DISPLAY`.

- Shared **token id** maps to one fake entity id spawned to every nearby viewer.
- Server-facing item for hidden cards is `card_back`. Owner (and showdown) get face item via metadata only.
- Table origin `Location` stays fixed. Deals and slides use Display **interpolation duration** plus translation / rotation / scale. Do not teleport fake entities for card motion.
- Fake entities are not clickable. Spawn a real **Interaction** on the deck for right-click draw.
- Table title is a real **TextDisplay** (everyone should see "Poker"). Do not reuse packet speech bubbles for that.

Confirm ItemDisplay metadata indices against 1.21.8 when implementing Batch 4. Scale uses `org.joml.Vector3f` with ProtocolLib `Registry.get(Vector3f.class)`.

Display API (dumb): `spawn(token)`, `despawn`, `setItem`, `setItemFor(player, item)`, `setTransform(token, transform, durationTicks)`, plus join / chunk refresh.

## 3x3 table anchors

Named offsets in config (tweaked later). Default intent: deck on an edge or corner, pot at center, hands along the rim at table Y. Cards stay small (`card-scale`).

| Anchor | Role |
|--------|------|
| `deck` | Shoe stack (at most `stack-visible-max` backs) |
| `discard` | Face-down pile beside the shoe (`table.discard-offset`). Auto-recycles into the shoe when the shoe is empty or the table has no hands. No click shuffle. |
| `pot` | Wager pile (Batch 8) |
| `hand` | Derived from player position vs deck, Y locked to table |

Visible stack layers: `visible = ceil(remaining / full * stack-visible-max)`, never 52 displays.

## Deck composition

The IA pack can include 54 cards (52 + 2 jokers). Poker uses set `french_52` so `draw()` never returns a joker. Unused jokers can sit in the box as layout-only tokens later.

Ace file and IA id is `_1` or `1`, not 14. Ace-high ranking is a poker flag, not a filename.

## Commands

| Command | Permission | Batch |
|---------|------------|-------|
| `/games reload` | `games.admin.reload` | 1 |
| `/games place` | `games.admin` | 5 (admin, no deck) |
| `/games bet ...` | `games.bet` | Phase 2 blackjack |

## Dependencies

`TLibs`, `ItemsAdder`, `ProtocolLib`. DenarEconomy softdepend (wager). RPCharacters softdepend (Phase 2 voice). SimpleFactions softdepend (guild auto cap + bank; [GUILD_TABLES.md](GUILD_TABLES.md)). SF does not depend on Games.
