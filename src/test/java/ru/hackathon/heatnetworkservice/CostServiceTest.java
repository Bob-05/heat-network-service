package ru.hackathon.heatnetworkservice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CostServiceTest {

    @Test
    void unconnectedPenalty_formula() {
        double flow = 20.0;
        double expected = 100_000_000.0 + 500_000.0 * flow;
        assertEquals(110_000_000.0, expected);
    }

    @Test
    void score_formula() {
        double calculatedCost = 13_974_800.0;
        double length = 100.0;

        double score = 0.7 * (calculatedCost / 25_000_000.0)
                + 0.3 * (length / 100.0);

        assertEquals(0.6913, Math.round(score * 10000.0) / 10000.0, 0.0001);
    }
}