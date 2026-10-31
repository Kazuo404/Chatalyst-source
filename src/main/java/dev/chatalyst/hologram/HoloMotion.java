package dev.chatalyst.hologram;

/**
 * Movimiento de un cartel.
 *
 * @param animation animacion de texto por defecto de todas sus lineas ("none" = estatico)
 * @param speed     ticks por fotograma (2 = el ritmo de /announce, 10 fotogramas por segundo)
 * @param spin      giro continuo en grados por segundo (0 = quieto; negativo = al reves)
 * @param bob       amplitud del vaiven vertical en bloques (0 = quieto)
 * @param refresh   segundos entre refrescos de placeholders
 */
public record HoloMotion(String animation, int speed, double spin, double bob, int refresh) {

    public static final HoloMotion DEFAULT = new HoloMotion("none", 2, 0, 0, 5);

    public HoloMotion {
        animation = animation == null || animation.isBlank() ? "none" : animation;
        speed = (int) HoloLook.clamp(speed, 1, 40);
        spin = HoloLook.clamp(spin, -360, 360);
        bob = HoloLook.clamp(bob, 0, 2);
        refresh = (int) HoloLook.clamp(refresh, 1, 600);
    }

    public HoloMotion withAnimation(String v) {
        return new HoloMotion(v, speed, spin, bob, refresh);
    }

    public HoloMotion withSpeed(int v) {
        return new HoloMotion(animation, v, spin, bob, refresh);
    }

    public HoloMotion withSpin(double v) {
        return new HoloMotion(animation, speed, v, bob, refresh);
    }

    public HoloMotion withBob(double v) {
        return new HoloMotion(animation, speed, spin, v, refresh);
    }

    public HoloMotion withRefresh(int v) {
        return new HoloMotion(animation, speed, spin, bob, v);
    }

    /** Si el cartel se mueve aunque sus textos esten quietos. */
    public boolean moves() {
        return spin != 0 || bob > 0;
    }
}
