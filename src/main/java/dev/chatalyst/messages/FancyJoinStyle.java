package dev.chatalyst.messages;

import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Estilos visuales de la entrada fancy: cada uno pinta un efecto (particulas, rayo o fuegos
 * artificiales) en la posicion del jugador que entra. El nombre ({@link #id()}) se guarda en
 * messages.yml bajo {@code Join.Fancy.Tiers.<tier>.Style} y se elige desde una GUI.
 *
 * <p>Todos los efectos deben ejecutarse en el hilo de region del sujeto (lo garantiza
 * {@link FancyJoinPlayback}, que corre en su entity scheduler), asi que son seguros en Folia.
 */
public enum FancyJoinStyle {

    NONE("none", Material.BARRIER),
    LIGHTNING("lightning", Material.LIGHTNING_ROD),
    FIREWORKS("fireworks", Material.FIREWORK_ROCKET),
    FIREWORK_SHOW("firework-show", Material.FIREWORK_STAR),
    SPIRAL("spiral", Material.END_ROD),
    HALO("halo", Material.GOLDEN_HELMET),
    FLAMES("flames", Material.BLAZE_POWDER),
    SOULS("souls", Material.SOUL_LANTERN),
    ENCHANT("enchant", Material.ENCHANTING_TABLE),
    TOTEM("totem", Material.TOTEM_OF_UNDYING),
    EXPLOSION("explosion", Material.TNT),
    HEARTS("hearts", Material.POPPY),
    PORTAL("portal", Material.ENDER_PEARL),
    DRAGON("dragon", Material.DRAGON_BREATH),
    SNOW("snow", Material.SNOWBALL),
    NOTES("notes", Material.NOTE_BLOCK);

    private final String id;
    private final Material icon;

    FancyJoinStyle(String id, Material icon) {
        this.id = id;
        this.icon = icon;
    }

    public String id() {
        return id;
    }

    public Material icon() {
        return icon;
    }

    /** Estilo por su id de config; {@link #NONE} si es nulo o desconocido. */
    public static FancyJoinStyle byId(String id) {
        if (id == null) return NONE;
        String norm = id.trim().toLowerCase(Locale.ROOT);
        for (FancyJoinStyle style : values()) {
            if (style.id.equals(norm)) return style;
        }
        return NONE;
    }

    /** Pinta el efecto en la posicion del jugador. Ejecutar en el hilo de region del sujeto. */
    public void play(Player subject) {
        World world = subject.getWorld();
        Location feet = subject.getLocation();
        Location center = feet.clone().add(0, 1.0, 0);
        switch (this) {
            case NONE -> { }
            case LIGHTNING -> world.strikeLightningEffect(feet);
            case FIREWORKS -> fireworks(subject, 1);
            case FIREWORK_SHOW -> fireworks(subject, 3);
            case SPIRAL -> helix(world, feet, Particle.END_ROD);
            case HALO -> ring(world, center.clone().add(0, 1.1, 0), 0.8, Particle.END_ROD, 40);
            case FLAMES -> burst(world, center, Particle.FLAME, 60, 0.5, 0.8, 0.5, 0.02);
            case SOULS -> {
                burst(world, center, Particle.SOUL_FIRE_FLAME, 40, 0.4, 0.9, 0.4, 0.02);
                burst(world, center, Particle.SOUL, 25, 0.5, 1.0, 0.5, 0.01);
            }
            case ENCHANT -> burst(world, center, Particle.ENCHANT, 90, 0.6, 1.0, 0.6, 1.0);
            case TOTEM -> burst(world, center, Particle.TOTEM_OF_UNDYING, 90, 0.5, 1.0, 0.5, 0.4);
            case EXPLOSION -> world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 1);
            case HEARTS -> burst(world, center, Particle.HEART, 30, 0.5, 0.9, 0.5, 0.0);
            case PORTAL -> burst(world, center, Particle.PORTAL, 140, 0.4, 1.0, 0.4, 0.6);
            case DRAGON -> burst(world, center, Particle.DRAGON_BREATH, 70, 0.5, 0.6, 0.5, 0.02);
            case SNOW -> burst(world, center, Particle.SNOWFLAKE, 90, 0.6, 1.2, 0.6, 0.02);
            case NOTES -> burst(world, center, Particle.NOTE, 30, 0.6, 1.0, 0.6, 1.0);
        }
    }

    private static void burst(World world, Location c, Particle particle, int count,
            double dx, double dy, double dz, double extra) {
        world.spawnParticle(particle, c, count, dx, dy, dz, extra);
    }

    private static void ring(World world, Location c, double radius, Particle particle, int points) {
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            world.spawnParticle(particle, c.getX() + radius * Math.cos(angle), c.getY(),
                    c.getZ() + radius * Math.sin(angle), 1, 0, 0, 0, 0);
        }
    }

    private static void helix(World world, Location base, Particle particle) {
        int points = 60;
        double turns = 3.0, height = 2.4, radius = 0.6;
        for (int i = 0; i < points; i++) {
            double t = (double) i / points;
            double angle = turns * 2 * Math.PI * t;
            world.spawnParticle(particle, base.getX() + radius * Math.cos(angle),
                    base.getY() + height * t, base.getZ() + radius * Math.sin(angle),
                    1, 0, 0, 0, 0);
        }
    }

    private static void fireworks(Player subject, int count) {
        World world = subject.getWorld();
        for (int i = 0; i < count; i++) {
            Firework firework = world.spawn(subject.getLocation(), Firework.class);
            FireworkMeta meta = firework.getFireworkMeta();
            meta.addEffect(randomEffect());
            // Vuela con mecha (power 1) y estalla en el aire, ARRIBA del jugador. No se detona a
            // quemarropa: la explosion de un cohete puede danar a quien esta al lado.
            meta.setPower(1);
            firework.setFireworkMeta(meta);
        }
    }

    private static FireworkEffect randomEffect() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        FireworkEffect.Type[] types = FireworkEffect.Type.values();
        return FireworkEffect.builder()
                .with(types[rnd.nextInt(types.length)])
                .withColor(randomColor(), randomColor())
                .withFade(randomColor())
                .flicker(rnd.nextBoolean())
                .trail(rnd.nextBoolean())
                .build();
    }

    private static Color randomColor() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        return Color.fromRGB(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256));
    }
}
