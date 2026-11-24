package re.imc.geysermodelengineextension.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BooleanPackerWireContractTest {
    @Test
    void preservesEmptyAndTwentyFourBitWireFrames() {
        assertEquals(0, BooleanPacker.booleansToInt(List.of()));
        assertEquals(List.of(), BooleanPacker.booleansToInts(List.of()));
        assertEquals(List.of(16777215), BooleanPacker.booleansToInts(java.util.Collections.nCopies(24, true)));
        List<Boolean> values = new ArrayList<>(java.util.Collections.nCopies(24, true));
        values.add(true);
        assertEquals(List.of(16777215, 1), BooleanPacker.booleansToInts(values));
    }

    @Test
    void propertyInsertionOrderDoesNotChangeWireBitPositions() {
        Map<String, Boolean> values = new LinkedHashMap<>();
        for (int index = 24; index >= 0; index--)
            values.put(String.format(java.util.Locale.ROOT, "bone%02d", index), index == 0 || index >= 23);
        assertEquals(List.of(8388609, 1), BooleanPacker.mapBooleansToInts(values));
        assertEquals(3, BooleanPacker.mapBooleansToInt(Map.of("z", true, "a", true)));
    }
}
