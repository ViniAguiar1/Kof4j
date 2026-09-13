package dev.kof.compiler.js;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsRuntimePruneWriterTest {

    private static String runtime(Path dir) throws IOException {
        return Files.readString(dir.resolve("kof-runtime.mjs"));
    }

    @Test
    void unreachableFamiliesAreAbsent(@TempDir Path dir) throws IOException {
        new JsArtifactWriter().writeRuntime(dir, List.of("kofPrintln"), List.of());
        String js = runtime(dir);
        assertTrue(js.contains("function kofPrintln"), "a semente tem de estar presente");
        for (String ausente : List.of("kofSecSha256", "kofSecJwtSign", "kofUiWindowNew",
                "kofUiButtonNew", "kofWsConnect", "kofNetSplit", "kofUuidV4")) {
            assertFalse(js.contains(ausente), ausente + " não é alcançável por println");
        }
        assertTrue(js.length() < 30_000, "hello deve ficar nos KB: " + js.length());
    }

    @Test
    void reachableFamilyStaysAndItsNeighboursDoNot(@TempDir Path dir) throws IOException {
        new JsArtifactWriter().writeRuntime(dir, List.of("kofPrintln", "kofSecSha256"), List.of());
        String js = runtime(dir);
        assertTrue(js.contains("kofSecSha256"), "crypto pedido tem de entrar");
        assertFalse(js.contains("kofUiWindowNew"), "kof.ui continua fora");
        assertFalse(js.contains("kofWsConnect"), "websocket continua fora");
    }

    @Test
    void secondModuleUnionsInsteadOfInheritingTheFirstClosure(@TempDir Path dir) throws IOException {
        new JsArtifactWriter().writeRuntime(dir, List.of("kofPrintln"), List.of());
        assertFalse(runtime(dir).contains("kofSecSha256"), "primeiro módulo: sem crypto");

        new JsArtifactWriter().writeRuntime(dir, List.of("kofSecSha256"), List.of());
        String js = runtime(dir);
        assertTrue(js.contains("kofSecSha256"), "o fecho do segundo módulo precisa entrar");
        assertTrue(js.contains("function kofPrintln"), "e o do primeiro não pode sumir");
        assertTrue(js.contains("// kof:seeds kofPrintln,kofSecSha256"),
                "o cabeçalho registra a união, em ordem estável");
    }

    @Test
    void rewriteWithSameSeedsIsByteStable(@TempDir Path dir) throws IOException {
        new JsArtifactWriter().writeRuntime(dir, List.of("kofListNew", "kofPrintln"), List.of());
        byte[] first = Files.readAllBytes(dir.resolve("kof-runtime.mjs"));
        new JsArtifactWriter().writeRuntime(dir, List.of("kofPrintln", "kofListNew"), List.of());
        assertArrayEquals(first, Files.readAllBytes(dir.resolve("kof-runtime.mjs")),
                "mesma entrada (em qualquer ordem) tem de dar o mesmo artefato");
    }

    @Test
    void headerReportsUnitCountAndNoSilentFallback(@TempDir Path dir) throws IOException {
        new JsArtifactWriter().writeRuntime(dir, List.of("kofPrintln"), List.of());
        String js = runtime(dir);
        assertTrue(js.contains("// kof:units "), "o artefato diz quantas unidades entraram");
        assertEquals(js.contains("// kof:fallback "), !JsRuntimeSlices.select(List.of("kofPrintln")).notes().isEmpty(),
                "fallback, quando houver, é observável no cabeçalho");
    }

    @Test
    void ioRuntimeFollowsItsOwnSeeds(@TempDir Path dir) throws IOException {
        new JsArtifactWriter().writeRuntime(dir, List.of("kofPrintln"), List.of("kofIoPrint"));
        String io = Files.readString(dir.resolve("kof-runtime-io.mjs"));
        assertTrue(io.contains("kofIoPrint"), "a semente de io entra no arquivo de io");
        assertFalse(runtime(dir).contains("export function kofIoPrint"),
                "e não vaza para o runtime core");
    }
}
