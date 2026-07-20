package re.imc.geysermodelengine.managers.model.entity;

/** Resolves ModelEngine's effective pose without requiring an ActiveModel binding. */
final class ModelEnginePoseResolver {

    private ModelEnginePoseResolver() {
    }

    static Pose resolve(
            float bodyYaw,
            float headPitch,
            float headYaw,
            boolean lockPitch,
            boolean lockYaw
    ) {
        return new Pose(bodyYaw, lockPitch ? 0.0f : headPitch, lockYaw ? bodyYaw : headYaw);
    }

    record Pose(float bodyYaw, float headPitch, float headYaw) {
    }
}
