package dev.chatalyst.chatgame;

import dev.chatalyst.command.RuntimeCommandRegistrar;
import dev.chatalyst.util.MM;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * {@code /trivia}: ver la pregunta en el aire; con permiso de admin, lanzar una o cancelarla.
 */
public final class ChatGameCommand implements RuntimeCommandRegistrar.Handler {

    static final String ADMIN = "chatalyst.command.trivia.admin";

    private final ChatGameService games;

    public ChatGameCommand(ChatGameService games) {
        this.games = games;
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        ChatGameSettings s = games.settings();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission(ADMIN);
        switch (sub) {
            case "iniciar", "start" -> {
                if (!admin) return show(sender, s);
                if (games.current().isPresent()) return send(sender, s.message("Busy"));
                String category = args.length > 1 ? args[1] : null;
                if (category != null && s.category(category).isEmpty()) {
                    return send(sender, s.message("Unknown_Category").replace("{category}", MM.escape(category))
                            .replace("{categories}", s.categories().stream().map(ChatGameSettings.Category::id)
                                    .collect(Collectors.joining(", "))));
                }
                return send(sender, s.message(games.launch(category).isPresent() ? "Started" : "Busy"));
            }
            case "saltar", "skip" -> {
                if (!admin) return show(sender, s);
                return send(sender, s.message(games.skip() ? "Skipped" : "None")
                        .replace("{minutes}", Long.toString(Math.max(1, games.millisToNext() / 60_000))));
            }
            default -> {
                if (admin && !sub.isEmpty()) return send(sender, s.message("Usage"));
                return show(sender, s);
            }
        }
    }

    private boolean show(CommandSender sender, ChatGameSettings s) {
        boolean es = dev.chatalyst.lang.Lang.isSpanish(sender);
        if (!s.enabled()) return send(sender, s.message("Disabled", es));
        return games.current()
                .map(r -> send(sender, games.fill(s.message("Current", es), r, null, s, es)))
                .orElseGet(() -> send(sender, s.message("None", es)
                        .replace("{minutes}", Long.toString(Math.max(1, (games.millisToNext() + 59_999) / 60_000)))));
    }

    private static boolean send(CommandSender sender, String text) {
        if (text != null && !text.isBlank()) sender.sendMessage(MM.full(text));
        return true;
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) return List.of();
        if (args.length == 1) return List.of("iniciar", "saltar");
        if (args.length == 2 && args[0].equalsIgnoreCase("iniciar")) {
            List<String> out = new ArrayList<>();
            for (ChatGameSettings.Category c : games.settings().categories()) out.add(c.id());
            return out;
        }
        return List.of();
    }
}
