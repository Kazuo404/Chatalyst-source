package dev.chatalyst.channel;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Un canal de chat. Se carga desde la seccion Channels.List de config.yml.
 */
public final class Channel {

    public final String name;
    public final List<String> aliases;
    public final ChannelType type;
    public final String permission;     // vacio = cualquiera puede leer/escribir
    public final String format;         // nombre del formato (formats/<x>.yml)
    public final boolean autoListen;    // se escucha por defecto sin tener que unirse
    public final boolean forceListen;   // se escucha incluso en modo exclusivo (ej. staff)
    public final boolean proxy;         // reenvia a toda la red (solo canales GLOBAL)
    public final String groupPlaceholder; // GROUP: placeholder cuyo valor define el grupo
    public final String problem;        // por que no debe cargarse tal cual, o null

    private Channel(String name, List<String> aliases, ChannelType type,
                    String permission, String format, boolean autoListen, boolean forceListen,
                    boolean proxy, String groupPlaceholder, String problem) {
        this.name = name;
        this.aliases = aliases;
        this.type = type;
        this.permission = permission;
        this.format = format;
        this.autoListen = autoListen;
        this.forceListen = forceListen;
        this.proxy = proxy;
        this.groupPlaceholder = groupPlaceholder;
        this.problem = problem;
    }

    public static Channel fromConfig(String name, ConfigurationSection sec) {
        List<String> aliases = new ArrayList<>();
        aliases.add(name.toLowerCase(Locale.ROOT));
        for (String a : sec.getStringList("Aliases")) {
            String low = a.toLowerCase(Locale.ROOT);
            if (!aliases.contains(low)) aliases.add(low);
        }
        // Una errata en Type no puede dejar publico un canal que se penso privado: el canal se
        // rechaza entero (ver ChannelManager), nunca se convierte en GLOBAL.
        String rawType = sec.getString("Type", "GLOBAL");
        String problem = null;
        ChannelType type = ChannelType.GLOBAL;
        try {
            type = ChannelType.valueOf(rawType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            problem = "unknown Type '" + rawType + "'";
        }
        String group = sec.getString("Group_Placeholder", "").trim();
        if (type == ChannelType.GROUP && !isPlaceholder(group)) {
            problem = "Type GROUP needs Group_Placeholder, for example %clan_name%";
        }
        // Al cruzar el proxy solo viaja el texto ya formateado, sin emisor: el servidor de
        // destino no puede saber quien comparte mundo, region, party o grupo. Por eso un canal
        // con alcance nunca sale de su servidor.
        boolean proxy = sec.getBoolean("Proxy", false) && type == ChannelType.GLOBAL;
        return new Channel(
                name,
                aliases,
                type,
                sec.getString("Permission", ""),
                sec.getString("Format", "chat"),
                sec.getBoolean("Auto_Listen", true),
                sec.getBoolean("Force_Listen", false),
                proxy,
                group,
                problem
        );
    }

    private static boolean isPlaceholder(String value) {
        return value.length() > 2 && value.startsWith("%") && value.endsWith("%");
    }

    /** True si los mensajes de este canal son privados de un grupo (party o GROUP). */
    public boolean isPrivateGroup() {
        return type == ChannelType.PARTY || type == ChannelType.GROUP;
    }

    public boolean canUse(org.bukkit.permissions.Permissible who) {
        return permission == null || permission.isEmpty() || who.hasPermission(permission);
    }

    public boolean matchesAlias(String input) {
        return aliases.contains(input.toLowerCase(Locale.ROOT));
    }
}
