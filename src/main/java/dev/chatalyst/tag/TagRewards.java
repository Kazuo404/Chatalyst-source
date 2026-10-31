package dev.chatalyst.tag;

import dev.chatalyst.util.Sanitize;

import java.util.ArrayList;
import java.util.List;

/**
 * Acciones ejecutadas una sola vez despues de persistir un desbloqueo. Los comandos se
 * guardan sin slash inicial y admiten {player}, {uuid}, {title_id}, {title_name},
 * {points} y {season}.
 */
public record TagRewards(List<String> commands) {

    public static final int MAX_COMMANDS = 8;
    public static final int MAX_COMMAND_LENGTH = 256;

    public TagRewards {
        List<String> safe = new ArrayList<>();
        if (commands != null) {
            for (String command : commands) {
                String normalized = normalize(command);
                if (!normalized.isEmpty() && safe.size() < MAX_COMMANDS) safe.add(normalized);
            }
        }
        commands = List.copyOf(safe);
    }

    public static TagRewards none() {
        return new TagRewards(List.of());
    }

    public TagRewards add(String command) {
        if (commands.size() >= MAX_COMMANDS) return this;
        List<String> next = new ArrayList<>(commands);
        next.add(command);
        return new TagRewards(next);
    }

    public TagRewards update(int index, String command) {
        if (index < 0 || index >= commands.size()) return this;
        List<String> next = new ArrayList<>(commands);
        next.set(index, command);
        return new TagRewards(next);
    }

    public TagRewards remove(int index) {
        if (index < 0 || index >= commands.size()) return this;
        List<String> next = new ArrayList<>(commands);
        next.remove(index);
        return new TagRewards(next);
    }

    private static String normalize(String raw) {
        String safe = Sanitize.stripInvisible(raw == null ? "" : raw)
                .replace('\r', ' ').replace('\n', ' ').trim();
        while (safe.startsWith("/")) safe = safe.substring(1).stripLeading();
        return Sanitize.limit(safe, MAX_COMMAND_LENGTH);
    }
}
