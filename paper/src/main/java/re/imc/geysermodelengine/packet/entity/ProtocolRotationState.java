package re.imc.geysermodelengine.packet.entity;

/**
 * Tracks rotations at the precision used by the Java entity protocol.
 *
 * <p>Entity rotations are encoded as a single byte. Comparing the source floats directly would
 * therefore emit packets for changes that cannot reach the client. The normalized values are
 * still retained so a later position sync can use the freshest pose.</p>
 */
final class ProtocolRotationState {

    private static final float ROTATION_FACTOR = 256.0f / 360.0f;

    private Rotation rotation = Rotation.ZERO;
    private boolean initialized;

    Update update(float bodyYaw, float headPitch, float headYaw) {
        if (!Float.isFinite(bodyYaw) || !Float.isFinite(headPitch) || !Float.isFinite(headYaw)) {
            return new Update(rotation, false, false, false);
        }

        Rotation next = Rotation.of(bodyYaw, headPitch, headYaw);
        boolean bodyRotationChanged = !initialized
                || rotation.bodyYawByte() != next.bodyYawByte()
                || rotation.headPitchByte() != next.headPitchByte();
        boolean headYawChanged = !initialized || rotation.headYawByte() != next.headYawByte();

        rotation = next;
        initialized = true;
        return new Update(rotation, true, bodyRotationChanged, headYawChanged);
    }

    Rotation current() {
        return rotation;
    }

    static float normalize(float degrees) {
        float normalized = degrees % 360.0f;
        if (normalized >= 180.0f) normalized -= 360.0f;
        if (normalized < -180.0f) normalized += 360.0f;
        return normalized == -0.0f ? 0.0f : normalized;
    }

    static int protocolByte(float degrees) {
        // PacketEvents uses the same float multiplication followed by a truncating int cast.
        return ((int) (normalize(degrees) * ROTATION_FACTOR)) & 0xFF;
    }

    record Rotation(
            float bodyYaw,
            float headPitch,
            float headYaw,
            int bodyYawByte,
            int headPitchByte,
            int headYawByte
    ) {
        private static final Rotation ZERO = new Rotation(0.0f, 0.0f, 0.0f, 0, 0, 0);

        static Rotation of(float bodyYaw, float headPitch, float headYaw) {
            float normalizedBodyYaw = normalize(bodyYaw);
            float normalizedHeadPitch = normalize(headPitch);
            float normalizedHeadYaw = normalize(headYaw);
            return new Rotation(
                    normalizedBodyYaw,
                    normalizedHeadPitch,
                    normalizedHeadYaw,
                    protocolByte(normalizedBodyYaw),
                    protocolByte(normalizedHeadPitch),
                    protocolByte(normalizedHeadYaw)
            );
        }
    }

    record Update(
            Rotation rotation,
            boolean accepted,
            boolean bodyRotationChanged,
            boolean headYawChanged
    ) {
    }
}
