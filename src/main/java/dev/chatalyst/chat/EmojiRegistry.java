package dev.chatalyst.chat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Catalogo de emojis escribibles como :nombre:. Solo simbolos Unicode que la fuente de
 * Minecraft SI dibuja (corazones, estrellas, flechas, musica, formas, clima, ajedrez...);
 * las imágenes de emoji a color no se ven en vanilla sin un resource pack o ItemsAdder. El admin
 * puede añadir o sobrescribir mas en config (Chat.Emojis.Custom).
 */
public final class EmojiRegistry {

    private EmojiRegistry() {
    }

    public static Map<String, String> defaults() {
        Map<String, String> m = new LinkedHashMap<>();
        // Corazones / afecto
        m.put("heart", "❤");      m.put("heart2", "♥");   m.put("hearts", "♡");
        m.put("love", "❤");
        // Estrellas
        m.put("star", "★");       m.put("star2", "☆");    m.put("sparkle", "✦");
        m.put("sparkles", "✧");   m.put("shine", "✨");    m.put("glow", "✻");
        // Flechas
        m.put("arrow", "➤");      m.put("right", "→");    m.put("left", "←");
        m.put("up", "↑");         m.put("down", "↓");     m.put("upright", "↗");
        m.put("triright", "▶");   m.put("trileft", "◀");  m.put("triup", "▲");
        m.put("tridown", "▼");    m.put("rr", "»");       m.put("ll", "«");
        m.put("return", "↩");     m.put("refresh", "↻");
        // Marcas / estado
        m.put("check", "✔");      m.put("tick", "✓");     m.put("cross", "✖");
        m.put("x", "✘");          m.put("dot", "•");      m.put("circle", "●");
        m.put("ocircle", "○");    m.put("square", "■");   m.put("osquare", "□");
        m.put("odiamond", "◇");   m.put("smallsquare", "▪");
        // Musica
        m.put("note", "♪");       m.put("notes", "♫");    m.put("note3", "♬");
        m.put("music", "♩");
        // Clima / naturaleza
        m.put("sun", "☀");        m.put("cloud", "☁");    m.put("umbrella", "☂");
        m.put("snowman", "☃");    m.put("snow", "❄");     m.put("snowflake", "❅");
        m.put("comet", "☄");      m.put("flower", "❀");   m.put("floral", "⚘");
        // Simbolos / signos
        m.put("skull", "☠");      m.put("radioactive", "☢"); m.put("biohazard", "☣");
        m.put("warning", "⚠");    m.put("bolt", "⚡");     m.put("flag", "⚑");
        m.put("oflag", "⚐");      m.put("swords", "⚔");   m.put("yinyang", "☯");
        m.put("peace", "☮");      m.put("anchor", "⚓");   m.put("gear", "⚙");
        m.put("atom", "⚛");       m.put("recycle", "♻");  m.put("infinity", "∞");
        m.put("degree", "°");     m.put("micro", "µ");    m.put("copyright", "©");
        m.put("registered", "®"); m.put("tm", "™");       m.put("euro", "€");
        m.put("pound", "£");      m.put("yen", "¥");      m.put("cent", "¢");
        // Sin emoji para '§': es el codigo de color interno de Minecraft y TODO el pipeline
        // lo elimina del texto de jugador (ChatSanitizer, MM.safe); reintroducirlo via
        // :section: permitiria colarlo en superficies que serializan a legacy (TAB, Dynmap,
        // bridges). El simbolo de parrafo '¶' cubre el uso decorativo sin ese riesgo.
        m.put("bullet", "‣");     m.put("section", "¶");
        // Caras
        m.put("smile", "☺");      m.put("smile2", "☻");   m.put("frown", "☹");
        // Manos
        m.put("point", "☛");      m.put("pointleft", "☚"); m.put("pointup", "☝");
        m.put("victory", "✌");    m.put("write", "✍");
        // Ajedrez / cartas
        m.put("king", "♔");       m.put("queen", "♕");    m.put("rook", "♖");
        m.put("bishop", "♗");     m.put("knight", "♘");   m.put("pawn", "♙");
        m.put("spade", "♠");      m.put("club", "♣");     m.put("card_heart", "♥");
        m.put("card_diamond", "♦");
        // Objetos
        m.put("mail", "✉");       m.put("pencil", "✎");   m.put("phone", "☎");
        m.put("scissors", "✂");   m.put("plane", "✈");    m.put("hourglass", "⌛");
        m.put("watch", "⌚");      m.put("crown", "♛");    m.put("key", "⚿");
        // Flechas dobles / varios
        m.put("star4", "✶");      m.put("star6", "✹");    m.put("asterisk", "✱");
        m.put("flower2", "✿");    m.put("cross2", "✝");   m.put("check2", "☑");
        return m;
    }
}
