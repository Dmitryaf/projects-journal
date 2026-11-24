package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class GitProcess {

    private static final int OUTPUT_LIMIT = 2 * 1024 * 1024;

    private GitProcess() {
    }

    static String run(Path directory, String... arguments) throws IOException {
        return requireSuccess(execute(directory, List.of(), OUTPUT_LIMIT, 15, arguments));
    }

    static String status(Path directory) throws IOException {
        var filters = execute(directory, List.of(), OUTPUT_LIMIT, 15,
                "config", "--null", "--name-only", "--get-regexp", "^filter\\..*\\.(clean|process|required)$");
        var options = new ArrayList<String>();
        if (filters.exitCode() != 1) {
            for (String key : nulFields(requireSuccess(filters))) {
                if (key.contains("=")) {
                    throw new IOException("Не удалось отключить внешние Git-фильтры.");
                }
                options.add("-c");
                options.add(key + (key.endsWith(".required") ? "=false" : "="));
            }
        }
        return requireSuccess(execute(directory, options, OUTPUT_LIMIT, 15,
                "status", "--porcelain=v1", "-z", "--untracked-files=normal", "--ignore-submodules=dirty"));
    }

    static List<String> tracked(Path directory) throws IOException {
        return nulFields(requireSuccess(execute(directory, List.of(), 8 * 1024 * 1024, 15,
                "ls-files", "-z", "--cached"))).stream().distinct().toList();
    }

    static String worktreeList(Path directory) throws IOException {
        return requireSuccess(execute(directory, List.of(), 512 * 1024, 5,
                "worktree", "list", "--porcelain", "-z"));
    }

    static List<String> nulFields(String text) {
        return Arrays.stream(text.split("\u0000")).filter(field -> !field.isEmpty()).toList();
    }

    private static Result execute(Path directory, List<String> options, int maximum, int timeoutSeconds,
            String... arguments) throws IOException {
        var command = new ArrayList<>(List.of("git", "--no-optional-locks", "--no-pager",
                "-c", "core.fsmonitor=false", "-c", "core.untrackedCache=false",
                "-c", "log.showSignature=false", "-C", directory.toString()));
        command.addAll(options);
        command.addAll(Arrays.asList(arguments));
        var builder = new ProcessBuilder(command);
        var environment = builder.environment();
        for (String key : List.of("GIT_DIR", "GIT_WORK_TREE", "GIT_COMMON_DIR", "GIT_INDEX_FILE",
                "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_EXTERNAL_DIFF")) {
            environment.remove(key);
        }
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_NO_LAZY_FETCH", "1");
        environment.put("GIT_ALLOW_PROTOCOL", "");
        Process process = builder.start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                process.getOutputStream().close();
                var stdout = executor.submit(() -> readOutput(process.getInputStream(), process, maximum));
                var stderr = executor.submit(() -> readOutput(process.getErrorStream(), process, maximum));
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    throw new IOException("Истекло время ожидания Git.");
                }
                stderr.get();
                return new Result(process.exitValue(), new String(stdout.get(), StandardCharsets.UTF_8));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Чтение Git прервано.", interrupted);
            } catch (ExecutionException failure) {
                throw new IOException("Не удалось прочитать вывод Git.", failure.getCause());
            } finally {
                terminate(process);
            }
        }
    }

    private static byte[] readOutput(InputStream stream, Process process, int maximum) throws IOException {
        try (stream) {
            byte[] bytes = stream.readNBytes(maximum + 1);
            if (bytes.length > maximum) {
                terminate(process);
                throw new IOException("Вывод Git превысил допустимый размер.");
            }
            return bytes;
        }
    }

    private static void terminate(Process process) {
        if (process.isAlive()) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private static String requireSuccess(Result result) throws IOException {
        if (result.exitCode() != 0) {
            throw new IOException("Git завершился с кодом " + result.exitCode() + ".");
        }
        return result.output();
    }

    private record Result(int exitCode, String output) {
    }
}
