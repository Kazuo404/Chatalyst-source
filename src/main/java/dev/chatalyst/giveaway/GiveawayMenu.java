package dev.chatalyst.giveaway;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.gui.MenuNavigation;
import dev.chatalyst.lang.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a draw is set up: the KIND of prize first, then the prize itself.
 *
 * <p>The kind row is not decoration. A server that gives away ranks, store kits, crate keys,
 * loose items and money ends up with a catalogue where those are five completely different
 * promises, and "pick something from a wall of forty icons" is how staff start a rank draw when
 * they meant to start a key draw. Choosing the kind first says out loud what is being given
 * away before anything is chosen.
 */
public final class GiveawayMenu implements Menu {

    private final ChatalystPlugin plugin;
    private final GiveawayManager giveaways;
    /** null = la portada, con una carta por categoria. Si no, la categoria abierta. */
    private final String category;
    // slot -> prize, resolved while building so a click never depends on catalogue order.
    private final List<GiveawayPrize> shown = new ArrayList<>();
    private final List<dev.chatalyst.gui.Catalog> categories = new ArrayList<>();
    /** Slot del inventario -> posicion en `shown` / `categories`. Se llena al pintar. */
    private final java.util.Map<Integer, Integer> posicion = new java.util.HashMap<>();

    public GiveawayMenu(ChatalystPlugin plugin, GiveawayManager giveaways) {
        this(plugin, giveaways, null);
    }

    private GiveawayMenu(ChatalystPlugin plugin, GiveawayManager giveaways, String category) {
        this.plugin = plugin;
        this.giveaways = giveaways;
        this.category = category;
    }

    @Override
    public Component title() {
        return Lang.get("giveaway-gui.admin-title");
    }

    @Override
    public Component title(Player viewer) {
        return Lang.get(viewer, "giveaway-gui.admin-title");
    }

    @Override
    public int size() {
        return disposicion().filas() * 9;
    }

    // ---- disposicion -------------------------------------------------------------------

    /**
     * Como se reparte la pantalla. Sale de `Giveaway_Menu` en giveaway.yml, no del codigo:
     * mover un boton no deberia obligar a recompilar el plugin.
     *
     * <p>Los slots del YAML se escriben EN BASE 1, que es como se cuenta una cuadricula de
     * 9x6 mirandola: la primera casilla es la 1 y la ultima la 54. Bukkit los numera desde
     * 0, y esa conversion se hace aqui dentro para que nadie tenga que pensarla al editar.
     */
    private record Disposicion(int filas, int columnas, int filasRejilla,
                               int atras, int estado, int cerrar) {
        int capacidad() {
            return columnas * filasRejilla;
        }
    }

    private Disposicion disposicion() {
        var cfg = plugin.getConfig();
        int filas = acotar(cfg.getInt("Giveaway_Menu.Rows", 6), 3, 6);
        int columnas = acotar(cfg.getInt("Giveaway_Menu.Grid.Columns", 7), 1, 9);
        // Se reservan la fila de arriba (aire) y la de abajo (navegacion).
        int filasRejilla = acotar(cfg.getInt("Giveaway_Menu.Grid.Rows", filas - 2), 1, filas - 1);
        int ultima = (filas - 1) * 9;
        return new Disposicion(filas, columnas, filasRejilla,
                boton(cfg, "Giveaway_Menu.Buttons.Back", ultima + 1, filas),
                boton(cfg, "Giveaway_Menu.Buttons.Status", ultima + 5, filas),
                boton(cfg, "Giveaway_Menu.Buttons.Close", ultima + 9, filas));
    }

    private static int acotar(int valor, int min, int max) {
        return Math.max(min, Math.min(max, valor));
    }

    /** Lee un slot en base 1 y lo devuelve en base 0. Fuera de rango, se usa el de fabrica. */
    private int boton(org.bukkit.configuration.file.FileConfiguration cfg,
                      String ruta, int porDefectoBase1, int filas) {
        int valor = cfg.getInt(ruta, porDefectoBase1);
        if (valor < 1 || valor > filas * 9) {
            plugin.getLogger().warning("giveaway.yml: " + ruta + " = " + valor
                    + " se sale de la cuadricula de " + filas + "x9 (1.." + filas * 9
                    + "). Uso " + porDefectoBase1 + ".");
            valor = porDefectoBase1;
        }
        return valor - 1;
    }

    /**
     * Los slots donde caen los items, centrados.
     *
     * <p>Antes se pintaba en fila desde un slot fijo, asi que una categoria con 7 cartas
     * quedaba arrimada al borde izquierdo y la ultima fila a medias tambien. Aqui se centra
     * en los dos ejes: las columnas sobrantes se reparten a los lados, y las filas que no
     * se usan, arriba y abajo.
     */
    private int[] huecos(Disposicion d, int cuantos) {
        int total = Math.min(cuantos, d.capacidad());
        int filasUsadas = (total + d.columnas() - 1) / d.columnas();
        int desdeArriba = 1 + (d.filasRejilla() - filasUsadas) / 2;
        int margenColumnas = (9 - d.columnas()) / 2;
        int[] slots = new int[total];
        for (int i = 0; i < total; i++) {
            int fila = i / d.columnas();
            int enEstaFila = Math.min(d.columnas(), total - fila * d.columnas());
            int sangria = (d.columnas() - enEstaFila) / 2;   // la fila incompleta, centrada
            slots[i] = (desdeArriba + fila) * 9
                    + margenColumnas + sangria + (i % d.columnas());
        }
        return slots;
    }

