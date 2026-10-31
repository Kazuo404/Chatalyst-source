package dev.chatalyst.anonymous;

import java.util.Random;

/**
 * Generador de alias con pinta de jugador REAL de Minecraft: adjetivo + sustantivo con
 * variantes tipicas de gamertag (guion bajo, numeros, minusculas, prefijos "Itz"/"Not",
 * el clasico xX...Xx). Con ~50x50 combinaciones base por ~10 patrones salen cientos de
 * miles de nombres distintos, todos dentro del limite de 16 caracteres de Minecraft.
 * Ejemplos: ShadowWolf42, Nether_Nomad, ItzPanda, xXBlazeXx, frostgolem, Creeper771.
 */
final class AnonymousNames {

    private static final String[] ADJECTIVES = {
            "Shadow", "Silent", "Crazy", "Lucky", "Frost", "Ender", "Pixel", "Nether",
            "Mystic", "Turbo", "Cosmic", "Salty", "Sneaky", "Golden", "Iron", "Emerald",
            "Cloudy", "Stormy", "Wild", "Lazy", "Happy", "Dark", "Swift", "Blazing",
            "Sleepy", "Epic", "Mega", "Ultra", "Tiny", "Grumpy", "Spicy", "Frozen",
            "Cursed", "Hollow", "Rusty", "Neon", "Retro", "Quiet", "Broken", "Sunny",
            "Ancient", "Crimson", "Amber", "Static", "Lunar", "Solar", "Misty", "Feral"
    };

    private static final String[] NOUNS = {
            "Wolf", "Creeper", "Panda", "Fox", "Blaze", "Miner", "Slime", "Wither",
            "Raider", "Nomad", "Knight", "Ninja", "Wizard", "Pirate", "Ghost", "Falcon",
            "Tiger", "Otter", "Golem", "Rabbit", "Phantom", "Drifter", "Zombie", "Piglin",
            "Trader", "Hermit", "Squid", "Parrot", "Llama", "Turtle", "Axolotl", "Warden",
            "Sentry", "Rogue", "Viking", "Samurai", "Hunter", "Gamer", "Spider", "Drake",
            "Raven", "Badger", "Moth", "Comet", "Ember", "Willow", "Storm", "Echo"
    };

    private static final String[] PREFIXES = {
            "Itz", "Its", "The", "Mr", "Not", "Sir", "Dr", "Big", "Lil", "Just"
    };

    private AnonymousNames() {
    }

    /** Alias nuevo con aspecto de nick real, garantizado dentro de los 16 caracteres. */
    static String next(Random random) {
        for (int attempt = 0; attempt < 8; attempt++) {
            String name = build(random);
            if (name.length() <= 16) return name;
        }
        // Fallback ultracorto por si la mala suerte encadena combos largos.
        return NOUNS[random.nextInt(NOUNS.length)] + (10 + random.nextInt(90));
    }

    private static String build(Random random) {
        String adj = ADJECTIVES[random.nextInt(ADJECTIVES.length)];
        String noun = NOUNS[random.nextInt(NOUNS.length)];
        return switch (random.nextInt(10)) {
            // Los patrones mas comunes pesan mas (varios casos por patron).
            case 0, 1, 2 -> adj + noun;                              // ShadowWolf
            case 3 -> adj + "_" + noun;                              // Nether_Nomad
            case 4, 5 -> adj + noun + digits(random);                // FrostGolem77
            case 6 -> (adj + noun).toLowerCase(java.util.Locale.ROOT); // frostgolem
            case 7 -> PREFIXES[random.nextInt(PREFIXES.length)] + noun; // ItzPanda
            case 8 -> noun + digits(random) + digits(random);        // Creeper771
            default -> "xX" + noun + "Xx";                           // xXBlazeXx
        };
    }

    // 1 o 2 digitos; concatenado dos veces da hasta 4 (Creeper771, Wolf2048).
    private static String digits(Random random) {
        return String.valueOf(random.nextInt(random.nextBoolean() ? 10 : 100));
    }
}
