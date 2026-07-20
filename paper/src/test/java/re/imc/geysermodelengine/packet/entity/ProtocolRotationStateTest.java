package re.imc.geysermodelengine.packet.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolRotationStateTest {

    @Test
    void normalizesEquivalentHalfTurnsToNegative180() {
        assertEquals(-180.0f, ProtocolRotationState.normalize(-180.0f));
        assertEquals(-180.0f, ProtocolRotationState.normalize(180.0f));
        assertEquals(-180.0f, ProtocolRotationState.normalize(540.0f));
        assertEquals(-180.0f, ProtocolRotationState.normalize(-540.0f));
    }

    @Test
    void doesNotResendWhenCrossingEquivalent180Representation() {
        ProtocolRotationState state = new ProtocolRotationState();
        state.update(-180.0f, 0.0f, -180.0f);

        ProtocolRotationState.Update update = state.update(180.0f, 0.0f, 180.0f);

        assertTrue(update.accepted());
        assertFalse(update.bodyRotationChanged());
        assertFalse(update.headYawChanged());
    }

    @Test
    void deduplicatesChangesBelowProtocolPrecision() {
        ProtocolRotationState state = new ProtocolRotationState();
        state.update(10.0f, 5.0f, 20.0f);

        ProtocolRotationState.Update update = state.update(10.5f, 5.5f, 20.5f);

        assertFalse(update.bodyRotationChanged());
        assertFalse(update.headYawChanged());
        assertEquals(10.5f, update.rotation().bodyYaw());
        assertEquals(5.5f, update.rotation().headPitch());
        assertEquals(20.5f, update.rotation().headYaw());
    }

    @Test
    void distinguishesBodyAndHeadOnlyChanges() {
        ProtocolRotationState state = new ProtocolRotationState();
        state.update(0.0f, 0.0f, 0.0f);

        ProtocolRotationState.Update headUpdate = state.update(0.0f, 0.0f, 30.0f);
        assertFalse(headUpdate.bodyRotationChanged());
        assertTrue(headUpdate.headYawChanged());

        ProtocolRotationState.Update bodyUpdate = state.update(45.0f, 15.0f, 30.0f);
        assertTrue(bodyUpdate.bodyRotationChanged());
        assertFalse(bodyUpdate.headYawChanged());
    }

    @Test
    void rejectsNonFinitePoseWithoutChangingLastValidState() {
        ProtocolRotationState state = new ProtocolRotationState();
        ProtocolRotationState.Update valid = state.update(45.0f, 15.0f, 30.0f);

        ProtocolRotationState.Update invalid = state.update(Float.NaN, Float.POSITIVE_INFINITY, 90.0f);

        assertFalse(invalid.accepted());
        assertFalse(invalid.bodyRotationChanged());
        assertFalse(invalid.headYawChanged());
        assertEquals(valid.rotation(), invalid.rotation());
        assertEquals(valid.rotation(), state.current());
    }

    @Test
    void treatsWrappedAnglesAsTheSameWireRotation() {
        ProtocolRotationState state = new ProtocolRotationState();
        state.update(-179.0f, 0.0f, -179.0f);

        ProtocolRotationState.Update update = state.update(181.0f, 0.0f, 181.0f);

        assertFalse(update.bodyRotationChanged());
        assertFalse(update.headYawChanged());
    }
}
