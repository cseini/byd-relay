package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SentrySustainTest {

    @Test
    public void continuousHits_confirmAfterMostOfTheWindow() {
        SentrySustain s = new SentrySustain(2000);
        boolean confirmed = false;
        long t = 0;
        for (int i = 0; i < 6; i++, t += 200) assertFalse(s.update(true, t));
        for (int i = 0; i < 4; i++, t += 200) confirmed |= s.update(true, t);
        assertTrue(confirmed);
    }

    @Test
    public void singleHit_isNeverConfirmed() {
        SentrySustain s = new SentrySustain(2000);
        long t = 0;
        for (int i = 0; i < 30; i++, t += 200) assertFalse(s.update(i == 12, t));
    }

    @Test
    public void flickeringAroundThreshold_withEnoughHits_isConfirmed() {
        SentrySustain s = new SentrySustain(2000);
        boolean confirmed = false;
        long t = 0;
        for (int i = 0; i < 30; i++, t += 200) confirmed |= s.update(i % 4 != 3, t);   // 75% 명중
        assertTrue(confirmed);
    }

    @Test
    public void sparseHits_areNotConfirmed() {
        SentrySustain s = new SentrySustain(2000);
        long t = 0;
        for (int i = 0; i < 40; i++, t += 200) assertFalse(s.update(i % 3 == 0, t));   // 33% 명중
    }

    @Test
    public void notConfirmedWhenCurrentSampleIsMiss() {
        SentrySustain s = new SentrySustain(2000);
        long t = 0;
        for (int i = 0; i < 12; i++, t += 200) s.update(true, t);
        assertFalse(s.update(false, t));
    }

    @Test
    public void resetStartsOver() {
        SentrySustain s = new SentrySustain(2000);
        long t = 0;
        for (int i = 0; i < 12; i++, t += 200) s.update(true, t);
        s.reset();
        assertFalse(s.update(true, t));
    }
}
