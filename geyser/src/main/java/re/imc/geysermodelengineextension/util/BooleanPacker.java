package re.imc.geysermodelengineextension.util;

import java.util.List;
import java.util.Map;

/** Existing entry point delegating to the single shared wire-format implementation. */
public class BooleanPacker {
    public static final int MAX_BOOLEANS = re.imc.geysermodelengine.shared.BooleanPacker.MAX_BOOLEANS;

    public static int booleansToInt(List<Boolean> values) {
        return re.imc.geysermodelengine.shared.BooleanPacker.booleansToInt(values);
    }

    public static int mapBooleansToInt(Map<String, Boolean> values) {
        return re.imc.geysermodelengine.shared.BooleanPacker.mapBooleansToInt(values);
    }

    public static List<Integer> booleansToInts(List<Boolean> values) {
        return re.imc.geysermodelengine.shared.BooleanPacker.booleansToInts(values);
    }

    public static List<Integer> mapBooleansToInts(Map<String, Boolean> values) {
        return re.imc.geysermodelengine.shared.BooleanPacker.mapBooleansToInts(values);
    }
}
