package dev.chatalyst.config;

import dev.chatalyst.util.YamlFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Actualiza el config.yml del servidor cuando el plugin trae opciones nuevas: copia las
 * claves que falten desde el default del jar, SIN tocar los valores del usuario ni sus
 * colecciones (canales, reglas del router, grupos...) — borrar una entrada de esas listas
 * es una decision del dueño y no debe "resucitar" en cada arranque.
 *
 * <p>Excepcion controlada por version: las entradas nuevas de colecciones que estrena una
 * version (p. ej. los canales trade/auction de la v2) se añaden UNA sola vez, guardando
 * Config_Version para no repetirlo.
 */
public final class ConfigUpgrader {

    /**
     * Version actual del esquema de config. Subir al añadir migraciones nuevas.
     *
     * <p>Importante: {@link #upgrade} corre antes del split sólo para migrar instalaciones
     * monolíticas antiguas. Toda migración futura de una sección propiedad de chat.yml,
     * moderation.yml u otro archivo partido también debe abrir ese archivo explícitamente,
     * como hacen {@code upgradeMessages}, {@code upgradeFancySigns} y
     * {@code upgradeStaffModeration}; config.yml ya no contiene esas secciones en servidores
     * que fueron divididos anteriormente.
     */
    // v3 = igual que v2 (canales trade/auction), reejecutada porque un build dev de la v2
    // grababa Config_Version ANTES de migrar y dejaba configs marcadas sin los canales.
    // v4 = {ping_bars} -> {ping_ms} en config.yml y formats/*.yml existentes (el numero
    // coloreado es el default desde 2026-07-10; las copias viejas seguian con barritas).
    // v5 = messages.yml: seccion Kick + Death.Causes/Death.Effects para archivos existentes.
    // v6 = Death.Enabled pasa a true UNA vez (el false anterior era el default viejo del
    // plugin, no una decision del dueño; quien lo apague despues, se respeta).
    // v7 = anti-repeat de rules.yml deja de aplicarse a COMANDOS (rompia nombres tipo
    // "ventilador666"), y el click de los nombres en formats/*.yml pasa de
    // "/msg {player_name} " al placeholder inteligente "{msg_command}".
    // v8 = el chat local pasa de proximidad por bloques (RANGED) a radio por FREQUENCY.
    // v9 = trade y auction incorporan Enabled: false. Se añade expresamente porque
    // Channels.List es una coleccion del usuario y el merge generico no toca sus hijos.
    // v10 = esos canales pueden activarse temporalmente cuando una integracion compatible
    // los necesita, sin encender el mercado nativo.
    // v11 = centro administrativo de red: monitor por backend y anuncios globales.
    // v12 = escalacion por menciones al staff, feeds en vivo con permisos y canal staff.
    // v13 = deteccion natural sin @ y conversaciones privadas de soporte en toda la red.
    // v14 = prioridad helper -> mod -> admin -> owner y solicitudes genericas al equipo.
    // v15 = identidad automatica del backend desde Velocity/Bungee, con fallback manual.
    // v16 = autenticacion HMAC de paquetes de red, con derivacion automatica en Velocity.
    // v17 = reglas v2 (grupos, variables, scripts, estado persistente) y auditoria ampliada.
    // v18 = entradas fancy (messages.yml Join.Fancy): sonido global/rayo/title por rango,
    // configurable desde la GUI de admin. Los tiers de ejemplo se copian UNA vez.
    // v19 = los fancy joins se enlazan tambien por grupo LuckPerms/Vault y el nombre usa
    // el color real del rango, con prioridad sobre cualquier color personal del nick.
    // v20 = autodeteccion de cualquier grupo primario nuevo de LuckPerms con entrada generica.
    // v21 = entradas fancy bilingues por espectador y sonido explicitamente global.
    // v22 = Fancy Signs: un solo permiso de rango desbloquea colores legacy/MiniMessage,
    // gradientes, arcoiris y todos los estilos configurados.
    // v23 = suite /staff: kick/ban/mute fancy, freeze/vanish/inventario persistentes; ademas
    // quita la negrita de fabrica del owner/admin y hace determinista el color del rank_nick.
    // v24 = freeze review fancy con boton HTTPS de Discord y permaban con gracia al salir;
    // la presencia de red evita castigar transferencias legitimas entre backends.
    // v25 = la entrada autodetectada anuncia el TAG del rango ({rank_tag}, "[MVP++]") en vez
    // del id interno del grupo de LuckPerms ({player_group}, "mvpplusplus").
    // v26 = matrimonios (/marry): la linea {married_tag} entra en los hovers de chat.yml.
    // La feature esta APAGADA de fabrica, asi que la linea no se ve hasta que el dueño la
    // enciende (y en un soltero desaparece sola).
    // v27 = el degradado de fabrica 'silver' se renombra a 'chrome' con un neon blanco.
    // v28 = el hover del nombre en chat.yml se tiñe por rango: los divisores usan
    // {rank_gradient} (neon derivado del color primario del rango) y los "»" usan {rank_color}.
    // v30 = rediseño integral de GUI: paleta morado espacial en mensajes existentes,
    // presets de moderación bilingües y pantallas premium de kick/ban/mute.
    // v31 = el puente DiscordSRV/webhook se sustituye por un bot bidireccional embebido.
    // v32 = vinculacion persistente, rangos LuckPerms <-> Discord y entrega Tebex idempotente.
    // v33 = IDs de rango estructurados para Discord/LuckPerms y catalogo seguro de rangos Tebex.
    // v34 = presentacion de compras Tebex global/por rango, editable por YAML y GUI.
    // v35 = tienda /cht ranks editable por YAML con enlaces HTTPS exactos por Rank ID.
    // v36 = los tramos de los roles de castigo de Discord pasan a ser coleccion del dueño;
    // la siembra de una vez les lleva los de fabrica a quien ya tenia integrations.yml.
    static final int CURRENT_VERSION = 36;

    /**
     * Secciones cuyos HIJOS define el usuario (añade/borra entradas a su gusto): el merge
     * generico nunca mete claves dentro de ellas.
     */
    static final Set<String> USER_COLLECTIONS = Set.of(
            "Channels.List",
            "Channel_Router.Rules",
            "Channel_Router.Shortcut_Prefixes",
            "Groups.List",
            "Trade_Plugins.Announce.Commands",
            "Rank_Catalog",
            "Store_Sections.Categories",
            // Borrar un premio del sorteo es una decision del dueno, no una hoja que falte.
            "Giveaway_Prizes",
            "Discord.Channel_Ids",
            "Discord.Linked_Accounts",
            "Discord.Rank_Sync.Mappings",
            "Discord.Rank_Sync.Groups",
            // Los tramos son del dueño: quitar el que no quiere ("sin rol para castigos
            // cortos") es una configuracion que la propia config documenta como valida, y sin
            // esto el merge de hojas se lo devolvia en el siguiente arranque. Se siembran una
            // sola vez desde seedPunishmentTiers, no aqui.
            "Discord.Punishment_Roles.Ban.Tiers",
            "Discord.Punishment_Roles.Freeze.Tiers",
            "Discord.Punishment_Roles.Mute.Tiers",
            "Tebex.Ranks",
            "Tebex.Allowed_Groups",
            "Chat_Color.Colors",
            "Chat_Color.Gradients",
            "Fancy_Signs.Styles",
            "Chat.Emojis.Custom",
            "Warning_Points.Thresholds",
            "Command_Aliases",
            "Mail.Notifications",   // sub-arbol estable; sin motivo para tocarlo
            "Announcer.Messages",
            "Staff_Mentions.Priority.Tiers");

    /** Colecciones del usuario en messages.yml (grupos condicionales y lineas por causa). */
    static final Set<String> MESSAGES_USER_COLLECTIONS = Set.of(
            "Join.Groups", "Join.Fancy.Tiers", "Join.Fancy.Auto_Detect_LuckPerms",
            "Quit.Groups", "Kick.Groups", "Death.Groups",
            "Death.Causes", "Motd.First_Time", "Motd.Default");

    private ConfigUpgrader() {
    }

