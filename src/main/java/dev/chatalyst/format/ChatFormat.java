package dev.chatalyst.format;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Un formato de chat completo: una lista ordenada de {@link FormatPart}.
 * Se carga desde un YAML (seccion "Parts").
 */
public final class ChatFormat {

    public final String name;
    public final List<FormatPart> parts;

    private ChatFormat(String name, List<FormatPart> parts) {
        this.name = name;
        this.parts = parts;
    }

    public static ChatFormat fromConfig(String name, ConfigurationSection root) {
        List<FormatPart> parts = new ArrayList<>();
        ConfigurationSection partsSec = root.getConfigurationSection("Parts");
        if (partsSec != null) {
            for (String key : partsSec.getKeys(false)) {
                ConfigurationSection p = partsSec.getConfigurationSection(key);
                if (p == null) continue;
                String receiverPermission = p.getString("Receiver_Permission",
                        p.getString("View_Permission", ""));
                parts.add(new FormatPart(
                        key,
                        p.getString("Message", ""),
                        readLines(p, "Hover"),
                        p.getString("Run_Command"),
                        p.getString("Suggest_Command"),
                        p.getString("Open_Url"),
                        p.getString("Copy_To_Clipboard"),
                        p.getString("Insertion"),
                        new FormatCondition(p.getString("Sender_Permission", ""),
                                p.getString("Sender_Variable", ""),
                                p.getString("Sender_Condition", "")),
                        new FormatCondition(receiverPermission,
                                p.getString("Receiver_Variable", ""),
                                p.getString("Receiver_Condition", ""))
                ));
            }
        }
        return new ChatFormat(name, parts);
    }

    // Hover puede ser una sola linea (string) o una lista de lineas.
    private static List<String> readLines(ConfigurationSection sec, String path) {
        if (sec.isList(path)) {
            return new ArrayList<>(sec.getStringList(path));
        }
        String single = sec.getString(path);
        if (single != null && !single.isEmpty()) {
            List<String> l = new ArrayList<>();
            l.add(single);
            return l;
        }
        return new ArrayList<>();
    }
}
