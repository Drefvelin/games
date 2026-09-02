# Guild tables and house bank

Lock for the guild-house work. Implementation order: [IMPLEMENTATION_BATCHES.md](IMPLEMENTATION_BATCHES.md) phase **Guild house**. Do not start Hold'em.

Bank money reaches the felt only through `BankAccount`, as a leg of a wager transaction. See [WAGER_ENGINE.md](WAGER_ENGINE.md).

Games **softdepends SimpleFactions** and may call guild / bank / modifiers. SimpleFactions **does not** import Games, persist table ids, or listen to table events. **No Bukkit events** for create, dealer, or bank.

## Modes

| Mode | Auto play | Tray money | Expansion slot |
|------|-----------|------------|----------------|
| Private dealer, no guild | No | Dealer stocks the tray by hand and gets the float back | No |
| Guild, human dealer | No | Withdraw the round reserve from the **owning guild bank** | No |
| Guild auto | Yes | Withdraw the round reserve from the **owning guild bank**, then spawn chips on the tray | Yes |
| Staff auto | Yes | **Mint** shortfall (current behavior) | No |

Who turns the cards and who funds the house are separate questions. `GuildTables.houseBacked` answers the money one: a staff mint or a guild bank behind the table. A guild table is bank funded either way, because a non-member cannot take the shoe, so the tray is the guild's float and its winnings are the guild's. Only a table with no guild at all is stocked by its dealer, and on a backed table hand-stocking the tray is refused outright with `wager.tray_is_funded`.

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

## House float (any backed table)

No "please top up" wait. Cover is withdraw-or-refuse.

1. During the bet window, after a legal chip-in, `need = max(0, action - tray)`. Only auto tables run a bet window, so on a human-dealt table the round reserve at step 5 is the only cover step.
2. If `need == 0`, do nothing.
3. Staff auto: spawn `need` (today).
4. Guild auto: if bank wealth `< need` or bankrupt / no bank, **do not accept that chip-in** (restore item, message). Else `withdraw(need)`, spawn on tray.
5. When bets close, blackjack reserves the round's worst case: `sum(boxStake) * max-hands-per-box * 2`, rounded up to a whole coin. Withdraw-or-refuse again, but at round scope: if the bank cannot supply it the round does not start and every box is refunded. Splits and doubles inside the round then take their cover from the reserve and never touch the bank. `closeBets` is the gate for both modes: the bet timer calls it on auto tables, and a human dealer reaches it with `/games bet close`, which is also the only way their round starts.
6. Player wins peel the tray. The settle shortfall path still exists but the reserve should make it unreachable, so it logs a mismatch when `wager.audit-log` is on.
7. Round end (settled or abandoned): the whole tray `deposit`s back to the owning guild bank, so the float is zero between rounds and house wins are banked each round rather than piling up as chips. Staff mint burns it. A table with no guild returns the float to its dealer instead.
8. Pickup / teardown: any remaining tray on a backed table `deposit`s to the owning guild bank; if that guild is gone, drop the tray items. Staff mint tray is despawned, not dropped. Player pots still drop.

A guild running several tables shares one bank, so reserving per round is also what stops two tables from both counting the same denars.

## Tax on winnings

A guild owes tax on what its tables win, not on the float they were stocked with. Each table counts the denars it has taken out of the bank and not yet paid back, and money returning to the bank clears that count before any of it is called profit. Only the excess is declared, as the `GAMBLING` cashflow on the owning guild's ledger, which is folded into the guild's gross taxable income.

Tables settle separately, so a table that is up declares its winnings even if another table under the same guild is down. A table that is down carries its shortfall forward and pays nothing until it is level again. The guild that owns the table declares it, so a branch guild's takings show on the branch and reach the capital through the ordinary tax on guilds.

Staff mint tables and private tables with a human dealer owe nothing: neither is backed by a guild bank, so neither has winnings to declare. See [WAGER_ENGINE.md](WAGER_ENGINE.md) for how the count is kept.

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
