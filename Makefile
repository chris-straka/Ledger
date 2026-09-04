# Bare `make` lists every target that has a `## comment` after its name.
.DEFAULT_GOAL := help
help:
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

# .PHONY tells Make these are cmd names, not files on the hard drive.
.PHONY: help dev up upd down clean format check test integration-test crash-test verify demo logs

dev: ## Tilt development loop
	tilt up

up: ## docker compose up (foreground)
	docker compose up --remove-orphans

upd: ## docker compose up -d
	docker compose up -d

down: ## docker compose down
	docker compose down

clean: ## docker compose down -v (WIPES all local ledger data and volumes)
	docker compose down -v

format: ## apply code formatting
	./gradlew spotlessApply

check: ## format check + unit tests + integration tests
	./gradlew check

test: ## fast unit/domain tests (no Docker needed)
	./gradlew test

integration-test: ## real PostgreSQL suite (Testcontainers; incl. concurrency proof)
	./gradlew integrationTest

crash-test: ## isolated SIGKILL harness (own Compose project and volumes)
	./scripts/crash-recovery.sh

verify: ## non-empty integrity audit (posting closure + per-currency conservation)
	./scripts/verify.sh

demo: ## reproducible interviewer walkthrough
	./scripts/demo.sh

logs: ## scoped service logs; make logs c=ledger-api
	docker compose logs -f $(c)
