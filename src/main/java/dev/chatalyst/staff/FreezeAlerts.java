package dev.chatalyst.staff;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.util.FoliaTasks;
import dev.chatalyst.util.MM;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

/**
 * Aviso en el chat para el staff cuando alguien queda congelado o entra congelado:
 * quien fue, el motivo, y un boton [IR HACIA EL] que teletransporta con /tpto.
 * Cada receptor lo ve en su idioma y en su propia region.
 */
final class FreezeAlerts {

    private FreezeAlerts() {
    }

    static void notifyStaff(StaffModerationModule module, String targetName,
                            String storedReason, String staffName, boolean joined) {
        FoliaTasks.onlinePlayers(module.plugin(), viewer -> {
            if (!StaffAccess.has(viewer, "chatalyst.command.freeze")) return;
            if (viewer.getName().equalsIgnoreCase(targetName)) return;
            // El revisor no necesita el aviso de SU propio /freeze (ya tuvo feedback), pero
            // si su congelado vuelve a entrar debe enterarse: su chat se re-enlaza.
            if (!joined && viewer.getName().equalsIgnoreCase(staffName)) return;
            Component alert = module.formatter().feedback(viewer, joined
                            ? "staff-moderation.freeze-alert-join"
                            : "staff-moderation.freeze-alert",
                    storedReason,
                    "player", MM.escape(targetName), "staff", MM.escape(staffName));
            Component go = Lang.get(viewer, "staff-moderation.freeze-alert-go")
                    .clickEvent(ClickEvent.runCommand("/tpto " + targetName))
                    .hoverEvent(HoverEvent.showText(
                            Lang.get(viewer, "staff-moderation.freeze-alert-go-hover")));
            viewer.sendMessage(alert.append(Component.space()).append(go));
        });
    }
}
