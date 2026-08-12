package dev.c1bridge.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public final class HumanRideSimulatorTest {
    @Test
    public void cycleContainsWarmupSprintDropAndRecovery() {
        HumanRideSimulator simulator = new HumanRideSimulator(196, 100, 42L);
        int minimumCadence = Integer.MAX_VALUE;
        int maximumCadence = Integer.MIN_VALUE;
        int highCadenceSamples = 0;
        int recoveryCadenceSamples = 0;

        for (int index = 0; index < HumanRideSimulator.CYCLE_SECONDS; index++) {
            HumanRideSimulator.Signal signal = simulator.next();
            minimumCadence = Math.min(minimumCadence, signal.cadenceRpm);
            maximumCadence = Math.max(maximumCadence, signal.cadenceRpm);
            if (signal.cadenceRpm >= 92) {
                highCadenceSamples++;
            }
            if (signal.cadenceRpm <= 36) {
                recoveryCadenceSamples++;
            }
        }

        assertTrue(minimumCadence <= 34);
        assertTrue(maximumCadence >= 97);
        assertTrue(highCadenceSamples >= 10);
        assertTrue(recoveryCadenceSamples >= 8);
    }

    @Test
    public void powerAndCadenceVaryWithoutSharingOneWaveform() {
        HumanRideSimulator simulator = new HumanRideSimulator(196, 84, 91L);
        Set<Integer> powers = new HashSet<>();
        Set<Integer> cadences = new HashSet<>();
        int differentDirectionChanges = 0;
        HumanRideSimulator.Signal previous = simulator.next();

        for (int index = 1; index < 150; index++) {
            HumanRideSimulator.Signal signal = simulator.next();
            powers.add(signal.powerWatts);
            cadences.add(signal.cadenceRpm);
            int powerDirection = Integer.compare(signal.powerWatts, previous.powerWatts);
            int cadenceDirection = Integer.compare(signal.cadenceRpm, previous.cadenceRpm);
            if (powerDirection != cadenceDirection) {
                differentDirectionChanges++;
            }
            previous = signal;
        }

        assertTrue(powers.size() > 50);
        assertTrue(cadences.size() > 35);
        assertTrue(differentDirectionChanges > 25);
        assertNotEquals(powers.size(), cadences.size());
    }

    @Test
    public void fixedSeedProducesRepeatablePreview() {
        HumanRideSimulator first = new HumanRideSimulator(240, 100, 7123L);
        HumanRideSimulator second = new HumanRideSimulator(240, 100, 7123L);

        for (int index = 0; index < 180; index++) {
            HumanRideSimulator.Signal firstSignal = first.next();
            HumanRideSimulator.Signal secondSignal = second.next();
            assertEquals(firstSignal.powerWatts, secondSignal.powerWatts);
            assertEquals(firstSignal.cadenceRpm, secondSignal.cadenceRpm);
            assertTrue(firstSignal.powerWatts >= 0 && firstSignal.powerWatts <= 999);
            assertTrue(firstSignal.cadenceRpm >= 0 && firstSignal.cadenceRpm <= 220);
        }
    }
}
