package dev.chatalyst.staff;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import dev.chatalyst.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Acumulador de castigos: cruzar {@code Kicks_For_Ban} expulsiones recientes (contadas del
 * historial durable dentro de {@code Kick_Window}) o {@code Warn_Points_For_Ban} puntos de
 * aviso escala a un ban automático. El motivo se persiste como token idioma-neutro
 * ({@code {default:escalation}}), así el baneado y cada staff lo ven en su idioma.
 */
public final class PunishmentEscalation {

    private static final String ROOT = "Staff_Moderation.Escalation.";
    private static final String ACTOR = "Chatalyst";

    private final StaffModerationModule module;

    PunishmentEscalation(StaffModerationModule module) {
        this.module = module;
    }

    private boolean enabled() {
        return module.enabled()
                && module.plugin().getConfig().getBoolean(ROOT + "Enabled", true);
    }

    /** Tras registrar un kick en el historial (corre en la región del objetivo). */
    void afterKick(Player target) {
        if (!enabled()) return;
        // Mismas exenciones que la escalación por avisos: un OP exento o un objetivo con
        // bypass de ban nunca debe recibir el ban automático por acumulación de kicks.
        if (target.isOp() && StaffAccess.exemptsOps()) return;
        if (target.hasPermission("chatalyst.bypass.ban")) return;
        int needed = module.plugin().getConfig().getInt(ROOT + "Kicks_For_Ban", 3);
        if (needed <= 0) return;
        long window = TimeUtil.parseDuration(module.plugin().getConfig()
                .getString(ROOT + "Kick_Window", "30d"));
        boolean countConsole = module.plugin().getConfig()
                .getBoolean(ROOT + "Count_Console_Kicks", false);
        long merge = Math.max(0L, module.plugin().getConfig()
                .getLong(ROOT + "Kick_Merge_Minutes", 10L)) * 60_000L;
        int recent = recentKicks(module.history().list(target.getUniqueId()),
                System.currentTimeMillis(), window, countConsole, merge);
        if (recent >= needed) escalate(target.getUniqueId(), target.getName(), "kicks", recent);
    }

    /** Tras sumar puntos de aviso (puede llegar desde el hilo async del chat). */
    public void onWarnPoints(Player target, int totalPoints) {
        if (!enabled()) return;
        int needed = module.plugin().getConfig().getInt(ROOT + "Warn_Points_For_Ban", 3);
        if (needed <= 0 || totalPoints < needed) return;
        if (target.isOp() && StaffAccess.exemptsOps()) return;
        if (target.hasPermission("chatalyst.bypass.ban")) return;
        escalate(target.getUniqueId(), target.getName(), "warns", totalPoints);
    }

    /** Nombre con el que Bukkit identifica a la consola en {@code CommandSender#getName()}. */
    static final String CONSOLE = "CONSOLE";

    /**
     * Kicks dentro de la ventana Y posteriores al ultimo ban del historial. Un ban (manual,
     * temporal o por escalada) "salda" los kicks previos: asi un unban es borron y cuenta
     * nueva y el siguiente ban solo llega por kicks NUEVOS, no por los que ya se castigaron.
     * Pura para poder probarla sin servidor.
     *
     * <p>{@code countConsole} decide si cuentan los kicks lanzados desde la CONSOLA. Por
     * defecto NO, y el motivo es que casi nunca los escribe una persona: un anticheat que
     * castiga hace {@code Bukkit.dispatchCommand(consola, "kick ...")}, y si Chatalyst posee
     * la etiqueta {@code kick} esa expulsion entra aqui como si la hubiera decidido el staff.
     * El resultado es doble castigo — el anticheat ya tiene su propia escalera — y un ban
     * automatico que nadie recuerda haber puesto. Esta acumulacion existe para castigos del
     * STAFF, que es lo que dice su propio nombre.
     */
    static int recentKicks(List<HistoryLog.Entry> history, long now, long windowMillis,
                           boolean countConsole) {
        return recentKicks(history, now, windowMillis, countConsole, 0L);
    }

    /**
     * Como arriba, pero los kicks del MISMO staff separados menos de {@code mergeMillis} cuentan
     * como uno. Sin esto un solo helper con permiso de kick podia forzar un ban automatico
     * echando tres veces seguidas al mismo jugador en diez segundos: la escalada existe para
     * reincidencia, no para que una persona sola sume tres castigos de golpe.
     */
    static int recentKicks(List<HistoryLog.Entry> history, long now, long windowMillis,
                           boolean countConsole, long mergeMillis) {
        long sinceBan = lastBanTime(history);
        java.util.Map<String, Long> lastCounted = new java.util.HashMap<>();
        int count = 0;
        for (HistoryLog.Entry entry : history) {
            if (!"kick".equals(entry.type())) continue;
            if (entry.time() <= sinceBan) continue; // kick anterior al ultimo ban: ya saldado
            if (!countConsole && CONSOLE.equalsIgnoreCase(entry.staff())) continue;
            if (windowMillis > 0 && windowMillis != TimeUtil.PERMANENT
                    && now - entry.time() > windowMillis) continue;
            String staff = entry.staff() == null ? "" : entry.staff().toLowerCase(java.util.Locale.ROOT);
            Long previous = lastCounted.get(staff);
            if (mergeMillis > 0 && previous != null && Math.abs(entry.time() - previous) < mergeMillis) {
                continue; // mismo staff, casi a la vez: es el mismo episodio
            }
            lastCounted.put(staff, entry.time());
            count++;
        }
        return count;
    }

