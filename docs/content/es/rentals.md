---
title: Alquileres
description: Deja que los jugadores alquilen rangos y ventajas como /fly con dinero del juego durante un tiempo, concedidos por LuckPerms y devueltos si algo falla.
---

Los alquileres dan a los jugadores una forma de ganarse ventajas jugando. En lugar de que un rango o `/fly` solo se consigan pagando en tu tienda, los jugadores los alquilan un día o una semana con el dinero del juego. Quien paga conserva la comodidad; quien juega tiene su propio camino.

Cada alquiler se concede por LuckPerms con caducidad. No hay alquileres permanentes: un alquiler que no caduca sería una venta.

## Cómo se activa

Los alquileres necesitan [Vault](https://www.spigotmc.org/resources/vault.34315/) con un plugin de economía, y [LuckPerms](https://luckperms.net/) para rangos y permisos.

1. Escribe al menos una oferta en `Rental_Offers` dentro de `plugins/Chatalyst/rentals.yml` (ver abajo).
2. Pon `Rentals.Enabled: true` y ejecuta `/cht reload`.
3. Da `chatalyst.command.rental` a los grupos que puedan alquilar.

> [!WARNING]
> `chatalyst.command.rental` no está declarado con un valor por defecto en `plugin.yml`, así que solo lo tienen los operadores hasta que lo concedas. Dáselo a tu grupo por defecto con LuckPerms, por ejemplo `/lp group default permission set chatalyst.command.rental true`.

```yaml
Rentals:
  Enabled: false
  Command: "alquilar"
  Command_Aliases:
    - "rent"
    - "alquiler"
  Flight_Permissions:
    - "essentials.fly"
```

## Configuración

### General

| Opción | Por defecto | Qué hace |
|---|---|---|
| `Enabled` | `false` | Enciende el menú y el comando. Viene apagado porque no hay ofertas hasta que las escribes. |
| `Command` | `alquilar` | El nombre del comando. |
| `Command_Aliases` | `rent`, `alquiler` | Nombres extra. Si otro plugin ya tiene un nombre, se salta. |
| `Flight_Permissions` | `essentials.fly` | Nodos que dan vuelo. Cuando caduca un alquiler con uno de ellos, el vuelo se apaga en el momento, con un aviso un minuto antes y un aterrizaje suave. |

El comando se registra con el servidor en marcha, no en `plugin.yml`, así que un servidor que no usa alquileres deja esos nombres libres.

### Categorías

```yaml
Rental_Categories:
  rangos:
    Name: "Ranks"
    Name_es: "Rangos"
    Icon: PAPER
    Order: 1
  cosmeticos:
    Name: "Cosmetics"
    Name_es: "Cosméticos"
    Icon: ENDER_EYE
    Order: 2
  comandos:
    Name: "Commands"
    Name_es: "Comandos"
    Icon: FEATHER
    Order: 3
```

Una categoría vacía no se enseña. Una oferta que apunte a una categoría que no existe aparece en «Sin categoría».

### Ofertas

`Rental_Offers` viene vacío. El archivo trae este ejemplo comentado:

```yaml
Rental_Offers: {}
#  vuelo-1d:
#    Name: "<#7BFFB0><bold>Flight for a day</bold>"
#    Name_es: "<#7BFFB0><bold>Vuelo por un dia</bold>"
#    Icon: FEATHER
#    Glow: true
#    Description:
#      - "<#C0B3D9>Use /fly wherever you are allowed to"
#    Description_es:
#      - "<#C0B3D9>Usa /fly donde tengas permitido"
#    Permissions:
#      - essentials.fly
#    Duration: 1d
#    Price: 5000
```

| Campo | Qué hace |
|---|---|
| `Name` / `Name_es` | Nombre en el menú, por idioma. |
| `Icon`, `Glow` | Objeto del menú y brillo de encantamiento. |
| `Description` / `Description_es` | Líneas bajo la oferta. |
| `Category` | El id de la categoría a la que pertenece. |
| `Permissions` | Nodos de LuckPerms que se conceden durante el alquiler. |
| `Rank` | Un grupo de LuckPerms, para alquilar el rango entero. |
| `Duration` | Obligatoria y nunca permanente: `12h`, `1d`, `7d`, `1w`. |
| `Price` | Precio en dinero del juego, por Vault. |

Una oferta sin duración, sin precio o que no concede nada no se enseña.

### Vender alquileres desde otra tienda

Otras tiendas pueden vender alquileres sin saber nada de Chatalyst:

- **Por comando.** Una tienda, un cartel o un NPC ejecuta `alquilar <id de oferta>` como el jugador. El cobro, la caducidad y la devolución siguen ocurriendo en Chatalyst.
- **Con vale.** Algunas tiendas solo saben vender objetos. Vende un papel cuyo lore tenga una línea `vale: <id de oferta>`. Al hacer clic derecho se canjea el alquiler sin volver a cobrar. Los vales siguen funcionando aunque el menú de alquiler esté apagado, y los jugadores no pueden falsificarlos porque en supervivencia no se puede escribir el lore.

### Seguridad

Primero se cobra al jugador y después se concede el rango o el permiso. Si LuckPerms no puede concederlo (por ejemplo, porque se borró el grupo), se devuelve el dinero y se avisa al jugador y a la consola.

## Comandos y permisos

| Comando | Permiso (por defecto) | Qué hace |
|---|---|---|
| `/alquilar` (`/rent`, `/alquiler`) | `chatalyst.command.rental` (op hasta que lo concedas) | Abre el menú de alquiler. |
| `/alquilar <id de oferta>` | `chatalyst.command.rental` | Alquila una oferta directamente. |

## Límites

- Un alquiler es un nodo temporal de LuckPerms. Chatalyst no suma tiempo a un alquiler que sigue activo: alquilar otra vez la misma oferta mientras dura puede cobrar sin alargarla. Revisa los nodos del jugador en LuckPerms para ver qué tiene.
- Sin Vault y un plugin de economía no se puede alquilar nada, ni siquiera con vales.
- Los mismos ids de oferta se usan en comandos, vales y menú. Que sean cortos, en minúsculas y definitivos.
