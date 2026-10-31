package dev.chatalyst.gui;

import dev.chatalyst.util.MM;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Constructor de items para TODAS las GUIs del plugin (antes cada menu tenia su copia).
 * Nombre y lore en MiniMessage ya estilizados; se quita la cursiva por defecto de Bukkit.
 */
public final class MenuItems {

    /**
     * NOMBRES de item, el escalon intermedio de la jerarquia: el titulo del menu lleva su
     * degradado, el nombre usa el violeta espacial luminoso, y la descripcion se queda en
     * el gris violeta.
     *
     * <p>Con la paleta neon SI lleva degradado: los titulos van sobre la barra gris del
     * inventario en tonos oscuros y los nombres sobre el tooltip negro en claros, asi que
     * ya no compiten — antes, con ambos en la misma familia clara, un degradado aqui le
     * robaba protagonismo al titulo. Se cierra para no derramarse sobre el resto del nombre.
     */
    private static final String NOMBRE_ABRE = "<gradient:#B56CFF:#E4A8FF>";
    private static final String NOMBRE_CIERRA = "</gradient>";

    private MenuItems() {
    }

    /**
     * Aplica el lila de la paleta salvo que el texto YA traiga su propio estilo: un nombre
     * que empieza por un tag MiniMessage se respeta tal cual (estados en verde/rojo,
     * nombres con degradado propio, etc.).
     */
    static String estilizarNombre(String name) {
        if (name == null || name.isEmpty()) return name;
        return name.stripLeading().startsWith("<")
                ? name : NOMBRE_ABRE + name + NOMBRE_CIERRA;
    }

    /**
     * Ritmo visual comun del tooltip: una linea de aire separa el nombre del contenido y
     * cualquier texto sin color explicito recibe el gris-violeta de lectura. Los estados que
     * ya declaran verde, rojo u otro color semantico se conservan.
     */
    static List<String> estilizarLore(List<String> loreLines) {
        if (loreLines == null || loreLines.isEmpty()) return List.of();
        java.util.ArrayList<String> styled = new java.util.ArrayList<>(loreLines.size() + 1);
        if (!loreLines.getFirst().isEmpty()) styled.add("");
        for (String line : loreLines) {
            String safe = line == null ? "" : line;
            styled.add(safe.isEmpty() || safe.stripLeading().startsWith("<")
                    ? safe : "<#C0B3D9>" + safe);
        }
        return List.copyOf(styled);
    }

    public static ItemStack item(Material material, String name, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        return item(item, name, loreLines);
    }

    /**
     * Una cabeza con la textura que se le indique, para menus que quieren la figura de
     * algo concreto en vez de un bloque que se le parezca.
     *
     * <p>Se le pasa la URL de la piel tal cual la publica minecraft-heads
     * ({@code http://textures.minecraft.net/texture/<hash>}). Bukkit no admite la URL a
     * secas: hay que envolverla en el JSON del perfil y mandarla en base64, que es lo
     * unico que hace este metodo.
     *
     * <p>El perfil lleva un UUID aleatorio a proposito: con uno fijo, el cliente cachea
     * la primera textura y acaba pintando la misma cabeza en todas partes.
     */
    public static ItemStack cabezaConTextura(String urlTextura) {
        ItemStack cabeza = new ItemStack(Material.PLAYER_HEAD);
        if (urlTextura == null || urlTextura.isBlank()) {
            return cabeza;
        }
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + urlTextura + "\"}}}";
        String valor = java.util.Base64.getEncoder().encodeToString(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        cabeza.editMeta(org.bukkit.inventory.meta.SkullMeta.class, meta -> {
            com.destroystokyo.paper.profile.PlayerProfile perfil =
                    org.bukkit.Bukkit.createProfile(java.util.UUID.randomUUID(), "head");
            perfil.setProperty(
                    new com.destroystokyo.paper.profile.ProfileProperty("textures", valor));
            meta.setPlayerProfile(perfil);
        });
        return cabeza;
    }

    /** Conserva CustomModelData/PDC de un item externo (por ejemplo ItemsAdder). */
    public static ItemStack item(ItemStack base, String name, List<String> loreLines) {
        ItemStack item = base == null ? new ItemStack(Material.PAPER) : base.clone();
        item.editMeta(meta -> {
            meta.displayName(MM.full(estilizarNombre(name)).decoration(TextDecoration.ITALIC, false));
            meta.lore(estilizarLore(loreLines).stream()
                    .map(line -> MM.full(line).decoration(TextDecoration.ITALIC, false))
                    .toList());
        });
        return item;
    }

    /** Variante con el nombre ya construido como Component (cabezas, nombres con placeholders). */
    public static ItemStack item(Material material, net.kyori.adventure.text.Component name,
            List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(noItalic(name));
            meta.lore(estilizarLore(loreLines).stream()
                    .map(line -> MM.full(line).decoration(TextDecoration.ITALIC, false))
                    .toList());
        });
        return item;
    }

    /** Quita la cursiva por defecto de Bukkit a un Component ya construido (nombres de items). */
    public static net.kyori.adventure.text.Component noItalic(
            net.kyori.adventure.text.Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Añade lineas (MiniMessage) al lore EXISTENTE de un item, sin tocar su nombre ni el
     * resto de su meta. Para items reales mostrados en GUIs (mercado) que necesitan contexto
     * extra debajo de su tooltip original.
     */
    public static void appendLore(ItemStack item, List<String> lines) {
        item.editMeta(meta -> {
            var lore = meta.lore() != null
                    ? new java.util.ArrayList<>(meta.lore())
                    : new java.util.ArrayList<net.kyori.adventure.text.Component>();
            for (String line : lines) {
                lore.add(MM.full(line).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
        });
    }
}
