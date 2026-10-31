package dev.chatalyst.market;

import java.util.UUID;

/**
 * Transferencia Vault compensable. Los items solo deben moverse cuando devuelve SUCCESS.
 * Si el abono falla, devuelve el retiro al pagador; si tambien falla esa devolucion, la
 * registra en el ledger durable que proporciona {@link CompensationSink}.
 */
final class EconomyTransaction {

    enum Result { SUCCESS, WITHDRAW_FAILED, CREDIT_FAILED, COMPENSATION_FAILED }

    interface Gateway {
        boolean withdraw(UUID player, double amount);

        boolean deposit(UUID player, double amount);
    }

    @FunctionalInterface
    interface CompensationSink {
        boolean queue(UUID player, double amount);
    }

    private EconomyTransaction() {
    }

    static Result transfer(UUID from, UUID to, double amount, Gateway gateway,
                           CompensationSink compensation) {
        if (amount == 0 || from.equals(to)) return Result.SUCCESS;
        if (!Double.isFinite(amount) || amount < 0) return Result.WITHDRAW_FAILED;
        if (!gateway.withdraw(from, amount)) return Result.WITHDRAW_FAILED;
        if (gateway.deposit(to, amount)) return Result.SUCCESS;

        // No hay entrega de items tras este punto. Intentamos dejar al pagador exactamente
        // como estaba y, si el proveedor tambien rechaza el reintegro, lo persistimos.
        if (!gateway.deposit(from, amount) && !compensation.queue(from, amount)) {
            return Result.COMPENSATION_FAILED;
        }
        return Result.CREDIT_FAILED;
    }
}
