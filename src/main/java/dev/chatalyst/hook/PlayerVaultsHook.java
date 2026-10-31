package dev.chatalyst.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Puerta de entrada a PlayerVaultsX (las "mochilas" /pv). Se enlaza por REFLEXION: PlayerVaultsX
 * no publica artefacto en ningun repositorio estable y solo hacen falta cuatro llamadas, asi que
 * no merece una dependencia de compilacion ni una clase puente. Si el plugin no esta, o su API
 * cambia de forma, todo devuelve "no hay mochilas" y el resto de Chatalyst ni se entera.
 *
 * <p>Probado contra PlayerVaultsX 4.2.x y 4.4.x. Las mochilas viven en
 * {@code plugins/PlayerVaults/newvaults/<uuid>.yml}, una clave {@code vaultN} por mochila.
 *
 * <p>Dos formas de mirar, con garantias distintas:
 * <ul>
 *   <li>{@link #contents} es de SOLO LECTURA y sin efectos: si la mochila esta abierta ahora
 *       mismo (por su dueno o por otro staff) se copia el inventario vivo, que tiene los cambios
 *       aun sin guardar; si no, se lee lo guardado. No registra nada en PlayerVaultsX, asi que no
 *       puede bloquear ni pisar la mochila de nadie.</li>
 *   <li>{@link #openForEdit} delega en el propio {@code /pv <jugador> <n>} de PlayerVaultsX: es
 *       el unico que sabe guardar al cerrar y compartir el inventario con el dueno si lo tiene
 *       abierto a la vez. Reimplementar eso aqui seria pedir items duplicados.</li>
 * </ul>
 *
 * <p>Todas las llamadas van en el hilo principal: PlayerVaultsX no es thread-safe (sus mapas son
 * HashMap) y no corre en Folia.
 */
public final class PlayerVaultsHook {

    /** Nombre con el que se registra en plugin.yml (no "PlayerVaultsX"). */
    static final String PLUGIN_NAME = "PlayerVaults";

    private static Api api;
    /** La instancia del plugin para la que se resolvio {@link #api} (null = sin resolver). */
    private static Plugin resolvedFor;

    private PlayerVaultsHook() {
    }

    /** PlayerVaultsX instalado, habilitado y con la API que esperamos. */
    public static boolean isAvailable() {
        return api() != null;
    }

    /** Olvida la resolucion: tras un reload de plugins se vuelve a buscar. */
    public static void reset() {
        api = null;
        resolvedFor = null;
    }

    /** Numeros de mochila que tiene guardados {@code owner}, ordenados. Vacio si no hay. */
    public static SortedSet<Integer> vaultNumbers(UUID owner) {
        Api a = api();
        if (a == null || owner == null) return Collections.emptySortedSet();
        try {
            // getVaultNumbers crea el .yml si no existe: comprobarlo antes evita que mirar a
            // alguien sin mochilas le deje un archivo vacio.
            if (!a.fileFor(owner).isFile()) return Collections.emptySortedSet();
            Object raw = a.getVaultNumbers.invoke(a.manager(), owner.toString());
            TreeSet<Integer> out = new TreeSet<>();
            if (raw instanceof Collection<?> numbers) {
                for (Object n : numbers) {
                    if (n instanceof Integer i && i > 0) out.add(i);
                }
            }
            return Collections.unmodifiableSortedSet(out);
        } catch (Throwable t) {
            return fail("vaultNumbers", t, Collections.emptySortedSet());
        }
    }

    /**
     * Copia de lo que hay en la mochila {@code number} de {@code owner}, o null si no existe.
     * Los items son clones: modificarlos no toca la mochila.
     */
    public static ItemStack[] contents(UUID owner, int number) {
        Api a = api();
        if (a == null || owner == null || number < 1) return null;
        try {
            Inventory live = a.liveInventory(owner, number);
            if (live != null) return copy(live.getContents());
            if (!vaultNumbers(owner).contains(number)) return null;
            Object stored = a.getVault.invoke(a.manager(), owner.toString(), number);
            return stored instanceof Inventory inv ? copy(inv.getContents()) : null;
        } catch (Throwable t) {
            return fail("contents", t, null);
        }
    }

    /** Si alguien tiene abierta esa mochila ahora mismo (lo que se ve incluye cambios sin guardar). */
    public static boolean isOpenNow(UUID owner, int number) {
        Api a = api();
        if (a == null || owner == null) return false;
        try {
            return a.liveInventory(owner, number) != null;
        } catch (Throwable t) {
            return fail("isOpenNow", t, false);
        }
    }

    /**
     * Abre la mochila REAL para editarla, con la mecanica de PlayerVaultsX (guarda al cerrar).
     * Devuelve false si no se pudo (plugin ausente, mochila inexistente, jugador dormido...);
     * en ese caso PlayerVaultsX ya le ha dicho el motivo al staff con sus propios mensajes.
     */
    public static boolean openForEdit(Player staff, UUID owner, int number) {
        Api a = api();
        if (a == null || staff == null || owner == null || number < 1) return false;
        try {
            Object ok = a.openOtherVault.invoke(null, staff, owner.toString(),
                    String.valueOf(number));
            return Boolean.TRUE.equals(ok);
        } catch (Throwable t) {
            return fail("openForEdit", t, false);
        }
    }

    /**
     * Si {@code owner} puede abrir HOY su mochila {@code number}, segun los permisos que el propio
     * PlayerVaultsX comprueba ({@code playervaults.amount.N}). Solo se sabe de un jugador
     * conectado: los permisos de Bukkit no existen mientras esta fuera, y entonces es false.
     */
    public static boolean canAccess(Player owner, int number) {
        Api a = api();
        if (a == null || owner == null || number < 1 || a.checkPerms == null) return false;
        try {
            return Boolean.TRUE.equals(a.checkPerms.invoke(null, owner, number));
        } catch (Throwable t) {
            return fail("canAccess", t, false);
        }
    }

    private static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] out = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            out[i] = items[i] == null || items[i].getType().isAir() ? null : items[i].clone();
        }
        return out;
    }

    private static <T> T fail(String what, Throwable t, T fallback) {
        Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite
                && ite.getCause() != null ? ite.getCause() : t;
        Plugin self = Bukkit.getPluginManager().getPlugin("Chatalyst");
        if (self != null) {
            self.getLogger().log(Level.WARNING, "PlayerVaultsX " + what + " failed: " + cause);
        }
        return fallback;
    }

    private static Api api() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (plugin == null || !plugin.isEnabled()) return null;
        // Si PlayerVaults se recargo (otra instancia, otro ClassLoader), lo resuelto ya no vale;
        // un fallo tambien se recuerda por instancia para no avisar en cada clic.
        if (resolvedFor == plugin) return api;
        resolvedFor = plugin;
        try {
            api = new Api(plugin);
        } catch (Throwable t) {
            api = null;
            fail("hook (unsupported version " + plugin.getPluginMeta().getVersion() + ")", t, null);
        }
        return api;
    }

    /** Los handles de reflexion, resueltos una vez por instancia del plugin. */
    private static final class Api {
        final Plugin plugin;
        final Method managerInstance;
        final Method getVaultNumbers;
        final Method getVault;
        final Method openOtherVault;
        final Method getOpenInventories;
        final Method getVaultData;
        final Constructor<?> viewInfo;
        /** Opcional: sin el, la lista se queda solo con las mochilas que tienen algo. */
        final Method checkPerms;

        Api(Plugin plugin) throws ReflectiveOperationException {
            this.plugin = plugin;
            ClassLoader loader = plugin.getClass().getClassLoader();
            String base = "com.drtshock.playervaults.vaultmanagement.";
            Class<?> manager = Class.forName(base + "VaultManager", true, loader);
            Class<?> operations = Class.forName(base + "VaultOperations", true, loader);
            Class<?> info = Class.forName(base + "VaultViewInfo", true, loader);
            managerInstance = manager.getMethod("getInstance");
            getVaultNumbers = manager.getMethod("getVaultNumbers", String.class);
            getVault = manager.getMethod("getVault", String.class, int.class);
            openOtherVault = operations.getMethod("openOtherVault",
                    Player.class, String.class, String.class);
            getOpenInventories = plugin.getClass().getMethod("getOpenInventories");
            getVaultData = plugin.getClass().getMethod("getVaultData");
            viewInfo = info.getConstructor(String.class, int.class);
            Method perms;
            try {
                perms = operations.getMethod("checkPerms",
                        org.bukkit.command.CommandSender.class, int.class);
            } catch (NoSuchMethodException older) {
                perms = null;
            }
            checkPerms = perms;
        }

        Object manager() throws ReflectiveOperationException {
            return managerInstance.invoke(null);
        }

        File fileFor(UUID owner) throws ReflectiveOperationException {
            return new File((File) getVaultData.invoke(plugin), owner + ".yml");
        }

        /** El inventario que PlayerVaultsX tiene abierto para esa mochila, o null. */
        Inventory liveInventory(UUID owner, int number) throws ReflectiveOperationException {
            // La clave es VaultViewInfo#toString(); se construye con su propia clase para no
            // depender del formato exacto del texto.
            String key = viewInfo.newInstance(owner.toString(), number).toString();
            Object open = ((Map<?, ?>) getOpenInventories.invoke(plugin)).get(key);
            return open instanceof Inventory inv ? inv : null;
        }
    }
}
