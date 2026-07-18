package io.boins.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads {@link BoinsOptions} from a YAML options file, keeping the core dependency-free.
 *
 * <p>If the file does not exist, a commented default file is created at the given path
 * (including all parent directories) and its defaults are used. If the file exists but is
 * not valid, a {@link BoinsException} is thrown — a broken configuration must never be
 * silently replaced.</p>
 *
 * <p>The parser accepts the strict YAML subset that Boins itself writes: {@code key: value}
 * pairs, {@code #} comments, and one list of flat objects under {@code repositories:}.
 * Anything else — unknown keys, bad indentation, malformed values — is an error. Relative
 * paths are resolved against the directory containing the options file, so a data folder
 * can be moved as a whole.</p>
 */
public final class BoinsOptionsFile {

    private BoinsOptionsFile() {
    }

    /**
     * Loads options from {@code file}, creating a default options file first when missing.
     *
     * @throws BoinsException if the file cannot be created, read or parsed
     */
    public static BoinsOptions loadOrCreate(Path file) throws BoinsException {
        if (!Files.exists(file)) {
            writeDefault(file);
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BoinsException("Failed to read the options file " + file, e);
        }
        return parse(lines, file);
    }

    // ---------------------------------------------------------------- default file

    private static void writeDefault(Path file) throws BoinsException {
        String template = """
                # Boins storage options.
                # Relative paths are resolved against this file's directory.

                blobFileLimit: 36              # blob file size limit as a power of two (2^36 = 64 GiB)
                minBlobBytes: 1024             # append-candidate cutoff, see the Boins Book
                fsyncMode: INTERVAL            # ALWAYS | INTERVAL | NEVER
                fsyncIntervalMillis: 1000      # flush period for fsyncMode INTERVAL, in milliseconds
                minRepositoryFreeBytes: 1073741824   # a repo stops accepting inserts below this free space (1 GiB)
                metricsFlushIntervalMillis: 10000    # how often cumulative metrics are persisted
                # freeCellsFile: ./repo1/free-cells.boins   # defaults to <first repository>/free-cells.boins
                # metricsFile: ./repo1/metrics.boins        # defaults to <first repository>/metrics.boins

                repositories:                  # one entry per physical disk
                  - dir: ./repo1               # repository directory, created if missing
                    blobIdOffset: 0            # first blob id of this repository; NEVER change it later
                    diskType: SSD              # SSD | HDD (write concurrency strategy)
                """;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, template, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BoinsException("Failed to create the default options file " + file, e);
        }
    }

    // ---------------------------------------------------------------- parsing

    private static BoinsOptions parse(List<String> lines, Path file) throws BoinsException {
        Path baseDir = file.toAbsolutePath().getParent();
        BoinsOptions.Builder builder = BoinsOptions.builder();
        RepoFields repo = null;
        boolean inRepositories = false;
        boolean anyRepository = false;

        for (int i = 0; i < lines.size(); i++) {
            int lineNumber = i + 1;
            String line = stripComment(lines.get(i));
            if (line.isBlank()) {
                continue;
            }
            int indent = indentOf(line);
            String content = line.strip();

            if (indent == 0) {
                if (repo != null) {
                    anyRepository = true;
                    addRepository(builder, repo, baseDir, file);
                    repo = null;
                }
                inRepositories = false;
                KeyValue kv = splitKeyValue(content, file, lineNumber);
                if (kv.key.equals("repositories")) {
                    if (!kv.value.isEmpty()) {
                        throw invalid(file, lineNumber, "'repositories' must be a list, not a scalar value");
                    }
                    inRepositories = true;
                } else {
                    applyTopLevel(builder, kv, baseDir, file, lineNumber);
                }
            } else if (content.startsWith("- ")) {
                if (!inRepositories) {
                    throw invalid(file, lineNumber, "a list item is only allowed under 'repositories:'");
                }
                if (repo != null) {
                    anyRepository = true;
                    addRepository(builder, repo, baseDir, file);
                }
                repo = new RepoFields();
                applyRepoField(repo, splitKeyValue(content.substring(2).strip(), file, lineNumber), file, lineNumber);
            } else {
                if (repo == null) {
                    throw invalid(file, lineNumber, "unexpected indented line outside a repository entry");
                }
                applyRepoField(repo, splitKeyValue(content, file, lineNumber), file, lineNumber);
            }
        }
        if (repo != null) {
            anyRepository = true;
            addRepository(builder, repo, baseDir, file);
        }
        if (!anyRepository) {
            throw new BoinsException("Invalid options file " + file + ": the 'repositories' list is missing or empty");
        }
        try {
            return builder.build();
        } catch (IllegalArgumentException e) {
            throw new BoinsException("Invalid options file " + file + ": " + e.getMessage(), e);
        }
    }

    private static void applyTopLevel(BoinsOptions.Builder builder, KeyValue kv, Path baseDir,
                                      Path file, int lineNumber) throws BoinsException {
        switch (kv.key) {
            case "blobFileLimit" -> builder.blobFileLimit((int) parseLong(kv, file, lineNumber));
            case "minBlobBytes" -> builder.minBlobBytes(parseLong(kv, file, lineNumber));
            case "fsyncMode" -> builder.fsyncMode(parseEnum(BoinsOptions.FsyncMode.class, kv, file, lineNumber));
            case "fsyncIntervalMillis" -> builder.fsyncIntervalMillis(parseLong(kv, file, lineNumber));
            case "minRepositoryFreeBytes" -> builder.minRepositoryFreeBytes(parseLong(kv, file, lineNumber));
            case "metricsFlushIntervalMillis" -> builder.metricsFlushIntervalMillis(parseLong(kv, file, lineNumber));
            case "freeCellsFile" -> builder.freeCellsFile(resolve(baseDir, requireValue(kv, file, lineNumber)));
            case "metricsFile" -> builder.metricsFile(resolve(baseDir, requireValue(kv, file, lineNumber)));
            default -> throw invalid(file, lineNumber, "unknown key '" + kv.key + "'");
        }
    }

    private static void applyRepoField(RepoFields repo, KeyValue kv, Path file, int lineNumber)
            throws BoinsException {
        switch (kv.key) {
            case "dir" -> repo.dir = requireValue(kv, file, lineNumber);
            case "blobIdOffset" -> repo.blobIdOffset = parseLong(kv, file, lineNumber);
            case "diskType" -> repo.diskType = parseEnum(BoinsOptions.DiskType.class, kv, file, lineNumber);
            default -> throw invalid(file, lineNumber, "unknown repository key '" + kv.key + "'");
        }
    }

    private static void addRepository(BoinsOptions.Builder builder, RepoFields repo, Path baseDir, Path file)
            throws BoinsException {
        if (repo.dir == null) {
            throw new BoinsException("Invalid options file " + file + ": a repository entry has no 'dir'");
        }
        builder.addRepository(resolve(baseDir, repo.dir), repo.blobIdOffset, repo.diskType);
    }

    private static final class RepoFields {
        String dir;
        long blobIdOffset = 0L;
        BoinsOptions.DiskType diskType = BoinsOptions.DiskType.SSD;
    }

    private record KeyValue(String key, String value) {
    }

    private static KeyValue splitKeyValue(String content, Path file, int lineNumber) throws BoinsException {
        int colon = content.indexOf(':');
        if (colon <= 0) {
            throw invalid(file, lineNumber, "expected 'key: value', got '" + content + "'");
        }
        String key = content.substring(0, colon).strip();
        String value = content.substring(colon + 1).strip();
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
            value = value.substring(1, value.length() - 1);
        }
        return new KeyValue(key, value);
    }

    private static String stripComment(String line) {
        if (line.stripLeading().startsWith("#")) {
            return "";
        }
        // A '#' preceded by whitespace starts a trailing comment; values must not contain '#'.
        for (int i = 1; i < line.length(); i++) {
            if (line.charAt(i) == '#' && Character.isWhitespace(line.charAt(i - 1))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static String requireValue(KeyValue kv, Path file, int lineNumber) throws BoinsException {
        if (kv.value.isEmpty()) {
            throw invalid(file, lineNumber, "key '" + kv.key + "' has no value");
        }
        return kv.value;
    }

    private static long parseLong(KeyValue kv, Path file, int lineNumber) throws BoinsException {
        try {
            return Long.parseLong(requireValue(kv, file, lineNumber).replace("_", ""));
        } catch (NumberFormatException e) {
            throw invalid(file, lineNumber, "key '" + kv.key + "' must be an integer, got '" + kv.value + "'");
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, KeyValue kv, Path file, int lineNumber)
            throws BoinsException {
        try {
            return Enum.valueOf(type, requireValue(kv, file, lineNumber).toUpperCase());
        } catch (IllegalArgumentException e) {
            throw invalid(file, lineNumber, "key '" + kv.key + "' has invalid value '" + kv.value + "'");
        }
    }

    private static Path resolve(Path baseDir, String value) {
        Path path = Path.of(value);
        if (path.isAbsolute() || baseDir == null) {
            return path;
        }
        return baseDir.resolve(path).normalize();
    }

    private static BoinsException invalid(Path file, int lineNumber, String message) {
        return new BoinsException("Invalid options file " + file + " (line " + lineNumber + "): " + message);
    }
}