    @Override
    public void build(Inventory inv, Player viewer) {
        inv.clear();
        shown.clear();
        categories.clear();

        // Read ONCE. Resolving the catalogue parses every prize's items and materials, and
        // the filter row alone used to ask for it six more times per screen.
        //
        // Y se pide al MANAGER, no a la config: `GiveawayPrize.all` son solo los premios
        // escritos a mano en Giveaway_Prizes, y los rangos que este server resulta tener se
        // suman despues, en `catalogue()`. Pidiendolos aqui a la config, la deteccion entera
        // se hacia y se tiraba a la basura antes de llegar a la pantalla.
        List<GiveawayPrize> catalogue = giveaways.catalogue();
        java.util.Map<dev.chatalyst.gui.Catalog, List<GiveawayPrize>> grouped =
                dev.chatalyst.gui.Catalog.group(plugin.getConfig(),
                        "Giveaway_Categories", catalogue,
                        prize -> prize.category(plugin.getConfig()));

        Disposicion d = disposicion();
        posicion.clear();

        if (category == null) {
            // Portada: una carta por categoria. Sin esto, abrir /sorteo era volcar el
            // catalogo entero en la cara y buscar a ojo entre veinte cosas.
            if (grouped.isEmpty()) {
                inv.setItem(centro(d), MenuItems.item(Material.BARRIER,
                        Lang.raw(viewer, "giveaway-gui.no-prizes"),
                        Lang.rawList(viewer, "giveaway-gui.no-prizes-lore")));
            }
            List<dev.chatalyst.gui.Catalog> grupos = new ArrayList<>(grouped.keySet());
            int[] slots = huecos(d, grupos.size());
            for (int i = 0; i < slots.length; i++) {
                dev.chatalyst.gui.Catalog grupo = grupos.get(i);
                posicion.put(slots[i], i);
                inv.setItem(slots[i], categoryCard(viewer, grupo, grouped.get(grupo)));
                categories.add(grupo);
            }
        } else {
            List<GiveawayPrize> prizes = grouped.entrySet().stream()
                    .filter(entry -> entry.getKey().id().equals(category))
                    .findFirst().map(java.util.Map.Entry::getValue).orElse(List.of());
            if (prizes.isEmpty()) {
                inv.setItem(centro(d), MenuItems.item(Material.BARRIER,
                        Lang.raw(viewer, "giveaway-gui.no-prizes"),
                        Lang.rawList(viewer, "giveaway-gui.no-prizes-lore")));
            }
            // Una categoria mas grande que la rejilla perdia los premios sobrantes sin
            // decirlo, y desde el menu no habia forma de llegar a ellos.
            if (prizes.size() > d.capacidad()) {
                plugin.getLogger().warning("El sorteo '" + category + "' tiene "
                        + prizes.size() + " premios y en la pantalla caben " + d.capacidad()
                        + ". Sube Giveaway_Menu.Grid.Columns o .Rows en giveaway.yml,"
                        + " o los de mas no se podran elegir.");
            }
            int[] slots = huecos(d, prizes.size());
            for (int i = 0; i < slots.length; i++) {
                GiveawayPrize prize = prizes.get(i);
                posicion.put(slots[i], i);
                shown.add(prize);
                inv.setItem(slots[i], card(viewer, prize));
            }
            inv.setItem(d.atras(), MenuNavigation.back(viewer));
        }

        inv.setItem(d.estado(), statusButton(viewer));
        inv.setItem(d.cerrar(), MenuNavigation.close(viewer));
    }

