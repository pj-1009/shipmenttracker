# Everything runs in Docker, so the only host tools needed are docker, kind and kubectl.

IMAGE      := shipmenttracker:dev
CLUSTER    := shipmenttracker
KUBE       := kubectl --context kind-$(CLUSTER) -n shipmenttracker
MVN_IMAGE  := maven:3.9.9-eclipse-temurin-21
PG_IMAGE   := postgres:16-alpine

# Throwaway database for tests, on host port 5433 (5432 is often taken).
TEST_NET   := shipmenttracker-test
TEST_DB    := shipmenttracker-test-db

.PHONY: test test-db test-db-down image cluster deploy status port-forward admin-token undeploy cluster-down

## Tests: start a throwaway Postgres, then run `mvn test` in a Maven container.
test: test-db
	docker run --rm --network $(TEST_NET) \
	  -v "$(CURDIR)":/src -w /src -v shipmenttracker-m2:/root/.m2 \
	  -e DATABASE_URL=jdbc:postgresql://$(TEST_DB):5432/shipments \
	  -e DATABASE_USER=shipments -e DATABASE_PASSWORD=shipments \
	  $(MVN_IMAGE) mvn -B -q test

test-db:
	@docker network inspect $(TEST_NET) >/dev/null 2>&1 || docker network create $(TEST_NET) >/dev/null
	@docker inspect $(TEST_DB) >/dev/null 2>&1 || docker run -d --name $(TEST_DB) --network $(TEST_NET) \
	  -p 5433:5432 -e POSTGRES_DB=shipments -e POSTGRES_USER=shipments -e POSTGRES_PASSWORD=shipments \
	  $(PG_IMAGE) >/dev/null
	@echo "waiting for test database..."
	@until docker exec $(TEST_DB) pg_isready -U shipments -d shipments >/dev/null 2>&1; do sleep 1; done
	@sleep 1

test-db-down:
	-docker rm -f $(TEST_DB)
	-docker network rm $(TEST_NET)

## Container image
image:
	docker build -t $(IMAGE) .

## Kubernetes (a separate kind cluster; never touches other clusters)
cluster:
	@if kind get clusters | grep -qx $(CLUSTER); then echo "cluster $(CLUSTER) already exists"; else \
	  prev=$$(kubectl config current-context 2>/dev/null); \
	  kind create cluster --config kind-config.yaml; \
	  if [ -n "$$prev" ]; then kubectl config use-context "$$prev" >/dev/null; echo "kubectl context left on $$prev"; fi; \
	fi

deploy: image cluster
	kind load docker-image $(IMAGE) --name $(CLUSTER)
	kubectl --context kind-$(CLUSTER) apply -f k8s/
	@# Make the auth secret once, with random values, so it never lives in the repo.
	@$(KUBE) get secret shipmenttracker-auth >/dev/null 2>&1 || \
	  $(KUBE) create secret generic shipmenttracker-auth \
	    --from-literal=JWT_SECRET="$$(openssl rand -base64 48)" \
	    --from-literal=ADMIN_TOKEN="$$(openssl rand -hex 32)"
	$(KUBE) rollout restart deployment/shipmenttracker
	$(KUBE) rollout status statefulset/postgres --timeout=180s
	$(KUBE) rollout status deployment/shipmenttracker --timeout=180s

status:
	$(KUBE) get pods,svc,pvc

## Reach the API at http://localhost:9090 (8080 is often taken)
port-forward:
	$(KUBE) port-forward svc/shipmenttracker 9090:8080

## Print the admin token, for calling /admin/* (keep it private)
admin-token:
	@$(KUBE) get secret shipmenttracker-auth -o jsonpath='{.data.ADMIN_TOKEN}' | base64 -d; echo

undeploy:
	kubectl --context kind-$(CLUSTER) delete -f k8s/ --ignore-not-found

cluster-down:
	kind delete cluster --name $(CLUSTER)
