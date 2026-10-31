package dev.chatalyst.channel;

/**
 * Alcance de un canal:
 * <ul>
 *   <li>{@link #GLOBAL}: lo ve todo el que escuche el canal, en cualquier mundo.</li>
 *   <li>{@link #WORLD}: solo jugadores en el mismo mundo que quien escribe.</li>
 *   <li>{@link #FREQUENCY}: chat local por RADIO — solo se oyen quienes estan sintonizados a
 *       la misma frecuencia (NN.NN), sin importar la distancia. Cada jugador elige la suya.</li>
 *   <li>{@link #REGION}: solo jugadores que comparten una region de WorldGuard.</li>
 *   <li>{@link #PARTY}: solo miembros del mismo party (Parties de AlessioDP).</li>
 *   <li>{@link #GROUP}: solo jugadores con el mismo valor de un placeholder, lo que sirve para
 *       clanes, equipos o gremios de cualquier plugin sin depender de su API.</li>
 * </ul>
 */
public enum ChannelType {
    GLOBAL,
    WORLD,
    FREQUENCY,
    REGION,
    PARTY,
    GROUP
}
