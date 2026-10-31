package dev.chatalyst.discord;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;

/**
 * Mirrors reports, staff chat and security alerts to private Discord channels through
 * Chatalyst's own bot. This deliberately has no webhook fallback, preventing duplicate
 * delivery and keeping every Discord operation under one connection.
 */
public final class StaffNotifier {

    private final JavaPlugin plugin;
    private final DiscordBot bot;

    public StaffNotifier(JavaPlugin plugin, DiscordBot bot) {
        this.plugin = plugin;
        this.bot = bot;
    }

    private boolean on(String toggle) {
        // A priority-only setup configures Priority_Channel_Id alone; requiring Channel_Id
        // there silently swallowed every staff signal.
        boolean anyChannel = !plugin.getConfig().getString(
                        "Discord.Staff.Channel_Id", "").isBlank()
                || !plugin.getConfig().getString(
                        "Discord.Staff.Priority_Channel_Id", "").isBlank();
        return plugin.getConfig().getBoolean("Discord.Enabled", false)
                && plugin.getConfig().getBoolean("Discord.Staff.Enabled", false)
                && plugin.getConfig().getBoolean("Discord.Staff." + toggle, true)
                && anyChannel;
    }

    /** Rojo de la casa para lo que pide atencion. */
    private static final int COLOR_REPORT = 0xFF4D6D;

    public void reportCreated(int id, String reporter, String target, String reason) {
        reportCreated(id, reporter, target, reason, null, null);
    }

    /**
     * @param reporterUuid who filed it, and {@code targetUuid} who was reported. Both may be
     *        null for a legacy report that never stored one; the card then just says so.
     */
    public void reportCreated(int id, String reporter, String target, String reason,
                              String reporterUuid, String targetUuid) {
        if (!on("Reports")) return;
        // El texto del jugador va dentro de un bloque de codigo: un motivo sin escapar podria
        // cerrar el formato y falsificar campos dentro de la tarjeta.
        DiscordEmbed card = DiscordEmbed.titled("Report #" + id, COLOR_REPORT)
                .field("Reported player", code(target) + linkNote(targetUuid), true)
                .field("By", code(reporter) + linkNote(reporterUuid), true)
                .field("Status", "`OPEN`", true)
                .field("Reason", code(reason), false)
                .footer("Chatalyst reports · #" + id)
                .build();
        // Los botones hacen lo mismo que !claim, !reply y compañia, asi que dependen del
        // mismo interruptor: si Handle_From_Discord esta apagado, se envia la tarjeta sin
        // ellos en vez de enseñar botones que van a contestar que no.
        bot.sendStaffEmbed(card, buttonsEnabled() ? List.of(
                DiscordButton.forReport("claim", id, "Claim",
                        DiscordButton.Style.PRIMARY),
                DiscordButton.forReport("reply", id, "Reply",
                        DiscordButton.Style.SECONDARY),
                DiscordButton.forReport("resolve", id, "Resolve",
                        DiscordButton.Style.SUCCESS),
                DiscordButton.forReport("reject", id, "Reject",
                        DiscordButton.Style.DANGER)) : List.of(), false);
    }

    /**
     * Whether this account is linked, said on the same line as the name.
     *
     * <p>Staff need it to know what they can actually do from here. A linked account can be
     * mentioned, DM'd about the outcome, and carries the ban and punishment roles across to
     * Discord; an unlinked one cannot be reached here at all, and a report about them has to
     * be handled in game. Guessing which is which by searching the member list by hand is
     * exactly the friction this card exists to remove.
     *
     * <p>The mention is rendered, not the raw ID, so it is one click to the profile. Nobody
     * is pinged by it: every message this bot sends suppresses mentions.
     */
    static String linkNote(String uuid, java.util.function.Function<UUID, String> lookup) {
        UUID id = parseUuid(uuid);
        if (id == null) return "\nlink unknown";
        String discordId = lookup.apply(id);
        return discordId == null ? "\n✖ not linked" : "\n✔ <@" + discordId + ">";
    }

    private String linkNote(String uuid) {
        DiscordLinkManager links = plugin instanceof dev.chatalyst.ChatalystPlugin chatalyst
                ? chatalyst.getDiscordLinks() : null;
        if (links == null) return "";
        return linkNote(uuid, links::discordId);
    }

    static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private boolean buttonsEnabled() {
        return plugin.getConfig().getBoolean("Discord.Staff.Handle_From_Discord", false);
    }

    /**
     * Texto de jugador dentro de un bloque, con los backticks propios neutralizados para que
     * nadie cierre el bloque y escriba fuera de el.
     */
    private static String code(String value) {
        String flat = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return flat.isEmpty() ? "`—`" : "`" + flat.replace("`", "'") + "`";
    }

    public void reportUpdate(String plainText, boolean priority) {
        if (on("Reports")) bot.sendStaffMessage(plainText, priority);
    }

    public void staffChat(String sender, String message) {
        if (on("Staff_Chat")) {
            bot.sendStaffMessage("**Staff Chat** — " + DiscordBot.escapeMarkdown(sender)
                    + ": " + DiscordBot.escapeMarkdown(message), false);
        }
    }

    public void alert(String plainText) {
        if (on("Alerts")) bot.sendStaffMessage("**Alert** — " + plainText, false);
    }
}
