package de.trakt.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Server-side translation flattening for API responses.
 *
 * <p>Vanilla translatable components are not resolved on a dedicated server, so their
 * {@code getString()} returns the raw key. This helper loads the mod's own lang files from the
 * jar (en_us and ru_ru) and resolves keys manually, including ordered argument substitution
 * ({@code %s} / {@code %1$s}).
 */
public final class ApiLang {
    private static final Map<String, String> EN = new HashMap<>();
    private static final Map<String, String> RU = new HashMap<>();

    private ApiLang() {}

    static void load() {
        loadLocale("en_us", EN);
        loadLocale("ru_ru", RU);
    }

    private static void loadLocale(String locale, Map<String, String> target) {
        target.clear();
        // Vanilla translations first (item/block/entity names), then mod translations on top.
        for (String candidate : new String[]{
                "/assets/minecraft/lang/" + locale + ".json",
                "/assets/trakt/lang/" + locale + ".json",
        }) {
            try (InputStream stream = ApiLang.class.getResourceAsStream(candidate)) {
                if (stream == null) {
                    continue;
                }
                JsonObject object = JsonParser.parseReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                    target.put(entry.getKey(), entry.getValue().getAsString());
                }
            } catch (IOException | RuntimeException ignored) {
                // Partial tables are fine; flatten falls back to the raw key.
            }
        }
    }

    /** Best-effort English text for a translation key. */
    public static String en(String key) {
        return EN.getOrDefault(key, key);
    }

    /** Best-effort Russian text for a translation key. */
    public static String ru(String key) {
        return RU.getOrDefault(key, EN.getOrDefault(key, key));
    }

    /** Resolves a translation key in the requested locale with ordered argument substitution. */
    public static String resolve(String key, Object[] args, String locale) {
        Map<String, String> table = "ru_ru".equals(locale) ? RU : EN;
        String template = table.getOrDefault(key, EN.getOrDefault(key, key));
        if (args == null || args.length == 0) {
            return template;
        }
        StringBuilder builder = new StringBuilder();
        int argIndex = 0;
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '%') {
                if (i + 1 < template.length() && template.charAt(i + 1) == '%') {
                    builder.append('%');
                    i += 2;
                    continue;
                }
                // Positional form %1$s
                int dollar = template.indexOf('$', i + 1);
                if (dollar > 0 && dollar - i <= 3 && isAllDigits(template, i + 1, dollar)) {
                    int position = Integer.parseInt(template.substring(i + 1, dollar));
                    int end = endOfConversion(template, dollar + 1);
                    if (position >= 1 && position <= args.length) {
                        builder.append(stringify(args[position - 1]));
                    }
                    i = end;
                    continue;
                }
                int end = endOfConversion(template, i + 1);
                if (argIndex < args.length) {
                    builder.append(stringify(args[argIndex++]));
                }
                i = end;
                continue;
            }
            builder.append(c);
            i++;
        }
        return builder.toString();
    }

    private static int endOfConversion(String template, int from) {
        int i = from;
        while (i < template.length()) {
            char c = template.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                return i + 1;
            }
            if (Character.isDigit(c) || c == '.' || c == ',' || c == '-' || c == '+' || c == '#'
                    || c == ' ' || c == '(' || c == ')') {
                i++;
                continue;
            }
            return i;
        }
        return i;
    }

    private static boolean isAllDigits(String text, int from, int until) {
        if (from >= until) {
            return false;
        }
        for (int i = from; i < until; i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString();
    }
}
