package io.github.dmitryaf.projectsjournal;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class CodexProcess {

    static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
    static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("codex-deadlines").factory());

    private CodexProcess() {
    }

    static String executable(Map<String, String> environment) {
        var configured = environment.get("CONTROL_CENTER_CODEX_PATH");
        return configured == null || configured.isEmpty() ? WINDOWS ? "codex.exe" : "codex" : configured;
    }

    static Process start(String program, List<String> prefix, List<String> args, Map<String, String> environment,
                         Path directory) throws IOException {
        var command = new ArrayList<String>(1 + prefix.size() + args.size());
        command.add(program);
        command.addAll(prefix);
        command.addAll(args);
        var builder = new ProcessBuilder(command);
        if (directory != null) {
            builder.directory(directory.toFile());
        }
        builder.environment().clear();
        builder.environment().putAll(environment);
        return builder.start();
    }

    static String capture(String program, List<String> prefix, List<String> args, Map<String, String> environment,
                          Path directory, int seconds, int maximum) throws IOException {
        var process = start(program, prefix, args, environment, directory);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                process.getOutputStream().close();
                var output = threads.submit(() -> readBounded(process, process.getInputStream(), maximum));
                var diagnostics = threads.submit(() -> readBounded(process, process.getErrorStream(), maximum));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
                if (!process.waitFor(seconds, TimeUnit.SECONDS) || process.exitValue() != 0) {
                    throw new IOException("Codex CLI command did not finish successfully");
                }
                var bytes = output.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                diagnostics.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Codex CLI command interrupted");
            } catch (ExecutionException | TimeoutException failed) {
                throw new IOException("Codex CLI output unavailable");
            } finally {
                terminate(process);
                close(process.getInputStream());
                close(process.getErrorStream());
            }
        }
    }

    private static byte[] readBounded(Process process, InputStream stream, int maximum) throws IOException {
        try (stream) {
            var bytes = stream.readNBytes(maximum + 1);
            if (bytes.length > maximum) {
                terminate(process);
                throw new LimitException();
            }
            return bytes;
        }
    }

    static void drain(InputStream diagnostics) {
        try (diagnostics) {
            diagnostics.transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignored) {
            // CLI diagnostics can include private paths or content.
        }
    }

    static void close(Closeable stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Process shutdown closes its side of the pipe independently.
        }
    }

    static void terminate(Process process) {
        if (process.isAlive()) {
            var children = process.descendants().toList();
            for (var child : children.reversed()) {
                child.destroyForcibly();
            }
            process.destroyForcibly();
        }
    }

    static void awaitExit(Process process) throws IOException {
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                throw new IOException("Codex CLI did not stop");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Codex CLI shutdown interrupted");
        }
    }

    static TempDirectory temporaryDirectory(String prefix) throws IOException {
        var root = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().toRealPath();
        return new TempDirectory(root, Files.createTempDirectory(root, prefix), prefix);
    }

    record TempDirectory(Path root, Path path, String prefix) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (!root.equals(path.getParent()) || !path.getFileName().toString().startsWith(prefix)) {
                throw new IOException("Unexpected Codex temporary path");
            }
            IOException failure = null;
            for (int attempt = 0; attempt < 4; attempt++) {
                try {
                    remove();
                    return;
                } catch (IOException error) {
                    failure = error;
                    if (attempt < 3) {
                        var interrupted = Thread.interrupted();
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException wake) {
                            interrupted = true;
                        } finally {
                            if (interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                }
            }
            throw failure;
        }

        private void remove() throws IOException {
            removeEntry(path);
        }

        private void removeEntry(Path entry) throws IOException {
            BasicFileAttributes attributes;
            try {
                var parent = entry.getParent().toRealPath();
                if ((entry.equals(path) && !parent.equals(root)) || (!entry.equals(path) && !parent.startsWith(path))) {
                    throw new IOException("Codex child replaced a temporary directory");
                }
                attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException removed) {
                return;
            }
            if (attributes.isDirectory() && !attributes.isSymbolicLink() && !attributes.isOther()) {
                if (!entry.toRealPath().startsWith(path)) {
                    throw new IOException("Codex child replaced a temporary directory");
                }
                try (var children = Files.newDirectoryStream(entry)) {
                    for (var child : children) {
                        removeEntry(child);
                    }
                }
            }
            Files.deleteIfExists(entry);
        }
    }

    record Line(String text, boolean complete) {
    }

    static final class LimitException extends IOException {
    }

    static final class Lines implements AutoCloseable {
        private final BufferedInputStream input;
        private final int lineLimit;
        private final int totalLimit;
        private int bytes;

        Lines(InputStream input, int lineLimit, int totalLimit) {
            this.input = new BufferedInputStream(input);
            this.lineLimit = lineLimit;
            this.totalLimit = totalLimit;
        }

        Line next() throws IOException {
            var line = new ByteArrayOutputStream();
            for (int value; (value = input.read()) != -1; ) {
                if (++bytes > totalLimit) {
                    throw new LimitException();
                }
                if (value == '\n') {
                    return new Line(line.toString(StandardCharsets.UTF_8), true);
                }
                if (line.size() >= lineLimit) {
                    throw new LimitException();
                }
                line.write(value);
            }
            return line.size() == 0 ? null : new Line(line.toString(StandardCharsets.UTF_8), false);
        }

        @Override
        public void close() {
            CodexProcess.close(input);
        }
    }
}
