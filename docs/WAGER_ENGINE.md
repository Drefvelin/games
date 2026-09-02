# Wager engine

Every denar that moves on a table moves through `net.tfminecraft.games.wager`. Games describe what they want and commit once; they never touch a player's inventory, the ledger, or a guild bank themselves.

This exists because the old shape was mutate-then-compensate: take some coins, put some coins somewhere, and if the second step failed, hand back what the first one took. Refunds were allowed to return less than they took, and one caller ignored the return value entirely, which is how a player could pay for a double and not get one.

## Accounts

A `MoneyAccount` is a place money can sit. All of them answer the same questions: what have you got, can you produce exactly this much without moving it yet, and will you take this.

| Account | Holds | Notes |
|---|---|---|
| `PlayerAccount` | Coins in an inventory | Filtered, so a blackjack box only takes real money coins. Falls back to dropping at the table when the player is gone, so a payout is never deleted |
| `BucketAccount` | One owner's stakes in the `TableLedger` | The tray is the bucket keyed by the table's own id |
| `BankAccount` | The owning guild bank | The only class in games that reaches SimpleFactions money, via the package-private `GuildBank` |
| `MintAccount` | Nothing, creates money | Refuses on anything but a staff-mint table |

Build them through `Accounts`: `bucket`, `tray`, `pockets`, `coins`, `declared`, `payee`, `ground`, `bank`, `mint`, and `house`, which picks mint or bank depending on the table.

## A transaction

```java
MoneyTx tx = WagerEngine.get().begin(table, "double down");
tx.move(Accounts.coins(table, player), Accounts.bucket(table, owner).at(spot), bet);
TxResult result = tx.commit();
if (!result.ok()) {
    player.sendMessage(Messages.get(result.messageKey()));
    return;
}
```

Legs come in four shapes:

- `move` - exactly this much or the whole transaction refuses.
- `moveUpTo` - as much as the source can hand over exactly, never more than asked, never a failure for being short.
- `moveAll` and `moveStreet` - everything a bucket holds, or everything it holds from one betting street.
- `spread` - one amount covered by several sources in turn, which is how a pot pays out of the buckets that built it.

`commit()` runs with no yield point in the middle:

1. **Plan.** Every source works out which coins it would hand over, without moving any of them. A source that cannot make the amount exactly fails the leg.
2. **Accept.** Destinations confirm they can take the planned value. Buckets fed from the bank or the mint derive their value from what the coin template can represent, so the bank leg and the stake leg always agree and no compensating deposit is ever needed.
3. **Apply.** One synchronous pass: take, accept, log.
4. **Audit.** `LedgerAudit.check` re-asserts the invariants, and the felt total is compared against what the legs said it should move.

Reserve then apply, never apply then undo. A caller that gets a failure knows nothing moved and can simply refuse its action.

`TxResult.reason()` names why a refusal happened - `PLAYER_SHORT`, `NO_CHANGE`, `FELT_SHORT`, `BANK_SHORT`, `NO_TEMPLATE`, `MINT_REFUSED`, `NO_TABLE` - and `messageKey()` maps it to `messages.yml`, so the player is told the real problem rather than a guess.

## Chips are only ever a picture

`PayoutFlight` animations are drawn from what a committed transaction moved and carry no value. Chip piles are a projection of the ledger, refreshed once per commit through `WagerHost`, which `TableManager` implements. Set `wager.show-chips: false` and the tables still work, with amount labels instead of piles.

A `Stake` carries the spot it was put down on, so a player's chips sit where they clicked rather than on a spiral off one anchor per box. A spot is part of a stake's identity: coins landing within `wager.merge-range` of a stack of the same kind grow it, and coins landing anywhere else start a new heap. Only deliberate placement takes a spot, via `BucketAccount.placedAt`. Money the house puts down uses `at` instead and has no spot, which is what leaves the auto dealer's tray on its own layout grid.

## Profit, and what a guild owes tax on