    /** Momento del ban mas reciente (ban, tempban o escalation-ban), o MIN_VALUE si no hay. */
    static long lastBanTime(List<HistoryLog.Entry> history) {
        long last = Long.MIN_VALUE;
        for (HistoryLog.Entry entry : history) {
            String type = entry.type();
            // banip tambien: banea la cuenta igual, y sin contarlo un unban posterior dejaba
            // vivos los kicks que ya se habian castigado con el.
            if (("ban".equals(type) || "tempban".equals(type) || "escalation-ban".equals(type)
                    || "banip".equals(type))
                    && entry.time() > last) {
                last = entry.time();
            }
        }
        return last;
    }

    /** Peldano de la escalera: sube con cada ban previo, tope en el ultimo valor. Pura. */
    static long ladderStep(List<Long> ladder, int priorEscalationBans) {
        return ladder.get(Math.min(Math.max(priorEscalationBans, 0), ladder.size() - 1));
    }

    private void escalate(UUID targetId, String targetName, String triggerKey, int count) {
        FoliaTasks.global(module.plugin(), () -> {
            if (module.bans().get(targetId) != null) return; // ya está baneado
            // La duracion sube un peldano por cada escalation-ban previo del historial: el
            // reincidente recibe un ban mas severo. Como recentKicks ya no cuenta los kicks
            // anteriores al ultimo ban, un unban es borron y cuenta nueva.
            long duration = banDuration(module.history().list(targetId));
            String reason = ModerationFormatter.escalationToken();
            try {
                module.bans().ban(Bukkit.getOfflinePlayer(targetId), duration, reason, ACTOR);
            } catch (RuntimeException failure) {
                if (module.bans().get(targetId) == null) {
                    module.plugin().getLogger().severe("Could not apply the escalation ban for "
                            + targetName + ": " + failure.getMessage());
                    return;
                }
            }
            module.endFreezeForBan(targetId);
            module.history().record(targetId, "escalation-ban", ACTOR,
                    module.formatter().plainReason(reason)
                            + " (" + count + " " + triggerKey + ")");
            // Que un unban no re-banee al primer aviso siguiente: el contador vuelve a cero.
            if ("warns".equals(triggerKey) && module.plugin().getWarningPoints() != null) {
                module.plugin().getWarningPoints().clear(targetId);
            }

            BanRecord record = module.bans().get(targetId);
            long until = record == null
                    ? TimeUtil.deadline(duration)
                    : record.until();
            Player online = Bukkit.getPlayer(targetId);
            if (online != null) {
                FoliaTasks.entity(module.plugin(), online, current -> current.kick(
                        module.formatter().screen(current, ModerationFormatter.Screen.BAN,
                                current.getName(), ACTOR, until, reason)));
            }

            FoliaTasks.onlinePlayers(module.plugin(),
                    viewer -> StaffAccess.has(viewer, "chatalyst.command.ban"),
                    viewer -> viewer.sendMessage(module.formatter().feedback(viewer,
                            "staff-moderation.escalation-banned", reason,
                            "player", MM.escape(targetName),
                            "trigger", Lang.raw(viewer,
                                    "staff-moderation.escalation-trigger-" + triggerKey,
                                    "count", count))));
            var console = module.plugin().getServer().getConsoleSender();
            console.sendMessage(module.formatter().feedback(console,
                    "staff-moderation.escalation-banned", reason,
                    "player", MM.escape(targetName),
                    "trigger", Lang.raw(console,
                            "staff-moderation.escalation-trigger-" + triggerKey,
                            "count", count)));
            module.audit(console, "escalation ban",
                    targetName + " (" + count + " " + triggerKey + ")");
        });
    }

    /**
     * Duracion del proximo ban por acumulacion: la escalera {@code Ban_Durations} indexada
     * por cuantos escalation-ban ya tiene el jugador (0 -> 1er peldano, 1 -> 2o...). Si no
     * hay escalera valida se cae al valor unico legado {@code Ban_Duration}, y si tampoco,
     * al default de fabrica (7d). Un typo (duracion 0) NUNCA escala a permanente por error.
     */
    private long banDuration(List<HistoryLog.Entry> history) {
        List<Long> ladder = new ArrayList<>();
        for (String step : module.plugin().getConfig().getStringList(ROOT + "Ban_Durations")) {
            long ms = TimeUtil.parseDuration(step);
            if (ms == 0) {
                module.plugin().getLogger().warning("Staff_Moderation.Escalation.Ban_Durations"
                        + " entry '" + step + "' is not a valid duration; ignoring it."
                        + " Use formats like 12h, 7d, 30d or \"perm\".");
                continue;
            }
            ladder.add(ms);
        }
        if (ladder.isEmpty()) {
            long single = TimeUtil.parseDuration(
                    module.plugin().getConfig().getString(ROOT + "Ban_Duration", "7d"));
            ladder.add(single == 0 ? TimeUtil.parseDuration("7d") : single);
        }
        int priorBans = 0;
        for (HistoryLog.Entry entry : history) {
            if ("escalation-ban".equals(entry.type())) priorBans++;
        }
        return ladderStep(ladder, priorBans);
    }
}
