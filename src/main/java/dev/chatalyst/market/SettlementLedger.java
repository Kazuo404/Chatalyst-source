package dev.chatalyst.market;

import dev.chatalyst.db.Store;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Write-ahead ledger for operations that cross Vault and an item escrow. Vault has no
 * idempotency key, so PROCESSING is intentionally never replayed automatically: an operator
 * must inspect the economy history/balances and declare it paid or unpaid.
 */
final class SettlementLedger {

    enum Kind { SALE, AUCTION, TRADE }
    enum Status { PREPARED, PROCESSING, PAID, UNPAID }
    enum Source { LISTING, ESCROW }

    record Transfer(Source source, String marker, UUID originalOwner, UUID paidOwner) {
        static Transfer of(Source source, UUID originalOwner, UUID paidOwner, String marker) {
            return new Transfer(source, marker == null ? "" : marker, originalOwner, paidOwner);
        }
    }

    record Settlement(String id, Kind kind, Status status, UUID payer, UUID payee,
                      double amount, int listingId, long created, List<Transfer> transfers) {
        Settlement withStatus(Status next) {
            return new Settlement(id, kind, next, payer, payee, amount, listingId, created,
                    transfers);
        }
    }

    private final Store store;

    SettlementLedger(Store store) {
        this.store = store;
    }

    Settlement create(Kind kind, UUID payer, UUID payee, double amount, int listingId,
                      List<Transfer> transfers) {
        if (kind == null || payer == null || payee == null || !Double.isFinite(amount)
                || amount < 0 || transfers == null
                || (amount == 0 && transfers.isEmpty())) return null;
        if (transfers.stream().anyMatch(t -> t == null || t.marker().isBlank())) return null;
        Settlement settlement = new Settlement(UUID.randomUUID().toString(), kind,
                Status.PREPARED, payer, payee, amount, listingId, System.currentTimeMillis(),
                List.copyOf(transfers));
        return store.compareAndPutDurably(settlement.id(), null, encode(settlement))
                ? settlement : null;
    }

    Settlement transition(Settlement expected, Status next) {
        if (expected == null || next == null) return null;
        Settlement changed = expected.withStatus(next);
        return store.compareAndPutDurably(expected.id(), encode(expected), encode(changed))
                ? changed : null;
    }

    boolean remove(Settlement settlement) {
        return settlement != null
                && store.compareAndRemoveDurably(settlement.id(), encode(settlement));
    }

    List<Settlement> all(Kind... kinds) {
        Set<Kind> accepted = kinds == null || kinds.length == 0
                ? Set.of() : Set.of(kinds);
        List<Settlement> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : store.all().entrySet()) {
            Settlement settlement = decode(entry.getValue());
            if (settlement != null && entry.getKey().equals(settlement.id())
                    && (accepted.isEmpty() || accepted.contains(settlement.kind()))) {
                out.add(settlement);
            }
        }
        out.sort(java.util.Comparator.comparingLong(Settlement::created));
        return out;
    }

    Set<String> blockedMarkers(Kind... kinds) {
        return all(kinds).stream().flatMap(s -> s.transfers().stream())
                .filter(t -> t.source() == Source.ESCROW && !t.marker().isBlank())
                .map(Transfer::marker).collect(Collectors.toUnmodifiableSet());
    }

    Settlement get(String id) {
        if (id == null) return null;
        Settlement settlement = decode(store.get(id));
        return settlement != null && id.equals(settlement.id()) ? settlement : null;
    }

    private static String encode(Settlement settlement) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(1);
                out.writeUTF(settlement.id());
                out.writeUTF(settlement.kind().name());
                out.writeUTF(settlement.status().name());
                out.writeUTF(settlement.payer().toString());
                out.writeUTF(settlement.payee().toString());
                out.writeDouble(settlement.amount());
                out.writeInt(settlement.listingId());
                out.writeLong(settlement.created());
                out.writeInt(settlement.transfers().size());
                for (Transfer transfer : settlement.transfers()) {
                    out.writeUTF(transfer.source().name());
                    out.writeUTF(transfer.marker());
                    out.writeUTF(transfer.originalOwner().toString());
                    out.writeUTF(transfer.paidOwner().toString());
                }
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Settlement decode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(
                Base64.getDecoder().decode(raw)))) {
            if (in.readInt() != 1) return null;
            String id = in.readUTF();
            Kind kind = Kind.valueOf(in.readUTF());
            Status status = Status.valueOf(in.readUTF());
            UUID payer = UUID.fromString(in.readUTF());
            UUID payee = UUID.fromString(in.readUTF());
            double amount = in.readDouble();
            int listingId = in.readInt();
            long created = in.readLong();
            int count = in.readInt();
            if (id.isBlank() || !Double.isFinite(amount) || amount < 0 || count < 0
                    || count > 64) return null;
            List<Transfer> transfers = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Source source = Source.valueOf(in.readUTF());
                String marker = in.readUTF();
                UUID original = UUID.fromString(in.readUTF());
                UUID paid = UUID.fromString(in.readUTF());
                transfers.add(new Transfer(source, marker, original, paid));
            }
            if (amount == 0 && transfers.isEmpty()) return null;
            if (in.available() != 0) return null;
            return new Settlement(id, kind, status, payer, payee, amount, listingId, created,
                    List.copyOf(transfers));
        } catch (RuntimeException | java.io.IOException invalid) {
            return null;
        }
    }
}
