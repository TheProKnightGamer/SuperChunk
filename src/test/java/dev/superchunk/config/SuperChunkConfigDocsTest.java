package dev.superchunk.config;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Properties;

/** Checks the documented {@code superchunk.properties} layout and that it round-trips every value. */
public final class SuperChunkConfigDocsTest {
    public static void main(String[] args) throws Exception {
        Map<String, String> defaults = map("DEFAULTS");
        Map<String, String> descriptions = map("DESCRIPTIONS");
        for (String key : defaults.keySet()) {
            String d = descriptions.get(key);
            check(d != null && d.trim().length() >= 20, "missing or too short description for " + key);
        }
        check(descriptions.keySet().equals(defaults.keySet()), "descriptions for unknown keys");

        Properties values = new Properties();
        defaults.forEach(values::setProperty);
        String text = SuperChunkConfig.render(values);
        check(text.contains("# superchunk-config-format: 3"), "format marker");
        for (String line : text.split("\n", -1)) {
            for (char c : line.toCharArray()) {
                check(c >= 0x20 && c <= 0x7e, "non-ASCII character in: " + line);
            }
            if (line.startsWith("# ") && !line.startsWith("# ===== ")) {
                check(line.length() <= 100, "comment line too long: " + line);
            }
        }
        for (String key : defaults.keySet()) {
            int at = text.indexOf("\n" + key + "=");
            check(at > 0, "key line missing: " + key);
            String before = text.substring(Math.max(0, at - 1200), at);
            String firstWords = descriptions.get(key).split(" ")[0] + " " + descriptions.get(key).split(" ")[1];
            check(before.contains(firstWords), "description not above " + key);
            String defaultLine = before.substring(before.lastIndexOf('\n') + 1);
            check(defaultLine.startsWith("# Default: " + defaults.get(key)), "default line not above " + key);
        }
        check(roundTrip(values).equals(values), "defaults round trip");

        // User values and extra keys survive exactly, however awkward.
        Properties custom = (Properties) values.clone();
        String[] awkward = {"C:\\Users\\me\\world", " leading space", "a=b:c#d!e", "tab\there", "line\nbreak",
                "caf\u00e9 \u4e16\u754c", "\\u0041 literal", "trailing space ", "=starts", "#starts", "!starts", ""};
        int n = 0;
        for (String v : awkward) {
            custom.setProperty("gpu.batchLimit", v);
            custom.setProperty("lithium.custom.rule" + n, v);
            custom.setProperty("c2me.some key:with=seps" + n, v);
            check(roundTrip(custom).equals(custom), "round trip of " + v);
            n++;
        }
        String withExtras = SuperChunkConfig.render(custom);
        check(withExtras.contains("# ===== Other settings ====="), "extras section");
        System.out.println("SuperChunkConfigDocs: " + defaults.size() + " documented options; "
                + (awkward.length + 1) + " round-trip cases passed");

        // Regenerates the shipped sample (dist/config/superchunk.properties) from the defaults:
        //   ./gradlew configDocsRegressionTest --args="--write-sample $PWD/dist/config/superchunk.properties"
        if (args.length == 2 && args[0].equals("--write-sample")) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(args[1]), text, java.nio.charset.StandardCharsets.ISO_8859_1);
            System.out.println("SuperChunkConfigDocs: wrote " + args[1]);
        }
    }

    private static Properties roundTrip(Properties values) throws Exception {
        Properties loaded = new Properties();
        loaded.load(new StringReader(SuperChunkConfig.render(values)));
        return loaded;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> map(String name) throws ReflectiveOperationException {
        Field f = SuperChunkConfig.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Map<String, String>) f.get(null);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
