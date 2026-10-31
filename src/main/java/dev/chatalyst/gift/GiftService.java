package dev.chatalyst.gift;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.util.InventoryUtil;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Abrir una caja: elegir el premio y entregarlo.
 *
 * <p>El sorteo es por peso acumulado sobre la suma de la caja, asi que un peso 0 no sale nunca
 * y no hay que mantener nada cuadrado a cien. La tirada se hace AQUI y no al fabricar la caja:
 * ver {@link GiftTier}.
 */
public final class GiftService {

    private final ChatalystPlugin plugin;
    private final GiftItem items;

    public GiftService(ChatalystPlugin plugin, GiftItem items) {
        this.plugin = plugin;
        this.items = items;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("Gifts.Enabled", true);
    }

    public List<GiftTier> catalogue() {
        return GiftTier.all(plugin.getConfig());
    }

    public Optional<GiftTier> tier(String id) {
        return GiftTier.resolve(plugin.getConfig(), id);
    }

    public GiftItem items() {
        return items;
    }

    /** La caja tal y como se entrega. */
    public ItemStack create(GiftTier tier, CommandSender viewer, int amount) {
        return items.create(tier, plugin.getConfig(), viewer, amount);
    }

    /**
     * Tira un premio de esta caja.
     *
     * @return el premio, o vacio si la caja no tiene tabla — cosa que se comprueba ANTES de
     *         consumirla, para que una tabla mal escrita no se coma la caja del jugador.
     */
    public Optional<GiftReward> draw(GiftTier tier) {
        int total = tier.totalWeight();
        if (total <= 0) return Optional.empty();
        Random random = ThreadLocalRandom.current();
        int roll = random.nextInt(total);
        for (GiftReward reward : tier.rewards()) {
            int weight = Math.max(0, reward.weight());
            if (weight <= 0) continue;
            roll -= weight;
            if (roll < 0) return Optional.of(reward);
        }
        // Inalcanzable salvo carrera con un /cht reload a mitad de tirada; el ultimo con peso
        // es una respuesta mejor que nada.
        return tier.rewards().stream().filter(r -> r.weight() > 0).reduce((a, b) -> b);
    }

    /**
     * Entrega el premio y hace el ruido. Lo que no quepa cae al suelo, nunca se pierde.
     *
     * @return el objeto entregado, con la cantidad ya TIRADA. Hace falta para el mensaje: la
     *         caja anuncia lo que salio, no el rango de lo que podria haber salido.
     */
    public ItemStack deliver(Player player, GiftReward reward) {
        ItemStack prize = reward.roll(ThreadLocalRandom.current());
        InventoryUtil.giveOrDrop(player, prize);
        Sound sound = sound();
        if (sound != null) player.playSound(player.getLocation(), sound, 1f, 1.2f);
        return prize;
    }

    private Sound sound() {
        String configured = plugin.getConfig().getString("Gifts.Sound", "ENTITY_PLAYER_LEVELUP");
        if (configured == null || configured.isBlank()) return null;
        try {
            return Sound.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            // Un sonido inventado no vale una excepcion en mitad de un regalo.
            return null;
        }
    }
}
