package dev.chatalyst.util;

import java.util.regex.Pattern;

/**
 * Ejecuta regex de la CONFIG sobre texto del JUGADOR con un presupuesto de tiempo: un patron
 * con backtracking catastrofico ("(\\w+\\s?)+$") mas un mensaje diseñado clavarian el hilo de
 * chat (o el principal, en signs/anvil) sin este corte. El truco es un CharSequence cuyo
 * charAt — que el motor de regex toca en cada paso de backtracking — lanza al agotar el plazo.
 *
 * <p>Fail-open por diseño: el que llama trata el timeout como "no matchea" y avisa al admin,
 * porque bloquear el chat legitimo por una regla mal escrita seria peor que saltarsela.
 */
public final class SafeRegex {

    /** 50 ms por patron y mensaje: ordenes de magnitud sobre cualquier matcheo legitimo. */
    public static final long DEFAULT_BUDGET_NANOS = 50_000_000L;

    // Cuantas veces un matcheo agoto su presupuesto (regla o palabra de filtro demasiado
    // pesada). Solo se toca en la ruta lenta rara, asi que no cuesta nada en el caso normal.
    // Lo lee /chatalyst perf como senal de "hay un patron problematico en la config".
    private static final java.util.concurrent.atomic.AtomicLong BUDGET_EXCEEDED =
            new java.util.concurrent.atomic.AtomicLong();

    /** Total de matcheos que agotaron su presupuesto desde el arranque (diagnostico). */
    public static long budgetExceededCount() {
        return BUDGET_EXCEEDED.get();
    }

    /** Lanzada cuando el matcheo agota su presupuesto. Sin stacktrace: es control de flujo. */
    public static final class BudgetExceededException extends RuntimeException {
        BudgetExceededException() {
            super(null, null, false, false);
        }
    }

    private SafeRegex() {
    }

    /** {@code pattern.matcher(text).find()} con presupuesto. */
    public static boolean find(Pattern pattern, String text, long budgetNanos)
            throws BudgetExceededException {
        return pattern.matcher(budgeted(text, budgetNanos)).find();
    }

    /** {@code pattern.matcher(text).matches()} con presupuesto. */
    public static boolean matches(Pattern pattern, String text, long budgetNanos)
            throws BudgetExceededException {
        return pattern.matcher(budgeted(text, budgetNanos)).matches();
    }

    /** {@code pattern.matcher(text).replaceAll(replacement)} con presupuesto. */
    public static String replaceAll(Pattern pattern, String text, String replacement,
            long budgetNanos) throws BudgetExceededException {
        return pattern.matcher(budgeted(text, budgetNanos)).replaceAll(replacement);
    }

    /**
     * Envuelve un texto para un Matcher propio: cada charAt que toque el motor de regex
     * chequea el plazo y lanza {@link BudgetExceededException} al agotarlo. Para casos que
     * necesitan el Matcher directo (p. ej. reemplazo con logica por coincidencia).
     */
    public static CharSequence budgeted(String text, long budgetNanos) {
        return new BudgetedCharSequence(text, System.nanoTime() + budgetNanos);
    }

    private static final class BudgetedCharSequence implements CharSequence {
        private final CharSequence text;
        private final long deadline;

        BudgetedCharSequence(CharSequence text, long deadline) {
            this.text = text;
            this.deadline = deadline;
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() - deadline > 0) {
                BUDGET_EXCEEDED.incrementAndGet();
                throw new BudgetExceededException();
            }
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new BudgetedCharSequence(text.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
