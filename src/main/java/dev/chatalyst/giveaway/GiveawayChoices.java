package dev.chatalyst.giveaway;

import dev.chatalyst.gui.MenuItems;
import dev.chatalyst.lang.Lang;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * El boton de "una opcion de varias", compartido por las pantallas que eligen algo.
 *
 * <p>Estaba escrito dos veces, una en la pantalla de la cantidad y otra en la de la duracion,
 * casi igual. Dos copias de lo mismo no se quedan iguales: la primera vez que alguien retoque
 * una, las dos pantallas dejan de parecerse sin que nadie lo decida.
 */
final class GiveawayChoices {

    private GiveawayChoices() {
    }

    /**
     * @param picked lo elegido se marca Y brilla. Una lista de opciones que no dice cual esta
     *               puesta obliga a recordar lo que se decidio hace tres pantallas.
     */
    static ItemStack option(CommandSender viewer, Material material, String label,
                            String color, boolean picked) {
        List<String> lore = List.of(picked
                ? "<#3DFFA8>" + Lang.raw(viewer, "giveaway-gui.option-picked")
                : "<#7B68A0>" + Lang.raw(viewer, "giveaway-gui.option-pick"));
        ItemStack item = MenuItems.item(material,
                (picked ? "<#3DFFA8>" : color) + label, lore);
        if (picked) item.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        return item;
    }
}
