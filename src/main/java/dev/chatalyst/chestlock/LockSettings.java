package dev.chatalyst.chestlock;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code chestlocks.yml} ya leido.
 *
 * @param lockable           materiales (o {@code *SUFIJO}) que se pueden proteger
 * @param consumeSign        gastar el cartel al proteger (en supervivencia)
 * @param placeSign          colgar un letrero con el dueno en la cara clicada si esta libre
 * @param blockHoppers       las tolvas no sacan nada de un contenedor protegido
 * @param maxTrusted         cuanta gente puede agregar el dueno a cada cofre
 */
public record LockSettings(boolean enabled, java.util.Set<String> disabledWorlds, boolean requireRegion, List<String> lockable, boolean consumeSign, boolean placeSign,
                           boolean blockHoppers, int maxTrusted, List<String> signLines,
                           Map<String, String> messages) {

    static final List<String> DEFAULT_LOCKABLE = List.of("CHEST", "TRAPPED_CHEST", "*COPPER_CHEST", "BARREL",
            "FURNACE", "BLAST_FURNACE", "SMOKER", "HOPPER");

    private static final Map<String, String> DEFAULT_MESSAGES = Map.ofEntries(
            Map.entry("Locked", "<#3DFFA8>Cofre protegido. Solo tú (y quien agregues con <#FBF2FF>/candado agregar<#3DFFA8>) puede abrirlo."),
            Map.entry("Sign_Choice", "<#C0B3D9>Deja el cartel <#FBF2FF>en blanco<#C0B3D9> para ponerle candado al cofre, o escribe algo para que sea un cartel normal."),
            Map.entry("Sign_Choice_en", "<#C0B3D9>Leave the sign <#FBF2FF>blank<#C0B3D9> to lock the chest, or write something to keep it as a normal sign."),
            Map.entry("Sign_Only", "<#C0B3D9>Cartel puesto. El cofre no tiene candado."),
            Map.entry("Sign_Only_en", "<#C0B3D9>Sign placed. The chest is not locked."),
            Map.entry("Locked_en", "<#3DFFA8>Chest locked. Only you (and whoever you add with <#FBF2FF>/candado agregar<#3DFFA8>) can open it."),
            Map.entry("Already_Yours_en", "<#C0B3D9>This chest is already yours. Use <#FBF2FF>/candado info<#C0B3D9> to see who has access."),
            Map.entry("Denied_en", "<#FF4D6D>This chest is locked by <#FBF2FF>{owner}<#FF4D6D>."),
            Map.entry("Denied_Break_en", "<#FF4D6D>You can't break a chest locked by <#FBF2FF>{owner}<#FF4D6D>."),
            Map.entry("Denied_Merge_en", "<#FF4D6D>You can't place a chest next to the locked chest of <#FBF2FF>{owner}<#FF4D6D>."),
            Map.entry("Info_en", "<#C0B3D9>Owner: <#FBF2FF>{owner}<#C0B3D9> · Access: <#FBF2FF>{trusted}"),
            Map.entry("Not_Locked_en", "<#C0B3D9>That block is not locked."),
            Map.entry("Not_Container_en", "<#FF4D6D>Look at a chest (or another container) within 5 blocks."),
            Map.entry("Not_Owner_en", "<#FF4D6D>Only the owner can change this lock."),
            Map.entry("Added_en", "<#3DFFA8>{player} can now open this chest."),
            Map.entry("Removed_en", "<#3DFFA8>{player} can no longer open this chest."),
            Map.entry("Unlocked_en", "<#3DFFA8>Lock removed."),
            Map.entry("Unknown_Player_en", "<#FF4D6D>Unknown player <#FBF2FF>{player}<#FF4D6D> (they must have joined the server before)."),
            Map.entry("Too_Many_en", "<#FF4D6D>This chest already has {max} people with access."),
            Map.entry("Disabled_en", "<#FF4D6D>Chest locks are turned off."),
            Map.entry("Not_Your_Region", "<#FF4D6D>Solo puedes proteger cofres en una protección donde seas dueño o miembro."),
            Map.entry("Not_Your_Region_en", "<#FF4D6D>You can only lock chests in a protection you own or are a member of."),
            Map.entry("World_Disabled_en", "<#FF4D6D>You can't lock chests here."),
            Map.entry("Need_Region_en", "<#FF4D6D>You can only lock chests inside a protection (your claim)."),
            Map.entry("Usage_en", "<#C0B3D9>Lock a chest with <#FBF2FF>shift + right click<#C0B3D9> holding a sign. <#FBF2FF>/candado info · agregar <player> · quitar <player> · eliminar"),
            Map.entry("World_Disabled", "<#FF4D6D>Aquí no se pueden proteger cofres."),
            Map.entry("Need_Region", "<#FF4D6D>Solo puedes proteger cofres dentro de una protección (tu terreno)."),
            Map.entry("Already_Yours", "<#C0B3D9>Este cofre ya es tuyo. Usa <#FBF2FF>/candado info<#C0B3D9> para ver quién tiene acceso."),
            Map.entry("Denied", "<#FF4D6D>Este cofre está protegido por <#FBF2FF>{owner}<#FF4D6D>."),
            Map.entry("Denied_Break", "<#FF4D6D>No puedes romper un cofre protegido de <#FBF2FF>{owner}<#FF4D6D>."),
            Map.entry("Denied_Merge", "<#FF4D6D>No puedes poner un cofre junto al cofre protegido de <#FBF2FF>{owner}<#FF4D6D>."),
            Map.entry("Info", "<#C0B3D9>Dueño: <#FBF2FF>{owner}<#C0B3D9> · Con acceso: <#FBF2FF>{trusted}"),
            Map.entry("Not_Locked", "<#C0B3D9>Ese bloque no está protegido."),
            Map.entry("Not_Container", "<#FF4D6D>Mira a un cofre (u otro contenedor) a menos de 5 bloques."),
            Map.entry("Not_Owner", "<#FF4D6D>Solo el dueño puede cambiar este candado."),
            Map.entry("Added", "<#3DFFA8>{player} ya puede abrir este cofre."),
            Map.entry("Removed", "<#3DFFA8>{player} ya no puede abrir este cofre."),
            Map.entry("Unlocked", "<#3DFFA8>Candado quitado. "),
            Map.entry("Unknown_Player", "<#FF4D6D>No conozco a <#FBF2FF>{player}<#FF4D6D> (tiene que haber entrado al servidor alguna vez)."),
            Map.entry("Too_Many", "<#FF4D6D>Este cofre ya tiene {max} personas con acceso."),
            Map.entry("Disabled", "<#FF4D6D>La protección de cofres está apagada."),
            Map.entry("Usage", "<#C0B3D9>Protege un cofre con <#FBF2FF>shift + clic derecho<#C0B3D9> llevando un cartel. <#FBF2FF>/candado info · agregar <jugador> · quitar <jugador> · eliminar"));

    public static LockSettings read(FileConfiguration cfg) {
        String r = "Chest_Locks.";
        List<String> lockable = cfg.isList(r + "Lockable") ? cfg.getStringList(r + "Lockable") : DEFAULT_LOCKABLE;
        List<String> normalized = new ArrayList<>();
        for (String s : lockable) if (s != null && !s.isBlank()) normalized.add(s.trim().toUpperCase(Locale.ROOT));
        Map<String, String> messages = new LinkedHashMap<>(DEFAULT_MESSAGES);
        ConfigurationSection m = cfg.getConfigurationSection(r + "Messages");
        if (m != null) for (String k : m.getKeys(false)) {
            String v = m.getString(k);
            if (v != null) messages.put(k, v);
        }
        List<String> lines = cfg.isList(r + "Sign_Lines") ? cfg.getStringList(r + "Sign_Lines")
                : List.of("<#C44FE0><b>[Privado]</b>", "<#240046>{owner}");
        java.util.Set<String> worlds = new java.util.HashSet<>();
        for (String w : cfg.getStringList(r + "Disabled_Worlds")) worlds.add(w.toLowerCase(Locale.ROOT));
        return new LockSettings(cfg.getBoolean(r + "Enabled", false), java.util.Set.copyOf(worlds),
                cfg.getBoolean(r + "Require_Region", true),
                List.copyOf(normalized),
                cfg.getBoolean(r + "Consume_Sign", true), cfg.getBoolean(r + "Place_Sign", true),
                cfg.getBoolean(r + "Block_Hopper_Extraction", true),
                Math.max(0, cfg.getInt(r + "Max_Trusted", 10)),
                List.copyOf(lines.subList(0, Math.min(4, lines.size()))), Map.copyOf(messages));
    }

    public boolean worldAllowed(String world) {
        return world == null || !disabledWorlds.contains(world.toLowerCase(Locale.ROOT));
    }

    /** Si ese material se puede proteger: nombre exacto o {@code *SUFIJO}. */
    public boolean isLockable(Material material) {
        return matches(lockable, material.name());
    }

    static boolean matches(List<String> patterns, String name) {
        for (String p : patterns) {
            if (p.startsWith("*") ? name.endsWith(p.substring(1)) : name.equals(p)) return true;
        }
        return false;
    }

    public String message(String key) {
        return messages.getOrDefault(key, "");
    }

    /** {@code Key_en} para quien juega en ingles, {@code Key_es} o {@code Key} para espanol. */
    public String message(String key, boolean spanish) {
        String v = messages.get(key + (spanish ? "_es" : "_en"));
        return v != null && !v.isBlank() ? v : message(key);
    }
}
