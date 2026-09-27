package com.senyalert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Isolated check for source-tree, packaged, and explicitly configured data paths. */
public final class AppDataDirectorySmoke {
    private AppDataDirectorySmoke() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Pass one new, isolated verification directory.");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(root);

        Path sourceRoot = root.resolve("source-project");
        Path establishedStore = sourceRoot.resolve("java-dashboard");
        Files.createDirectories(establishedStore);
        writeNew(establishedStore.resolve("pom.xml"), "<project/>");
        writeNew(establishedStore.resolve("incidents.db"), "fixture-only");
        writeNew(sourceRoot.resolve("incidents.db"), "stray-fixture-only");
        expect(establishedStore, AppDataDirectory.resolve(sourceRoot, null), "source tree established store");

        Path packagedRoot = root.resolve("packaged-app");
        Files.createDirectories(packagedRoot);
        writeNew(packagedRoot.resolve("senyalert-config.json"), "{}");
        expect(packagedRoot, AppDataDirectory.resolve(packagedRoot, null), "packaged current directory");

        Path explicitRoot = root.resolve("explicit-store");
        Files.createDirectories(explicitRoot);
        expect(explicitRoot, AppDataDirectory.resolve(sourceRoot, explicitRoot.toString()), "explicit directory");

        System.out.println("AppDataDirectory smoke passed: " + root);
    }

    private static void writeNew(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static void expect(Path expected, Path actual, String label) {
        if (!expected.toAbsolutePath().normalize().equals(actual)) {
            throw new AssertionError(label + " resolved " + actual + " instead of " + expected);
        }
    }
}
