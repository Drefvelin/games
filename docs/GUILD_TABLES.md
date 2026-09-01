# Guild tables and house bank

Lock for the guild-house work. Implementation order: [IMPLEMENTATION_BATCHES.md](IMPLEMENTATION_BATCHES.md) phase **Guild house**. Do not start Hold'em.

Games **softdepends SimpleFactions** and may call guild / bank / modifiers. SimpleFactions **does not** import Games, persist table ids, or listen to table events. **No Bukkit events** for create, dealer, or bank.

## Modes

| Mode | Auto play | Tray money | Expansion slot |
|------|-----------|------------|----------------|
| Human dealer | No | Dealer stocks the tray | No |
| Guild auto | Yes | Withdraw shortfall from the **owning guild bank**, then spawn chips on the tray | Yes |
| Staff auto | Yes | **Mint** shortfall (current behavior) | No |

Staff auto needs `games.autodealer.staff`. It is not the default for a normal place.

`games.yml` `blackjack.auto-dealer` is only the **options-GUI default when the placer has staff perm**. Player tables default auto **off**. Min/max/bet-seconds in yaml stay the starting values for the GUI.

## Ownership

On place, store:

- `ownerPlayer` (placer UUID)
- `ownerGuildId` (guild id at place time, or null if no SF / no guild)

Cap and bank always use **that guild id**, not "whoever is standing here." Re-resolve the guild object on cover / pickup. If the guild is gone: refuse auto cover, and on pickup skip bank deposit (chips still despawn / given per existing teardown).

## Cap

SF `GuildModifier.AUTO_DEALER_TABLES` from an **expansion upgrade** (not a branch). `allowed-types`: `guild` and `realm`. Stacks with upgrade level via existing `Guild.getModifier`.

A table counts against the cap if `autoDealer && !staffMint` and `ownerGuildId` matches.

Refuse (do not place, or do not turn auto on) when `count >= cap`. Cap `0` means guild auto is off. Missing SF: guild auto off; staff mint still works.

If upgrades drop so `count > cap`, **do not** pick up tables or cancel a live round. Every counted table of that guild **cannot start a new round** until `count <= cap` (turn auto off or pick up extras). A bet window that was already open refunds and does not deal.

Toggling auto **off** frees the slot immediately. Human takeover of a guild-auto table (between rounds) does the same.

## House float (guild auto)

No "please top up" wait. Cover is withdraw-or-refuse.

1. After a legal chip-in (or double/split extra), `need = max(0, action - tray)`.
2. If `need == 0`, do nothing.
3. Staff auto: spawn `need` (today).
4. Guild auto: if bank wealth `< need` or bankrupt / no bank, **do not accept that chip-in** (restore item, message). Else `withdraw(need)`, spawn on tray.
5. Player wins peel the tray; settle shortfall (3:2) uses the same withdraw-or-fail for the extra only.
6. Pickup / teardown: guild auto tray `deposit`s to the owning guild bank; if that guild is gone, drop the tray items. Staff mint tray is despawned, not dropped. Player pots still drop.

House wins stay on the tray (already guild money as chips).

## Dealer

Not during a live round.

Right-click shoe (existing claim path):

- Already this player: **unset** dealer.
- Empty seat, human table: **set** if the player is in `ownerGuildId` (or staff). No guild on the table: placer / staff only.
- Guild auto, between rounds: **takeover** - become human dealer, `autoDealer=false`, tray stays, slot freed.
- Staff auto: guild members **cannot** takeover. Staff can turn mint off from options (when not live) or pick up the table.

Leave-distance still does not clear dealer. Logout / pickup still does (existing).

## Options GUI

After game-select **blackjack** (not free play / poker in this phase): second inventory.

- Auto on/off
- Staff mint on/off (hidden without perm)
- Bet min / max (anvil or click steps; stay within yaml bounds unless staff)
- Max boxes (num players)
- Shuffle: **each round** vs **shoe** (recycle when empty; allows counting)

Confirm places the table. Changing options later: sneak-click shoe while not live, same GUI, re-check cap if turning auto on.

## Shuffle

| Policy | Behavior |
|--------|----------|
| `ROUND` | Full shuffle into the shoe at each round start (after muck / before deal) |
| `SHOE` | Keep the current recycle-when-empty shoe |

Default `SHOE`. Engine stays dumb; blackjack asks the table for policy.

## Isolation

One Games class (same idea as `RpNames`): plugin present + enabled, then `FactionManager` / `Guild` / `Bank`. Catch `LinkageError`. Do not scatter SF types through `BlackjackGame`.

SF changes this phase: `GuildModifier` entry + `upgrades.yml` row. No Games dependency in SF `pom` / `plugin.yml`.
