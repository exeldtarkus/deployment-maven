package com.bcalife.common.deploy.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Utility class untuk mencari lokasi file .env di dalam struktur project.
 */
public class EnvLocator {

    private static final String ENV_FILE_NAME = ".env";
    private static final int MAX_SEARCH_DEPTH = 5;

    private static final Set<String> IGNORED_DIRS = new java.util.HashSet<>(java.util.Arrays.asList(
            "target", "build", "out", "node_modules",
            ".git", ".idea", ".vscode", "deployment"
    ));

    /**
     * Mencari file .env di dalam direktori root yang diberikan.
     * Menggunakan Optional agar lebih aman dari NullPointerException.
     *
     * @param rootDirPath Path folder root project (contoh: System.getProperty("user.dir"))
     * @return Optional berisi Path dari file .env jika ditemukan.
     */
    public static Optional<Path> find(String rootDirPath) {
        Path rootDir = Paths.get(rootDirPath).toAbsolutePath();

        // 1. Cek lokasi prioritas utama (Best Practices)
        Path rootEnv = rootDir.resolve(ENV_FILE_NAME);
        Path resourcesEnv = rootDir.resolve("src/main/resources").resolve(ENV_FILE_NAME);

        if (Files.exists(rootEnv)) {
            return Optional.of(rootEnv);
        }
        if (Files.exists(resourcesEnv)) {
            return Optional.of(resourcesEnv);
        }

        // 2. Jika tidak ada di lokasi standar, lakukan pemindaian (scan) menyeluruh
        try (Stream<Path> stream = Files.walk(rootDir, MAX_SEARCH_DEPTH)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(ENV_FILE_NAME))
                    .filter(EnvLocator::isNotInIgnoredDirectory)
                    .findFirst();
        } catch (IOException e) {
            System.err.println("[WARN] Terjadi kesalahan saat memindai folder untuk .env: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Mengecek apakah file berada di dalam salah satu folder yang dilarang/diabaikan.
     */
    private static boolean isNotInIgnoredDirectory(Path path) {
        for (Path part : path) {
            if (IGNORED_DIRS.contains(part.toString())) {
                return false;
            }
        }
        return true;
    }
}