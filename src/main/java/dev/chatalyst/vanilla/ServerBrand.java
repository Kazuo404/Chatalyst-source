package dev.chatalyst.vanilla;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * El nombre del software del servidor que el cliente enseña en la pantalla F3 ("Paper",
 * "Purpur"...). Cambiarlo es la otra mitad de esconder /plugins y /version: de poco sirve
 * tapar los comandos si basta con pulsar F3 para leer sobre que corre el servidor.
 *
 * <p>Esta clase NO depende de PacketEvents (la intercepcion vive en el puente aislado): aqui
 * solo se decide el texto y se arma el contenido del mensaje.
 */
public final class ServerBrand {

    /** Canal por el que el servidor le dice su nombre al cliente. */
    public static final String CHANNEL = "minecraft:brand";

    /** El cliente lo pinta en una sola linea de F3; mas largo solo se sale de la pantalla. */
    static final int MAX_LENGTH = 64;

    private final JavaPlugin plugin;
    private volatile byte[] payload;

    public ServerBrand(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        boolean enabled = plugin.getConfig().getBoolean("Server_Brand.Enabled", false);
        String name = plugin.getConfig().getString("Server_Brand.Name", "");
        payload = enabled && name != null && !name.isBlank() ? encode(name) : null;
    }

    /** Contenido del mensaje de marca ya armado, o null si la funcion esta apagada. */
    public byte[] payload() {
        return payload;
    }

    /**
     * La marca como la espera el cliente: un texto con su longitud delante.
     *
     * <p>F3 pinta el texto con el motor de fuentes normal, asi que los codigos {@code &a}
     * funcionan — pero el cliente escribe mas cosas a continuacion en la misma linea, y sin un
     * reset al final el color de la marca se las llevaria por delante.
     */
    static byte[] encode(String name) {
        String shown = colour(name.strip());
        if (shown.length() > MAX_LENGTH) shown = shown.substring(0, MAX_LENGTH);
        if (shown.indexOf('§') >= 0) shown += "§r";
        byte[] text = shown.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length + 2);
        int length = text.length;
        while ((length & ~0x7F) != 0) {
            out.write((length & 0x7F) | 0x80);
            length >>>= 7;
        }
        out.write(length);
        out.writeBytes(text);
        return out.toByteArray();
    }

    /** {@code &a} -> {@code §a}, solo donde lo que sigue es de verdad un codigo de color. */
    private static String colour(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' && i + 1 < text.length()
                    && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(text.charAt(i + 1)) >= 0) {
                out.append('§');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
