package dev.chatalyst.profile;

import dev.chatalyst.toggle.ToggleType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Interruptores de la seccion AJUSTES de /perfil (logica pura, sin Bukkit).
 *
 * <p>Los cinco primeros son exactamente los de /toggle y se guardan con {@code ToggleManager};
 * {@link #TELEPORT} es el /tptoggle de EssentialsX y no vive en Chatalyst.
 */
public enum ProfileSetting {
    PM(ToggleType.PM),
    ANNOUNCEMENTS(ToggleType.ANNOUNCEMENTS),
    MENTIONS(ToggleType.MENTIONS),
    JOINQUIT(ToggleType.JOINQUIT),
    TRANSLATE(ToggleType.TRANSLATE),
    TELEPORT(null);

    private final ToggleType toggle;

    ProfileSetting(ToggleType toggle) {
        this.toggle = toggle;
    }

    /** El ajuste de /toggle que cambia este interruptor, o null si no es de /toggle. */
    public ToggleType toggle() {
        return toggle;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Clave base en lang ({@code profile.setting.<id>.name} / {@code .lore}). */
    public String langKey() {
        return "profile.setting." + id();
    }

    /**
     * Interruptores que se pintan, en orden. La traduccion solo existe si el servidor la tiene
     * encendida (igual que en /toggle) y el de teletransporte solo si hay forma de cambiarlo.
     */
    public static List<ProfileSetting> visible(boolean translationAvailable,
                                               boolean teleportAvailable) {
        List<ProfileSetting> out = new ArrayList<>(values().length);
        for (ProfileSetting setting : values()) {
            if (setting == TRANSLATE && !translationAvailable) continue;
            if (setting == TELEPORT && !teleportAvailable) continue;
            out.add(setting);
        }
        return List.copyOf(out);
    }

    /** Estado que muestra un interruptor: encendido, apagado o desconocido (lo lleva otro plugin). */
    public enum State {
        ON, OFF, UNKNOWN;

        public static State of(Boolean enabled) {
            if (enabled == null) return UNKNOWN;
            return enabled ? ON : OFF;
        }

        /** Clave de lang de la linea de estado. */
        public String langKey() {
            return "profile.status-" + name().toLowerCase(Locale.ROOT);
        }
    }
}
