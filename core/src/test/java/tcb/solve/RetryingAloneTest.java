package tcb.solve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import tcb.engine.NondeterministicReplayException;

/** A game whose replays weren't deterministic is solved again, alone (issue #6). */
class RetryingAloneTest {
    @Test
    void nondeterministicReplayIsRetriedWithNothingElseRunning() throws Exception {
        CountDownLatch otherStarted = new CountDownLatch(1);
        CountDownLatch otherMayFinish = new CountDownLatch(1);
        List<String> events = new ArrayList<>();
        Thread other = new Thread(() -> Tournament.retryingAlone(() -> {
            otherStarted.countDown();
            try {
                otherMayFinish.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            synchronized (events) {
                events.add("other finished");
            }
            return 0;
        }));
        other.start();
        otherStarted.await();

        AtomicInteger attempts = new AtomicInteger();
        Thread retry = new Thread(() -> Tournament.retryingAlone(() -> {
            if (attempts.incrementAndGet() == 1) {
                // wrapped, as Forge may do
                throw new RuntimeException(new NondeterministicReplayException("options changed"));
            }
            assertTrue(Tournament.SOLVING.isWriteLockedByCurrentThread());
            synchronized (events) {
                events.add("retried");
            }
            return 1;
        }));
        retry.start();
        Thread.sleep(200);
        assertEquals(List.of(), events, "the retry must wait for the game already running");
        otherMayFinish.countDown();
        other.join();
        retry.join();
        assertEquals(2, attempts.get());
        assertEquals(List.of("other finished", "retried"), events);
        assertFalse(Tournament.SOLVING.isWriteLocked());
    }

    @Test
    void otherErrorsAreNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        IllegalArgumentException boom = new IllegalArgumentException("boom");
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> Tournament.retryingAlone(() -> {
            attempts.incrementAndGet();
            throw boom;
        }));
        assertSame(boom, thrown);
        assertEquals(1, attempts.get());
    }

    @Test
    void aSecondFailureIsReported() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(NondeterministicReplayException.class, () -> Tournament.retryingAlone(() -> {
            attempts.incrementAndGet();
            throw new NondeterministicReplayException("options changed");
        }));
        assertEquals(2, attempts.get());
    }
}
