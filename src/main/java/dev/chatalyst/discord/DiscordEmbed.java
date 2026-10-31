package dev.chatalyst.discord;

import java.util.ArrayList;
import java.util.List;

/**
 * Una tarjeta de Discord, descrita sin depender de Discord.
 *
 * <p>Quien construye el contenido — el mercado, por ejemplo — no debería tener que conocer JDA
 * para contar que alguien vendió algo. Aquí se describe QUÉ se enseña; traducirlo a un embed
 * de verdad es cosa de {@link DiscordBot}, y eso hace que el texto se pueda probar sin
 * levantar un bot.
 *
 * <p>Todo viene recortado a los límites de Discord desde el constructor. Un embed que pasa de
 * ellos no se envía a medias: la petición entera la rechaza la API, así que un nombre de objeto
 * absurdamente largo tiraría el aviso completo en vez de solo verse feo.
 */
public record DiscordEmbed(String title, String description, int color,
                           List<Field> fields, String footer) {

    /** Límites de la API de Discord, no nuestros. */
    private static final int MAX_TITLE = 256;
    private static final int MAX_DESCRIPTION = 4_096;
    private static final int MAX_FIELD_NAME = 256;
    private static final int MAX_FIELD_VALUE = 1_024;
    private static final int MAX_FOOTER = 2_048;
    private static final int MAX_FIELDS = 25;

    public record Field(String name, String value, boolean inline) {
    }

    public DiscordEmbed {
        title = bounded(title, MAX_TITLE);
        description = bounded(description, MAX_DESCRIPTION);
        footer = bounded(footer, MAX_FOOTER);
        List<Field> safe = new ArrayList<>();
        if (fields != null) {
            for (Field field : fields) {
                if (safe.size() >= MAX_FIELDS) break;
                if (field == null) continue;
                String name = bounded(field.name(), MAX_FIELD_NAME);
                String value = bounded(field.value(), MAX_FIELD_VALUE);
                // Discord rechaza un campo con nombre o valor vacío, y con él el embed entero.
                if (name.isBlank() || value.isBlank()) continue;
                safe.add(new Field(name, value, field.inline()));
            }
        }
        fields = List.copyOf(safe);
    }

    public static Builder titled(String title, int color) {
        return new Builder(title, color);
    }

    /** Construcción legible: el orden de los campos es el orden en que se leen. */
    public static final class Builder {

        private final String title;
        private final int color;
        private final List<Field> fields = new ArrayList<>();
        private String description = "";
        private String footer = "";

        private Builder(String title, int color) {
            this.title = title;
            this.color = color;
        }

        public Builder description(String value) {
            this.description = value == null ? "" : value;
            return this;
        }

        public Builder field(String name, String value, boolean inline) {
            fields.add(new Field(name, value, inline));
            return this;
        }

        public Builder footer(String value) {
            this.footer = value == null ? "" : value;
            return this;
        }

        public DiscordEmbed build() {
            return new DiscordEmbed(title, description, color, fields, footer);
        }
    }

    private static String bounded(String value, int max) {
        if (value == null) return "";
        String flat = value.replace('\r', ' ');
        if (flat.length() <= max) return flat;
        return flat.substring(0, Math.max(0, max - 1)) + "…";
    }
}
