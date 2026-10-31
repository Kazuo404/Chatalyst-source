package dev.chatalyst.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Body handlers HTTP que cancelan la descarga al superar un limite duro de bytes. */
public final class LimitedBodyHandlers {

    private LimitedBodyHandlers() {
    }

    public static HttpResponse.BodyHandler<String> ofString(int maxBytes, Charset charset) {
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        if (charset == null) throw new IllegalArgumentException("charset is required");
        return info -> subscriber(maxBytes, charset, info.headers()
                .firstValueAsLong("Content-Length").orElse(-1L));
    }

    /** Visible en el paquete para probar limites y cancelacion sin abrir sockets. */
    static HttpResponse.BodySubscriber<String> subscriber(
            int maxBytes, Charset charset, long declaredLength) {
        return new LimitedStringSubscriber(maxBytes, charset, declaredLength);
    }

    private static final class LimitedStringSubscriber
            implements HttpResponse.BodySubscriber<String> {
        private final int maxBytes;
        private final Charset charset;
        private final boolean declaredTooLarge;
        private final ByteArrayOutputStream bytes;
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private LimitedStringSubscriber(int maxBytes, Charset charset, long declaredLength) {
            this.maxBytes = maxBytes;
            this.charset = charset;
            this.declaredTooLarge = declaredLength > maxBytes;
            this.bytes = new ByteArrayOutputStream(Math.min(maxBytes, 8_192));
        }

        @Override
        public CompletionStage<String> getBody() {
            return body;
        }

        @Override
        public synchronized void onSubscribe(Flow.Subscription next) {
            if (next == null) {
                fail(new NullPointerException("subscription"));
                return;
            }
            if (subscription != null || body.isDone()) {
                next.cancel();
                return;
            }
            subscription = next;
            if (declaredTooLarge) {
                next.cancel();
                fail(tooLarge());
                return;
            }
            next.request(1);
        }

        @Override
        public synchronized void onNext(List<ByteBuffer> chunks) {
            if (body.isDone()) return;
            try {
                for (ByteBuffer chunk : chunks) {
                    int length = chunk.remaining();
                    if (length > maxBytes - bytes.size()) {
                        if (subscription != null) subscription.cancel();
                        fail(tooLarge());
                        return;
                    }
                    byte[] copy = new byte[length];
                    chunk.get(copy);
                    bytes.write(copy, 0, copy.length);
                }
                if (subscription != null) subscription.request(1);
            } catch (RuntimeException failure) {
                if (subscription != null) subscription.cancel();
                fail(failure);
            }
        }

        @Override
        public synchronized void onError(Throwable failure) {
            fail(failure);
        }

        @Override
        public synchronized void onComplete() {
            if (!body.isDone()) body.complete(bytes.toString(charset));
        }

        private IOException tooLarge() {
            return new IOException("HTTP response exceeded " + maxBytes + " bytes");
        }

        private void fail(Throwable failure) {
            body.completeExceptionally(failure);
        }
    }
}
