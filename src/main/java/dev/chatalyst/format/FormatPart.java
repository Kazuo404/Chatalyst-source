package dev.chatalyst.format;

import java.util.List;

/**
 * Una parte de un formato de chat (prefix, nombre, separador, mensaje, ...).
 * Espeja el concepto "Parts" del formato.
 */
public final class FormatPart {

    public final String id;
    /** Texto MiniMessage; admite placeholders {..} y %..%. */
    public final String message;
    /** Lineas del tooltip al pasar el raton (MiniMessage). Vacio = sin hover. */
    public final List<String> hover;
    /** Comando que se ejecuta al hacer click. Null = ninguno. */
    public final String runCommand;
    /** Comando que se sugiere (rellena el chat) al hacer click. Null = ninguno. */
    public final String suggestCommand;
    /** URL a abrir al hacer click. Null = ninguna. */
    public final String openUrl;
    /** Texto a copiar al portapapeles al hacer click. Null = ninguno. */
    public final String copyToClipboard;
    /** Text inserted into chat input on shift-click. */
    public final String insertion;
    public final FormatCondition senderCondition;
    public final FormatCondition receiverCondition;

    public FormatPart(String id, String message, List<String> hover, String runCommand,
                      String suggestCommand, String openUrl, String copyToClipboard,
                      String insertion, FormatCondition senderCondition,
                      FormatCondition receiverCondition) {
        this.id = id;
        this.message = message == null ? "" : message;
        this.hover = hover == null ? List.of() : hover;
        this.runCommand = emptyToNull(runCommand);
        this.suggestCommand = emptyToNull(suggestCommand);
        this.openUrl = emptyToNull(openUrl);
        this.copyToClipboard = emptyToNull(copyToClipboard);
        this.insertion = emptyToNull(insertion);
        this.senderCondition = senderCondition;
        this.receiverCondition = receiverCondition;
    }

    /** True si esta parte es el hueco donde va el mensaje del jugador. */
    public boolean isMessagePart() {
        return message.contains("{message}");
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
