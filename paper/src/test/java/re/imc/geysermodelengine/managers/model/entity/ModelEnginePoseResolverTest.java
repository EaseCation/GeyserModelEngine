package re.imc.geysermodelengine.managers.model.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelEnginePoseResolverTest {

    @Test
    void preservesModeledEntityPoseWhenUnlocked() {
        ModelEnginePoseResolver.Pose pose = ModelEnginePoseResolver.resolve(90.0f, -25.0f, 35.0f, false, false);

        assertEquals(90.0f, pose.bodyYaw());
        assertEquals(-25.0f, pose.headPitch());
        assertEquals(35.0f, pose.headYaw());
    }

    @Test
    void zeroesPitchWhenPitchIsLocked() {
        ModelEnginePoseResolver.Pose pose = ModelEnginePoseResolver.resolve(90.0f, -25.0f, 35.0f, true, false);

        assertEquals(90.0f, pose.bodyYaw());
        assertEquals(0.0f, pose.headPitch());
        assertEquals(35.0f, pose.headYaw());
    }

    @Test
    void followsBodyYawWhenYawIsLocked() {
        ModelEnginePoseResolver.Pose pose = ModelEnginePoseResolver.resolve(90.0f, -25.0f, 35.0f, false, true);

        assertEquals(90.0f, pose.bodyYaw());
        assertEquals(-25.0f, pose.headPitch());
        assertEquals(90.0f, pose.headYaw());
    }

    @Test
    void appliesBothModelEngineLocksTogether() {
        ModelEnginePoseResolver.Pose pose = ModelEnginePoseResolver.resolve(-135.0f, 20.0f, 45.0f, true, true);

        assertEquals(-135.0f, pose.bodyYaw());
        assertEquals(0.0f, pose.headPitch());
        assertEquals(-135.0f, pose.headYaw());
    }
}
