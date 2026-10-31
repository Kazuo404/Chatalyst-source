package dev.chatalyst.staff;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.module.Module;
import org.bukkit.command.CommandSender;

/** Lifecycle boundary for the complete standalone staff and punishment system. */
public final class StaffModerationModule implements Module {

    private final ChatalystPlugin plugin;
    private ModerationFormatter formatter;
    private BanManager bans;
    private FreezeManager freezes;
    private VanishManager vanish;
    private StaffModeManager staffMode;
    private FreezeNotice freezeNotice;
    private FreezeDisconnectGuard disconnectGuard;
    private StaffCommandRegistrar commands;
    private ModerationPrompt reasonPrompt;
    private InventoryEditSession inventoryEditor;
    private HistoryLog history;
    private StaffNotes notes;
    private AltTracker alts;
    private IpBanManager ipBans;
    private ContainerInspectMode chestView;
    private PunishmentEscalation escalation;
    /** 2026-10-03: funciones (staff/tools y staff/lookup). */
    private dev.chatalyst.staff.tools.LockdownManager lockdown;

    public StaffModerationModule(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "staff moderation";
    }

    @Override
    public void enable() {
        formatter = new ModerationFormatter(plugin);
        bans = new BanManager(plugin.getStorageManager(), formatter);
        bans.load();
        bans.enforceRemoteBans(plugin);
        freezes = new FreezeManager(plugin.getStorageManager());
        freezes.load();
        // Tras un reinicio/reload con congelados aun conectados, la inmovilidad se reaplica.
        for (org.bukkit.entity.Player online : plugin.getServer().getOnlinePlayers()) {
            if (freezes.isFrozen(online.getUniqueId())
                    && !(online.isOp() && StaffAccess.exemptsOps())) {
                FreezeHold.apply(plugin, online);
            }
        }
        vanish = new VanishManager(plugin, plugin.getStorageManager());
        vanish.load();
        staffMode = new StaffModeManager(plugin, plugin.getStorageManager(), vanish);
        staffMode.load();
        freezeNotice = new FreezeNotice(plugin, formatter);
        disconnectGuard = new FreezeDisconnectGuard(this);

        plugin.getServer().getPluginManager().registerEvents(bans, plugin);
        plugin.getServer().getPluginManager().registerEvents(vanish, plugin);
        plugin.getServer().getPluginManager().registerEvents(new FreezeListener(this), plugin);
        plugin.getServer().getPluginManager().registerEvents(
                new StaffModeListener(this, staffMode), plugin);
        plugin.getServer().getPluginManager().registerEvents(
                new VanishObserverListener(this), plugin);

        chestView = new ContainerInspectMode(this);
        plugin.getServer().getPluginManager().registerEvents(chestView, plugin);
        // Antes del enrutador: /lockdown recibe la instancia al construirse.
        lockdown = new dev.chatalyst.staff.tools.LockdownManager(this,
                plugin.getStorageManager().store("staff_lockdown"));
        plugin.getServer().getPluginManager().registerEvents(lockdown, plugin);
        commands = new StaffCommandRegistrar(plugin, new StaffCommand(this));
        plugin.getServer().getPluginManager().registerEvents(commands, plugin);
        reasonPrompt = new ModerationPrompt(this);
        plugin.getServer().getPluginManager().registerEvents(reasonPrompt, plugin);
        inventoryEditor = new InventoryEditSession(this);
        plugin.getServer().getPluginManager().registerEvents(inventoryEditor, plugin);
        history = new HistoryLog(plugin.getStorageManager());
        notes = new StaffNotes(plugin.getStorageManager());
        alts = new AltTracker(plugin, plugin.getStorageManager());
        plugin.getServer().getPluginManager().registerEvents(alts, plugin);
        // Una vez por servidor: las conexiones de los logs viejos y de Essentials entran al
        // registro, para que /dupeip conozca tambien a quien no ha vuelto desde entonces.
        if (plugin.getConfig().getBoolean("Staff_Moderation.Import_Old_Connections", true)) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                try {
                    AltImport.Result r = alts.importHistory(false);
                    if (r != null) {
                        plugin.getLogger().info("Imported old connections for /dupeip: " + r.logins()
                                + " logins from " + r.files() + " logs, " + r.accounts() + " accounts, "
                                + r.connections() + " connections.");
                    }
                } catch (RuntimeException failed) {
                    plugin.getLogger().warning("Could not import old connections: " + failed);
                }
            });
        }
        // /ipmute: las cuentas que entran por una conexion silenciada quedan silenciadas.
        plugin.getServer().getPluginManager().registerEvents(
                new dev.chatalyst.staff.lookup.IpMuteListener(this), plugin);
        ipBans = new IpBanManager(this, plugin.getStorageManager());
        ipBans.enforceRemoteBans();
        plugin.getServer().getPluginManager().registerEvents(ipBans, plugin);
        escalation = new PunishmentEscalation(this);
        StaffAccess.applySeeVanishedDefault(plugin);
        if (enabled()) commands.register();
    }

    @Override
    public void reload() {
        formatter.reload();
        StaffAccess.applySeeVanishedDefault(plugin);
        boolean on = enabled();
        // Antes de soltar los comandos: quien este en modo staff recupera su inventario. Si
        // se hiciera despues, /staff off ya no existiria y quedarian atrapados con el kit.
        if (!on) staffMode.exitAll();
        commands.apply(on);
    }

    /** Relee las caches persistentes despues de un cambio exitoso de conexion SQL. */
    public void reloadPersistentState() {
        java.util.Set<java.util.UUID> previouslyFrozen = freezes.active().keySet();
        bans.load();
        freezes.load();
        vanish.load();
        staffMode.load();
        dev.chatalyst.util.FoliaTasks.onlinePlayers(plugin, online -> {
            java.util.UUID id = online.getUniqueId();
            if (freezes.isFrozen(id) && !(online.isOp() && StaffAccess.exemptsOps())) {
                FreezeHold.apply(plugin, online);
            } else if (previouslyFrozen.contains(id)) {
                FreezeHold.release(plugin, online);
            }
        });
    }

    @Override
    public void disable() {
        if (alts != null) alts.stopRetention();
        if (staffMode != null) staffMode.shutdown();
        if (vanish != null) vanish.shutdown();
        if (freezes != null) freezes.shutdown();
        if (bans != null) bans.shutdown();
        if (history != null) history.shutdown();
        if (notes != null) notes.shutdown();
        if (alts != null) alts.shutdown();
        if (ipBans != null) ipBans.shutdown();
    }

    public boolean enabled() {
        // Opt-in: apagado de fabrica para no competir con el plugin de staff del servidor.
        return plugin.getConfig().getBoolean("Staff_Moderation.Enabled", false);
    }

    public ChatalystPlugin plugin() {
        return plugin;
    }

    public dev.chatalyst.staff.tools.LockdownManager lockdown() {
        return lockdown;
    }

    public ModerationFormatter formatter() {
        return formatter;
    }

    public BanManager bans() {
        return bans;
    }

    public FreezeManager freezes() {
        return freezes;
    }

    public VanishManager vanish() {
        return vanish;
    }

    public StaffModeManager staffMode() {
        return staffMode;
    }

    public FreezeNotice freezeNotice() {
        return freezeNotice;
    }

    FreezeDisconnectGuard disconnectGuard() {
        return disconnectGuard;
    }

    ModerationPrompt reasonPrompt() {
        return reasonPrompt;
    }

    public InventoryEditSession inventoryEditor() {
        return inventoryEditor;
    }

    public HistoryLog history() {
        return history;
    }

    public StaffNotes notes() {
        return notes;
    }

    public AltTracker alts() {
        return alts;
    }

    ContainerInspectMode chestView() {
        return chestView;
    }

    public IpBanManager ipBans() {
        return ipBans;
    }

    public PunishmentEscalation escalation() {
        return escalation;
    }

    /**
     * Un ban cierra la revisión activa del objetivo: consume el freeze para que el
     * FreezeGuard no re-banee encima al expulsarlo y el chat del revisor se desenlace.
     */
    public void endFreezeForBan(java.util.UUID id) {
        if (freezes != null) freezes.consume(id);
    }

    public void audit(CommandSender actor, String action, String detail) {
        if (plugin.getStaffSecurityListener() != null) {
            plugin.getStaffSecurityListener().audit(actor, action, detail);
        }
    }
}
