package tcb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import tcb.solve.Turns;

class TurnsTest {

    @Test
    void forgeTurnsPairIntoCyclesFromOne() {
        assertEquals(1, Turns.cycle(0)); // before the first turn, e.g. opening-hand decisions
        assertEquals(1, Turns.cycle(1));
        assertEquals(1, Turns.cycle(2));
        assertEquals(2, Turns.cycle(3));
        assertEquals(25, Turns.cycle(50));
    }

    @Test
    void turnLimitCoversBothPlayersTurns() {
        assertEquals(50, Turns.forgeTurns(25));
        assertEquals(25, Turns.cycle(Turns.forgeTurns(25)));
    }

    @Test
    void activePlayerAlternatesFromTheStartingPlayer() {
        assertEquals(1, Turns.activePlayer(0, 1));
        assertEquals(1, Turns.activePlayer(1, 1));
        assertEquals(0, Turns.activePlayer(2, 1));
        assertEquals(0, Turns.activePlayer(3, 0));
        assertEquals(1, Turns.activePlayer(4, 0));
    }
}
