package dev.chatalyst.anonymous;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Descarga la skin FIRMADA de una cuenta real directamente de Mojang.
 *
 * Existe porque {@code PlayerProfile.complete(true)} de Paper no busca perfiles cuando el
 * servidor va con online-mode=false —el caso de una red no premium detras de Velocity— y
 * devuelve false sin decir por que. El pool de /anonymous se quedaba vacio en cada arranque
 * con "no accounts could be loaded" y ningun motivo, aunque Mojang respondiera perfectamente.
 *
 * Dos peticiones: nombre -> UUID, y UUID -> perfil con la firma (unsigned=false). Sin firma el
 * cliente rechaza la skin, asi que un perfil sin ella se trata como fallo, no como exito a medias.
 * Se llama siempre desde un hilo asincrono.
 */
final class MojangSkinLookup {

    /** Nombre tal como lo escribe Mojang (caja correcta) y la propiedad textures firmada. */
    record Skin(String name, String value, String signature) {
    }

    private static final String LOOKUP = "https://api.minecraftservices.com/minecraft/profile/lookup/name/";
    private static final String SESSION = "https://sessionserver.mojang.com/session/minecraft/profile/";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private MojangSkinLookup() {
    }

    static Skin fetch(String accountName) throws IOException, InterruptedException {
        String lookup = get(LOOKUP + URLEncoder.encode(accountName, StandardCharsets.UTF_8));
        return parseSkin(get(SESSION + parseId(lookup) + "?unsigned=false"));
    }

    /** El UUID sin guiones de la respuesta de busqueda por nombre. */
    static String parseId(String json) throws IOException {
        JsonObject root = object(json);
        JsonElement id = root.get("id");
        if (id == null || !id.isJsonPrimitive() || id.getAsString().isBlank()) {
            throw new IOException("respuesta sin id");
        }
        return id.getAsString();
    }

    /** Nombre y textures firmadas del perfil de sesion. */
    static Skin parseSkin(String json) throws IOException {
        JsonObject root = object(json);
        JsonElement properties = root.get("properties");
        if (properties == null || !properties.isJsonArray()) throw new IOException("perfil sin propiedades");
        JsonElement nameElement = root.get("name");
        String name = nameElement != null && nameElement.isJsonPrimitive() ? nameElement.getAsString() : "";
        JsonArray list = properties.getAsJsonArray();
        for (JsonElement element : list) {
            if (!element.isJsonObject()) continue;
            JsonObject property = element.getAsJsonObject();
            JsonElement propertyName = property.get("name");
            if (propertyName == null || !"textures".equals(propertyName.getAsString())) continue;
            JsonElement value = property.get("value");
            JsonElement signature = property.get("signature");
            if (value == null || value.getAsString().isBlank()) throw new IOException("textures vacias");
            if (signature == null || signature.getAsString().isBlank()) throw new IOException("textures sin firma");
            if (name.isBlank()) throw new IOException("perfil sin nombre");
            return new Skin(name, value.getAsString(), signature.getAsString());
        }
        throw new IOException("la cuenta no tiene skin");
    }

    private static JsonObject object(String json) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) throw new IOException("respuesta no es un objeto JSON");
            return parsed.getAsJsonObject();
        } catch (RuntimeException malformed) {
            throw new IOException("JSON invalido: " + malformed.getMessage(), malformed);
        }
    }

    private static String get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("User-Agent", "Chatalyst")
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        return switch (response.statusCode()) {
            case 200 -> response.body();
            case 204, 404 -> throw new IOException("la cuenta no existe");
            case 429 -> throw new IOException("Mojang limito las peticiones (429)");
            default -> throw new IOException("HTTP " + response.statusCode());
        };
    }
}
