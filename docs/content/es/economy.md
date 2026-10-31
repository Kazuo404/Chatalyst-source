---
title: Ranking de dinero
description: Un menú con los jugadores más ricos, calculado en segundo plano desde Vault para que abra al instante, con skins reales y apodos actuales.
---

`/baltop` abre el ranking de los jugadores más ricos del servidor. Es un menú, no un muro de líneas en el chat: cada jugador aparece con su cabeza, su apodo actual, su saldo y su puesto, con medallas para el podio.

El ranking se calcula en segundo plano y se guarda en caché, así que se abre al instante aunque hayan entrado miles de jugadores.

## Cómo se activa

El ranking necesita [Vault](https://www.spigotmc.org/resources/vault.34315/) y un plugin de economía (por ejemplo EssentialsX). Viene encendido en `plugins/Chatalyst/economy.yml`:

```yaml
Balance_Top:
  Enabled: true
  Size: 45
  Update_Interval: 300
```

Si no encuentra una economía compatible, el módulo se queda inactivo.

## Configuración

| Opción | Por defecto | Qué hace |
|---|---|---|
| `Enabled` | `true` | Enciende `/baltop`. |
| `Size` | `45` | Cuántos jugadores entran en el ranking. Máximo 54. |
| `Update_Interval` | `300` | Segundos entre recálculos en segundo plano. |

Al abrir `/baltop` también se pide un cálculo nuevo, así que un cambio reciente de saldo aparece en cuanto termina, sin esperar al siguiente ciclo. Varias peticiones seguidas comparten un mismo cálculo.

La línea de saldo y la de puesto de cada jugador salen de los archivos de idioma (`baltop.gui-balance` y `baltop.gui-rank`), así que puedes cambiarles el estilo sin tocar el plugin. Ver [Idiomas y mensajes](localization).

## Comandos y permisos

| Comando | Permiso (por defecto) | Qué hace |
|---|---|---|
| `/baltop` (`/moneytop`, `/balancetop`, `/richest`) | `chatalyst.command.baltop` (true) | Abre el ranking. |

## Límites

- El ranking lee los saldos a través de Vault. Se salta a los jugadores cuyo saldo no puede consultar el plugin de economía mientras están desconectados.
- Enseña el ranking de la economía de este servidor. En una red, cada servidor ordena su propia economía salvo que tu plugin de economía comparta los saldos.
- El ranking es solo eso, un ranking. Chatalyst no trae una economía propia; para comerciar, ver [Mercado e intercambios](market).
