package dev.chatalyst.config;

import dev.chatalyst.ChatalystPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Inventario de las features del plugin y su estado (ON/OFF) leido de la config real. Lo usa el
 * banner de arranque (para que un comprador VEA en consola que hay apagado, sin adivinar) y el
 * comando {@code /chatalyst features} (para consultarlo en caliente). Cada feature apunta a su
 * clave de config y al archivo donde vive, asi el aviso dice exactamente que activar y donde.
 */
public final class FeatureReport {

    private FeatureReport() {
    }

    /** Una feature: etiqueta legible, clave de config, archivo donde vive y su default. */
    public record Feature(String label, String configPath, String file, boolean def) {
    }

    /** Estado resuelto de una feature contra la config actual. */
    public record Result(Feature feature, boolean on) {
        public String label() {
            return feature.label();
        }
    }

    // Solo las secciones que se mergean a getConfig() (ver ConfigFiles.LAYOUT) + rules/swear,
    // que se cargan aparte y se resuelven por su manager. Orden = agrupado por archivo.
    /** Package-visible so a test can check every advertised toggle really exists. */
    static final List<Feature> FEATURES = List.of(
            // chat.yml
            new Feature("Chat formatting", "Chat.Enabled", "chat.yml", true),
            new Feature("Channels", "Channels.Enabled", "chat.yml", true),
            new Feature("Mentions", "Mentions.Enabled", "chat.yml", true),
            new Feature("Grammar assist", "Grammar.Enabled", "chat.yml", false),
            new Feature("Tab-complete filter", "Tab_Complete.Enabled", "chat.yml", false),
            new Feature("Message replacements", "Message_Replacements.Enabled", "chat.yml", false),
            new Feature("Server brand (F3)", "Server_Brand.Enabled", "chat.yml", false),
            // moderation.yml
            new Feature("Staff item audit", "Staff_Item_Audit.Enabled", "moderation.yml", false),
            new Feature("Anti-spam", "Anti_Spam.Enabled", "moderation.yml", true),
            new Feature("Anti-bot", "Anti_Bot.Enabled", "moderation.yml", false),
            new Feature("Chat security", "Chat_Security.Enabled", "moderation.yml", true),
            new Feature("Command blocker", "Command_Blocker.Enabled", "moderation.yml", false),
            new Feature("Reports", "Reports.Enabled", "moderation.yml", true),
            new Feature("Staff suite", "Staff_Moderation.Enabled", "moderation.yml", false),
            new Feature("Staff security", "Staff_Security.Enabled", "moderation.yml", false),
            new Feature("Chat log", "Log.Enabled", "moderation.yml", true),
            new Feature("Warning points", "Warning_Points.Enabled", "moderation.yml", true),
            new Feature("Item watch", "Item_Watch.Enabled", "moderation.yml", false),
            // economy.yml
            new Feature("Market", "Market.Enabled", "economy.yml", false),
            new Feature("Trade", "Trade.Enabled", "economy.yml", false),
            new Feature("Balance top", "Balance_Top.Enabled", "economy.yml", true),
            // players.yml
            new Feature("AFK", "Afk.Enabled", "players.yml", true),
            new Feature("Private messages", "Private_Messages.Enabled", "players.yml", false),
            new Feature("Mail", "Mail.Enabled", "players.yml", true),
            new Feature("Item rename", "Item_Name.Enabled", "players.yml", true),
            new Feature("Newcomer welcome", "Newcomer.Enabled", "players.yml", false),
            new Feature("Anonymous mode", "Anonymous.Enabled", "players.yml", true),
            new Feature("Marriage", "Marriage.Enabled", "players.yml", false),
            new Feature("Player profile", "Profile.Enabled", "players.yml", true),
            // bot.yml
            new Feature("Announcer", "Announcer.Enabled", "bot.yml", true),
            // network.yml
            new Feature("Proxy / network", "Proxy.Enabled", "network.yml", false),
            new Feature("Staff mentions", "Staff_Mentions.Enabled", "network.yml", true),
            // nametag.yml (moved out of integrations.yml: these are how a player is drawn,
            // not a third-party hook). The file shown here is what the admin is told to open,
            // so it has to follow the section, or the instruction points at a missing key.
            new Feature("Nametag (classic)", "Nametag.Enabled", "nametag.yml", false),
            new Feature("Nametag (fancy)", "Nametag.Fancy.Enabled", "nametag.yml", false),
            new Feature("Nametag title", "Nametag.Fancy.Title.Enabled", "nametag.yml", false),
            new Feature("Tab list", "Tab_List.Enabled", "nametag.yml", false),

            // integrations.yml
            new Feature("Discord", "Discord.Enabled", "integrations.yml", false),
            new Feature("Discord link required", "Discord.Linking.Required.Enabled",
                    "integrations.yml", false),
            new Feature("Tebex rank delivery", "Tebex.Enabled", "integrations.yml", false),
            new Feature("Rank storefront", "Tebex.Shop.Enabled", "integrations.yml", false),
            new Feature("Proximity voice", "Voice.Enabled", "integrations.yml", false),
            new Feature("Dynmap reports", "Dynmap.Reports.Enabled", "integrations.yml", false),
            new Feature("Live translation", "Translation.Enabled", "integrations.yml", false),
            new Feature("Scripting", "Scripting.Enabled", "integrations.yml", true),

            // store.yml
            new Feature("Item store", "Store.Enabled", "store.yml", false),

            // giveaway.yml
            new Feature("Giveaways", "Giveaway.Enabled", "giveaway.yml", true),

            // holograms.yml
            new Feature("Holograms", "Holograms.Enabled", "holograms.yml", true),

            // gifts.yml
            new Feature("Gift boxes", "Gifts.Enabled", "gifts.yml", true),

            // chatgames.yml
            new Feature("Chat quiz", "Chat_Games.Enabled", "chatgames.yml", false),

            // chestlocks.yml
            new Feature("Chest locks", "Chest_Locks.Enabled", "chestlocks.yml", false));

    /** Estado de todas las features (config + rules/swear por manager), en orden. */
    public static List<Result> evaluate(ChatalystPlugin plugin) {
        List<Result> out = new ArrayList<>(FEATURES.size() + 2);
        for (Feature f : FEATURES) {
            out.add(new Result(f, plugin.getConfig().getBoolean(f.configPath(), f.def())));
        }
        // Cargados aparte (no en getConfig): se preguntan a su manager.
        out.add(new Result(new Feature("Chat rules", "Enabled", "rules.yml", true),
                plugin.getRuleEngine() != null && plugin.getRuleEngine().isEnabled()));
        out.add(new Result(new Feature("Swear filter", "Enabled", "swear.yml", true),
                plugin.getSwearFilter() != null && plugin.getSwearFilter().isEnabled()));
        return out;
    }

    /** Solo las apagadas, para el aviso "esto esta off y asi lo enciendes". */
    public static List<Result> disabled(List<Result> all) {
        List<Result> off = new ArrayList<>();
        for (Result r : all) {
            if (!r.on()) off.add(r);
        }
        return off;
    }
}
