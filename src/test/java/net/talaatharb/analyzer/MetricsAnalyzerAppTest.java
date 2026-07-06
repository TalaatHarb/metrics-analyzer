package net.talaatharb.analyzer;

import net.talaatharb.analyzer.model.ClassMetrics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetricsAnalyzerAppTest {

    /**
     * Validates the debt score formula:
     * score = CC × (1 − MI/100) × (1 + coupling/10)
     */
    @Test
    void debtScoreShouldReflectHighCcAndLowMiAsHighDebt() {
        // CC=15, MI=20 (low maintainability), coupling=5 → 15 * 0.80 * 1.5 = 18.0
        ClassMetrics high = new ClassMetrics("pkg", "Foo", 200, 10, 5, 5, 0.5, 15, 50, 30, 20.0);
        double score = debtScore(high);
        assertTrue(score > 10.0, "High-CC, low-MI class should have score > 10, got " + score);
    }

    @Test
    void debtScoreShouldBeZeroForZeroCc() {
        // CC=0 → score=0 regardless of other metrics
        ClassMetrics clean = new ClassMetrics("pkg", "Bar", 50, 3, 2, 2, 0.0, 0, 0, 5, 85.0);
        assertEquals(0.0, debtScore(clean), 0.001);
    }

    @Test
    void debtScoreShouldBeHigherForHighCouplingThanLow() {
        ClassMetrics lowCoupling  = new ClassMetrics("pkg", "A", 100, 5, 3, 2,  0.0, 5, 10, 8, 50.0);
        ClassMetrics highCoupling = new ClassMetrics("pkg", "B", 100, 5, 3, 20, 0.0, 5, 10, 8, 50.0);
        assertTrue(debtScore(highCoupling) > debtScore(lowCoupling));
    }

    @Test
    void debtScoreShouldBeHigherForLowMiThanHigh() {
        ClassMetrics highMi = new ClassMetrics("pkg", "C", 100, 5, 3, 3, 0.0, 8, 15, 10, 90.0);
        ClassMetrics lowMi  = new ClassMetrics("pkg", "D", 100, 5, 3, 3, 0.0, 8, 15, 10, 20.0);
        assertTrue(debtScore(lowMi) > debtScore(highMi));
    }

    @Test
    void debtScoreShouldClampNegativeMiToZero() {
        // MI clamped at 0 → (1 − 0/100) = 1.0
        ClassMetrics negMi = new ClassMetrics("pkg", "E", 50, 2, 1, 1, 0.0, 4, 5, 5, -10.0);
        double score = debtScore(negMi);
        assertEquals(4.0 * 1.0 * (1.0 + 1.0 / 10.0), score, 0.001);
    }

    // Mirrors the private static method in MetricsAnalyzerApp
    private static double debtScore(ClassMetrics cm) {
        double cc = cm.getCyclomaticComplexity();
        double mi = Math.min(100.0, Math.max(0.0, cm.getMaintainabilityIndex()));
        double coupling = cm.getEfferentCoupling();
        return cc * (1.0 - mi / 100.0) * (1.0 + coupling / 10.0);
    }
}
