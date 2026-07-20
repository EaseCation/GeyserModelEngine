package re.imc.geysermodelengineextension.managers.resourcepack;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

final class ResourcePackFileOps {

    private ResourcePackFileOps() {
    }

    static boolean contentEqualsIgnoringManifest(Path leftRoot, Path rightRoot) throws IOException {
        if (!Files.isDirectory(leftRoot) || !Files.isDirectory(rightRoot)) {
            return false;
        }

        List<Path> leftFiles = relativeFiles(leftRoot, true);
        List<Path> rightFiles = relativeFiles(rightRoot, true);
        if (!leftFiles.equals(rightFiles)) {
            return false;
        }

        for (Path relative : leftFiles) {
            if (Files.mismatch(leftRoot.resolve(relative), rightRoot.resolve(relative)) != -1) {
                return false;
            }
        }
        return true;
    }

    static List<Path> relativeFiles(Path root, boolean ignoreManifest) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .filter(path -> !ignoreManifest || !path.toString().replace('\\', '/').equals("manifest.json"))
                    .sorted(Comparator.comparing(path -> path.toString().replace('\\', '/')))
                    .toList();
        }
    }

    static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
