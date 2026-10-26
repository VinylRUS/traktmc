package de.trakt.api;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Flattens vanilla {@link Component} trees into localized plain text for API responses,
 * using {@link ApiLang} for server-side translation resolution.
 */
public final class ApiText {
    private ApiText() {}

    /** Returns the translation key if the component root is translatable, otherwise null. */
    public static String translatableKey(Component component) {
        if (component != null && component.getContents() instanceof TranslatableContents contents) {
            return contents.getKey();
        }
        return null;
    }

    /** Localized plain text of the component in the requested locale (en_us fallback). */
    public static String flatten(Component component, String locale) {
        if (component == null) {
            return "";
        }
        if (component.getContents() instanceof TranslatableContents contents) {
            Object[] args = contents.getArgs();
            Object[] flattened = new Object[args == null ? 0 : args.length];
            for (int i = 0; i < flattened.length; i++) {
                Object arg = args[i];
                flattened[i] = arg instanceof Component nested ? flatten(nested, locale) : String.valueOf(arg);
            }
            return ApiLang.resolve(contents.getKey(), flattened, locale);
        }
        return component.getString();
    }
}
