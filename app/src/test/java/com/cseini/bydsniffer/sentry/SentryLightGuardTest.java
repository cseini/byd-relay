package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SentryLightGuardTest {

    @Test
    public void steadyScene_isNotLighting() {
        SentryLightGuard g = new SentryLightGuard();
        for (int i = 0; i < 20; i++) assertFalse(g.check(100, 0.01f));
    }

    @Test
    public void meanJumpWithLargeArea_isLighting() {
        SentryLightGuard g = new SentryLightGuard();
        g.check(100, 0.01f);
        assertTrue(g.check(112, 0.30f));
    }

    @Test
    public void meanJumpWithSmallArea_isNotLighting() {
        SentryLightGuard g = new SentryLightGuard();
        g.check(100, 0.01f);
        assertFalse(g.check(112, 0.05f));
    }

    @Test
    public void suddenAreaJump_isLighting() {
        SentryLightGuard g = new SentryLightGuard();
        g.check(100, 0.02f);
        assertTrue(g.check(101, 0.60f));
    }

    @Test
    public void objectEnteringGradually_isNotLighting() {
        SentryLightGuard g = new SentryLightGuard();
        g.check(100, 0.02f);
        assertFalse(g.check(102, 0.15f));
        assertFalse(g.check(104, 0.35f));
        assertFalse(g.check(106, 0.60f));
    }

    @Test
    public void resetForgetsPreviousSample() {
        SentryLightGuard g = new SentryLightGuard();
        g.check(100, 0.01f);
        g.reset();
        assertFalse(g.check(140, 0.90f));
    }
}