    /**
     * Aplica el upgrade sobre plugins/Chatalyst/config.yml si hace falta. Devuelve cuantas
     * claves se añadieron (0 = no se toco el archivo). Llamar ANTES de leer la config.
     */
    public static int upgrade(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.exists()) return 0; // primera instalacion: se copia el default entero
        YamlConfiguration defaults = loadDefaults(plugin);
        if (defaults == null) return 0;

        // OJO: la version original se captura ANTES del merge — el merge añade el
        // Config_Version del default y, si se leyera despues, saltaria las migraciones.
        YamlConfiguration initial = loadUser(plugin, file);
        if (initial == null) return 0;
        int from = initial.getInt("Config_Version", 1);

        // Antes de la PRIMERA migracion, la carpeta entera. Varias de las que vienen abajo no
        // añaden claves: reescriben texto que ya estaba escrito, y aunque apuntan al texto de
        // fabrica trabajan sobre ficheros que el dueño ha podido editar. Sin respaldo no se
        // migra — misma regla que ya sigue el traslado de secciones entre ficheros.
        if (from < CURRENT_VERSION && !ConfigSnapshot.take(plugin, from)) {
            plugin.getLogger().severe("Configuration upgrade to v" + CURRENT_VERSION
                    + " was skipped because its backup could not be written. The plugin keeps"
                    + " running on the current configuration and will retry next startup.");
            return 0;
        }
        boolean migrationsOk = true;

        // Migraciones de TEXTO plano (conservan los comentarios del usuario): van antes
        // de parsear para el merge, porque tocan el propio archivo.
        if (from < 4) {
            RewriteResult result = rewritePingTokensInFiles(plugin);
            migrationsOk &= result.success();
            if (result.changed() > 0) {
                plugin.getLogger().info("Updated " + result.changed() + " file(s): {ping_bars} -> "
                        + "{ping_ms} (colored number is the default; use {ping_bars} to go back).");
            }
        }
        if (from < 7) {
            RewriteResult result = rewriteFormatFiles(plugin, "/msg {player_name} ", "{msg_command}");
            migrationsOk &= result.success();
            if (result.changed() > 0) {
                plugin.getLogger().info("Updated " + result.changed() + " format(s): name click now"
                        + " suggests the preferred message command (Chat.Name_Click_Prefer).");
            }
            migrationsOk &= fixAntiRepeatContexts(plugin);
        }

        // messages.yml recibe el mismo trato (claves nuevas + Death.Causes una vez en v5).
        migrationsOk &= upgradeMessages(plugin, from);
        if (from < 22) {
            migrationsOk &= upgradeFancySigns(plugin);
        }
        if (from < 30) {
            migrationsOk &= upgradeSpacePurplePalette(plugin);
            migrationsOk &= upgradeStaffModeration(plugin, from);
        }
        if (from < 26) {
            migrationsOk &= addMarriageHoverLine(plugin);
        }
        // v27: el renombrado silver -> chrome es idempotente, pero debe alcanzar a los
        // servidores en 22..26 (que tenian el degradado de fabrica). Gatearlo con from < 22
        // lo saltaba justo en esa franja, sellandolos a v27 con el 'silver' viejo intacto.
        if (from < 27) {
            migrationsOk &= upgradeChromeGradient(plugin);
        }
        if (from < 28) {
            migrationsOk &= recolourRankHover(plugin);
        }
        // v29: la fila del matrimonio del hover. Va en su PROPIA version, no junto a
        // recolourRankHover (v28), a proposito: cuando se añadio ya habia servidores —el de
        // pruebas incluido— sellados en 28 por un build anterior que solo traia la mitad del
        // rediseño (chat.yml migrado, players.yml no). Gatearla en from < 28 la habria
        // saltado justo en ellos, que son los que la necesitan. Es idempotente: si el tag ya
        // esta al dia, no cambia nada.
        if (from < 29) {
            migrationsOk &= recolourMarriageTag(plugin);
            migrationsOk &= fixMismatchedColorTags(plugin);
        }
        if (from < 23) {
            migrationsOk &= rewriteFormatFiles(plugin, "Message: \"{rank_color}{player_nick}\"",
                    "Message: \"<reset>{rank_nick}\"").success();
            migrationsOk &= rewriteFormatFiles(plugin, "{rank_color}{player_nick}",
                    "{rank_nick}").success();
            migrationsOk &= upgradeLangV23(plugin);
        }

        if (!migrationsOk) {
            plugin.getLogger().warning("Configuration migration was incomplete; Config_Version"
                    + " was not advanced and the migration will retry next startup.");
            return 0;
        }
        YamlConfiguration user = loadUser(plugin, file);
        if (user == null) return 0;
        List<String> added = new ArrayList<>();
        mergeMissing(defaults, user, added);
        migrate(defaults, user, added, from);
        // Los archivos por dominio (players.yml, chat.yml...) tambien reciben las claves
        // nuevas: antes solo se completaba config.yml, y una feature cuya opcion vivia en un
        // split file quedaba invisible para quien ya tenia el archivo generado.
        int splitAdded = mergeMissingIntoSplitFiles(plugin);
        // Despues del merge y no dentro de el: los tramos estan protegidos como coleccion, asi
        // que el merge generico ya no puede llevarlos y hace falta sembrarlos una vez.
        RewriteResult seeded = seedPunishmentTiers(plugin, from);
        splitAdded += seeded.changed();
        if (!seeded.success()) {
            plugin.getLogger().warning("Discord punishment tiers could not be seeded;"
                    + " Config_Version was not advanced and the migration will retry next"
                    + " startup.");
        }

