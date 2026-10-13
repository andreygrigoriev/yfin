package io.andreygrigoriev.yfin.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Runs the {@code native-smoke} executable built by {@code -Pnative} (history, info, statements
 * over HTTPS and a WebSocket stream, all in a native image) against live Yahoo. Skipped when the
 * executable was not built, i.e. without {@code -Pnative}.
 */
class NativeSmokeIT {

    private static final Path EXECUTABLE =
            Path.of(System.getProperty("native.smoke.executable", "target/native-smoke"));

    @Test
    void nativeExecutablePassesEveryCheck() throws IOException, InterruptedException {
        Assumptions.assumeTrue(
                Files.isExecutable(EXECUTABLE), "native-smoke not built (run with -Pnative): " + EXECUTABLE);

        Process process = new ProcessBuilder(EXECUTABLE.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        boolean exited = process.waitFor(3, TimeUnit.MINUTES);
        if (!exited) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(exited)
                .as("native-smoke finished within 3 minutes, output:%n%s", output)
                .isTrue();
        assertThat(process.exitValue()).as("exit status, output:%n%s", output).isZero();
        assertThat(output)
                .contains("OK history", "OK info", "OK statements", "OK live", "SMOKE PASSED")
                .doesNotContain("FAIL");
    }
}
