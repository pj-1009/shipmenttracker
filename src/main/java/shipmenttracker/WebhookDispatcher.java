package shipmenttracker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Sends queued webhook deliveries in the background, retrying failures.
 *
 * <p>Each pod runs one dispatcher. They share the outbox safely: a delivery is
 * claimed before it's sent, so two pods never send the same one at the same
 * time. Each webhook gets its events one at a time, in the order they
 * happened; a failing delivery holds back the later ones until it succeeds or
 * is given up on. A delivery can still arrive twice (say a pod dies after
 * sending but before recording success), so receivers should ignore event ids
 * they've already seen.
 *
 * <p>Every request carries {@code X-Shipmenttracker-Signature: t=<unix seconds>,v1=<hex>},
 * where v1 is HMAC-SHA256 of {@code "<t>.<body>"} keyed by the webhook's secret.
 */
public final class WebhookDispatcher implements AutoCloseable {

    public static final String SIGNATURE_HEADER = "X-Shipmenttracker-Signature";
    public static final String EVENT_ID_HEADER = "X-Shipmenttracker-Event-Id";

    /**
     * @param allowPrivate  accept http and private addresses (local development and tests only)
     * @param maxAttempts   give up after this many tries
     * @param retryBase     wait before the 2nd try; doubles each time, up to {@code retryMax}
     * @param retryMax      longest wait between tries
     * @param pollInterval  how often to look for due deliveries when idle
     * @param callbackRetention  how long a finished order's callback (and its delivery log) is kept
     * @param cleanupInterval    how often to remove callbacks past their retention
     */
    public record Config(boolean allowPrivate, int maxAttempts, Duration retryBase, Duration retryMax,
                         Duration pollInterval, Duration callbackRetention, Duration cleanupInterval) {

        /**
         * Safe defaults: https to public addresses only; 8 tries over about 4
         * minutes; finished orders' callbacks kept for a day.
         */
        public static Config defaults() {
            return new Config(false, 8, Duration.ofSeconds(2), Duration.ofHours(1), Duration.ofSeconds(1),
                    Duration.ofDays(1), Duration.ofMinutes(1));
        }
    }

    private static final int BATCH = 20;
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final WebhookStore store;
    private final Config config;
    private final Clock clock;
    private final HttpClient client;
    private final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();
    private final Thread loop;
    private volatile boolean running = true;

    public WebhookDispatcher(WebhookStore store, Config config) {
        this(store, config, Clock.systemUTC());
    }

    WebhookDispatcher(WebhookStore store, Config config, Clock clock) {
        this.store = store;
        this.config = config;
        this.clock = clock;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER) // a redirect could lead to an internal address
                .executor(senders)
                .build();
        this.loop = Thread.ofVirtual().name("webhook-dispatcher").unstarted(this::run);
    }

    public void start() {
        loop.start();
    }

    @Override
    public void close() {
        running = false;
        loop.interrupt();
        try {
            loop.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        senders.shutdownNow();
    }

    private void run() {
        java.time.Instant nextCleanup = java.time.Instant.EPOCH;
        while (running) {
            try {
                if (!clock.instant().isBefore(nextCleanup)) {
                    store.deleteFinishedCallbacks(config.callbackRetention());
                    nextCleanup = clock.instant().plus(config.cleanupInterval());
                }
                List<WebhookStore.Claimed> batch = store.claim(BATCH, LEASE);
                for (WebhookStore.Claimed d : batch) {
                    senders.submit(() -> send(d));
                }
                if (batch.size() < BATCH) {
                    Thread.sleep(config.pollInterval());
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                System.err.println("webhook dispatcher: " + e.getMessage());
                try {
                    Thread.sleep(config.pollInterval());
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void send(WebhookStore.Claimed d) {
        Integer status = null;
        String error;
        try {
            // Check again at send time: the host's DNS may have changed since registration.
            URI uri = WebhookUrls.check(d.url(), config.allowPrivate());
            if (d.secret() == null) {
                // Can't happen through the API (orders with callbacks need a secret first); never send unsigned.
                store.markFailed(d.id(), null, "no signing secret");
                return;
            }
            long t = clock.instant().getEpochSecond();
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "shipmenttracker-webhooks/1")
                    .header(EVENT_ID_HEADER, d.eventId().toString())
                    .header(SIGNATURE_HEADER, "t=" + t + ",v1=" + sign(d.secret(), t, d.payload()))
                    .POST(HttpRequest.BodyPublishers.ofString(d.payload()))
                    .build();
            HttpResponse<Void> res = client.send(req, HttpResponse.BodyHandlers.discarding());
            status = res.statusCode();
            if (status >= 200 && status < 300) {
                store.markDelivered(d.id(), status);
                return;
            }
            error = "receiver answered " + status;
        } catch (WebhookUrls.InvalidUrl e) {
            error = e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return; // shutting down; the lease runs out and another pod (or this one, later) retries
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        try {
            if (d.attempts() >= config.maxAttempts()) {
                store.markFailed(d.id(), status, error);
            } else {
                store.markRetry(d.id(), status, error, backoff(d.attempts()));
            }
        } catch (RuntimeException e) {
            System.err.println("webhook dispatcher: could not record failure: " + e.getMessage());
        }
    }

    /** Wait before the next try, after {@code attempts} tries so far. */
    Duration backoff(int attempts) {
        long factor = 1L << Math.min(attempts - 1, 20);
        Duration d = config.retryBase().multipliedBy(factor);
        return d.compareTo(config.retryMax()) > 0 ? config.retryMax() : d;
    }

    /** HMAC-SHA256 of "<t>.<body>" with the webhook secret, as lowercase hex. */
    public static String sign(String secret, long t, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((t + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
