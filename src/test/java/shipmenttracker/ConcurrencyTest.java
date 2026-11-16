package shipmenttracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Many requests at the same moment must not lose, duplicate or mix up shipments. */
class ConcurrencyTest {

    private static final int PER_TENANT = 250;

    @Test
    void parallelCreatesGetUniqueIdsAndReachTheRightTenant() throws Exception {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
             TestServer server = new TestServer(pool)) {
            TestServer.Account acme = server.tenantWithUser("acme", "alice");
            TestServer.Account globex = server.tenantWithUser("globex", "bob");
            TestServer.Account fast = server.carrierWithWorker("fastfreight", "dave");

            List<Callable<String>> jobs = new ArrayList<>();
            for (int i = 0; i < PER_TENANT; i++) {
                for (TestServer.Account tenant : List.of(acme, globex)) {
                    String tracking = "TRK" + i;
                    jobs.add(() -> server.createShipment(fast, tenant.orgId(), tracking));
                }
            }

            Set<String> ids = new HashSet<>();
            for (Future<String> f : runTogether(pool, jobs)) {
                assertTrue(ids.add(f.get()), "duplicate id " + f.get());
            }
            assertEquals(2 * PER_TENANT, ids.size());

            for (TestServer.Account tenant : List.of(acme, globex)) {
                TestServer.Response list = server.get("/shipments", tenant.token());
                assertEquals(PER_TENANT, list.body().size());
                list.body().forEach(s -> assertEquals(tenant.orgId(), s.get("tenantId").asLong()));
            }
        }
    }

    @Test
    void onlyOneWorkerWinsARaceToPickUp() throws Exception {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
             TestServer server = new TestServer(pool)) {
            TestServer.Account acme = server.tenantWithUser("acme", "alice");
            TestServer.Account first = server.carrierWithWorker("fastfreight", "worker0");
            List<TestServer.Account> workers = new ArrayList<>(List.of(first));
            for (int i = 1; i < 20; i++) {
                workers.add(server.addUser("carrierId", first.orgId(), "worker" + i));
            }
            String id = server.createShipment(first, acme.orgId(), "RACE-1");

            List<Callable<Integer>> jobs = new ArrayList<>();
            for (TestServer.Account w : workers) {
                jobs.add(() -> server.setStatus(w, id, "PICKED_UP").status());
            }
            int ok = 0;
            int conflict = 0;
            for (Future<Integer> f : runTogether(pool, jobs)) {
                if (f.get() == 200) {
                    ok++;
                } else if (f.get() == 409) {
                    conflict++;
                }
            }
            assertEquals(1, ok, "exactly one worker picks it up");
            assertEquals(workers.size() - 1, conflict, "everyone else is told it's already taken");

            TestServer.Response history = server.get("/shipments/" + id + "/history", acme.token());
            assertEquals(2, history.body().size(), "CREATED + one PICKED_UP");
        }
    }

    /** Holds every job at a gate, then releases them together so they really overlap. */
    private static <T> List<Future<T>> runTogether(ExecutorService pool, List<Callable<T>> jobs) {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> job : jobs) {
            futures.add(pool.submit(() -> {
                go.await();
                return job.call();
            }));
        }
        go.countDown();
        return futures;
    }
}
