package com.soloarmy.bingo;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Small, deterministic request-policy helpers; no network operations. */
final class RequestSafety
{
    private RequestSafety() { }

    static boolean canRequest(boolean running, boolean trackingEnabled, boolean loggedIn)
    {
        return running && trackingEnabled && loggedIn;
    }

    static boolean begin(AtomicBoolean inFlight)
    {
        return inFlight.compareAndSet(false, true);
    }

    /** Only the callback owning the current request may release its in-flight guard. */
    static <T> boolean finishOwned(AtomicReference<T> reference, AtomicBoolean guard, T request)
    {
        if (!reference.compareAndSet(request, null)) return false;
        guard.set(false);
        return true;
    }

    static boolean isCurrent(long expectedGeneration, long currentGeneration,
        boolean running, boolean trackingEnabled, boolean loggedIn)
    {
        return expectedGeneration == currentGeneration && canRequest(running, trackingEnabled, loggedIn);
    }

    static boolean isCurrentToken(long expectedGeneration, long currentGeneration,
        boolean running, boolean trackingEnabled, boolean loggedIn,
        String expectedToken, String currentToken)
    {
        return isCurrent(expectedGeneration, currentGeneration, running, trackingEnabled, loggedIn)
            && expectedToken != null && expectedToken.equals(currentToken);
    }

    static long retryDelayMillis(int failureCount)
    {
        int failures = Math.max(1, Math.min(6, failureCount));
        return Math.min(300_000L, 15_000L * (1L << (failures - 1)));
    }

    static boolean intervalElapsed(long lastAttemptMillis, long nowMillis, long intervalMillis)
    {
        return nowMillis >= lastAttemptMillis && nowMillis - lastAttemptMillis >= intervalMillis;
    }
}