    /** El hueco del medio de la rejilla, para el cartel de "aqui no hay nada". */
    private int centro(Disposicion d) {
        int[] uno = huecos(d, 1);
        return uno.length > 0 ? uno[0] : 0;
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (!player.hasPermission(GiveawayCommand.ADMIN_PERMISSION)) return;
        Disposicion d = disposicion();
        if (slot == d.cerrar()) {
            player.closeInventory();
            return;
        }
        if (slot == d.estado()) {
            if (giveaways.phase() != GiveawayManager.Phase.IDLE) {
                giveaways.cancel();
                player.sendMessage(Lang.get(player, "giveaway.cancelled-by-you"));
                player.closeInventory();
            }
            return;
        }
        if (slot == d.atras() && category != null) {
            Menu.open(player, new GiveawayMenu(plugin, giveaways));
            return;
        }
        // Con la rejilla centrada el slot ya no es "principio + i": se mira el mapa que
        // se lleno al pintar, asi el clic no depende de como quedaran repartidas las filas.
        int index = posicion.getOrDefault(slot, -1);
        if (category == null) {
            if (index >= 0 && index < categories.size()) {
                Menu.open(player, new GiveawayMenu(plugin, giveaways,
                        categories.get(index).id()));
            }
            return;
        }
        if (index >= 0 && index < shown.size()) {
            GiveawayPrize chosen = shown.get(index);
            // Derecho: editar los permisos que concede. Izquierdo sigue llevando a lanzarlo —
            // lo que se hace todos los dias no puede quedar detras de un menu nuevo.
            if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
                Menu.open(player, new GiveawayPermissionsMenu(plugin, giveaways, chosen));
                return;
            }
            Menu.open(player, new GiveawayStartMenu(plugin, giveaways, chosen,
                    giveaways.defaultWarmupSeconds()));
        }
    }

    // ---- items -----------------------------------------------------------------------

    private ItemStack categoryCard(Player viewer, dev.chatalyst.gui.Catalog group,
                                   List<GiveawayPrize> prizes) {
        List<String> lore = new ArrayList<>();
        lore.add(Lang.raw(viewer, "menu.category-count",
                "count", String.valueOf(prizes.size())));
        lore.add("");
        // Un adelanto de lo que hay dentro: sin el, entrar en una categoria es una apuesta.
        for (int i = 0; i < prizes.size() && i < 5; i++) {
            lore.add("<#7B68A0>• <#C0B3D9>" + prizes.get(i)
                    .displayName(plugin.getConfig(), viewer));
        }
        if (prizes.size() > 5) {
            lore.add("<#5A4775>" + Lang.raw(viewer, "menu.category-more",
                    "count", String.valueOf(prizes.size() - 5)));
        }
        lore.add("");
        lore.add("<#7B68A0>" + Lang.raw(viewer, "menu.category-open"));
        return MenuItems.item(group.icon(),
                "<#E0AAFF>" + group.displayName(plugin.getConfig(), viewer), lore);
    }

    private ItemStack card(Player viewer, GiveawayPrize prize) {
        List<String> lore = new ArrayList<>(prize.description(plugin.getConfig(), viewer));
        lore.add("");
        lore.add(Lang.raw(viewer, "giveaway-gui.prize-kind",
                "kind", Lang.raw(viewer, "giveaway.kind." + prize.kind().key())));
        lore.add("<#7B68A0>" + Lang.raw(viewer, "giveaway-gui.prize-id", "id", prize.id()));
        lore.add(Lang.raw(viewer, "giveaway-gui.prize-edit-hint"));
        // Says out loud why a prize nobody wrote down is on this screen: it was found in
        // LuckPerms. Without it, an admin sees a rank they never configured and cannot
        // tell whether it is real or a leftover.
        if (prize.detected()) {
            lore.add(Lang.raw(viewer, "giveaway-gui.prize-detected"));
        }
        // The one thing an admin cannot see from the catalogue: whether this prize can
        // actually be handed over with the server configured the way it is right now.
        String problem = giveaways.delivery().problem(prize);
        lore.add(problem == null
                ? "<#3DFFA8>" + Lang.raw(viewer, "giveaway-gui.prize-ready")
                : "<#FF9F1C>" + Lang.raw(viewer, "giveaway-gui.prize-problem",
                        "problem", problem));
        lore.add("");
        lore.add("<#E0AAFF>" + Lang.raw(viewer, "giveaway-gui.prize-click"));
        // Un premio puede pedir una CABEZA en vez de un material, con `Icon_Texture`. Para
        // cosas que no tienen bloque que las represente (un poder, una mascota, un animal
        // concreto) elegir "el material que mas se le parece" es justo lo que hace que dos
        // premios distintos acaben con el mismo icono.
        String textura = plugin.getConfig().getString(
                "Giveaway_Prizes." + prize.id() + ".Icon_Texture", "");
        ItemStack base = textura == null || textura.isBlank()
                ? new ItemStack(prize.icon())
                : MenuItems.cabezaConTextura(textura);
        ItemStack item = MenuItems.item(base,
                prize.displayName(plugin.getConfig(), viewer), lore);
        if (prize.glow()) item.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        return item;
    }

    private ItemStack statusButton(Player viewer) {
        if (giveaways.phase() == GiveawayManager.Phase.IDLE) {
            return MenuItems.item(Material.PAPER,
                    Lang.raw(viewer, "giveaway-gui.status-idle"),
                    Lang.rawList(viewer, "giveaway-gui.status-idle-lore"));
        }
        GiveawayPrize running = giveaways.runningPrize();
        return MenuItems.item(Material.REDSTONE_BLOCK,
                "<#FF4D6D>" + Lang.raw(viewer, "giveaway-gui.status-running", "prize",
                        running == null ? "?" : running.id()),
                List.of("<#FF4D6D>" + Lang.raw(viewer, "giveaway-gui.status-running-lore")));
    }
}
