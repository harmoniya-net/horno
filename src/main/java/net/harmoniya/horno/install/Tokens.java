package net.harmoniya.horno.install;

import java.util.Map;

/**
 * The install profile's little substitution language.
 *
 * <p>Three things happen in a profile string: {@code {KEY}} is replaced from the
 * data map, {@code 'text'} is a literal whose quotes are dropped, and
 * {@code \x} escapes one character. That is the whole grammar, and it is why a
 * sha1 is written {@code 'de86…'} in the profile — so that a value which happens
 * to look like a token cannot become one.
 *
 * <p>An unknown key throws rather than resolving to empty. A processor handed an
 * empty path writes to the wrong place, and does so silently.
 */
final class Tokens {
    private Tokens() {
    }

    static String replace(Map<String, String> tokens, String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                if (i == value.length() - 1) {
                    throw new IllegalArgumentException("Trailing escape in " + value);
                }
                out.append(value.charAt(++i));
            } else if (c == '{' || c == '\'') {
                char close = c == '{' ? '}' : '\'';
                StringBuilder key = new StringBuilder();
                int end = -1;
                for (int j = i + 1; j < value.length(); j++) {
                    char d = value.charAt(j);
                    if (d == '\\') {
                        if (j == value.length() - 1) {
                            throw new IllegalArgumentException("Trailing escape in " + value);
                        }
                        key.append(value.charAt(++j));
                    } else if (d == close) {
                        end = j;
                        break;
                    } else {
                        key.append(d);
                    }
                }
                if (end < 0) {
                    throw new IllegalArgumentException("Unclosed " + c + " in " + value);
                }
                i = end;
                if (c == '\'') {
                    out.append(key);
                } else {
                    String replacement = tokens.get(key.toString());
                    if (replacement == null) {
                        throw new IllegalArgumentException("The install profile uses {" + key + "}, which it never defines");
                    }
                    out.append(replacement);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
