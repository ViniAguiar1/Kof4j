package dev.kof.compiler.js;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JsRuntimeTestSupport {

    private static final Pattern RUNTIME_IMPORT = Pattern.compile(
            "import\\s*\\{([^}]*)\\}\\s*from\\s*['\"]\\./kof-runtime(-io)?\\.mjs['\"]");

    private JsRuntimeTestSupport() {}

    public static void includeImportsOf(Path outDir, String js) throws IOException {
        Set<String> core = new LinkedHashSet<>();
        Set<String> io = new LinkedHashSet<>();
        Matcher m = RUNTIME_IMPORT.matcher(js);
        while (m.find()) {
            Collection<String> target = m.group(2) == null ? core : io;
            for (String name : m.group(1).split(",")) {
                String n = name.trim();
                if (!n.isEmpty()) target.add(n);
            }
        }
        if (core.isEmpty() && io.isEmpty()) return;
        new JsArtifactWriter().writeRuntime(outDir, List.copyOf(core), List.copyOf(io));
    }
}
