package com.flow.adhd;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FlowNotifServiceTest {
    @Test public void compactEligibleCountHidesZeroAndCapsAtNinePlus() {
        assertEquals("", FlowNotifService.compactEligibleCountLabel(0));
        assertEquals("", FlowNotifService.compactEligibleCountLabel(-1));
        assertEquals("1", FlowNotifService.compactEligibleCountLabel(1));
        assertEquals("9", FlowNotifService.compactEligibleCountLabel(9));
        assertEquals("9+", FlowNotifService.compactEligibleCountLabel(10));
    }
}
