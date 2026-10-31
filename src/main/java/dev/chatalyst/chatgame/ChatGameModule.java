package dev.chatalyst.chatgame;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.command.RuntimeCommandRegistrar;
import dev.chatalyst.module.Module;

import java.util.List;

/**
 * Preguntas en el chat (quimica, fisica, geografia, matematicas...) con premio para el primero
 * que acierta. Todo sale de {@code chatgames.yml}: categorias, preguntas, premios y textos.
 *
 * <p>El reloj y el listener se ponen siempre; con {@code Chat_Games.Enabled: false} no sale
 * ninguna pregunta, y encenderlo es un {@code /cht reload}, no un reinicio.
 */
public final class ChatGameModule implements Module {

    private final ChatalystPlugin plugin;
    private ChatGameService games;
    private RuntimeCommandRegistrar commands;

    public ChatGameModule(ChatalystPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "chatgames";
    }

    @Override
    public void enable() {
        games = new ChatGameService(plugin);
        games.start();
        plugin.getServer().getPluginManager().registerEvents(new ChatGameListener(games), plugin);
        commands = new RuntimeCommandRegistrar(plugin,
                new RuntimeCommandRegistrar.Spec(
                        "Chat_Games.Command", "Chat_Games.Command_Aliases",
                        "trivia", List.of("pregunta", "preguntas"),
                        "Chat quiz: see the current question", "chatalyst.command.trivia",
                        () -> plugin.getConfig().getBoolean("Chat_Games.Enabled", false)),
                new ChatGameCommand(games));
        plugin.getServer().getPluginManager().registerEvents(commands, plugin);
        commands.apply();
    }

    @Override
    public void reload() {
        if (games != null) games.reload();
        if (commands != null) commands.apply();
    }

    @Override
    public void disable() {
        if (games != null) games.stop();
    }
}
