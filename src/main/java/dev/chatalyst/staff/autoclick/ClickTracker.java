package dev.chatalyst.staff.autoclick;

import java.util.UUID;

/**
 * Estado de un jugador: filtro de balanceos + ventana de clics. Lo escriben el hilo de red (o
 * el de su region) y lo lee la evaluacion asincrona; cada operacion es corta y va bajo el
 * cerrojo del propio objeto, asi que dos jugadores nunca se esperan entre si.
 */
public final class ClickTracker {

    private final UUID id;
    private final boolean bedrock;
    private final ClickFilter filter = new ClickFilter();
    private final ClickSeries series;

    public ClickTracker(UUID id, boolean bedrock, int capacity, long maxGapNanos) {
        this.id = id;
        this.bedrock = bedrock;
        this.series = new ClickSeries(capacity, maxGapNanos);
    }

    /** Floodgate fabrica los UUID de Bedrock con los 64 bits altos a cero (00000000-0000-0000-). */
    public static boolean isBedrock(UUID id) {
        return id != null && id.getMostSignificantBits() == 0L;
    }

    public UUID id() {
        return id;
    }

    public boolean bedrock() {
        return bedrock;
    }

    public synchronized void swing(long nanos) {
        if (filter.swing(nanos)) series.add(nanos);
    }

    public synchronized void digStart(long nanos) {
        filter.digStart(nanos);
    }

    public synchronized void digStop(long nanos, boolean finished) {
        filter.digStop(nanos, finished);
    }

    public synchronized void use(long nanos) {
        filter.use(nanos);
    }

    public synchronized ClickAnalysis.Verdict evaluate(Thresholds thresholds, long now,
                                                       boolean highResolution) {
        return ClickAnalysis.evaluate(series, thresholds, now, highResolution);
    }

    public synchronized ClickAnalysis.Live live(Thresholds thresholds, long now) {
        return ClickAnalysis.live(series, thresholds, now);
    }
}
