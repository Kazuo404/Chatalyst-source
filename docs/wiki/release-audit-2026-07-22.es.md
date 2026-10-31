# Auditoría de entrega — 2026-07-22

Este documento cierra la revisión documentación-contra-diff de la entrega actual de Chatalyst
1.0.0. Abarca todo el worktree pendiente y no una sola feature. Es evidencia de revisión de código,
configuración y pruebas deterministas; no sustituye una matriz real de Paper/Purpur/Folia y plugins
de terceros.

## Mapa de la entrega

| Área | Documentación operativa | Arquitectura / evidencia de entrega |
|---|---|---|
| Mercado, subastas y trade directo | [Mercado e intercambio directo](market.es.md) | [ADR 0009](../adr/0009-market-contract-ui-and-durable-drafts.md) |
| GUI premium unificada, categorías y páginas | [Paleta](paleta.es.md) | [ADR 0010](../adr/0010-unified-purple-space-gui-system.md) |
| Animación de nametag flotante/TAB/chat y frames de announce | [Nametags flotantes](nametags.es.md), [Anuncios](announcements.es.md) | Changelog y regresiones de animación |
| Modo anónimo, inventarios compartidos y auditoría de cofres | [Registro de cofres, compartir inventario y modo anónimo](spy-anonymous.es.md) | Pruebas de identidad de sesión, auditoría y tokens acotados |
| Matrimonio y ceremonia | [Matrimonios](marriage.es.md) | Regresiones de estado, ceremonia y persistencia |
| Reglas, logs y seguridad de credenciales en comandos | [Motor de reglas](rules-engine.es.md) | [ADR 0011](../adr/0011-sensitive-command-redaction-boundary.md), regresiones de texto proyectado |
| Recarga de storage y hardening de red | [Almacenamiento y base de datos](database.es.md), [Proxy y Redis](proxy-redis.es.md), [Smoke test](smoke-test.es.md) | [ADR 0012](../adr/0012-targeted-rule-console-protocol.md), codecs y gates de operación |
| Moderación de staff y pantallas premium de castigo | [Suite de moderación](staff-suite.es.md) | Pruebas de idioma, preservación de templates y catálogo de comandos |

Todo texto runtime visible para jugadores añadido por esta entrega vive en `lang/en_US.yml` y
`lang/es_ES.yml`; la cobertura de claves Java y la paridad exacta de idiomas son gates del build.
La versión 30 de configuración migra colores y templates de fábrica de forma idempotente, sin
alterar textos personalizados por el owner ni paletas seleccionables por jugadores.

## Resultado de seguridad y fiabilidad

La revisión acumulada de todo el plugin cerró 25 defectos en esta entrega. Se revisaron ciclo de
vida/recarga, durabilidad YAML/SQLite/MySQL, codecs Redis y proxy, consumidores HTTP, propiedad de
región en Folia, escrow del mercado, autorización de GUI, límites del modo anónimo, auditoría y
motor de reglas.

El último hallazgo, y el de mayor impacto, fue una ruta de exposición de credenciales en las
salidas de reglas. Un comando como `/login secret.example` podía coincidir con una regla amplia de
publicidad/IP y llegar después al log `RULE`, Discord de staff o un `Notify` mediante
`{original_message}`. La frontera compartida de comandos sensibles conserva ahora únicamente
`/login <redacted>` para observadores externos. Las etiquetas obligatorias siguen protegidas aunque
se sustituyan las listas configurables, y separar con tabuladores no evade la redacción. El aviso
privado que recibe el propio remitente permanece intacto.

Otras garantías de toda la entrega verificadas durante la revisión:

- cantidades de reglas, nombres, trabajo de regex, textos de reportes/correo, cuerpos HTTP y
  buffers de red están acotados;
- transiciones atómicas o durables en reload, drafts del mercado, escrow, claims, confirmaciones
  y storage por jugador;
- autenticación fail-closed y límites de capacidad en tráfico proxy/Redis;
- identidad y localización por espectador sin filtrar nombres reales de anónimos;
- paginación estable/acotada y permisos recomprobados al ejecutar acciones de GUI.

La verificación final de rules v2.1 encontró seis defectos adicionales tras el primer borrador:
el preprocesado de acentos/UUID podía alterar el texto entregado; la consola remota ignoraba el
destino y duplicaba en modo de doble ruta; peers autenticados carecían de autorización local de
comandos; el contexto Discord no estaba conectado; imports inválidos/repetidos podían fallar en
silencio o ejecutar dos veces; y `@prolong` truncaba Unicode suplementario. Ahora las coincidencias
se proyectan al original, los imports son estrictos/first-wins, Discord vinculado y online usa el
scheduler de entidad, y la consola remota es dirigida, autorizada, deduplicada y auditada.

## Base de verificación

- Build Gradle limpio y suite automatizada completa: 551 pruebas, cero fallos.
- Parseo YAML, paridad de idiomas, cobertura de claves de idioma Java y descriptor del plugin.
- Firma Ed25519 e integridad del jar de release verificadas.
- Hash del jar staged comparado después del despliegue.
- `git diff --check` y escaneo del repositorio para claves privadas, tokens de webhook y
  credenciales comunes de proveedores antes del commit.

El riesgo operativo restante es la matriz de servidor real: ejecuta el [checklist bilingüe de
smoke test](smoke-test.es.md) sobre las versiones exactas de Paper/Purpur/Folia y plugins de
integración usadas en producción antes de reiniciar una red activa.
