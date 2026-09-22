# Bare `make` lists every target that has a `## comment` after its name.
.DEFAULT_GOAL := help
help:
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

# .PHONY tells Make these are cmd names, not files on the hard drive.
.PHONY: help dev jar up upd down clean format check test integration-test crash-test verify demo logs backup restore

jar: ## build the application jar (Gradle is incremental; safe to re-run)
	./gradlew build

dev: ## Tilt development loop
	tilt up

up: jar ## docker compose up (foreground; builds the jar first)
	docker compose up --remove-orphans

upd: jar ## docker compose up -d (builds the jar first)
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

backup: ## pg_dump of the dev DB (lands in backups/, gitignored; compose stack must be up)
	chmod +x scripts/*.sh
	./scripts/backup.sh

restore: ## restore a backup into a disposable DB and audit it (DUMP=backups/ledger-<stamp>.dump)
	chmod +x scripts/*.sh
	./scripts/restore.sh "$(DUMP)"

demo: ## reproducible interviewer walkthrough
	./scripts/demo.sh

logs: ## scoped service logs; make logs c=ledger-api
	docker compose logs -f $(c)