`BankAccount` is the only door between a guild bank and a table, so it is also where profit is worked out. Money going out raises the table's `houseFloat`; money coming in settles that float first, and only what is left over is profit. A guild pays tax on the profit, never on a float it took out and handed straight back.

Nothing is moved for tax. Winnings are already in the bank the moment `settleAutoTray` deposits the tray, so `BankAccount.accept` only reports the profit onward, through `GuildBank.declareProfit` into the owning guild's SimpleFactions ledger as its `GAMBLING` cashflow. That entry is gross counted, so it widens guild tax, overlord tax, tribute and reparations, and it settles as a ledger line with no delta of its own. Adding a delta would pay the guild for the same denars twice.

A six-box table at 1000 reserves 48000. If players net lose 1500 the tray banks 49500, the float is cleared and 1500 is declared. If players net win 2000 only 46000 comes back, the float stays 2000, nothing is declared, and the table's next 2000 of winnings is sheltered until it is level again. So a losing streak carries forward by itself, and a bank covering a winner directly leaves the float raised for the same reason: the house is down until it earns that back.

`houseFloat` lives on `Table` and is saved with it, because every commit ends in `WagerHost.moneyMoved`. It is zeroed if a table changes owning guild, since a float belongs to whoever put it up. Staff mint tables never reach `BankAccount` at all, so they declare nothing, and a private table with a human dealer returns its float to the dealer rather than a bank and is likewise untaxed.

## Coin selection is exact, not greedy

`CoinPlanner` does exact subset selection over distinct `(unit, count)` pairs, by depth-first search with memoisation on `(unitIndex, remaining)`.

Greedy largest-first is wrong here. Holding 6, 5 and 5 and needing 10, greedy takes the 6 and strands the rest. Ledger stakes carry arbitrary units, because `wager.items` entries declare any value and `proposeLoot` lets a player wager a loot item for any denar amount, so odd sets are normal rather than hypothetical. The same planner backs both `PlayerAccount` and `BucketAccount`, so a take and its refund can never disagree.

`CoinPlanner` has no Bukkit in it and is covered by `CoinPlannerTest`.

## The denomination set, and change

The live coins are pouch 100, stack 10, handful 5, gold coin 1, and `v.gold_ingot` at 1 with `withdraw: false`. Silver at 0.1, 0.05 and 0.01 exists but cannot be staked, since anything below a whole denar is rejected.

The usable set is therefore 100, 10, 5, 1, and the gap from 10 to 100 is why doubles used to fail so often: a 50 double needs five stacks, and a player holding one pouch could not pay at all. There is no 25 or 50 coin to bridge it.

So DenarEconomy makes change. `MoneyManager.breakCoin(stack, minUnitValue)` returns strictly smaller coins worth exactly the same, honouring `canWithdraw()` so a gold ingot is never produced, and refusing to break below 1 denar because the result would be unstakeable. The arithmetic sits in `CoinChange`, which is Bukkit-free and tested in `CoinChangeTest`.

When a player's coins cannot make an exact plan, `PlayerAccount` breaks the smallest coin that would help and plans again, gated on free inventory space. The break happens inside the take, as part of the same transaction, and is value neutral: the player is carrying different coins worth the same amount.

## Invariants

`LedgerAudit.check` runs after every commit and complains about a negative table total, a negative bucket, a stake counting below zero, and a chip worth nothing, which is money that can neither be paid out nor taken away.

`LedgerAudit.checkLoaded` totals each table on startup against the figure in its file, so a stake dropped on load is noticed before anyone plays on it.

Blackjack adds `checkBoxBets`, which asserts a box's ledger total equals the sum of its hands' bets. That is the check that catches a doubled bet backed by money that was handed back.

Nothing here corrects a total. Quietly fixing one would hide the bug that caused it, and a papered-over total is how a table ends up inflated with nobody knowing when it started. Everything goes to `MoneyLog.mismatch`; `wager.audit-log` turns the full trail on.

## Related

- [BLACKJACK.md](BLACKJACK.md) for the house reserve and split money flow
- [GUILD_TABLES.md](GUILD_TABLES.md) for where house money comes from