        if (added.isEmpty() && splitAdded == 0
                && (from == CURRENT_VERSION || !seeded.success())) {
            return 0;
        }
        // La version solo se sella si TODO lo que dependia de ella llego a escribirse. El
        // resto de claves nuevas si se guardan: son idempotentes y no dependen de la version.
        if (seeded.success()) {
            user.set("Config_Version", CURRENT_VERSION);
        }
        try {
            YamlFiles.saveAtomic(user, file);
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not update config.yml: " + ex.getMessage());
            return 0;
        }
        if (!added.isEmpty()) {
            plugin.getLogger().info("config.yml updated: " + added.size()
                    + " new setting(s) added with safe defaults. New sections keep their"
                    + " documentation in the bundled config; see /cht doctor if in doubt.");
        }
        return added.size();
    }

    /**
     * Completa los archivos DIVIDIDOS por dominio (players.yml, chat.yml, moderation.yml...)
     * con las claves que traiga el jar y ellos no tengan, igual que ya se hacia con
     * config.yml.
     *
     * <p>Sin esto, una opcion nueva que viviera en un split file solo existia para
     * instalaciones nuevas: el archivo del usuario ya estaba creado y nunca se tocaba. En
     * runtime la clave se resolvia por los defaults del jar, asi que el sintoma no era un
     * error sino algo peor — la opcion no aparecia en el archivo (nadie sabia que existia) y
     * cualquier lectura con un default explicito distinto se comia el valor de fabrica.
     *
     * @return cuantas claves se añadieron en total
     */
    static int mergeMissingIntoSplitFiles(JavaPlugin plugin) {
        int total = 0;
        // Antes del merge: si este añadiera Perks.Storage con sus valores de fabrica, las
        // filas que el dueño tenia en el antiguo Perks.Bank quedarian tapadas por un 3.
        carryMarriageStorage(plugin);
        for (String name : ConfigFiles.LAYOUT.keySet()) {
            File target = new File(plugin.getDataFolder(), name);
            if (!target.exists()) continue; // aun sin generar: ensureSplit lo escribira entero
            YamlConfiguration jar;
            try (var resource = plugin.getResource(name)) {
                if (resource == null) continue;
                jar = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(resource, StandardCharsets.UTF_8));
            } catch (Exception ex) {
                continue;
            }
            YamlConfiguration current = loadUser(plugin, target);
            if (current == null) continue; // archivo invalido: ConfigDoctor ya avisa de eso
            List<String> addedHere = new ArrayList<>();
            mergeMissing(jar, current, addedHere);
            if (addedHere.isEmpty()) continue;
            try {
                YamlFiles.saveAtomic(current, target);
            } catch (Exception ex) {
                plugin.getLogger().warning("Could not update " + name + ": " + ex.getMessage());
                continue;
            }
            total += addedHere.size();
            plugin.getLogger().info(name + " updated: " + addedHere.size()
                    + " new setting(s) added with safe defaults.");
        }
        return total;
    }

    /**
     * El cofre del matrimonio se llamaba {@code Perks.Bank}; ahora es {@code Perks.Storage} y
     * {@code Perks.Bank} es la cuenta de dinero. Quien ya tenia el archivo conserva lo que
     * eligio para el cofre (encendido y filas). Idempotente: con Storage presente no hace nada.
     */
    static void carryMarriageStorage(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "players.yml");
        if (!file.exists()) return;
        YamlConfiguration user = loadUser(plugin, file);
        if (user == null || !carryMarriageStorage(user)) return;
        try {
            YamlFiles.saveAtomic(user, file);
            plugin.getLogger().info("players.yml updated: the marriage chest settings moved from"
                    + " Marriage.Perks.Bank to Marriage.Perks.Storage (/marry storage). /marry bank"
                    + " is now the shared money account. No stored items were touched.");
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not update players.yml: " + ex.getMessage());
        }
    }

    /** Nucleo testeable de {@link #carryMarriageStorage(JavaPlugin)}: true si cambio algo. */
    static boolean carryMarriageStorage(ConfigurationSection user) {
        String old = "Marriage.Perks.Bank";
        String now = "Marriage.Perks.Storage";
        if (user.isConfigurationSection(now) || !user.contains(old + ".Rows", true)) return false;
        user.set(now + ".Enabled", user.getBoolean(old + ".Enabled", true));
        user.set(now + ".Rows", user.getInt(old + ".Rows", 3));
        // Rows ya no pertenece al banco de dinero: dejarla ahi solo confundiria.
        user.set(old + ".Rows", null);
        return true;
    }

    /** Los tramos de castigo, que son coleccion del dueño y por eso se siembran aparte. */
    static final List<String> PUNISHMENT_TIER_PATHS = List.of(
            "Discord.Punishment_Roles.Ban.Tiers",
            "Discord.Punishment_Roles.Freeze.Tiers",
            "Discord.Punishment_Roles.Mute.Tiers");

    /**
     * v36: lleva los tramos de fabrica a quien ya tenia integrations.yml, una sola vez.
     *
     * <p>Protegerlos como coleccion del dueño arregla que un tramo borrado volviera solo, pero
     * deja el otro lado sin resolver: el merge generico ya no puede llevarlos, asi que un
     * servidor que actualiza se quedaria sin ninguno. Y sin tramos la feature no falla — se
     * queda muda: {@code match} no encuentra ninguno, nadie recibe rol nunca, y el dueño solo
     * ve que enciende {@code Punishment_Roles.Enabled} y no pasa nada.
     *
     * <p>Distingue "no habia nada que sembrar" de "no se pudo sembrar", y esa diferencia es la
     * que decide si {@code Config_Version} avanza: la siembra va gateada por version, asi que
     * sellar la 36 sin haberla escrito no la retrasa — la cancela para siempre.
     *
     * @return cuantas claves se sembraron, y si el intento llego a completarse.
     */
    private static RewriteResult seedPunishmentTiers(JavaPlugin plugin, int from) {
        if (from >= CURRENT_VERSION) return new RewriteResult(0, true);
        File target = new File(plugin.getDataFolder(), "integrations.yml");
        // Aun sin generar: ensureSplit lo escribira entero, con sus tramos. Nada que sembrar,
        // y nada que reintentar tampoco.
        if (!target.exists()) return new RewriteResult(0, true);
        YamlConfiguration jar;
        try (var resource = plugin.getResource("integrations.yml")) {
            if (resource == null) return new RewriteResult(0, false);
            jar = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(resource, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return new RewriteResult(0, false);
        }
        YamlConfiguration current = loadUser(plugin, target);
        // Archivo invalido: ConfigDoctor ya avisa de eso. No se sella la version — cuando el
        // dueño arregle el YAML, el siguiente arranque siembra.
        if (current == null) return new RewriteResult(0, false);
        List<String> added = new ArrayList<>();
        for (String path : PUNISHMENT_TIER_PATHS) {
            seedSectionIfAbsent(jar, current, path, added);
        }
        if (added.isEmpty()) return new RewriteResult(0, true);
        try {
            YamlFiles.saveAtomic(current, target);
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not seed Discord punishment tiers: "
                    + ex.getMessage());
            return new RewriteResult(0, false);
        }
        plugin.getLogger().info("integrations.yml updated: default Discord punishment tiers"
                + " added. They are yours from now on — remove one and it stays removed.");
        return new RewriteResult(added.size(), true);
    }

    /**
     * Copia una seccion entera, pero SOLO si el usuario no tiene nada ahi.
     *
     * <p>La diferencia con {@link #mergeSectionMissing} es la que importa: una seccion con algo
     * dentro es del dueño AUNQUE le falten entradas respecto al jar, porque lo que falta es
     * justo lo que decidio quitar. Rellenar hoja por hoja es como volvia el tramo borrado.
     */
    static void seedSectionIfAbsent(ConfigurationSection defaults, ConfigurationSection user,
                                    String path, List<String> added) {
        ConfigurationSection source = defaults.getConfigurationSection(path);
        if (source == null) return;
        ConfigurationSection existing = user.getConfigurationSection(path);
        if (existing != null && !existing.getKeys(false).isEmpty()) return;
        for (String leaf : source.getKeys(true)) {
            if (source.isConfigurationSection(leaf)) continue;
            user.set(path + "." + leaf, source.get(leaf));
            added.add(path + "." + leaf);
        }
    }

    /** Copia claves hoja que falten (fuera de las colecciones del usuario). Testeable. */
    static void mergeMissing(ConfigurationSection defaults, ConfigurationSection user,
                             List<String> added) {
        mergeMissing(defaults, user, added, USER_COLLECTIONS);
    }

    static void mergeMissing(ConfigurationSection defaults, ConfigurationSection user,
                             List<String> added, Set<String> collections) {
        for (String key : defaults.getKeys(true)) {
            if (insideCollection(key, collections)) continue;
            if (defaults.isConfigurationSection(key)) continue; // solo hojas
            if (user.contains(key, true)) continue;
            if (translationOfRewrittenText(defaults, user, key)) continue;
            user.set(key, defaults.get(key));
            added.add(key);
        }
    }

    /**
     * ¿Es una traducción {@code _es} de fábrica que caería al lado de un texto que el dueño ya
     * reescribió?
     *
     * <p>Copiarla parece inofensivo y no lo es: deja el servidor hablando en dos voces, la del
     * dueño para un idioma y la de fábrica para el otro, sin que nadie lo haya decidido. Y se
     * nota justo donde más duele, en lo que lee todo el mundo a la vez — el anuncio de una
     * compra con {@code Audience: GLOBAL}, por ejemplo.
     *
     * <p>Al no copiarla, la clave base cubre los dos idiomas hasta que el dueño escriba su
     * propia traducción, que es lo que él mismo dejó dicho al reescribir el texto.
     */
    private static boolean translationOfRewrittenText(ConfigurationSection defaults,
                                                      ConfigurationSection user, String key) {
        if (!key.endsWith("_es")) return false;
        String base = key.substring(0, key.length() - "_es".length());
        String factory = defaults.getString(base);
        if (factory == null) return false; // no es un par: se copia como cualquier hoja nueva
        String theirs = user.getString(base);
        return theirs != null && !theirs.equals(factory);
    }

    /** Mismo upgrade para messages.yml: hojas nuevas + Death.Causes una sola vez (v5). */
    private static boolean upgradeMessages(JavaPlugin plugin, int from) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) return true;
        var resource = plugin.getResource("messages.yml");
        if (resource == null) return true;
        YamlConfiguration defaults;
        try (InputStreamReader r = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(r);
        } catch (Exception ex) {
            return false;
        }
        YamlConfiguration user = loadUser(plugin, file);
        if (user == null) return false;
        List<String> added = new ArrayList<>();
        mergeMissing(defaults, user, added, MESSAGES_USER_COLLECTIONS);
        if (from < 5) {
            copyCollectionEntry(defaults, user, "Death.Causes", added);
        }
        if (from < 6 && !user.getBoolean("Death.Enabled", true)) {
            // El false previo era el default de fabrica, no una eleccion: se enciende una
            // vez. Si el dueño lo vuelve a apagar, Config_Version ya sera 6 y se respeta.
            user.set("Death.Enabled", true);
            added.add("Death.Enabled");
        }
        if (from < 18) {
            // v18: tiers de ejemplo de las entradas fancy, UNA vez (Tiers es coleccion del
            // usuario: si borra un tier despues, no resucita).
            copyCollectionEntry(defaults, user, "Join.Fancy.Tiers", added);
        }
        if (from < 19) {
            migrateFancyJoinV19(defaults, user, added);
        }
        if (from < 21) {
            migrateFancyJoinV21(defaults, user, added);
        }
        if (from < 23) {
            migrateFancyJoinV23(user, added);
        }
        if (from < 25) {
            migrateFancyJoinV25(user, added);
        }
        if (added.isEmpty()) return true;
        try {
            YamlFiles.saveAtomic(user, file);
            plugin.getLogger().info("messages.yml updated: " + added.size()
                    + " new or migrated server-message setting(s) applied.");
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not update messages.yml: " + ex.getMessage());
            return false;
        }
        return true;
    }

    /** v19: añade aliases de grupo y actualiza solo los textos v18 intactos. */
    static void migrateFancyJoinV19(ConfigurationSection defaults, ConfigurationSection user,
                                   List<String> added) {
        ConfigurationSection defaultTiers = defaults.getConfigurationSection("Join.Fancy.Tiers");
        if (defaultTiers == null) return;
        for (String tier : defaultTiers.getKeys(false)) {
            String base = "Join.Fancy.Tiers." + tier;
            if (!user.isConfigurationSection(base)) continue;
            String groupsPath = base + ".Groups";
            if (!user.contains(groupsPath) && defaults.contains(groupsPath)) {
                user.set(groupsPath, defaults.getStringList(groupsPath));
                added.add(groupsPath);
            }
        }

        replaceFancyDefault(user, defaults, "owner", "Message",
                "<dark_gray>[<gold>*<dark_gray>] <gradient:#FFD700:#FF8800><bold>{player_name}</bold></gradient> <gray>has entered the server",
                added);
        replaceFancyDefault(user, defaults, "owner", "Title",
                "<gold><bold>* {player_name} *", added);
        replaceFancyDefault(user, defaults, "admin", "Message",
                "<dark_gray>[<aqua>*<dark_gray>] <gradient:#7A5CFF:#00E0FF><bold>{player_name}</bold></gradient> <gray>from the staff team is here",
                added);
        replaceFancyDefault(user, defaults, "vip", "Message",
                "<dark_gray>[<yellow>+<dark_gray>] <gradient:#00E0FF:#7A5CFF>{player_name}</gradient> <gray>VIP has arrived",
                added);
    }

    private static void replaceFancyDefault(ConfigurationSection user,
                                            ConfigurationSection defaults,
                                            String tier, String field, String oldValue,
                                            List<String> added) {
        String path = "Join.Fancy.Tiers." + tier + "." + field;
        if (!oldValue.equals(user.getString(path))) return;
        String replacement = defaults.getString(path);
        if (replacement == null || replacement.equals(oldValue)) return;
        user.set(path, replacement);
        added.add(path + " -> rank color");
    }

    /** v21: añade español solo cuando el texto base conserva el default; custom queda intacto. */
    static void migrateFancyJoinV21(ConfigurationSection defaults, ConfigurationSection user,
                                   List<String> added) {
        copyCollectionEntry(defaults, user, "Join.Fancy.Auto_Detect_LuckPerms", added);
        copyLocalizedFancyFields(defaults, user, "Join.Fancy.Auto_Detect_LuckPerms", added);

        ConfigurationSection tiers = defaults.getConfigurationSection("Join.Fancy.Tiers");
        if (tiers == null) return;
        for (String tier : tiers.getKeys(false)) {
            copyLocalizedFancyFields(defaults, user, "Join.Fancy.Tiers." + tier, added);
        }
    }

    private static void copyLocalizedFancyFields(ConfigurationSection defaults,
                                                 ConfigurationSection user, String base,
                                                 List<String> added) {
        if (!user.isConfigurationSection(base)) return;
        for (String field : List.of("Message", "Title", "Subtitle")) {
            String translatedPath = base + "." + field + "_es";
            if (user.contains(translatedPath) || !defaults.contains(translatedPath)) continue;
            String basePath = base + "." + field;
            if (!java.util.Objects.equals(user.getString(basePath), defaults.getString(basePath))) {
                continue;
            }
            user.set(translatedPath, defaults.getString(translatedPath));
            added.add(translatedPath);
        }
    }

    /**
     * v27: el degradado "silver" (#F5F5F5 -> #8C8C8C -> #F5F5F5) era un gris plano y turbio.
     * Pasa a llamarse "chrome" con un barrido blanco -> sombra -> brillo.
     *
     * <p>Solo se renombra si el valor sigue siendo el de fabrica: si el dueno del servidor
     * lo retoco, se respeta su version y se deja como esta. El permiso tambien cambia
     * ({@code chatalyst.gradient.silver} -> {@code .chrome}), asi que se avisa en consola.
     */
    private static boolean upgradeChromeGradient(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "players.yml");
        if (!file.exists()) return true;
        YamlConfiguration user = loadUser(plugin, file);
        if (user == null) return false;
        if (!migrateChromeGradient(user)) return true;
        try {
            YamlFiles.saveAtomic(user, file);
            plugin.getLogger().info("players.yml: degradado 'silver' renovado como 'chrome'. "
                    + "Actualiza el permiso: chatalyst.gradient.silver -> chatalyst.gradient.chrome");
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not update players.yml: " + ex.getMessage());
            return false;
        }
        return true;
    }

    /** El renombrado en si. Devuelve true si toco algo (extraido para poder testearlo). */
    static boolean migrateChromeGradient(YamlConfiguration user) {
        String path = "Chat_Color.Gradients.silver";
        // Solo se renombra el degradado de fabrica: si el dueno lo retoco, es suyo.
        if (!"<gradient:#F5F5F5:#8C8C8C:#F5F5F5>".equals(user.getString(path))) return false;
        // Si el dueño ya tiene un "chrome" propio, ese nombre es suyo: no se le pisa.
        if (user.isSet("Chat_Color.Gradients.chrome")) return false;
        user.set(path, null);
        user.set("Chat_Color.Gradients.chrome", "<gradient:#FFFFFF:#5A5A5A:#E8E8E8>");
        return true;
    }

    /** Adds the player-facing feature to the split players.yml once. */
    private static boolean upgradeFancySigns(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "players.yml");
        var resource = plugin.getResource("players.yml");
        if (!file.exists() || resource == null) return true;
        YamlConfiguration defaults;
        try (InputStreamReader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not read Fancy Signs defaults: " + ex.getMessage());
            return false;
        }

        YamlConfiguration user = loadUser(plugin, file);
        if (user == null) return false;
        List<String> added = new ArrayList<>();
        migrateFancySignsV22(defaults, user, added);
        if (added.isEmpty()) return true;
        try {
            YamlFiles.saveAtomic(user, file);
            plugin.getLogger().info("players.yml updated: Fancy Signs rank perk added.");
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not update players.yml: " + ex.getMessage());
            return false;
        }
        return true;
    }

    /** v22 pure migration: preserve any owner-authored Fancy_Signs tree verbatim. */
    static void migrateFancySignsV22(ConfigurationSection defaults, ConfigurationSection user,
                                    List<String> added) {
        copyCollectionEntry(defaults, user, "Fancy_Signs", added);
    }

    /**
     * v30: migra una sola vez los acentos heredados de archivos generados. Se excluye
     * players.yml porque allí viven paletas elegibles cuyo propósito es ofrecer otros colores.
     */
    private static boolean upgradeSpacePurplePalette(JavaPlugin plugin) {
        List<File> files = new ArrayList<>();
        File[] root = plugin.getDataFolder().listFiles((dir, name) ->
                name.endsWith(".yml") && !name.equals("players.yml"));
        if (root != null) files.addAll(List.of(root));
        for (String directory : List.of("lang", "formats")) {
            File[] nested = new File(plugin.getDataFolder(), directory)
                    .listFiles((dir, name) -> name.endsWith(".yml"));
            if (nested != null) files.addAll(List.of(nested));
        }
        boolean success = true;
        int changed = 0;
        for (File file : files) {
            try {
                String text = java.nio.file.Files.readString(file.toPath(), StandardCharsets.UTF_8);
                String updated = spacePurplePalette(text);
                if (updated.equals(text)) continue;
                YamlFiles.writeAtomic(file, updated);
                changed++;
            } catch (Exception ex) {
                plugin.getLogger().warning("Could not migrate the space-purple palette in "
                        + file.getName() + ": " + ex.getMessage());
                success = false;
            }
        }
        if (changed > 0) {
            plugin.getLogger().info("Space-purple palette applied to " + changed
                    + " existing configuration/language file(s). Custom text was preserved.");
        }
        return success;
    }

    /** Nucleo puro e idempotente de la migración cromática v30. */
    static String spacePurplePalette(String text) {
        if (text == null || text.isEmpty()) return text;
        return text
                .replace("<gradient:#FF4D6D:#FFC933>{msg}</gradient>",
                        "<gradient:#240046:#7B2CBF>{msg}</gradient>")
                .replace("<gradient:#FFC933:#3DFFA8>{msg}</gradient>",
                        "<gradient:#7B2CBF:#9D4EDD>{msg}</gradient>")
                .replace("<gradient:#3DFFA8:#00E5FF>{msg}</gradient>",
                        "<gradient:#9D4EDD:#C77DFF>{msg}</gradient>")
                .replace("<gradient:#00E5FF:#FF4D6D>{msg}</gradient>",
                        "<gradient:#C77DFF:#E0AAFF>{msg}</gradient>")
                .replace("gradient:#4A0080:#C4009B", "gradient:#240046:#9D4EDD:#E0AAFF")
                .replace("gradient:#5C0099:#A300C9", "gradient:#240046:#9D4EDD:#E0AAFF")
                .replace("#00E5FF", "#B56CFF")
                .replace("#FF2DCF", "#C77DFF")
                .replace("#6C7BFF", "#B56CFF")
                .replace("#FFB627", "#D8B4FE")
                .replace("#FFC933", "#E0AAFF")
                .replace("<#B56CFF><bold>Freeze</bold></blue>",
                        "<#B56CFF><bold>Freeze</bold>")
                .replace("<#B56CFF><bold>{player}</bold></blue>",
                        "<#B56CFF><bold>{player}</bold>");
    }

    /** Adds every new Staff_Moderation leaf to moderation.yml without changing custom values. */
    private static boolean upgradeStaffModeration(JavaPlugin plugin, int from) {
        File file = new File(plugin.getDataFolder(), "moderation.yml");
        var resource = plugin.getResource("moderation.yml");
        if (!file.exists() || resource == null) return true;
        YamlConfiguration defaults;
        try (InputStreamReader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not read staff moderation defaults: " + ex.getMessage());
            return false;
        }
        YamlConfiguration user = loadUser(plugin, file);
        if (user == null) return false;
        List<String> added = new ArrayList<>();
        mergeSectionMissing(defaults, user, "Staff_Moderation", added);
        if (from < 30) migratePremiumModerationV30(defaults, user, added);
        appendMissing(user, "Staff_Security.Commands", List.of(
                "/staff", "/kick", "/ban", "/tempban", "/unban",
                "/freeze", "/unfreeze", "/vanish"), added);
        if (added.isEmpty()) return true;
        try {
            YamlFiles.saveAtomic(user, file);
            plugin.getLogger().info("moderation.yml updated: standalone staff suite added.");
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not update moderation.yml: " + ex.getMessage());
            return false;
        }
        return true;
    }

    /** v30: sustituye solo las pantallas de fábrica anteriores, nunca una personalizada. */
    static void migratePremiumModerationV30(ConfigurationSection defaults,
                                            ConfigurationSection user,
                                            List<String> added) {
        replaceExactList(user, defaults, "Staff_Moderation.Kick.Screen", List.of(
                "<gradient:#FFD54F:#FF8F00><bold>YOU WERE KICKED</bold></gradient>", "",
                "{player}", "<#C0B3D9>Reason <#7B68A0>» <#FBF2FF>{reason}",
                "<#C0B3D9>Staff <#7B68A0>» <#FBF2FF>{staff}"), added);
        replaceExactList(user, defaults, "Staff_Moderation.Kick.Screen_es", List.of(
                "<gradient:#FFD54F:#FF8F00><bold>HAS SIDO EXPULSADO</bold></gradient>", "",
                "{player}", "<#C0B3D9>Motivo <#7B68A0>» <#FBF2FF>{reason}",
                "<#C0B3D9>Staff <#7B68A0>» <#FBF2FF>{staff}"), added);
        replaceExactList(user, defaults, "Staff_Moderation.Ban.Screen", List.of(
                "<#7B68A0>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━",
                "<gradient:#FF1744:#8B0000><bold>⛔ YOU ARE BANNED ⛔</bold></gradient>",
                "<#7B68A0>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", "", "{player}", "",
                "<#C0B3D9>Reason <#7B68A0>» <#FBF2FF>{reason}",
                "<#C0B3D9>Staff <#7B68A0>» <#FBF2FF>{staff}", "",
                "<#C0B3D9>Time left <#7B68A0>» <#E0AAFF>{remaining}",
                "<#C0B3D9>Unbanned on <#7B68A0>» <#B56CFF>{expires}", "",
                "<#7B68A0>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"), added);
        replaceExactList(user, defaults, "Staff_Moderation.Ban.Screen_es", List.of(
                "<#7B68A0>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━",
                "<gradient:#FF1744:#8B0000><bold>⛔ ESTÁS BANEADO ⛔</bold></gradient>",
                "<#7B68A0>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", "", "{player}", "",
                "<#C0B3D9>Motivo <#7B68A0>» <#FBF2FF>{reason}",
                "<#C0B3D9>Staff <#7B68A0>» <#FBF2FF>{staff}", "",
                "<#C0B3D9>Te queda <#7B68A0>» <#E0AAFF>{remaining}",
                "<#C0B3D9>Se levanta el <#7B68A0>» <#B56CFF>{expires}", "",
                "<#7B68A0>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"), added);
        replaceExactList(user, defaults, "Staff_Moderation.Mute.Screen", List.of(
                "<gradient:#FFB300:#FF6F00><bold>YOU WERE MUTED</bold></gradient>", "",
                "{player}", "<#C0B3D9>Duration <#7B68A0>» <#FBF2FF>{duration}",
                "<#C0B3D9>Reason <#7B68A0>» <#FBF2FF>{reason}",
                "<#C0B3D9>Staff <#7B68A0>» <#FBF2FF>{staff}"), added);
        replaceExactList(user, defaults, "Staff_Moderation.Mute.Screen_es", List.of(
                "<gradient:#FFB300:#FF6F00><bold>HAS SIDO SILENCIADO</bold></gradient>", "",
                "{player}", "<#C0B3D9>Duración <#7B68A0>» <#FBF2FF>{duration}",
                "<#C0B3D9>Motivo <#7B68A0>» <#FBF2FF>{reason}",
                "<#C0B3D9>Staff <#7B68A0>» <#FBF2FF>{staff}"), added);
    }

    private static void replaceExactList(ConfigurationSection user, ConfigurationSection defaults,
                                         String path, List<String> previous,
                                         List<String> added) {
        if (!user.getStringList(path).equals(previous)) return;
        user.set(path, defaults.getStringList(path));
        added.add(path + " -> premium v30");
    }

    static void mergeSectionMissing(ConfigurationSection defaults, ConfigurationSection user,
                                    String path, List<String> added) {
        ConfigurationSection section = defaults.getConfigurationSection(path);
        if (section == null) return;
        for (String leaf : section.getKeys(true)) {
            if (section.isConfigurationSection(leaf)) continue;
            String full = path + "." + leaf;
            if (user.contains(full, true)) continue;
            user.set(full, section.get(leaf));
            added.add(full);
        }
    }

    /** Removes only the exact v22 bold defaults; owner-authored fancy join text is untouched. */
    /**
     * v25: la entrada autodetectada imprimia el ID del grupo de LuckPerms ("entro con rango
     * mvpplusplus") porque {player_group} es el nombre interno, no el tag. Se cambia por
     * {rank_tag} (el prefijo con estilo, "[MVP++]", con el nombre del grupo de respaldo).
     * Se reescribe el TOKEN, no el mensaje entero, para respetar textos personalizados.
     */
    static void migrateFancyJoinV25(ConfigurationSection user, List<String> added) {
        for (String field : List.of("Message", "Message_es")) {
            String path = "Join.Fancy.Auto_Detect_LuckPerms." + field;
            String value = user.getString(path);
            if (value == null || !value.contains("{player_group}")) continue;
            // <white> pintaba el id en blanco; el tag trae su propio color del rango.
            String updated = value.replace("<white>{player_group}", "<reset>{rank_tag}")
                    .replace("{player_group}", "{rank_tag}");
            if (updated.equals(value)) continue;
            user.set(path, updated);
            added.add(path + " -> {rank_tag}");
        }
    }

    static void migrateFancyJoinV23(ConfigurationSection user, List<String> added) {
        replaceExact(user, "Join.Fancy.Tiers.owner.Message",
                "<dark_gray>[<gold>*<dark_gray>] <bold>{rank_nick}</bold> <gray>has entered the server",
                "<dark_gray>[<gold>*<dark_gray>] <reset>{rank_nick} <gray>has entered the server", added);
        replaceExact(user, "Join.Fancy.Tiers.owner.Message_es",
                "<dark_gray>[<gold>*<dark_gray>] <bold>{rank_nick}</bold> <gray>ha entrado al servidor",
                "<dark_gray>[<gold>*<dark_gray>] <reset>{rank_nick} <gray>ha entrado al servidor", added);
        replaceExact(user, "Join.Fancy.Tiers.owner.Title",
                "<gold><bold>* {rank_nick}<gold> *",
                "<gold>* <reset>{rank_nick}<gold> *", added);
        replaceExact(user, "Join.Fancy.Tiers.admin.Message",
                "<dark_gray>[<aqua>*<dark_gray>] <bold>{rank_nick}</bold> <gray>from the staff team is here",
                "<dark_gray>[<aqua>*<dark_gray>] <reset>{rank_nick} <gray>from the staff team is here", added);
        replaceExact(user, "Join.Fancy.Tiers.admin.Message_es",
                "<dark_gray>[<aqua>*<dark_gray>] <bold>{rank_nick}</bold> <gray>del equipo de staff esta aqui",
                "<dark_gray>[<aqua>*<dark_gray>] <reset>{rank_nick} <gray>del equipo de staff esta aqui", added);
    }

    private static void replaceExact(ConfigurationSection user, String path, String oldValue,
                                     String newValue, List<String> added) {
        if (!oldValue.equals(user.getString(path))) return;
        user.set(path, newValue);
        added.add(path + " -> no forced bold");
    }

    /** Updates the old built-in kick line in generated language files, preserving customs. */
    private static boolean upgradeLangV23(JavaPlugin plugin) {
        boolean english = migrateLangKick(plugin, "en_US.yml",
                "<white>{player_name} <gray>was kicked",
                "<dark_gray>[<yellow>!<dark_gray>] <yellow><bold>{player_name}</bold> <gray>was kicked");
        boolean spanish = migrateLangKick(plugin, "es_ES.yml",
                "<white>{player_name} <gray>fue expulsado",
                "<dark_gray>[<yellow>!<dark_gray>] <yellow><bold>{player_name}</bold> <gray>fue expulsado");
        return english && spanish;
    }

    private static boolean migrateLangKick(JavaPlugin plugin, String name, String oldValue,
                                         String newValue) {
        File file = new File(new File(plugin.getDataFolder(), "lang"), name);
        if (!file.exists()) return true;
        YamlConfiguration yaml = loadUser(plugin, file);
        if (yaml == null) return false;
        if (!oldValue.equals(yaml.getString("server-messages.kick"))) return true;
        yaml.set("server-messages.kick", newValue);
        try {
            YamlFiles.saveAtomic(yaml, file);
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not update lang/" + name + ": " + ex.getMessage());
            return false;
        }
        return true;
    }

    /**
     * Migraciones puntuales por version (cosas que el merge generico no debe hacer solo).
     * {@code from} es la version ORIGINAL del archivo, capturada antes del merge.
     */
    static void migrate(ConfigurationSection defaults, ConfigurationSection user,
                        List<String> added, int from) {
        if (from < 3) {
            // v2/v3: canales de mercado. Se añaden UNA vez; si el dueño los borra despues,
            // no vuelven (Config_Version ya sera 3).
            copyCollectionEntry(defaults, user, "Channels.List.trade", added);
            copyCollectionEntry(defaults, user, "Channels.List.auction", added);
        }
        if (from < 8) {
            // v8: el chat local deja de ser proximidad por bloques (RANGED) y pasa a radio por
            // FREQUENCY (los jugadores sintonizan su frecuencia con /frequency). Solo se migra
            // si sigue siendo el RANGED de fabrica; un local personalizado por el dueño se
            // respeta. El bloque Channels.Frequency y el permiso nuevos los añade el merge.
            String localType = user.getString("Channels.List.local.Type", "");
            if ("RANGED".equalsIgnoreCase(localType)) {
                user.set("Channels.List.local.Type", "FREQUENCY");
                user.set("Channels.List.local.Range", null); // ya no aplica
                added.add("Channels.List.local.Type -> FREQUENCY");
            }
        }
        if (from < 12 && user.isConfigurationSection("Channels.List.staff")
                && !user.getBoolean("Channels.List.staff.Proxy", false)) {
            user.set("Channels.List.staff.Proxy", true);
            added.add("Channels.List.staff.Proxy");
        }
        if (from < 13 && user.getBoolean("Staff_Mentions.Require_At", true)) {
            user.set("Staff_Mentions.Require_At", false);
            added.add("Staff_Mentions.Require_At -> false");
        }
        if (from < 14) {
            copyCollectionEntry(defaults, user, "Staff_Mentions.Priority.Tiers", added);
            appendMissing(user, "Staff_Mentions.Groups", List.of("staff", "equipo"), added);
            appendMissing(user, "Staff_Mentions.Help_Intent_Words", List.of("alguien"), added);
        }
        if (from < 9) {
            copyMissingChannelFlag(defaults, user, "trade", added);
            copyMissingChannelFlag(defaults, user, "auction", added);
        }
        if (from < 10) {
            copyMissingChannelOption(defaults, user, "trade",
                    "Auto_Enable_With_Market_Plugins", true, added);
            copyMissingChannelOption(defaults, user, "auction",
                    "Auto_Enable_With_Market_Plugins", true, added);
        }
    }

    private static void copyMissingChannelFlag(ConfigurationSection defaults,
                                               ConfigurationSection user, String channel,
                                               List<String> added) {
        copyMissingChannelOption(defaults, user, channel, "Enabled", false, added);
    }

    private static void copyMissingChannelOption(ConfigurationSection defaults,
                                                 ConfigurationSection user, String channel,
                                                 String option, boolean fallback,
                                                 List<String> added) {
        String section = "Channels.List." + channel;
        String path = section + "." + option;
        if (!user.isConfigurationSection(section) || user.contains(path)) return;
        user.set(path, defaults.getBoolean(path, fallback));
        added.add(path);
    }

    static void copyCollectionEntry(ConfigurationSection defaults,
                                    ConfigurationSection user, String path,
                                    List<String> added) {
        if (user.contains(path) || !defaults.isConfigurationSection(path)) return;
        ConfigurationSection sec = defaults.getConfigurationSection(path);
        for (String key : sec.getKeys(true)) {
            if (!sec.isConfigurationSection(key)) {
                user.set(path + "." + key, sec.get(key));
            }
        }
        added.add(path);
    }

    private static void appendMissing(ConfigurationSection user, String path,
            List<String> values, List<String> added) {
        List<String> current = new ArrayList<>(user.getStringList(path));
        boolean changed = false;
        for (String value : values) {
            if (current.stream().noneMatch(value::equalsIgnoreCase)) {
                current.add(value);
                changed = true;
            }
        }
        if (!changed) return;
        user.set(path, current);
        added.add(path);
    }

    /** v4: cambia las barritas por el numero coloreado. Puro y testeable. */
    static String rewritePingTokens(String text) {
        return text.replace("{ping_bars}", "{ping_ms}");
    }

    /** Aplica {@link #rewritePingTokens} a config.yml y formats/*.yml. Devuelve archivos tocados. */
    private static RewriteResult rewritePingTokensInFiles(JavaPlugin plugin) {
        List<File> files = new ArrayList<>();
        files.add(new File(plugin.getDataFolder(), "config.yml"));
        files.addAll(formatFiles(plugin));
        int changed = 0;
        boolean success = true;
        for (File f : files) {
            try {
                String text = java.nio.file.Files.readString(f.toPath(), StandardCharsets.UTF_8);
                String next = rewritePingTokens(text);
                if (!next.equals(text)) {
                    YamlFiles.writeAtomic(f, next);
                    changed++;
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Could not update " + f.getName() + ": " + ex.getMessage());
                success = false;
            }
        }
        return new RewriteResult(changed, success);
    }

    /** Reemplazo de texto plano en formats/*.yml (conserva comentarios). Devuelve tocados. */
    /**
     * v26: mete la linea del matrimonio en los hovers de formats/chat.yml, justo detras del
     * ping. Solo chat.yml a proposito: trade.yml y auction.yml comparten esa misma linea de
     * ping, pero el conyuge no pinta nada en el anuncio de una venta.
     */
    static boolean addMarriageHoverLine(JavaPlugin plugin) {
        File file = new File(new File(plugin.getDataFolder(), "formats"), "chat.yml");
        if (!file.exists()) return true;
        try {
            String text = java.nio.file.Files.readString(file.toPath(), StandardCharsets.UTF_8);
            String updated = insertMarriageHoverLine(text);
            if (updated.equals(text)) return true;
            YamlFiles.writeAtomic(file, updated);
            plugin.getLogger().info("formats/chat.yml updated: the marriage hover line"
                    + " ({married_tag}) was added; it stays invisible until Marriage.Enabled"
                    + " is turned on in players.yml.");
            return true;
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not add the marriage hover line: " + ex.getMessage());
            return false;
        }
    }

    /** Nucleo testeable de la v26: idempotente (no duplica la linea si ya esta). */
    static String insertMarriageHoverLine(String text) {
        if (text.contains("{married_tag}")) return text;
        String ping = "      - \"<gray>{$ping} <dark_gray>» {ping_ms} <dark_gray>· {ping_quality}\"";
        if (!text.contains(ping)) return text; // hover personalizado: no se toca
        // El salto se toma del PROPIO archivo, no de System.lineSeparator(): en Windows ese
        // seria \r\n y dejaria finales de linea mezclados dentro de un archivo escrito con \n.
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        return text.replace(ping, ping + eol
                + "      # Marriage (opt-in): vanishes completely for single players."
                + eol + "      - \"{married_tag}\"");
    }

    /**
     * v28: tiñe el hover del nombre en formats/chat.yml por rango. Los divisores pasan a
     * {rank_gradient} (neon derivado del color primario del rango) y los "»" a {rank_color}.
     * Solo chat.yml: la barra divisora tambien vive en trade.yml/auction.yml, donde
     * {rank_gradient} no tendria contexto de jugador.
     */
    static boolean recolourRankHover(JavaPlugin plugin) {
        File file = new File(new File(plugin.getDataFolder(), "formats"), "chat.yml");
        if (!file.exists()) return true;
        try {
            String text = java.nio.file.Files.readString(file.toPath(), StandardCharsets.UTF_8);
            String updated = recolourRankHover(text);
            if (updated.equals(text)) return true;
            YamlFiles.writeAtomic(file, updated);
            plugin.getLogger().info("formats/chat.yml updated: the name hover is now tinted by"
                    + " rank — dividers use {rank_gradient} (an auto neon built from the rank's"
                    + " colour) and the » separators use {rank_color}.");
            return true;
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not recolour the chat hover: " + ex.getMessage());
            return false;
        }
    }

    /**
     * v28 (segunda mitad): la fila del matrimonio del hover. Vive en {@code Marriage.Tag}
     * (players.yml), no en formats/chat.yml, asi que {@link #recolourRankHover} no la
     * alcanzaba — y el merge de claves solo AÑADE las que faltan, nunca reescribe una que
     * ya existe. Resultado: quien ya tenia la clave se quedaba con la unica fila del hover
     * con el separador en morado fijo mientras rango, party y ping pasaban al color del
     * rango. Esta migracion la pone al dia.
     */
    static boolean recolourMarriageTag(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "players.yml");
        if (!file.exists()) return true;
        try {
            String text = java.nio.file.Files.readString(file.toPath(), StandardCharsets.UTF_8);
            String updated = recolourMarriageTag(text);
            if (updated.equals(text)) return true;
            YamlFiles.writeAtomic(file, updated);
            plugin.getLogger().info("players.yml updated: the marriage row of the name hover"
                    + " now uses {rank_color} for its » separator, like the rank, party and"
                    + " ping rows.");
            return true;
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not recolour the marriage tag: " + ex.getMessage());
            return false;
        }
    }

    /**
     * Nucleo testeable: idempotente y conservador. Solo reconoce las plantillas DE FABRICA
     * anteriores (con y sin el corazon, comillas simples, dobles o sin comillas, que es como
     * las tres las escribe snakeyaml al reguardar). Un tag personalizado no coincide con
     * ninguna y se deja intacto.
     */
    static String recolourMarriageTag(String text) {
        String suffix = "{$married} {rank_color}» <#FBF2FF>{partner} <#7B68A0>·"
                + " <#C0B3D9>{since}";
        String[] olds = {
            // Estado de fabrica anterior: separador morado fijo, con el corazon delante.
            "<#FF2DCF>❤ {$married} <#7B68A0>» <#FBF2FF>{partner} <#7B68A0>·"
                    + " <#C0B3D9>{since}",
            // Intermedio: etiqueta ya alineada, separador aun fijo.
            "<#C0B3D9>{$married} <#7B68A0>» <#FBF2FF>{partner} <#7B68A0>·"
                    + " <#C0B3D9>{since}",
            // Intermedio: separador ya por rango, corazon aun presente.
            "<#C0B3D9>{$married} {rank_color}» <#FF2DCF>❤ <#FBF2FF>{partner}"
                    + " <#7B68A0>· <#C0B3D9>{since}",
        };
        for (String old : olds) {
            text = text.replace("Tag: \"" + old + "\"", "Tag: \"<#C0B3D9>" + suffix + "\"")
                    .replace("Tag: '" + old + "'", "Tag: '<#C0B3D9>" + suffix + "'")
                    .replace("Tag: " + old + "\n", "Tag: <#C0B3D9>" + suffix + "\n");
        }
        return text;
    }

    /**
     * v29: los botones y plantillas del spy/registro de cofres abrian con color hex
     * ({@code <#00E5FF>}) pero cerraban con un color NOMBRADO ({@code </aqua>}, {@code </gold>},
     * {@code </green>}, {@code </yellow>}). Como no hay {@code <aqua>} abierto que casen,
     * MiniMessage dejaba esos cierres como TEXTO literal en la linea ("[Ver]</green>"). Se
     * corrige en cada archivo de {@code lang/}.
     */
    static boolean fixMismatchedColorTags(JavaPlugin plugin) {
        File dir = new File(plugin.getDataFolder(), "lang");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files == null) return true;
        boolean ok = true;
        for (File file : files) {
            try {
                String text = java.nio.file.Files.readString(file.toPath(), StandardCharsets.UTF_8);
                String updated = fixMismatchedColorTags(text);
                if (updated.equals(text)) continue;
                YamlFiles.writeAtomic(file, updated);
                plugin.getLogger().info("lang/" + file.getName() + " updated: fixed spy/"
                        + "container buttons that closed a hex colour with a named tag (the"
                        + " </aqua>, </gold>... were showing as literal text).");
            } catch (Exception ex) {
                plugin.getLogger().warning("Could not fix colour tags in lang/" + file.getName()
                        + ": " + ex.getMessage());
                ok = false;
            }
        }
        return ok;
    }

    /**
     * Nucleo testeable: idempotente y conservador. Cada par corrige una subcadena ESPECIFICA
     * del bug (texto de boton + cierre nombrado espurio), asi que un valor personalizado que
     * no contenga exactamente esa subcadena se deja intacto. En los botones se quita el cierre
     * (el color hex termina solo al cerrar el hover/click); donde SIGUE texto, se re-fija el
     * color base de la linea ({@code <#C0B3D9>}) en vez de dejarlo colgando.
     */
    static String fixMismatchedColorTags(String text) {
        String[][] pairs = {
            {"{target}</aqua>{repeat}", "{target}{repeat}"},
            {"[Ir]</gold>", "[Ir]"},
            {"[Go]</gold>", "[Go]"},
            {"[Ver]</green>", "[Ver]"},
            {"[View]</green>", "[View]"},
            {"[TP]</aqua>", "[TP]"},
            {"[ver]</aqua>", "[ver]"},
            {"[show]</aqua>", "[show]"},
            {"[Responder]</yellow>", "[Responder]<#C0B3D9>"},
            {"[Reply]</yellow>", "[Reply]<#C0B3D9>"},
        };
        for (String[] pair : pairs) {
            text = text.replace(pair[0], pair[1]);
        }
        return text;
    }

    /**
     * Nucleo testeable de la v28: idempotente y conservador. Lleva el hover DE FABRICA al
     * nuevo estilo teñido por rango (divisores neon {rank_gradient}, "»" en {rank_color}).
     *
     * <p>Cubre los DOS estados de fabrica anteriores: el del ultimo release publico —divisor
     * {@code <dark_gray>} y colores {@code <gray>}/{@code <dark_gray>}, que NUNCA tuvo su
     * propia migracion hacia el rediseño hex— y el intermedio en hex plano. Un hover
     * personalizado (sin ninguna de las dos barras divisoras conocidas) o uno ya migrado se
     * dejan intactos. Solo chat.yml, asi que trade.yml/auction.yml no se tocan.
     */
    static String recolourRankHover(String text) {
        if (text.contains("{rank_gradient}")) return text; // ya migrado
        String hexDivider =
                "      - \"<gradient:#1A0B2E:#FF2DCF:#1A0B2E><strikethrough>            </strikethrough></gradient>\"";
        String legacyDivider = "      - \"<dark_gray><strikethrough>            </strikethrough>\"";
        // Sin una barra divisora de fabrica conocida es un hover propio del dueño: no se toca.
        if (!text.contains(hexDivider) && !text.contains(legacyDivider)) return text;
        String neonDivider =
                "      - \"{rank_gradient}<strikethrough>            </strikethrough></gradient>\"";
        return text
                // Divisores: hex plano O el <dark_gray> del ultimo release -> neon por rango.
                .replace(hexDivider, neonDivider)
                .replace(legacyDivider, neonDivider)
                // Lineas de datos, variante hex (estado intermedio de desarrollo).
                .replace("      - \"<#C0B3D9>{$rank} <#7B68A0>» {player_prefix}<reset>\"",
                        "      - \"<#C0B3D9>{$rank} {rank_color}» {player_prefix}<reset>\"")
                .replace("      - \"<#C0B3D9>{$party} <#7B68A0>» <#FBF2FF>{party}\"",
                        "      - \"<#C0B3D9>{$party} {rank_color}» <#FBF2FF>{party}\"")
                .replace("      - \"<#C0B3D9>{$ping} <#7B68A0>» {ping_ms} <#7B68A0>· {ping_quality}\"",
                        "      - \"<#C0B3D9>{$ping} {rank_color}» <#7B68A0>{ping_ms} <#7B68A0>· {ping_quality}\"")
                // Lineas de datos, variante <gray> del ultimo release publico -> el mismo
                // resultado hex+neon (asi un servidor real recibe TODO el rediseño de una vez).
                .replace("      - \"<gray>{$rank} <dark_gray>» {player_prefix}<reset>\"",
                        "      - \"<#C0B3D9>{$rank} {rank_color}» {player_prefix}<reset>\"")
                .replace("      - \"<gray>{$party} <dark_gray>» <white>{party}\"",
                        "      - \"<#C0B3D9>{$party} {rank_color}» <#FBF2FF>{party}\"")
                .replace("      - \"<gray>{$ping} <dark_gray>» {ping_ms} <dark_gray>· {ping_quality}\"",
                        "      - \"<#C0B3D9>{$ping} {rank_color}» <#7B68A0>{ping_ms} <#7B68A0>· {ping_quality}\"")
                .replace("      - \"<yellow><i>{$click-msg} {rank_nick}</i>\"",
                        "      - \"<#FFC933><i>{$click-msg} {rank_nick}</i>\"");
    }

    private static RewriteResult rewriteFormatFiles(JavaPlugin plugin, String find, String replace) {
        int changed = 0;
        boolean success = true;
        for (File f : formatFiles(plugin)) {
            try {
                String text = java.nio.file.Files.readString(f.toPath(), StandardCharsets.UTF_8);
                if (!text.contains(find)) continue;
                YamlFiles.writeAtomic(f, text.replace(find, replace));
                changed++;
            } catch (Exception ex) {
                plugin.getLogger().warning("Could not update " + f.getName() + ": " + ex.getMessage());
                success = false;
            }
        }
        return new RewriteResult(changed, success);
    }

    private static List<File> formatFiles(JavaPlugin plugin) {
        File[] formats = new File(plugin.getDataFolder(), "formats")
                .listFiles((dir, name) -> name.endsWith(".yml"));
        return formats == null ? List.of() : List.of(formats);
    }

    /**
     * v7: la regla anti-repeat colapsaba caracteres repetidos TAMBIEN en comandos y rompia
     * nombres de jugador ("ventilador666" -> "ventilador6"). Se le limitan los contextos
     * si el dueño no le habia puesto ninguno (si los personalizo, se respetan).
     */
    private static boolean fixAntiRepeatContexts(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "rules.yml");
        if (!file.exists()) return true;
        YamlConfiguration rules = loadUser(plugin, file);
        if (rules == null) return false;
        if (!rules.isConfigurationSection("Rules.anti-repeat")
                || rules.contains("Rules.anti-repeat.Contexts")) {
            return true;
        }
        rules.set("Rules.anti-repeat.Contexts", List.of("CHAT", "SIGN", "BOOK", "ANVIL", "NICK"));
        try {
            YamlFiles.saveAtomic(rules, file);
            plugin.getLogger().info("rules.yml updated: anti-repeat no longer applies to"
                    + " commands (it was mangling player names in arguments).");
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not update rules.yml: " + ex.getMessage());
            return false;
        }
        return true;
    }

    static boolean insideUserCollection(String key) {
        return insideCollection(key, USER_COLLECTIONS);
    }

    static boolean insideCollection(String key, Set<String> collections) {
        for (String prefix : collections) {
            if (key.equals(prefix) || key.startsWith(prefix + ".")) return true;
        }
        return false;
    }

    private static YamlConfiguration loadDefaults(JavaPlugin plugin) {
        var resource = plugin.getResource("config.yml");
        if (resource == null) return null;
        try (InputStreamReader r = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(r);
        } catch (Exception ex) {
            return null;
        }
    }

    private static YamlConfiguration loadUser(JavaPlugin plugin, File file) {
        try {
            return YamlFiles.load(file);
        } catch (Exception ex) {
            plugin.getLogger().severe("Refusing to migrate invalid " + file.getName() + ": "
                    + ex.getMessage());
            return null;
        }
    }

    private record RewriteResult(int changed, boolean success) {
    }
}
