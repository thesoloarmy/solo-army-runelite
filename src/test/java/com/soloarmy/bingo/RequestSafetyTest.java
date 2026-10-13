package com.soloarmy.bingo;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.time.temporal.ChronoUnit;
import net.runelite.client.task.Schedule;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure unit tests: no HTTP requests and no RuneLite client required. */
public class RequestSafetyTest
{
    @Test public void stateAndManifestPollingRespectTheirIntervals()
    {
        long now = 1_000_000L;
        assertFalse(RequestSafety.intervalElapsed(now, now + 59_999L, 60_000L));
        assertTrue(RequestSafety.intervalElapsed(now, now + 60_000L, 60_000L));
        assertFalse(RequestSafety.intervalElapsed(now, now + 299_999L, 300_000L));
        assertTrue(RequestSafety.intervalElapsed(now, now + 300_000L, 300_000L));
    }

    @Test public void duplicateRequestsAreRejectedWhileInFlight()
    {
        AtomicBoolean inFlight = new AtomicBoolean();
        assertTrue(RequestSafety.begin(inFlight));
        assertFalse(RequestSafety.begin(inFlight));
        inFlight.set(false);
        assertTrue(RequestSafety.begin(inFlight));
    }

    @Test public void linkingBackoffGrowsAndIsCapped()
    {
        assertEquals(15_000L, RequestSafety.retryDelayMillis(1));
        assertEquals(30_000L, RequestSafety.retryDelayMillis(2));
        assertEquals(60_000L, RequestSafety.retryDelayMillis(3));
        assertEquals(120_000L, RequestSafety.retryDelayMillis(4));
        assertEquals(240_000L, RequestSafety.retryDelayMillis(5));
        assertEquals(300_000L, RequestSafety.retryDelayMillis(6));
        assertEquals(300_000L, RequestSafety.retryDelayMillis(100));
    }

    @Test public void logoutInvalidatesOutstandingResponses()
    {
        assertTrue(RequestSafety.isCurrent(4, 4, true, true, true));
        assertFalse(RequestSafety.isCurrent(4, 5, true, true, true));
        assertFalse(RequestSafety.isCurrent(4, 4, true, true, false));
    }

    @Test public void shutdownInvalidatesOutstandingResponses()
    {
        assertFalse(RequestSafety.isCurrent(4, 4, false, true, true));
        assertFalse(RequestSafety.isCurrent(4, 5, false, true, true));
    }

    @Test public void disabledTrackingDisallowsRequests()
    {
        assertFalse(RequestSafety.canRequest(true, false, true));
        assertFalse(RequestSafety.isCurrent(1, 1, true, false, true));
        assertFalse(RequestSafety.canRequest(true, true, false));
        assertTrue(RequestSafety.canRequest(true, true, true));
    }
    @Test public void productionPollingUsesExpectedScheduleAnnotations() throws Exception
    {
        Schedule state = SoloArmyBingoPlugin.class.getMethod("refreshStatePeriodically").getAnnotation(Schedule.class);
        Schedule manifest = SoloArmyBingoPlugin.class.getMethod("refreshManifestPeriodically").getAnnotation(Schedule.class);
        assertNotNull(state);
        assertNotNull(manifest);
        assertEquals(1, state.period());
        assertEquals(ChronoUnit.MINUTES, state.unit());
        assertEquals(5, manifest.period());
        assertEquals(ChronoUnit.MINUTES, manifest.unit());
    }

    @Test public void lateCallbackCannotReleaseNewRequestGuard()
    {
        AtomicReference<Object> current = new AtomicReference<>();
        AtomicBoolean guard = new AtomicBoolean();
        Object oldRequest = new Object();
        Object newRequest = new Object();
        assertTrue(RequestSafety.begin(guard));
        current.set(oldRequest);
        // Session cancellation releases the old request before its callback arrives.
        current.set(null);
        guard.set(false);
        assertTrue(RequestSafety.begin(guard));
        current.set(newRequest);
        assertFalse(RequestSafety.finishOwned(current, guard, oldRequest));
        assertTrue(guard.get());
        assertSame(newRequest, current.get());
        assertTrue(RequestSafety.finishOwned(current, guard, newRequest));
        assertFalse(guard.get());
    }

    @Test public void staleDropResponseMustFailSessionAndTokenChecks()
    {
        assertTrue(RequestSafety.isCurrentToken(10, 10, true, true, true, "token-a", "token-a"));
        assertFalse(RequestSafety.isCurrentToken(10, 11, true, true, true, "token-a", "token-a"));
        assertFalse(RequestSafety.isCurrentToken(10, 10, true, true, false, "token-a", "token-a"));
        assertFalse(RequestSafety.isCurrentToken(10, 10, true, true, true, "token-a", "token-b"));
    }
}
