package dev.chatalyst.tebex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns the id of a rank into something worth showing a buyer.
 *
 * <p>A group is named for machines: {@code mvplusplus}, {@code vip_plus}, {@code yt}. Printed
 * as-is on a shop card that reads MVPLUSPLUS, which is nobody's idea of a product name — and
 * the owner should not have to write a {@code Display_Name} for every rank just to get the
 * obvious answer.
 *
 * <p>The interesting case is the shared letter. {@code mvplusplus} is not "mv" plus "plus"
 * twice; it is MVP where the p of MVP doubles as the p of the first "plus", which is how
 * these names get written in the first place. So after peeling the suffixes off, a stem that
 * is one letter short of a known acronym gets that letter back: {@code mv} → {@code mvp}, and
 * {@code vi} → {@code vip}. Without that rule the card would proudly read MV++.
 *
 * <p>Adivinar es el ULTIMO recurso. El servidor ya sabe como se llama cada rango, asi que
 * primero se le pregunta: el {@code displayname} que LuckPerms guarda para el grupo, y si no,
 * su prefijo — que es literalmente el nombre que los jugadores ya ven en el chat. Un rango
 * cuyo prefijo pone {@code [MVP++]} no necesita heuristica ninguna. Solo cuando el servidor
 * no dice nada se recurre a leer el id.
 *
 * <p>Y un {@code Display_Name} configurado en la tienda gana siempre: esto responde cuando
 * nadie ha dicho nada.
 */
public final class RankNames {

    /** Written in capitals by everyone who says them out loud. */
    private static final Set<String> ACRONYMS = Set.of(
            "vip", "mvp", "yt", "og", "pvp", "smp", "op", "gg", "hd", "mod", "sr",
            "ceo", "cto", "npc", "ftw", "afk", "dj", "tv");

    private static final String PLUS = "plus";
    private static final int MAX_PLUSSES = 6;

    /** Acronimos extra del servidor, en minusculas. Los pone el dueño en la config. */
    private static volatile Set<String> extraAcronyms = Set.of();

    private RankNames() {
    }

    /**
     * Acronimos propios del servidor ({@code Tebex.Shop.Acronyms}). Un servidor con rangos
     * llamados {@code ely} o {@code kz} no deberia tener que aguantar "Ely" solo porque la
     * lista de fabrica no los conozca.
     */
    public static void learn(List<String> configured) {
        Set<String> learned = new java.util.LinkedHashSet<>();
        if (configured != null) {
            for (String word : configured) {
                if (word == null) continue;
                String clean = word.trim().toLowerCase(Locale.ROOT);
                if (clean.matches("[a-z]{1,12}")) learned.add(clean);
            }
        }
        extraAcronyms = Set.copyOf(learned);
    }

    private static boolean isAcronym(String stem) {
        return ACRONYMS.contains(stem) || extraAcronyms.contains(stem);
    }

    /**
     * Nombre de un rango preguntando primero al servidor y adivinando solo al final.
     *
     * @param group el grupo de LuckPerms, si se sabe cual es
     * @param id    el id del paquete, como ultimo recurso
     */
    public static String of(String group, String id) {
        String fromServer = fromLuckPerms(group);
        if (!fromServer.isEmpty()) return fromServer;
        String fallback = pretty(id);
        return fallback.isEmpty() ? pretty(group) : fallback;
    }

    /** Lo que el servidor ya llama a este grupo, sin formato ni adornos, o "". */
    private static String fromLuckPerms(String group) {
        if (group == null || group.isBlank()) return "";
        String display = clean(dev.chatalyst.hook.LuckPermsHook.groupDisplayName(group));
        // getDisplayName() devuelve el NOMBRE del grupo cuando no hay displayname puesto:
        // eso no es una respuesta, es el id otra vez, y adivinar sobre el es nuestro trabajo.
        if (!display.isEmpty() && !display.equalsIgnoreCase(group.trim())) return display;
        return clean(dev.chatalyst.hook.LuckPermsHook.groupPrefix(group));
    }

    /**
     * Texto legible de un prefijo: fuera etiquetas, codigos de color y los corchetes con que
     * casi todos los envuelven. De {@code <gradient:#a:#b>[MVP++] } sale {@code MVP++}.
     */
    static String clean(String raw) {
        if (raw == null) return "";
        String text = dev.chatalyst.util.MM.strip(raw)
                .replaceAll("(?i)&[0-9a-fk-or]", "")
                .replace('§', ' ')
                .trim();
        // Los corchetes envuelven, no nombran: [MVP++] es MVP++.
        text = text.replaceAll("^[\\[({<]+", "").replaceAll("[\\])}>]+$", "").trim();
        return text.length() > 48 ? text.substring(0, 48).trim() : text;
    }

    /**
     * @param id the group or package id, e.g. {@code mvplusplus}
     * @return a name to show, e.g. {@code MVP++}; the input unchanged when it is not an id
     */
    public static String pretty(String id) {
        if (id == null) return "";
        String clean = id.trim();
        if (clean.isEmpty() || clean.length() > 64) return clean;
        // Ya viene escrito para leerse (mayusculas, signos, acentos): no hay nada que adivinar.
        if (!clean.equals(clean.toLowerCase(Locale.ROOT))
                || !clean.matches("[a-z0-9_.\\- ]+")) {
            return clean;
        }

        List<String> words = new ArrayList<>();
        int plusses = 0;
        for (String token : clean.split("[_.\\-\\s]+")) {
            if (token.isEmpty()) continue;
            if (token.equals(PLUS) || token.equals("+")) {
                plusses++;
                continue;
            }
            Stem stem = peel(token);
            plusses += stem.plusses();
            words.add(word(stem.text()));
        }
        // "plus" solo modifica si hay algo que modificar. Un rango llamado literalmente
        // "plus" no es un signo suelto: es su nombre.
        if (words.isEmpty()) {
            if (plusses == 0) return clean;
            words.add("Plus");
            plusses--;
        }
        return String.join(" ", words) + "+".repeat(Math.min(plusses, MAX_PLUSSES));
    }

    private record Stem(String text, int plusses) {
    }

    /** Peels the trailing "plus"es off a token, giving back a letter the last one swallowed. */
    private static Stem peel(String token) {
        String stem = token;
        int plusses = 0;
        while (plusses < MAX_PLUSSES && stem.length() > PLUS.length() && stem.endsWith(PLUS)) {
            stem = stem.substring(0, stem.length() - PLUS.length());
            plusses++;
        }
        if (plusses > 0 && !isAcronym(stem)) {
            // El caso de la letra compartida: "mvplusplus" es MVP++, no MV++.
            String restored = stem + PLUS.charAt(0);
            if (isAcronym(restored)) stem = restored;
        }
        return new Stem(stem, plusses);
    }

    private static String word(String stem) {
        if (stem.isEmpty()) return stem;
        if (isAcronym(stem)) return stem.toUpperCase(Locale.ROOT);
        return Character.toUpperCase(stem.charAt(0)) + stem.substring(1);
    }
}
