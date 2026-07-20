package re.imc.geysermodelengineextension.managers.resourcepack.generator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadModelProfileTest {

    @Test
    void findsOnlyTopLevelHeadAcrossTheFullAncestorChain() {
        HeadModelProfile profile = HeadModelProfile.analyze(geometry("""
                [
                  {"name":"root"},
                  {"name":"hi_head","parent":"root"},
                  {"name":"helper","parent":"hi_head"},
                  {"name":"h_eye","parent":"helper"},
                  {"name":"headc","parent":"root"},
                  {"name":"mount","parent":"root"}
                ]
                """));

        assertEquals(List.of("h_eye", "hi_head"), profile.modelEngineHeadBones());
        assertEquals(List.of("hi_head"), profile.headAnchors());
        assertEquals(List.of("h_eye"), profile.nestedHeadBones());
        assertEquals(List.of("headc"), profile.plainHeadBones());
        assertEquals(List.of("mount"), profile.mountBones());
    }

    @Test
    void keepsCaseAndWhitespaceVariantsOutOfTheModelEngineContract() {
        HeadModelProfile profile = HeadModelProfile.analyze(geometry("""
                [
                  {"name":"root"},
                  {"name":"H_Head","parent":"root"},
                  {"name":" hi_other","parent":"root"}
                ]
                """));

        assertTrue(profile.modelEngineHeadBones().isEmpty());
        assertTrue(profile.headAnchors().isEmpty());
        assertEquals(List.of("h_head", "hi_other"), profile.caseVariantHeadBones());
    }

    @Test
    void supportsMultipleIndependentAnchorsWithoutGuessingPlainHeads() {
        HeadModelProfile profile = HeadModelProfile.analyze(geometry("""
                [
                  {"name":"root"},
                  {"name":"h_left","parent":"root"},
                  {"name":"hi_right","parent":"root"},
                  {"name":"head","parent":"root"}
                ]
                """));

        assertEquals(List.of("h_left", "hi_right"), profile.headAnchors());
        assertEquals(List.of("head"), profile.plainHeadBones());
        assertEquals(List.of("h_left", "hi_right"), profile.nonHeadNamedAnchors());
    }

    @Test
    void rejectsMissingParentsCyclesAndNormalizedDuplicates() {
        assertThrows(IllegalArgumentException.class, () -> HeadModelProfile.analyze(geometry("""
                [{"name":"h_head","parent":"missing"}]
                """)));
        assertThrows(IllegalArgumentException.class, () -> HeadModelProfile.analyze(geometry("""
                [{"name":"a","parent":"b"},{"name":"b","parent":"a"}]
                """)));
        assertThrows(IllegalArgumentException.class, () -> HeadModelProfile.analyze(geometry("""
                [{"name":"Head"},{"name":"head"}]
                """)));
    }

    private static Geometry geometry(String bones) {
        Geometry geometry = new Geometry();
        geometry.setModelId("test");
        geometry.load("""
                {"format_version":"1.21.0","minecraft:geometry":[{
                  "description":{"identifier":"geometry.test"},
                  "bones":%s
                }]}
                """.formatted(bones));
        return geometry;
    }
}
