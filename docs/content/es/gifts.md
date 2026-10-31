---
title: Regalos
description: Cajas de regalo que se abren con clic derecho y dan un premio al azar por peso, con las probabilidades reales en el objeto y exportables a kits de otros plugins.
---

Una caja de regalo es una cabeza decorada que el jugador abre con clic derecho para llevarse un premio al azar. Cada tipo de caja (cobre, hierro, oro, esmeralda, diamante) tiene su propia tabla de premios. Las cajas pueden salir de cualquier sitio: un comando, un kit, un sorteo, una compra en la tienda.

El objeto dice la verdad: su lore lista todos los premios posibles con su porcentaje real, calculado por el plugin.

## Cómo se activa

Las cajas de regalo vienen encendidas, con cinco tipos listos para usar. La configuración está en `plugins/Chatalyst/gifts.yml`:

```yaml
Gifts:
  Enabled: true
  Command: "caja"
  Command_Aliases:
    - "cajas"
    - "giftbox"

  # Escribir en el lore lo que puede caer, con su probabilidad real.
  Show_Contents: true

  Max_Listed: 8

  Announce: true

  # Sonido al abrir. Vacio = en silencio.
  Sound: ENTITY_PLAYER_LEVELUP
```

Reparte una caja con `/caja dar <jugador> <tipo> [cantidad]`.

## Configuración

### General

| Opción | Por defecto | Qué hace |
|---|---|---|
| `Enabled` | `true` | Enciende las cajas de regalo. |
| `Command` | `caja` | El nombre del comando. |
| `Command_Aliases` | `cajas`, `giftbox` | Nombres extra. Si otro plugin ya tiene un nombre, se salta. |
| `Show_Contents` | `true` | Lista en el lore los premios posibles y sus probabilidades. Con `false` es una sorpresa. |
| `Max_Listed` | `8` | Premios listados antes de resumir con «y N cosas más». |
| `Announce` | `true` | Le dice al jugador por el chat qué le ha tocado. |
| `Sound` | `ENTITY_PLAYER_LEVELUP` | Sonido al abrir una caja. Vacío = en silencio. |

### Tipos de caja

Cada tipo está dentro de `Gift_Tiers`. Esta es la caja de cobre de fábrica:

```yaml
Gift_Tiers:

  cobre:
    Name: "Copper Gift"
    Name_es: "Regalo de Cobre"
    Order: 1
    Color: "#CD7F32"
    Glow: true
    Texture: "74300a5f8111753fb9e224d60e46d46fbb03cf3f0974b3dee43887ea5ff01fcb"
    Description:
      - "<gray>The first rung. Supplies, not treasure."
    Description_es:
      - "<gray>El primer escalon. Suministros, no tesoro."
    Rewards:
      - { Weight: 25, Material: COOKED_BEEF, Amount: 8-16, Label: "Cooked beef", Label_es: "Filetes" }
      - { Weight: 25, Material: COAL, Amount: 8-24, Label: "Coal", Label_es: "Carbon" }
      - { Weight: 18, Material: IRON_INGOT, Amount: 2-5, Label: "Iron", Label_es: "Hierro" }
      - { Weight: 15, Material: COPPER_INGOT, Amount: 8-16, Label: "Copper", Label_es: "Cobre" }
      - { Weight: 10, Material: EXPERIENCE_BOTTLE, Amount: 4-8, Label: "Bottles o' Enchanting", Label_es: "Botellas de experiencia" }
      - { Weight: 7, Material: GOLD_INGOT, Amount: 1-3, Label: "Gold", Label_es: "Oro" }
```

| Campo | Qué hace |
|---|---|
| `Name` / `Name_es` | Nombre de la caja por idioma. |
| `Order` | Posición en la lista. |
| `Color` | Color del nombre y de los títulos del lore. |
| `Glow` | Brillo de encantamiento. |
| `Texture` | Textura de la cabeza: el valor base64 completo o solo el hash de `textures.minecraft.net`. |
| `Description` / `Description_es` | Líneas del lore. |
| `Rewards` | La tabla de premios. |

Cada premio puede llevar `Weight`, `Material`, `Amount` (un número o un rango como `8-16`), `Label` / `Label_es` (cómo se nombra en el lore) y, si quieres un objeto especial, `Name`, `Lore`, `Enchantments` y `Glow`.

### El peso no es un porcentaje

`Weight: 25` no significa 25 %. Cada premio se sortea contra la suma de los pesos de su caja, así que puedes añadir uno sin recalcular los demás. El porcentaje real lo calcula el plugin y lo escribe en el objeto. `Weight: 0` deja un premio escrito pero que nunca sale, útil para un premio de temporada.

El premio se sortea al abrir la caja, no al fabricarla. Por eso, si retocas una tabla, también cambia lo que darán las cajas que ya están en los cofres de los jugadores.

### Meter cajas en otros plugins

Una caja es una cabeza con perfil y una marca interna, y eso no se puede escribir a mano en la configuración de otro plugin. `/caja exportar <tipo> [cantidad]` la guarda, serializada por Bukkit, en `plugins/Chatalyst/exports/gift-<tipo>.yml`, lista para pegarla en un kit (por ejemplo de AdvancedKits) y que además se vea en su vista previa.

## Comandos y permisos

| Comando | Permiso (por defecto) | Qué hace |
|---|---|---|
| `/caja` (`/cajas`, `/giftbox`) | `chatalyst.command.gift` (true) | Lista los tipos de caja y cuántos premios tiene cada uno. |
| `/caja dar <jugador> <tipo> [cantidad]` | `chatalyst.command.gift.admin` (op) | Da cajas a un jugador conectado (de 1 a 64). |
| `/caja exportar <tipo> [cantidad]` | `chatalyst.command.gift.admin` (op) | Exporta una caja a un archivo. |

También funcionan los subcomandos en inglés `give` y `export`.

## Límites

- Los premios son solo objetos. Para comandos, rangos o dinero, usa los [Sorteos](giveaways) o tu tienda.
- La caja se fabrica en el idioma de quien la recibe, porque el texto viaja dentro del objeto.
- Si el inventario del jugador está lleno, la caja cae a sus pies.
