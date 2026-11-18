package io.github.bitshiftdev.mavendetective.core.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Verfälschte Varianten eines gültigen Texts für Robustheitstests: abschneiden, Zeichen löschen,
 * Stücke verdoppeln, Sonderzeichen einstreuen. Mit festem Startwert, damit ein roter Lauf
 * reproduzierbar ist – die Nummer der Variante steht in der Fehlermeldung des Tests.
 */
public final class Mutations {

    /** Zeichen, die Parser gern aus dem Tritt bringen (Strukturzeichen, Escapes, Steuerzeichen, Unicode). */
    private static final String SPECIAL = "<>/\"'&;:[]{},-#|\\\n\r\t \u0000ä﻿\uD83D";
    private static final int MAX_CHUNK = 16;
    private static final int KINDS = 5;

    private Mutations() {
    }

    /** {@code count} Varianten von {@code valid}; gleiche Argumente ergeben gleiche Varianten. */
    public static List<String> of(String valid, int count, long seed) {
        Random random = new Random(seed);
        List<String> out = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            out.add(mutate(valid, random));
        }
        return out;
    }

    private static String mutate(String valid, Random random) {
        if (valid.isEmpty()) {
            return String.valueOf(SPECIAL.charAt(random.nextInt(SPECIAL.length())));
        }
        StringBuilder sb = new StringBuilder(valid);
        int edits = 1 + random.nextInt(3);
        for (int e = 0; e < edits && sb.length() > 0; e++) {
            int at = random.nextInt(sb.length());
            int len = Math.min(1 + random.nextInt(MAX_CHUNK), sb.length() - at);
            switch (random.nextInt(KINDS)) {
                case 0:
                    sb.setLength(at);
                    break;
                case 1:
                    sb.delete(at, at + len);
                    break;
                case 2:
                    sb.insert(at, sb.substring(at, at + len));
                    break;
                case 3:
                    sb.insert(at, SPECIAL.charAt(random.nextInt(SPECIAL.length())));
                    break;
                default:
                    sb.setCharAt(at, SPECIAL.charAt(random.nextInt(SPECIAL.length())));
                    break;
            }
        }
        return sb.toString();
    }
}
