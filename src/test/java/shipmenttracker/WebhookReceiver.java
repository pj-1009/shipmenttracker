package shipmenttracker;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/** A local HTTP server that records webhook calls, for tests. */
final class WebhookReceiver implements AutoCloseable {

    record Call(String body, String signature, String eventId, Instant at) {
        JsonNode json() throws IOException {
            return TestServer.JSON.readTree(body);
        }
    }

    private final HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    /** Status codes to answer with, in order; once used up, answers 200. */
    private final ConcurrentLinkedQueue<Integer> answers = new ConcurrentLinkedQueue<>();

    WebhookReceiver() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            String body;
            try (InputStream in = ex.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            calls.add(new Call(body,
                    ex.getRequestHeaders().getFirst(WebhookDispatcher.SIGNATURE_HEADER),
                    ex.getRequestHeaders().getFirst(WebhookDispatcher.EVENT_ID_HEADER),
                    Instant.now()));
            Integer code = answers.poll();
            ex.sendResponseHeaders(code == null ? 200 : code, -1);
            ex.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    /** Answer the next calls with these status codes (then 200). */
    void answerWith(Integer... codes) {
        answers.addAll(List.of(codes));
    }

    List<Call> calls() {
        return new ArrayList<>(calls);
    }

    /** Waits until at least {@code n} recorded calls match, and returns all matching calls. */
    List<Call> await(int n, Predicate<Call> match) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            List<Call> got = calls.stream().filter(match).toList();
            if (got.size() >= n) {
                return got;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("expected " + n + " webhook calls, got "
                + calls.stream().filter(match).count() + " (all calls: " + calls + ")");
    }

    List<Call> await(int n) throws InterruptedException {
        return await(n, c -> true);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
