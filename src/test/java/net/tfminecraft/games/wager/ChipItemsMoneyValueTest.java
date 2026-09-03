package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class ChipItemsMoneyValueTest {

    @Test
    void countsCoinStakesByTypeKeyWhenItemIsNull() {
        Stake coin = new Stake(null, "coin:gold_coin", 10, 5, 0);
        assertEquals(50, ChipItems.moneyValue(coin));
    }

    @Test
    void ignoresLootStakes() {
        Stake loot = new Stake(null, "item:loot", 100, 1, 0);
        assertEquals(0, ChipItems.moneyValue(loot));
    }

    @Test
    void sumsOnlyMoneyInAMix() {
        Stake coin = new Stake(null, "coin:gold_coin", 10, 5, 0);
        Stake loot = new Stake(null, "item:loot", 100, 1, 0);
        assertEquals(50, ChipItems.moneyValue(List.of(coin, loot)));
    }

    @Test
    void goldAndSilverTypeKeysCount() {
        assertEquals(5, ChipItems.moneyValue(new Stake(null, "gold", 1, 5, 0)));
        assertEquals(3, ChipItems.moneyValue(new Stake(null, "silver", 1, 3, 0)));
    }

    @Test
    void materialKeysDoNotCount() {
        assertEquals(0, ChipItems.moneyValue(new Stake(null, "mat:GOLD_INGOT", 10, 1, 0)));
    }

    @Test
    void nullAndEmptyAreZero() {
        assertEquals(0, ChipItems.moneyValue((Stake) null));
        assertEquals(0, ChipItems.moneyValue(List.of()));
        assertEquals(0, ChipItems.moneyValue((List<Stake>) null));
    }
}
