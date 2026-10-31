package dev.chatalyst.chat;

import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code {rank_chat}}: la etiqueta del rango ANIMADA dentro del chat (2026-10-05, KZ).
 *
 * <p>Un mensaje se dibuja una sola vez, asi que cambiar de caracter no anima nada en el chat. Desde 1.21.9
 * el texto puede llevar sprites de un atlas y el CLIENTE anima esas texturas con su .mcmeta; cada rango se
 * escribe como una fila de sprites de 8x8 del atlas de interfaz (Tools/rangos/chat_animado_gui.py).
 *
 * <p>Bedrock no entiende esos sprites: cada etiqueta lleva escondida su version fija (el prefijo de siempre)
 * en el campo «insertion», y al montar el mensaje para un jugador de Bedrock se cambia por ella.
 *
 * <pre>
 * Chat:
 *   Rank_Tags:
 *     Enabled: true
 *     Groups:
 *       founder: "&lt;sprite:'minecraft:gui':'server:chattag/founder_0'&gt;..."
 * </pre>
 */
public final class ChatRankTags {

    static final String MARCA = "chattag:";

    private static volatile boolean enabled;
    private static volatile Map<String, String> porGrupo = Map.of();

    private ChatRankTags() {
    }

    public static void load(FileConfiguration config) {
        enabled = config.getBoolean("Chat.Rank_Tags.Enabled", false);
        Map<String, String> nuevo = new HashMap<>();
        ConfigurationSection grupos = config.getConfigurationSection("Chat.Rank_Tags.Groups");
        if (grupos != null) {
            for (String grupo : grupos.getKeys(false)) {
                String mm = grupos.getString(grupo, "");
                if (mm != null && !mm.isBlank()) nuevo.put(grupo.toLowerCase(Locale.ROOT), mm);
            }
        }
        porGrupo = Map.copyOf(nuevo);
    }

    /** MiniMessage de la etiqueta animada del grupo, o el prefijo fijo si no tiene. */
    public static String render(String grupo, String prefijoFijo) {
        String fijo = prefijoFijo == null ? "" : prefijoFijo;
        if (!enabled || grupo == null) return fijo;
        String animada = porGrupo.get(grupo.toLowerCase(Locale.ROOT));
        if (animada == null) return fijo;
        String reserva = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(fijo.getBytes(StandardCharsets.UTF_8));
        return "<insert:'" + MARCA + reserva + "'>" + animada + "</insert>";
    }

    /** El mensaje tal cual, salvo para Bedrock: ahi cada etiqueta animada vuelve a ser la fija. */
    public static Component forViewer(Component mensaje, Player viewer) {
        if (viewer == null || !enabled || viewer.getUniqueId().getMostSignificantBits() != 0L) return mensaje;
        return fija(mensaje);
    }

    static Component fija(Component c) {
        String marca = c.insertion();
        if (marca != null && marca.startsWith(MARCA)) {
            String prefijo;
            try {
                prefijo = new String(Base64.getUrlDecoder().decode(marca.substring(MARCA.length())),
                        StandardCharsets.UTF_8);
            } catch (IllegalArgumentException rota) {
                prefijo = "";
            }
            return prefijo.isEmpty() ? Component.empty() : MM.full(prefijo);
        }
        List<Component> hijos = c.children();
        if (hijos.isEmpty()) return c;
        List<Component> cambiados = new ArrayList<>(hijos.size());
        boolean cambio = false;
        for (Component hijo : hijos) {
            Component nuevo = fija(hijo);
            cambio |= nuevo != hijo;
            cambiados.add(nuevo);
        }
        return cambio ? c.children(cambiados) : c;
    }
}
