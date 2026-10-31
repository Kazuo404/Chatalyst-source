package dev.chatalyst.giveaway;

import dev.chatalyst.hook.VaultHook;
import dev.chatalyst.lang.LocalizedConfig;
import dev.chatalyst.store.StoreProduct;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One prize a giveaway can hand over, resolved from {@code Giveaway_Prizes} and bounded on
 * every axis.
 *
 * <p>Only ids present in the catalogue can ever be drawn, so nothing about a prize is ever
 * taken from a command line: {@code /sorteo start} carries an id, never a material, an amount
 * or a command to run.
 *
 * <p>Items, commands and money are parsed by {@link StoreProduct}, the same code that validates
 * a paid delivery, rather than by a second parser that would drift from it. The two extra kinds
 * a giveaway adds are references, not contents: {@link Kind#STORE} names a product the owner
 * already sells and {@link Kind#RANK} names a rank in {@code Rank_Catalog}, so a prize cannot
 * quietly diverge from the shop it was copied out of.
 */
public record GiveawayPrize(String id, Kind kind, Material icon, boolean glow,
                            StoreProduct goods, String storeProduct, String rank,
                            String rankGroup, String detectedName, MoneyBand moneyOverride,
                            Long durationOverride) {

    /** What a prize hands over — and how the admin menu groups it. */
    public enum Kind {
        ITEMS(Material.CHEST),
        COMMANDS(Material.COMMAND_BLOCK),
        MONEY(Material.GOLD_INGOT),
        STORE(Material.ENDER_CHEST),
        RANK(Material.NETHER_STAR),
        PERMISSION(Material.FEATHER),
        /** Hecho en el juego con /sorteo crear: objetos enteros + comandos (GiveawayCustomPrizes). */
        CUSTOM(Material.SHULKER_BOX);

        private final Material defaultIcon;

        Kind(Material defaultIcon) {
            this.defaultIcon = defaultIcon;
        }

        public Material defaultIcon() {
            return defaultIcon;
        }

        /** Lower-case id used in lang keys and on the command line. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Kind parse(String raw) {
            if (raw == null) return null;
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
    }

    private static final String SECTION = "Giveaway_Prizes";

    /**
     * A rank this server has but nobody wrote a catalogue entry for: offered in the menu as if
     * it were configured, so a local setup can give away VIP without editing a file first.
     *
     * @param group the LuckPerms group verbatim, which may hold characters an id may not ("vip+")
     */
    static GiveawayPrize detectedRank(String id, String group, String displayName) {
        return new GiveawayPrize(id, Kind.RANK, GiveawayRanks.icon(), true,
                null, null, group.toLowerCase(Locale.ROOT), group, displayName, null, null);
    }

    /** Categoria del menu donde salen los premios hechos en el juego. */
    public static final String CUSTOM_CATEGORY = "personalizados";

    /** Un premio hecho en el juego. Su nombre, descripcion y contenido viven en el Store. */
    static GiveawayPrize custom(GiveawayCustomPrizes.Custom custom) {
        return new GiveawayPrize(custom.id, Kind.CUSTOM, custom.iconMaterial(), true,
                null, null, null, null, null, null, null);
    }

    /** Lo guardado de un premio CUSTOM, si sigue existiendo. Para leer: no se modifica. */
    public java.util.Optional<GiveawayCustomPrizes.Custom> customData() {
        if (kind != Kind.CUSTOM) return java.util.Optional.empty();
        return GiveawayCustomPrizes.get().flatMap(store -> store.peek(id));
    }

    /** True when this prize came from LuckPerms rather than from Giveaway_Prizes. */
    public boolean detected() {
        return detectedName != null;
    }

    /**
     * Name and description are written by the OWNER, so they are read per viewer through
     * {@link LocalizedConfig} rather than resolved once here: the same prize is shown in
     * English and in Spanish at the same time, to two players watching the same wheel.
     */
    public String displayName(FileConfiguration config, CommandSender viewer) {
        if (detectedName != null) return detectedName;
        if (kind == Kind.CUSTOM) return customData().map(c -> c.name).orElse(id);
        ConfigurationSection section = entry(config, id);
        String name = LocalizedConfig.string(section, "Name", viewer, id);
        // El mismo {amount} que la descripcion. Si funcionara en un campo y en el de al lado no,
        // el que lo escribiera en el nombre veria "{amount}" en crudo en la ruleta, delante de
        // todo el server, y no habria nada que le dijera por que en uno si y en el otro no.
        return kind == Kind.MONEY ? amountIn(name, viewer) : name;
    }

    /** Configured prizes first, then any detected rank no catalogue entry already covers. */
    public static List<GiveawayPrize> merge(List<GiveawayPrize> configured,
                                            List<GiveawayPrize> detected) {
        List<GiveawayPrize> out = new ArrayList<>(configured);
        for (GiveawayPrize candidate : detected) {
            boolean alreadyOffered = configured.stream().anyMatch(prize ->
                    prize.id().equalsIgnoreCase(candidate.id())
                            || (prize.kind() == Kind.RANK && prize.rank() != null
                            && prize.rank().equalsIgnoreCase(candidate.rank())));
            if (!alreadyOffered) out.add(candidate);
        }
        return List.copyOf(out);
    }

    /** Finds a prize by id inside an already-built catalogue (configured plus detected). */
    public static Optional<GiveawayPrize> resolveIn(List<GiveawayPrize> catalogue, String rawId) {
        String prizeId = normalizeId(rawId);
        if (prizeId == null) return Optional.empty();
        return catalogue.stream().filter(prize -> prize.id().equals(prizeId)).findFirst();
    }

    public List<String> description(FileConfiguration config, CommandSender viewer) {
        if (kind == Kind.CUSTOM) return customDescription(viewer);
        List<String> lines = LocalizedConfig.list(entry(config, id), "Description", viewer);
        if (kind != Kind.MONEY) return lines;
        // Un premio de dinero al que aun no se le ha escrito la cantidad tiene que decirlo, no
        // enseñar "{amount}" en crudo: el hueco vacio ES la informacion — falta escribirla.
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) out.add(amountIn(line, viewer));
        return List.copyOf(out);
    }

    /**
     * La descripcion escrita con /sorteo descripcion y, debajo, lo que trae de verdad: cuantos
     * objetos (los primeros por su nombre), cuantos comandos y cuantos ganadores. Se genera
     * del contenido para que nunca mienta si alguien edita el premio y olvida la descripcion.
     */
    private List<String> customDescription(CommandSender viewer) {
        GiveawayCustomPrizes.Custom custom = customData().orElse(null);
        if (custom == null) return List.of();
        List<String> out = new ArrayList<>(custom.description);
        List<org.bukkit.inventory.ItemStack> stacks = custom.viewStacks();
        if (!stacks.isEmpty()) {
            out.add(dev.chatalyst.lang.Lang.raw(viewer, "giveaway-custom.lore-items",
                    "count", String.valueOf(stacks.size())));
            int shownItems = 0;
            for (org.bukkit.inventory.ItemStack stack : stacks) {
                if (shownItems++ >= 5) {
                    out.add(dev.chatalyst.lang.Lang.raw(viewer, "giveaway-custom.lore-more",
                            "count", String.valueOf(stacks.size() - 5)));
                    break;
                }
                out.add(dev.chatalyst.lang.Lang.raw(viewer, "giveaway-custom.lore-item",
                        "amount", String.valueOf(stack.getAmount()),
                        "item", itemName(stack)));
            }
        }
        if (!custom.commands.isEmpty()) {
            out.add(dev.chatalyst.lang.Lang.raw(viewer, "giveaway-custom.lore-commands",
                    "count", String.valueOf(custom.commands.size())));
        }
        out.add(dev.chatalyst.lang.Lang.raw(viewer, "giveaway-custom.lore-winners",
                "count", String.valueOf(custom.winners)));
        return List.copyOf(out);
    }

    /** Nombre legible de un objeto: el que lleva puesto, o el del material. */
    static String itemName(org.bukkit.inventory.ItemStack stack) {
        var meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            net.kyori.adventure.text.Component name = meta.displayName();
            if (name != null) {
                return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(name);
            }
        }
        String raw = stack.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    /**
     * Rellena {@code {amount}} con lo que paga este premio, o con "sin poner" mientras no se
     * haya escrito: el hueco vacio ES la informacion, y ensenar el marcador en crudo no lo es.
     */
    private String amountIn(String text, CommandSender viewer) {
        if (text == null || !text.contains("{amount}")) return text;
        MoneyBand band = moneyBand();
        return text.replace("{amount}", band != null
                ? shown(band)
                : dev.chatalyst.lang.Lang.raw(viewer, "giveaway-gui.amount-unset"));
    }

    /**
     * Lo que paga un premio de dinero: una cifra fija, o una banda que se tira de nuevo en
     * cada sorteo.
     *
     * <p>La banda no es adorno. Con una cifra fija el premio se sabe de memoria a la segunda
     * vez y la ruleta deja de tener gracia; con {@code Min}/{@code Max} nadie sabe cuanto va a
     * caer hasta que cae.
     */
    public record MoneyBand(double min, double max) {
        /** True cuando de verdad hay algo que sortear y no dos veces el mismo numero. */
        public boolean isRange() {
            return max - min >= 0.01d;
        }
    }

    /**
     * Lo que paga este premio, que es una sola cosa: lo que el staff escribio al lanzarlo.
     *
     * <p>Antes esto se podia decir de cuatro maneras — {@code Money}, {@code Amount}, y un
     * {@code Min}/{@code Max} en el fichero — mas lo escrito al lanzar, que ganaba a las otras.
     * Cuatro sitios donde mirar para responder "cuanto paga esto", y tres de ellos podian
     * contradecir al cuarto sin que nadie lo notara hasta el sorteo. Ahora hay uno: null
     * significa que todavia no se ha dicho, y el boton de empezar se niega hasta que se diga.
     */
    public MoneyBand moneyBand() {
        return kind == Kind.MONEY ? moneyOverride : null;
    }

    /**
     * El mismo premio, pagando lo que se acaba de escribir por el chat.
     *
     * <p>La cantidad viaja DENTRO del premio, no aparte, porque el premio es lo unico que
     * llega hasta el final: la ruleta lo pinta, el anuncio lo nombra y la entrega lo cobra. Un
     * importe llevado por un canal paralelo se queda por el camino en alguno de los tres, y el
     * numero que se anuncia deja de ser el que se paga.
     */
    public GiveawayPrize withMoney(MoneyBand band) {
        return new GiveawayPrize(id, kind, icon, glow, goods, storeProduct, rank,
                rankGroup, detectedName, band, durationOverride);
    }

    /**
     * El mismo premio, durando lo que se acaba de escribir por el chat.
     *
     * <p>Viaja dentro del premio por lo mismo que la cantidad: el premio es lo unico que llega
     * hasta el final, y una duracion llevada por un canal aparte se queda por el camino entre
     * la pantalla que la anuncia y la entrega que la aplica.
     */
    public GiveawayPrize withDuration(long millis) {
        return new GiveawayPrize(id, kind, icon, glow, goods, storeProduct, rank,
                rankGroup, detectedName, moneyOverride, millis);
    }

    /** True si la duracion de este sorteo se escribio a mano en vez de venir del fichero. */
    public boolean durationTyped() {
        return durationOverride != null;
    }

    /**
     * La cantidad concreta de ESTE sorteo, o 0 si aun no se ha escrito ninguna.
     *
     * <p>Se redondea a entero cuando los dos extremos lo son, que es como se escriben siempre:
     * un premio anunciado como "$8.417,63" se lee como un fallo, no como un premio.
     */
    public double rollMoney() {
        MoneyBand band = moneyBand();
        if (band == null) return 0d;
        if (!band.isRange()) return band.min();
        double rolled = ThreadLocalRandom.current().nextDouble(band.min(), band.max());
        boolean whole = band.min() == Math.rint(band.min()) && band.max() == Math.rint(band.max());
        return whole ? Math.rint(rolled) : Math.round(rolled * 100d) / 100d;
    }


    /**
     * Como se lee una duracion en pantalla. {@code 0} no es "cero tiempo": es "este premio no
     * opina y manda el ajuste global", asi que se dice con palabras y no con un numero.
     */
    public static String durationShown(long millis, CommandSender viewer) {
        if (millis == dev.chatalyst.util.TimeUtil.PERMANENT) {
            return dev.chatalyst.lang.Lang.raw(viewer, "giveaway-gui.duration-permanent");
        }
        if (millis <= 0L) {
            return dev.chatalyst.lang.Lang.raw(viewer, "giveaway-gui.duration-default");
        }
        return dev.chatalyst.util.TimeUtil.formatElapsed(millis).trim();
    }

    /** Como se lee una banda: la cifra sola, o los dos extremos si de verdad hay rango. */
    public static String shown(MoneyBand band) {
        if (band == null) return "";
        return band.isRange()
                ? formatted(band.min()) + " - " + formatted(band.max())
                : formatted(band.min());
    }

    /**
     * La cifra con el formato de la economia del server, y si eso falla, la cifra pelada.
     *
     * <p>El formato lo pone un plugin de terceros a traves de Vault. Dibujar una carta no
     * puede depender de que ese plugin este bien: sin el la cantidad se lee igual, y una
     * excepcion aqui se llevaria por delante el menu entero delante de todo el server.
     */
    private static String formatted(double amount) {
        try {
            return VaultHook.format(amount);
        } catch (Throwable noEconomy) {
            return String.format(Locale.ROOT, "%,.0f", amount);
        }
    }



    /** Money is the one prize a Vault-less server silently cannot pay. */
    public double money() {
        return goods == null ? 0d : goods.money();
    }

    public static Optional<GiveawayPrize> resolve(FileConfiguration config, String rawId) {
        String prizeId = normalizeId(rawId);
        if (prizeId == null) return Optional.empty();
        ConfigurationSection catalog = config.getConfigurationSection(SECTION);
        if (catalog == null) return Optional.empty();
        String actualKey = catalog.getKeys(false).stream()
                .filter(key -> key.equalsIgnoreCase(prizeId))
                .findFirst().orElse(null);
        if (actualKey == null) return Optional.empty();
        ConfigurationSection entry = catalog.getConfigurationSection(actualKey);
        return entry == null ? Optional.empty() : fromSection(prizeId, entry);
    }

    /** Every prize that would actually hand something over, in file order. */
    public static List<GiveawayPrize> all(FileConfiguration config) {
        ConfigurationSection catalog = config.getConfigurationSection(SECTION);
        if (catalog == null) return List.of();
        List<GiveawayPrize> prizes = new ArrayList<>();
        for (String key : catalog.getKeys(false)) {
            resolve(config, key).ifPresent(prizes::add);
        }
        return List.copyOf(prizes);
    }

    static Optional<GiveawayPrize> fromSection(String prizeId, ConfigurationSection entry) {
        Kind kind = Kind.parse(entry.getString("Type"));
        if (kind == null) return Optional.empty();

        // Items/commands/money go through the store's own parser, so bounds, materials and
        // enchantments are validated exactly once in the whole plugin.
        StoreProduct goods = StoreProduct.of(prizeId, entry).orElse(null);
        String storeProduct = normalizeId(entry.getString("Product"));
        String rank = normalizeId(entry.getString("Rank"));

        // A prize that hands over nothing is not a prize: it would spin the wheel, name a
        // winner in front of the server and give them an empty inventory.
        boolean deliverable = switch (kind) {
            case ITEMS -> goods != null && !goods.items().isEmpty();
            case COMMANDS -> goods != null && !goods.commands().isEmpty();
            // El dinero nunca trae cifra: se escribe al lanzarlo. Un premio sin cantidad
            // todavia tiene que existir para poder escribirsela, y quien lo corta es el boton
            // de empezar, con su motivo, en vez de hacerlo desaparecer del menu sin decir nada.
            case MONEY -> true;
            case STORE -> storeProduct != null;
            case RANK -> rank != null;
            case PERMISSION -> !permissions(entry).isEmpty();
            // Los personalizados se hacen en el juego (/sorteo crear), no en el fichero.
            case CUSTOM -> false;
        };
        if (!deliverable) return Optional.empty();

        Material icon = Material.matchMaterial(
                String.valueOf(entry.getString("Icon", "")).toUpperCase(Locale.ROOT));
        if (icon == null || !icon.isItem() || icon.isAir()) icon = kind.defaultIcon();

        return Optional.of(new GiveawayPrize(prizeId, kind, icon,
                entry.getBoolean("Glow", false), goods, storeProduct, rank, null, null, null,
                null));
    }

    /**
     * En que categoria se busca este premio. Un rango detectado en LuckPerms no tiene entrada
     * en el fichero donde declararla, asi que cae en la de rangos por lo que ES, no por lo que
     * alguien escribio.
     */
    public String category(FileConfiguration config) {
        if (detectedName != null) return "rangos";
        if (kind == Kind.CUSTOM) return CUSTOM_CATEGORY;
        ConfigurationSection entry = entry(config, id);
        String written = entry == null ? null : entry.getString("Category");
        String normalized = dev.chatalyst.gui.Catalog.normalize(written);
        return normalized == null ? dev.chatalyst.gui.Catalog.FALLBACK : normalized;
    }

    /**
     * Permisos que este premio concede, ademas de lo que haga su tipo.
     *
     * <p>Van aparte de {@code Commands} a proposito. Un {@code lp user ... permission settemp}
     * escrito a mano depende de la sintaxis de OTRO plugin, no dice nada si LuckPerms no esta,
     * y no hay forma de saber si funciono. Por aqui se concede con la API, se puede comprobar,
     * y la duracion es la MISMA que la del rango del mismo premio en vez de un texto suelto
     * dentro de un comando.
     */
    public List<String> permissions(FileConfiguration config) {
        return permissions(entry(config, id));
    }

    static List<String> permissions(ConfigurationSection entry) {
        if (entry == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String raw : entry.getStringList("Permissions")) {
            if (raw == null) continue;
            String node = raw.trim().toLowerCase(Locale.ROOT);
            // Un permiso es un nodo, no una frase: cualquier cosa con espacios o con caracteres
            // que LuckPerms no admite se descarta antes de llegar a la API.
            if (validPermission(node)) out.add(node);
        }
        return List.copyOf(out);
    }

    /**
     * Un permiso es un NODO, no una frase. La misma regla que aplica el lector del fichero y la
     * que valida lo que se escribe por el menu: si fueran dos, el menu aceptaria algo que el
     * lector descarta y el staff lo veria desaparecer solo tras el siguiente reload.
     */
    public static boolean validPermission(String node) {
        return node != null && !node.isEmpty() && node.length() <= 128
                && node.matches("[a-z0-9._*-]+");
    }

    /**
     * Cuanto dura lo que concede este premio: el rango, y los permisos de arriba.
     *
     * <p>Un premio de una semana y uno para siempre son eventos distintos, y antes la duracion
     * era UNA sola para todos los sorteos del server. Sin escribir nada se comporta como antes.
     *
     * @return milisegundos, {@link dev.chatalyst.util.TimeUtil#PERMANENT} si es permanente, o 0
     *         si el premio no dice nada y manda el ajuste global.
     */
    public long duration(FileConfiguration config) {
        if (durationOverride != null) return durationOverride;
        ConfigurationSection entry = entry(config, id);
        String written = entry == null ? null : entry.getString("Duration");
        if (written == null || written.isBlank()) return 0L;
        return dev.chatalyst.util.TimeUtil.parseDuration(written);
    }

    private static ConfigurationSection entry(FileConfiguration config, String prizeId) {
        ConfigurationSection catalog = config.getConfigurationSection(SECTION);
        if (catalog == null) return null;
        for (String key : catalog.getKeys(false)) {
            if (key.equalsIgnoreCase(prizeId)) return catalog.getConfigurationSection(key);
        }
        return null;
    }

    /** Same shape as a store product id: a prize id is typed into a command. */
    static String normalizeId(String rawId) {
        if (rawId == null) return null;
        String normalized = rawId.trim().toLowerCase(Locale.ROOT);
        // '+' is deliberate: servers really do call their ranks vip+ and mvp++, and an id
        // that cannot hold the rank's own name would make those undrawable.
        return normalized.matches("[a-z0-9_+\\-]{1,64}") ? normalized : null;
    }
}
