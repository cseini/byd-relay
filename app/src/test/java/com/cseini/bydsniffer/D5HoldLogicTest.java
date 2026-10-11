package com.cseini.bydsniffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class D5HoldLogicTest {
    @Test
    public void voltStreak_countsConsecutiveLowSamples_andIgnoresUnusableOnes() {
        int s = 0;
        s = D5HoldLogic.voltStreak(s, 11.7, D5HoldLogic.CUTOFF_V);
        s = D5HoldLogic.voltStreak(s, 11.6, D5HoldLogic.CUTOFF_V);
        assertEquals(2, s);
        assertFalse(D5HoldLogic.cutoffReached(s));
        s = D5HoldLogic.voltStreak(s, null, D5HoldLogic.CUTOFF_V);
        assertEquals(2, s);
        s = D5HoldLogic.voltStreak(s, 3.0, D5HoldLogic.CUTOFF_V);
        assertEquals(2, s);
        s = D5HoldLogic.voltStreak(s, 11.5, D5HoldLogic.CUTOFF_V);
        assertTrue(D5HoldLogic.cutoffReached(s));
    }

    @Test
    public void voltStreak_resetsOnHealthySample() {
        int s = D5HoldLogic.voltStreak(2, 12.4, D5HoldLogic.CUTOFF_V);
        assertEquals(0, s);
    }

    @Test
    public void releaseList_namesOnlyHeldLevers_andSkips1901() {
        assertEquals("", D5HoldLogic.releaseList(0));
        assertEquals("", D5HoldLogic.releaseList(D5HoldLogic.FLAGS1901 | D5HoldLogic.SHELL));
        assertEquals("782(0/2) 전원유지(0) QNX", D5HoldLogic.releaseList(D5HoldLogic.FLAGS782 | D5HoldLogic.MCUPOWER | D5HoldLogic.QNX | D5HoldLogic.FLAGS1901));
        assertEquals("카메라(0) AP토큰제거", D5HoldLogic.releaseList(D5HoldLogic.CAM | D5HoldLogic.AP));
    }

    @Test
    public void allMask_coversEveryLever() {
        int all = D5HoldLogic.MCUPOWER | D5HoldLogic.FLAGS782 | D5HoldLogic.FLAGS1901 | D5HoldLogic.QNX | D5HoldLogic.CAM | D5HoldLogic.AP | D5HoldLogic.SHELL;
        assertEquals(D5HoldLogic.ALL, all);
    }
}
