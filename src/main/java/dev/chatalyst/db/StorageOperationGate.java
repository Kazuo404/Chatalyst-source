package dev.chatalyst.db;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Pausa brevemente las operaciones de Store mientras StorageManager cambia la conexion.
 * El lock justo evita que un flujo continuo de chat/escrituras deje esperando para siempre
 * al reload. ReentrantReadWriteLock permite que el hilo que posee write tome read al llamar
 * flush/refresh sobre los SqlStore durante la transaccion.
 */
final class StorageOperationGate {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    Lock shared() {
        return lock.readLock();
    }

    Lock exclusive() {
        return lock.writeLock();
    }
}
