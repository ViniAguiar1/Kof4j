package dev.kof.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * kof.ui Component Core (docs/ui/architecture.md §2.2): state, view builder,
 * lifecycle (mount/unmount), effects with automatic cleanup and the leak
 * probe. Rendering happens in the KofJS target (DOM); JVM and Native execute
 * the same program with no-op handles — the program must still compile and
 * run identically.
 */
class ComponentCoreE2ETest {

    private final CompilerDriver driver = new CompilerDriver();

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }

    private String runJvm(Path source, Path outDir, String expected) throws IOException {
        CompilationResult result = driver.compile(source, outDir, Target.JVM);
        assertTrue(result.success(), "Compilation should succeed: " + result.diagnostics().getDiagnostics());
        try {
            ProcessBuilder pb = new ProcessBuilder("java", "-cp", outDir.toString(), "Default.Main");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\r\n", "\n").trim();
            int ec = p.waitFor();
            assertEquals(0, ec, "Exit code should be 0, output: '" + output + "'");
            assertEquals(expected, output, "Unexpected output");
            return output;
        } catch (InterruptedException e) {
            throw new IOException("Interrupted while running JVM class", e);
        }
    }

    private String runNative(Path source, Path outDir, String expected) throws IOException {
        assumeTrue(isLinux(), "Native target runs on Linux");
        CompilationResult result = driver.compile(source, outDir, Target.NATIVE);
        assertTrue(result.success(), "Compilation should succeed: " + result.diagnostics().getDiagnostics());
        Path binFile = outDir.resolve("Default/Main");
        assertTrue(Files.exists(binFile), "Binary should exist");
        try {
            ProcessBuilder pb = new ProcessBuilder(binFile.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\r\n", "\n").trim();
            int ec = p.waitFor();
            assertEquals(0, ec, "Exit code should be 0, output: '" + output + "'");
            assertEquals(expected, output, "Unexpected output");
            return output;
        } catch (InterruptedException e) {
            throw new IOException("Interrupted while running native binary", e);
        }
    }

    private void both(Path tempDir, String name, String program, String expected) throws IOException {
        Path source = tempDir.resolve(name + ".kf");
        Files.writeString(source, program);
        runJvm(source, tempDir.resolve("jvm-" + name), expected);
        runNative(source, tempDir.resolve("native-" + name), expected);
    }

    /** Compiles for JS, runs in the embedded engine and returns stdout. */
    private String runJs(Path tempDir, String name, String program) throws IOException {
        Path source = tempDir.resolve(name + "-js.kf");
        Files.writeString(source, program);
        CompilationResult js = driver.compile(source, tempDir.resolve("js-" + name), Target.JS);
        assertTrue(js.success(), "JS compilation should succeed: " + js.diagnostics().getDiagnostics());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int code = dev.kof.runtime.KofJsRunner.run(
                tempDir.resolve("js-" + name).resolve("Default.mjs"), out,
                new java.io.ByteArrayInputStream(new byte[0]), out);
        assertEquals(0, code, "JS run should succeed: " + out);
        return out.toString().trim();
    }

    /** Compiles for JS and evaluates an extra probe expression via a shim module. */
    private String runJsProbe(Path tempDir, String name, String program,
                              String probeJs) throws IOException {
        Path source = tempDir.resolve(name + "-js.kf");
        Files.writeString(source, program);
        CompilationResult js = driver.compile(source, tempDir.resolve("js-" + name), Target.JS);
        assertTrue(js.success(), "JS compilation should succeed: " + js.diagnostics().getDiagnostics());
        Path module = tempDir.resolve("js-" + name).resolve("Default.mjs");
        // append the probe to the generated module (it re-runs main; the
        // probe asserts on the resulting runtime state and prints the result)
        Files.writeString(module, Files.readString(module) + "\n" + probeJs + "\n");
        dev.kof.compiler.js.JsRuntimeTestSupport.includeImportsOf(module.getParent(), probeJs);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int code = dev.kof.runtime.KofJsRunner.run(module, out,
                new java.io.ByteArrayInputStream(new byte[0]), out);
        assertEquals(0, code, "JS probe run should succeed: " + out);
        return out.toString().trim();
    }

    @Test
    void stateRoundTrip(@TempDir Path tempDir) throws IOException {
        String program = """
            main() {
                var app = Component(0)
                app.state = 41
                app.state = app.state + 1
                println(app.state)
                app.remove()
                println(uiNodesLive())
            }
            """;
        // JVM/Native: state getter is a no-op (0) and remove frees the
        // handle, so the live probe reports 0.
        both(tempDir, "state", program, "0\n0");
        assertEquals("42\n0", runJs(tempDir, "state", program),
                "state round-trip + leak probe on the JS target");
    }

    @Test
    void lifecycleOrder(@TempDir Path tempDir) throws IOException {
        String program = """
            main() {
                var app = Component(0)
                app.onMount(() -> println("mounted"))
                app.onDispose(() -> println("disposed"))
                var win = Window("App")
                win.bind(app)
                app.view((s: Int) -> {
                    var l = Label("v=" + s)
                    win.bind(l)
                    return l
                })
                app.remove()
                println(uiNodesLive())
            }
            """;
        both(tempDir, "lifecycle", program, "0");
        assertEquals("mounted\ndisposed\n0", runJs(tempDir, "lifecycle", program),
                "mount runs onMount, remove runs onDispose and frees the component");
    }

    @Test
    void effectRunsOnMountAndCleansUpOnUnmount(@TempDir Path tempDir) throws IOException {
        String program = """
            main() {
                var app = Component(0)
                app.effect(() -> println("effect-up"))
                app.onDispose(() -> println("disposed"))
                var win = Window("App")
                win.bind(app)
                app.remove()
                println(uiNodesLive())
            }
            """;
        both(tempDir, "effect", program, "0");
        assertEquals("effect-up\ndisposed\n0", runJs(tempDir, "effect", program),
                "effect runs once on mount; unmount frees the component");
    }

    @Test
    void viewReceivesStateAndRenders(@TempDir Path tempDir) throws IOException {
        String program = """
            main() {
                var app = Component(7)
                var win = Window("App")
                win.bind(app)
                app.view((s: Int) -> {
                    var l = Label("v=" + s)
                    win.bind(l)
                    return l
                })
                app.state = 8
                win.show()
            }
            """;
        Path source = tempDir.resolve("render.kf");
        Files.writeString(source, program);
        // JVM/Native: no-op handles — compiles and runs empty
        runJvm(source, tempDir.resolve("jvm-render"), "");
        runNative(source, tempDir.resolve("native-render"), "");
        // JS: the rendered page must show the state-driven label
        Path jsSource = tempDir.resolve("render-js.kf");
        Files.writeString(jsSource, program);
        CompilationResult js = driver.compile(jsSource, tempDir.resolve("js-render"), Target.JS);
        assertTrue(js.success(), "JS compilation should succeed: " + js.diagnostics().getDiagnostics());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        String html = dev.kof.runtime.KofJsRunner.runCaptureHtml(
                tempDir.resolve("js-render").resolve("Default.mjs"), out,
                new java.io.ByteArrayInputStream(new byte[0]), out);
        assertNotNull(html, "The window should serialize to HTML");
        assertTrue(html.contains("v=8"), "Re-rendered label must reflect the new state: " + html);
        assertFalse(html.contains("v=7"), "The stale view must not remain in the tree");
    }

    @Test
    void compositionBindMountsChildComponent(@TempDir Path tempDir) throws IOException {
        String program = """
            main() {
                var parent = Component(0)
                var child = Component(5)
                child.onMount(() -> println("child-mounted"))
                var win = Window("App")
                win.bind(parent)
                parent.bind(child)
                child.remove()
                println(uiNodesLive())
            }
            """;
        // JVM tracks handles in a live set (2 created, 1 removed → 1 alive);
        // Native handles are pure no-ops — the probe always reports 0.
        Path composeSrc = tempDir.resolve("compose.kf");
        Files.writeString(composeSrc, program);
        runJvm(composeSrc, tempDir.resolve("jvm-compose"), "1");
        runNative(composeSrc, tempDir.resolve("native-compose"), "0");
        // JS: bind mounted the child ("child-mounted"); remove frees only
        // the child — the parent is still alive.
        assertEquals("child-mounted\n1", runJs(tempDir, "compose", program),
                "bind mounts the child component; remove frees only the child");
    }

    @Test
    void onRegistersCentralizedHandler(@TempDir Path tempDir) throws IOException {
        // The KofJS host mock has no real DOM event dispatch; the probe
        // asserts the handler was registered on the component root element
        // (the centralised dispatch table used by kofUiComponentOn).
        String program = """
            main() {
                var app = Component(0)
                app.on("click", () -> println("clicked"))
                var win = Window("App")
                win.bind(app)
            }
            """;
        String probe = """
            import { kofUiNodesLive } from './kof-runtime.mjs';
            // find the component wrapper (only element with class kof-component)
            const root = document.getElementById("kof-root");
            const wrap = root.children[0].children[0];
            const handlers = wrap._kofHandlers && wrap._kofHandlers["click"];
            console.log("handlers=" + (handlers ? handlers.length : 0));
            console.log("live=" + kofUiNodesLive());
            """;
        assertEquals("handlers=1\nlive=1", runJsProbe(tempDir, "on", program, probe),
                "on() registers the handler in the component's centralised table");
    }

    @Test
    void batchingMultipleStateWritesSingleRender(@TempDir Path tempDir) throws IOException {
        // Multiple state writes in the same tick coalesce: the dirty queue
        // holds one entry per component, so the view runs once with the
        // FINAL state.
        String program = """
            main() {
                var app = Component(0)
                var win = Window("App")
                win.bind(app)
                app.view((s: Int) -> {
                    var l = Label("v=" + s)
                    win.bind(l)
                    return l
                })
                app.state = 1
                app.state = 2
                app.state = 3
                win.show()
            }
            """;
        Path batchSrc = tempDir.resolve("batch.kf");
        Files.writeString(batchSrc, program);
        runJvm(batchSrc, tempDir.resolve("jvm-batch"), "");
        runNative(batchSrc, tempDir.resolve("native-batch"), "");
        Path jsSource = tempDir.resolve("batch-js.kf");
        Files.writeString(jsSource, program);
        CompilationResult js = driver.compile(jsSource, tempDir.resolve("js-batch"), Target.JS);
        assertTrue(js.success(), "JS compilation should succeed: " + js.diagnostics().getDiagnostics());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        String html = dev.kof.runtime.KofJsRunner.runCaptureHtml(
                tempDir.resolve("js-batch").resolve("Default.mjs"), out,
                new java.io.ByteArrayInputStream(new byte[0]), out);
        assertNotNull(html, "The window should serialize to HTML");
        assertTrue(html.contains("v=3"), "Final state must win after batching: " + html);
        assertFalse(html.contains("v=1"), "Intermediate states must not leak into the tree");
    }

    @Test
    void unmountCascadesToChildren(@TempDir Path tempDir) throws IOException {
        String program = """
            main() {
                var parent = Component(0)
                var child = Component(1)
                child.onDispose(() -> println("child-disposed"))
                var win = Window("App")
                win.bind(parent)
                parent.bind(child)
                parent.remove()
                println(uiNodesLive())
            }
            """;
        Path cascadeSrc = tempDir.resolve("cascade.kf");
        Files.writeString(cascadeSrc, program);
        runJvm(cascadeSrc, tempDir.resolve("jvm-cascade"), "0");
        runNative(cascadeSrc, tempDir.resolve("native-cascade"), "0");
        // parent.remove() unmounts the subtree: child's onDispose runs and
        // BOTH components are freed.
        assertEquals("child-disposed\n0", runJs(tempDir, "cascade", program),
                "unmount cascades top-down; the whole subtree is freed");
    }

    @Test
    void stressTenThousandMountUnmountCycles(@TempDir Path tempDir) throws IOException {
        // docs/ui/architecture.md §2.2: stress 10.000 ciclos mount/unmount
        // sem vazamento — uiNodesLive() must return to 0.
        String program = """
            main() {
                var win = Window("App")
                var i = 0
                while (i < 10000) {
                    var app = Component(i)
                    app.onMount(() -> {})
                    app.onDispose(() -> {})
                    app.effect(() -> {})
                    win.bind(app)
                    app.view((s: Int) -> {
                        var l = Label("n=" + s)
                        win.bind(l)
                        return l
                    })
                    app.remove()
                    i = i + 1
                }
                println(uiNodesLive())
            }
            """;
        Path stressSrc = tempDir.resolve("stress.kf");
        Files.writeString(stressSrc, program);
        runJvm(stressSrc, tempDir.resolve("jvm-stress"), "0");
        runNative(stressSrc, tempDir.resolve("native-stress"), "0");
        assertEquals("0", runJs(tempDir, "stress", program),
                "10k mount/unmount cycles must leave no component alive (no leak)");
    }

    @Test
    void layoutPrimitivesRenderCssContainers(@TempDir Path tempDir) throws IOException {
        // Fase 4 (docs/ui/architecture.md §2.8): Box/Stack/Wrap/Grid/Spacer/
        // Center/Align are CSS-first containers; JVM/Native run them as no-ops.
        String program = """
            main() {
                var l1 = Label("a")
                var l2 = Label("b")
                var box = Box(listOf(l1, l2))
                var win = Window("App")
                win.bind(box)
                win.show()
            }
            """;
        Path layoutSrc = tempDir.resolve("layout.kf");
        Files.writeString(layoutSrc, program);
        runJvm(layoutSrc, tempDir.resolve("jvm-layout"), "");
        runNative(layoutSrc, tempDir.resolve("native-layout"), "");
        Path jsSource = tempDir.resolve("layout-js.kf");
        Files.writeString(jsSource, program);
        CompilationResult js = driver.compile(jsSource, tempDir.resolve("js-layout"), Target.JS);
        assertTrue(js.success(), "JS compilation should succeed: " + js.diagnostics().getDiagnostics());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        String html = dev.kof.runtime.KofJsRunner.runCaptureHtml(
                tempDir.resolve("js-layout").resolve("Default.mjs"), out,
                new java.io.ByteArrayInputStream(new byte[0]), out);
        assertNotNull(html, "The window should serialize to HTML");
        assertTrue(html.contains("kof-box"), "Box must render as a CSS container: " + html);
        assertTrue(html.contains(">a</span>") && html.contains(">b</span>"),
                "Box must contain its children: " + html);
    }

    @Test
    void eventsBubbleUpTheComponentTree(@TempDir Path tempDir) throws IOException {
        // Fase 5 (docs/ui/architecture.md §2.5): emit(child) -> child handler
        // -> bubbles to parent. emit(parent) reaches only the parent.
        String program = """
            main() {
                var parent = Component(0)
                var child = Component(1)
                var log = ""
                parent.on("ping", (e: Event) -> { log = log + "P:" + e.type() + "," })
                child.on("ping", (e: Event) -> { log = log + "C," })
                var win = Window("App")
                win.bind(parent)
                parent.bind(child)
                emit(child, "ping")
                emit(parent, "ping")
                println(log)
            }
            """;
        Path evSrc = tempDir.resolve("evbubble.kf");
        Files.writeString(evSrc, program);
        runJvm(evSrc, tempDir.resolve("jvm-evbubble"), "");
        runNative(evSrc, tempDir.resolve("native-evbubble"), "");
        assertEquals("C,P:ping,P:ping,", runJs(tempDir, "evbubble", program),
                "child event bubbles to parent; parent event stays local");
    }

    @Test
    void stopPropagationBlocksBubbling(@TempDir Path tempDir) throws IOException {
        String program = """
            main() {
                var parent = Component(0)
                var child = Component(1)
                var log = ""
                parent.on("ping", (e: Event) -> { log = log + "P," })
                child.on("ping", (e: Event) -> { log = log + "C,"; e.stopPropagation() })
                var win = Window("App")
                win.bind(parent)
                parent.bind(child)
                emit(child, "ping")
                println(log)
            }
            """;
        Path evSrc = tempDir.resolve("evstop.kf");
        Files.writeString(evSrc, program);
        runJvm(evSrc, tempDir.resolve("jvm-evstop"), "");
        runNative(evSrc, tempDir.resolve("native-evstop"), "");
        assertEquals("C,", runJs(tempDir, "evstop", program),
                "stopPropagation must stop the event from reaching the parent");
    }

    @Test
    void storeSharesStateAcrossComponents(@TempDir Path tempDir) throws IOException {
        // Fase 8 (docs/ui/architecture.md §2.6): Store is shared observable
        // state. Subscribers receive the current value on subscribe and every
        // change on set(). storesLive() is the leak probe.
        String program = """
            main() {
                var store = Store(10)
                var log = ""
                store.subscribe((v: Int) -> { log = log + "s=" + v + "," })
                println(store.get())
                store.set(20)
                println(log)
                println(store.get())
                println(storesLive())
            }
            """;
        Path storeSrc = tempDir.resolve("store.kf");
        Files.writeString(storeSrc, program);
        // JVM no-ops: get()=0, log vazio, get()=0, storesLive=1 (live set);
        // Native é no-op puro (storesLive=0).
        runJvm(storeSrc, tempDir.resolve("jvm-store"), "0\n\n0\n1");
        runNative(storeSrc, tempDir.resolve("native-store"), "0\n\n0\n0");
        assertEquals("10\ns=10,s=20,\n20\n1", runJs(tempDir, "store", program),
                "Store notifies subscribers on set; get returns the value; no store leak");
    }

    @Test
    void storeDrivesTwoComponentsIndependently(@TempDir Path tempDir) throws IOException {
        // two components subscribed to one store; the set() updates both via
        // their local state (minimal invalidation is preserved per component)
        String program = """
            main() {
                var store = Store(1)
                var a = Component(0)
                var b = Component(0)
                a.on("tick", (e: Event) -> {})
                store.subscribe((v: Int) -> { a.state = v })
                store.subscribe((v: Int) -> { b.state = v * 2 })
                var win = Window("App")
                win.bind(a)
                a.bind(b)
                store.set(5)
                println(a.state)
                println(b.state)
                println(storesLive())
            }
            """;
        Path storeSrc = tempDir.resolve("store2.kf");
        Files.writeString(storeSrc, program);
        // JVM: state getters sempre 0, storesLive=1; Native: storesLive=0.
        runJvm(storeSrc, tempDir.resolve("jvm-store2"), "0\n0\n1");
        runNative(storeSrc, tempDir.resolve("native-store2"), "0\n0\n0");
        // JS: each subscriber sees the value on subscribe (1 and 2) and on
        // set (5 and 10); the getters read the final local states.
        assertEquals("5\n10\n1", runJs(tempDir, "store2", program),
                "both components must be driven by the store");
    }
}
