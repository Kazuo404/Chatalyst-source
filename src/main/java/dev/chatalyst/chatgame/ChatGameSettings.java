package dev.chatalyst.chatgame;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * {@code chatgames.yml} ya leido. Se lee entero en cada ronda: una ronda trabaja con la foto
 * de la configuracion que tenia al empezar, y un {@code /cht reload} a mitad no la parte.
 *
 * @param minMinutes   minimo entre el final de una pregunta y la siguiente
 * @param maxMinutes   maximo (se sortea entre los dos)
 * @param minPlayers   jugadores conectados para que salgan preguntas solas
 * @param answerSecs   tiempo para responder
 * @param categories   categorias con peso &gt; 0 y algo que preguntar
 * @param messages     textos MiniMessage de {@code Chat_Games.Messages}
 */
public record ChatGameSettings(boolean enabled, java.util.Set<String> disabledWorlds, int minMinutes, int maxMinutes, int minPlayers,
                               int answerSecs, String questionSound, String winSound,
                               List<Category> categories, Map<Integer, Prize> difficulties,
                               Map<String, String> messages) {

    /**
     * Lo que se gana y como se llama. Sale de {@code Chat_Games.Difficulties} (1 facil, 2 media,
     * 3 dificil) segun la dificultad de cada pregunta; si no hay tabla de dificultades, de la categoria.
     */
    public record Prize(String label, String labelEn, double money, List<String> commands,
                        String rewardText, String rewardTextEn) {

        public String label(boolean spanish) {
            return spanish || labelEn.isBlank() ? label : labelEn;
        }

        public String rewardText(boolean spanish) {
            return spanish || rewardTextEn.isBlank() ? rewardText : rewardTextEn;
        }
    }

    /** El premio de esa pregunta: el de su dificultad (la suya o la de su categoria), o el de la categoria. */
    public Prize prize(Category category, ChatGameQuestion question) {
        int level = question.difficulty() > 0 ? question.difficulty() : category.difficulty();
        Prize byLevel = difficulties.get(level);
        if (byLevel != null) return byLevel;
        return new Prize("", "", category.money(), category.commands(), category.rewardText(true),
                category.rewardText(false));
    }

    /**
     * Una categoria: de donde salen sus preguntas y que se gana al acertar.
     *
     * @param generator  si no es null, las preguntas se inventan ({@link MathGenerator}); si no,
     *                   salen de {@code Chat_Game_Questions.<id>}
     * @param money      dinero por Vault al que acierta (0 = nada)
     * @param commands   comandos de consola al que acierta ({@code {player}}, {@code {uuid}})
     * @param rewardText como se describe el premio en el mensaje del ganador
     */
    public record Category(String id, String name, String nameEn, int weight, int difficulty,
                           boolean strictLanguage, MathGenerator.Level generator,
                           List<ChatGameQuestion> questions, double money, List<String> commands,
                           String rewardText, String rewardTextEn) {

        /**
         * Una pregunta al azar que no este en {@code recent} (las ultimas que salieron). Si la
         * categoria es tan corta que todas son recientes, sale cualquiera: mejor repetir que callar.
         */
        public ChatGameQuestion pick(Random random, java.util.function.Predicate<ChatGameQuestion> recent) {
            ChatGameQuestion q = null;
            for (int i = 0; i < 12; i++) {
                q = generator != null ? MathGenerator.generate(id, generator, random)
                        : questions.get(random.nextInt(questions.size()));
                if (!recent.test(q)) return q;
            }
            return q;
        }

        public String name(boolean spanish) {
            return spanish || nameEn.isBlank() ? name : nameEn;
        }

        public String rewardText(boolean spanish) {
            return spanish || rewardTextEn.isBlank() ? rewardText : rewardTextEn;
        }
    }

    private static final Map<String, String> DEFAULT_MESSAGES = Map.ofEntries(
            Map.entry("Question", "<#C44FE0><b>Pregunta</b> <#7B68A0>» <#C0B3D9>[{category}] <#FBF2FF>{question} <#7B68A0>(responde en el chat, {seconds}s · premio: {reward}<#7B68A0>)"),
            Map.entry("Winner", "<#C44FE0><b>Pregunta</b> <#7B68A0>» <#3DFFA8>{player}<#C0B3D9> acertó: <#FBF2FF>{answer}<#C0B3D9>. Se lleva {reward}<#C0B3D9>."),
            Map.entry("Timeout", "<#C44FE0><b>Pregunta</b> <#7B68A0>» <#C0B3D9>Nadie acertó. La respuesta era <#FBF2FF>{answer}<#C0B3D9>."),
            Map.entry("Current", "<#C44FE0><b>Pregunta</b> <#7B68A0>» <#C0B3D9>[{category}] <#FBF2FF>{question} <#7B68A0>(quedan {seconds}s)"),
            Map.entry("None", "<#C44FE0><b>Pregunta</b> <#7B68A0>» <#C0B3D9>Ahora no hay ninguna pregunta. La siguiente sale en unos <#FBF2FF>{minutes}<#C0B3D9> min."),
            Map.entry("Disabled", "<#FF4D6D>Las preguntas del chat están apagadas."),
            Map.entry("Started", "<#3DFFA8>Pregunta lanzada."),
            Map.entry("Skipped", "<#3DFFA8>Pregunta cancelada."),
            Map.entry("Busy", "<#FF4D6D>Ya hay una pregunta en marcha. Usa <#FBF2FF>/trivia saltar<#FF4D6D> primero."),
            Map.entry("Unknown_Category", "<#FF4D6D>No existe la categoría <#FBF2FF>{category}<#FF4D6D>. Hay: {categories}"),
            Map.entry("Usage", "<#C0B3D9>/trivia <#7B68A0>· <#C0B3D9>/trivia iniciar [categoría] <#7B68A0>· <#C0B3D9>/trivia saltar"),
            Map.entry("Question_en", "<#C44FE0><b>Question</b> <#7B68A0>» <#C0B3D9>[{category}] <#FBF2FF>{question} <#7B68A0>(answer in chat, {seconds}s · prize: {reward}<#7B68A0>)"),
            Map.entry("Winner_en", "<#C44FE0><b>Question</b> <#7B68A0>» <#3DFFA8>{player}<#C0B3D9> got it: <#FBF2FF>{answer}<#C0B3D9>. Prize: {reward}<#C0B3D9>."),
            Map.entry("Timeout_en", "<#C44FE0><b>Question</b> <#7B68A0>» <#C0B3D9>Nobody got it. The answer was <#FBF2FF>{answer}<#C0B3D9>."),
            Map.entry("Current_en", "<#C44FE0><b>Question</b> <#7B68A0>» <#C0B3D9>[{category}] <#FBF2FF>{question} <#7B68A0>({seconds}s left)"),
            Map.entry("None_en", "<#C44FE0><b>Question</b> <#7B68A0>» <#C0B3D9>No question right now. The next one comes in about <#FBF2FF>{minutes}<#C0B3D9> min."),
            Map.entry("Disabled_en", "<#FF4D6D>Chat questions are turned off."));

    public static ChatGameSettings read(FileConfiguration cfg, java.util.logging.Logger log) {
        String root = "Chat_Games.";
        int[] interval = range(cfg.getString(root + "Interval_Minutes", "8-15"), 8, 15);
        List<Category> categories = new ArrayList<>();
        ConfigurationSection cats = cfg.getConfigurationSection(root + "Categories");
        if (cats != null) {
            for (String id : cats.getKeys(false)) {
                ConfigurationSection c = cats.getConfigurationSection(id);
                if (c == null) continue;
                int weight = Math.max(0, c.getInt("Weight", 1));
                if (weight == 0) continue;
                MathGenerator.Level generator = null;
                String gen = c.getString("Generator", "");
                if (gen != null && !gen.isBlank()) {
                    generator = MathGenerator.Level.parse(gen);
                    if (generator == null) {
                        if (log != null) log.warning("Chat_Games: category '" + id + "' has an unknown Generator '"
                                + gen + "' (use math_simple or math_complex); skipped.");
                        continue;
                    }
                }
                List<ChatGameQuestion> questions = generator == null
                        ? questions(cfg.getList("Chat_Game_Questions." + id, List.of()), id) : List.of();
                if (generator == null && questions.isEmpty()) {
                    if (log != null) log.warning("Chat_Games: category '" + id + "' has no questions in"
                            + " Chat_Game_Questions." + id + "; skipped.");
                    continue;
                }
                categories.add(new Category(id, c.getString("Name", id), c.getString("Name_en", ""), weight,
                        Math.max(1, Math.min(3, c.getInt("Difficulty", 1))),
                        c.getBoolean("Strict_Language", false), generator, questions, Math.max(0, c.getDouble("Money", 0)),
                        List.copyOf(c.getStringList("Commands")),
                        c.getString("Reward_Text", ""), c.getString("Reward_Text_en", "")));
            }
        }
        Map<String, String> messages = new LinkedHashMap<>(DEFAULT_MESSAGES);
        ConfigurationSection m = cfg.getConfigurationSection(root + "Messages");
        if (m != null) for (String k : m.getKeys(false)) {
            String v = m.getString(k);
            if (v != null) messages.put(k, v);
        }
        Map<Integer, Prize> difficulties = new java.util.HashMap<>();
        ConfigurationSection ds = cfg.getConfigurationSection(root + "Difficulties");
        if (ds != null) for (String k : ds.getKeys(false)) {
            ConfigurationSection d = ds.getConfigurationSection(k);
            int level;
            try {
                level = Integer.parseInt(k.trim());
            } catch (NumberFormatException e) {
                if (log != null) log.warning("Chat_Games.Difficulties: '" + k + "' is not 1, 2 or 3; skipped.");
                continue;
            }
            if (d == null || level < 1 || level > 3) continue;
            difficulties.put(level, new Prize(d.getString("Name", ""), d.getString("Name_en", ""),
                    Math.max(0, d.getDouble("Money", 0)), List.copyOf(d.getStringList("Commands")),
                    d.getString("Reward_Text", ""), d.getString("Reward_Text_en", "")));
        }
        java.util.Set<String> worlds = new java.util.HashSet<>();
        for (String w : cfg.getStringList(root + "Disabled_Worlds")) worlds.add(w.toLowerCase(java.util.Locale.ROOT));
        return new ChatGameSettings(cfg.getBoolean(root + "Enabled", false), java.util.Set.copyOf(worlds),
                interval[0], interval[1],
                Math.max(0, cfg.getInt(root + "Min_Players", 2)),
                Math.max(10, cfg.getInt(root + "Answer_Seconds", 60)),
                cfg.getString(root + "Question_Sound", "BLOCK_NOTE_BLOCK_PLING"),
                cfg.getString(root + "Win_Sound", "ENTITY_PLAYER_LEVELUP"),
                List.copyOf(categories), Map.copyOf(difficulties), Map.copyOf(messages));
    }

    /**
     * Las filas {@code - {Q: ..., Q_en: ..., A: [...], A_en: [...]}}. {@code Q}/{@code A} son el
     * texto base (espanol en este servidor); {@code Q_es}/{@code A_es} lo sustituyen si estan.
     * Una fila sin pregunta o sin ninguna respuesta no vale.
     */
    static List<ChatGameQuestion> questions(List<?> rows, String category) {
        List<ChatGameQuestion> out = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) continue;
            String qEs = text(map.get("Q_es"), text(map.get("Q"), ""));
            String qEn = text(map.get("Q_en"), "");
            if (qEs.isBlank() && qEn.isBlank()) continue;
            List<String> aEs = answers(map.containsKey("A_es") ? map.get("A_es") : map.get("A"));
            List<String> aEn = answers(map.get("A_en"));
            if (aEs.isEmpty() && aEn.isEmpty()) continue;
            int level = 0;
            Object d = map.get("D");
            if (d instanceof Number n) level = n.intValue();
            else if (d != null) {
                try {
                    level = Integer.parseInt(String.valueOf(d).trim());
                } catch (NumberFormatException ignored) {
                    // dificultad ilegible: la de la categoria
                }
            }
            out.add(new ChatGameQuestion(category, qEs, qEn, aEs, aEn, level));
        }
        return List.copyOf(out);
    }

    private static String text(Object raw, String fallback) {
        return raw == null || String.valueOf(raw).isBlank() ? fallback : String.valueOf(raw).trim();
    }

    private static List<String> answers(Object a) {
        List<String> answers = new ArrayList<>();
        if (a instanceof List<?> list) {
            for (Object x : list) if (x != null && !String.valueOf(x).isBlank()) answers.add(String.valueOf(x).trim());
        } else if (a != null && !String.valueOf(a).isBlank()) {
            answers.add(String.valueOf(a).trim());
        }
        return answers;
    }

    /** {@code "8-15"} o {@code "10"}; un rango al reves se endereza. */
    static int[] range(String raw, int defMin, int defMax) {
        try {
            String s = raw == null ? "" : raw.trim();
            int dash = s.indexOf('-', 1);
            int a = Integer.parseInt(dash > 0 ? s.substring(0, dash).trim() : s);
            int b = dash > 0 ? Integer.parseInt(s.substring(dash + 1).trim()) : a;
            a = Math.max(1, a);
            b = Math.max(1, b);
            return new int[]{Math.min(a, b), Math.max(a, b)};
        } catch (RuntimeException e) {
            return new int[]{defMin, defMax};
        }
    }

    /** Una categoria al azar por peso, o vacio si no hay ninguna. */
    public Optional<Category> pickCategory(Random random) {
        int total = categories.stream().mapToInt(Category::weight).sum();
        if (total <= 0) return Optional.empty();
        int roll = random.nextInt(total);
        for (Category c : categories) {
            roll -= c.weight();
            if (roll < 0) return Optional.of(c);
        }
        return Optional.of(categories.getLast());
    }

    public Optional<Category> category(String id) {
        if (id == null) return Optional.empty();
        return categories.stream().filter(c -> c.id().equalsIgnoreCase(id.trim())).findFirst();
    }

    /** Si un jugador en ese mundo juega (ve las preguntas y puede ganar). */
    public boolean playsIn(String world) {
        return world == null || !disabledWorlds.contains(world.toLowerCase(java.util.Locale.ROOT));
    }

    public String message(String key) {
        return messages.getOrDefault(key, "");
    }

    /** El texto en el idioma del lector: {@code Key_en} para ingles, {@code Key_es} o {@code Key} para espanol. */
    public String message(String key, boolean spanish) {
        if (!spanish) {
            String en = messages.get(key + "_en");
            if (en != null && !en.isBlank()) return en;
        } else {
            String es = messages.get(key + "_es");
            if (es != null && !es.isBlank()) return es;
        }
        return message(key);
    }
}
