package net.tfminecraft.games.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.card.Card;

/**
 * Hold'em 5-card ranking. Catalog ranks stay on {@link Card}; ace-high uses game rank-values.
 */
final class HoldemRank {

    private HoldemRank() {}

    static final class Score implements Comparable<Score> {

        private final int[] keys;

        private Score(int[] keys) {
            this.keys = keys;
        }

        static Score none() {
            return new Score(new int[] {-1, 0, 0, 0, 0, 0});
        }

        static Score of(int category, int a, int b, int c, int d, int e) {
            return new Score(new int[] {category, a, b, c, d, e});
        }

        @Override
        public int compareTo(Score other) {
            if (other == null) {
                return 1;
            }
            for (int i = 0; i < keys.length; i++) {
                int cmp = Integer.compare(keys[i], other.keys[i]);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        }
    }

    static Score best(String gameId, List<Card> cards) {
        List<Card> usable = new ArrayList<>();
        if (cards != null) {
            for (Card card : cards) {
                if (card != null && !card.isJoker()) {
                    usable.add(card);
                }
            }
        }
        if (usable.size() < 5) {
            return Score.none();
        }
        Score best = Score.none();
        int n = usable.size();
        Card[] pick = new Card[5];
        for (int a = 0; a < n; a++) {
            pick[0] = usable.get(a);
            for (int b = a + 1; b < n; b++) {
                pick[1] = usable.get(b);
                for (int c = b + 1; c < n; c++) {
                    pick[2] = usable.get(c);
                    for (int d = c + 1; d < n; d++) {
                        pick[3] = usable.get(d);
                        for (int e = d + 1; e < n; e++) {
                            pick[4] = usable.get(e);
                            Score next = ofFive(gameId, pick);
                            if (next.compareTo(best) > 0) {
                                best = next;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    private static Score ofFive(String gameId, Card[] five) {
        int[] vals = new int[5];
        String suit0 = five[0].getSuit();
        boolean flush = suit0 != null && !suit0.isBlank();
        for (int i = 0; i < 5; i++) {
            vals[i] = Cache.sortValue(gameId, five[i].getRank());
            if (flush && (five[i].getSuit() == null || !suit0.equals(five[i].getSuit()))) {
                flush = false;
            }
        }
        Arrays.sort(vals);
        int[] desc = new int[] {vals[4], vals[3], vals[2], vals[1], vals[0]};
        int straightHigh = straightHigh(vals);
        if (flush && straightHigh > 0) {
            return Score.of(8, straightHigh, 0, 0, 0, 0);
        }
        int[] counts = new int[15];
        for (int v : vals) {
            if (v >= 0 && v < counts.length) {
                counts[v]++;
            }
        }
        int quad = 0;
        int trips = 0;
        int pairHigh = 0;
        int pairLow = 0;
        for (int v = 14; v >= 2; v--) {
            int n = counts[v];
            if (n >= 4 && quad == 0) {
                quad = v;
            } else if (n == 3 && trips == 0) {
                trips = v;
            } else if (n == 2) {
                if (pairHigh == 0) {
                    pairHigh = v;
                } else if (pairLow == 0) {
                    pairLow = v;
                }
            }
        }
        if (quad > 0) {
            return Score.of(7, quad, kickerExcept(desc, quad), 0, 0, 0);
        }
        if (trips > 0 && pairHigh > 0) {
            return Score.of(6, trips, pairHigh, 0, 0, 0);
        }
        if (flush) {
            return Score.of(5, desc[0], desc[1], desc[2], desc[3], desc[4]);
        }
        if (straightHigh > 0) {
            return Score.of(4, straightHigh, 0, 0, 0, 0);
        }
        if (trips > 0) {
            int k1 = 0;
            int k2 = 0;
            for (int v : desc) {
                if (v == trips) {
                    continue;
                }
                if (k1 == 0) {
                    k1 = v;
                } else if (k2 == 0) {
                    k2 = v;
                    break;
                }
            }
            return Score.of(3, trips, k1, k2, 0, 0);
        }
        if (pairHigh > 0 && pairLow > 0) {
            return Score.of(2, pairHigh, pairLow, kickerExcept(desc, pairHigh, pairLow), 0, 0);
        }
        if (pairHigh > 0) {
            int k1 = 0;
            int k2 = 0;
            int k3 = 0;
            for (int v : desc) {
                if (v == pairHigh) {
                    continue;
                }
                if (k1 == 0) {
                    k1 = v;
                } else if (k2 == 0) {
                    k2 = v;
                } else if (k3 == 0) {
                    k3 = v;
                    break;
                }
            }
            return Score.of(1, pairHigh, k1, k2, k3, 0);
        }
        return Score.of(0, desc[0], desc[1], desc[2], desc[3], desc[4]);
    }

    private static int straightHigh(int[] sortedAsc) {
        if (sortedAsc[0] == 2 && sortedAsc[1] == 3 && sortedAsc[2] == 4 && sortedAsc[3] == 5
                && sortedAsc[4] == 14) {
            return 5;
        }
        for (int i = 1; i < 5; i++) {
            if (sortedAsc[i] != sortedAsc[i - 1] + 1) {
                return 0;
            }
        }
        return sortedAsc[4];
    }

    private static int kickerExcept(int[] desc, int... skip) {
        outer:
        for (int v : desc) {
            for (int s : skip) {
                if (v == s) {
                    continue outer;
                }
            }
            return v;
        }
        return 0;
    }
}
