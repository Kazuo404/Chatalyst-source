package dev.chatalyst.toggle;

import dev.chatalyst.lang.Lang;
import dev.chatalyst.gui.Menu;
import dev.chatalyst.translate.TranslationService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /toggle <pm|announcements|mentions|joinquit|translate> */
public final class ToggleCommand implements CommandExecutor, TabCompleter {

    private final ToggleManager toggles;
    private final TranslationService translation;

    public ToggleCommand(ToggleManager toggles, TranslationService translation) {
        this.toggles = toggles;
        this.translation = translation;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.get(sender, "common.players-only"));
            return true;
        }
        if (args.length < 1) {
            Menu.open(p, new ToggleMenu(toggles, translation));
            return true;
        }
        ToggleType type = ToggleType.from(args[0]);
        if (type == null) {
            p.sendMessage(Lang.get(p, "toggle.unknown"));
            return true;
        }
        if (type == ToggleType.TRANSLATE && !translationAvailable()) {
            p.sendMessage(Lang.get(p, "toggle.translate-unavailable"));
            return true;
        }
        ToggleManager.ToggleResult result = toggles.toggleResult(p, type);
        if (!result.persisted()) {
            p.sendMessage(Lang.get(p, "plugin.storage-unavailable"));
            return true;
        }
        p.sendMessage(Lang.get(p, result.enabled() ? "toggle.enabled" : "toggle.disabled",
                "option", type.id()));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String pre = args[0].toLowerCase(Locale.ROOT);
            for (ToggleType t : ToggleType.values()) {
                if (t == ToggleType.TRANSLATE && !translationAvailable()) continue;
                if (t.id().startsWith(pre)) out.add(t.id());
            }
        }
        return out;
    }

    private boolean translationAvailable() {
        return translation != null && translation.enabled();
    }
}
