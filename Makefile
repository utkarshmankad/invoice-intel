# One-command local workflow (NFR-7, architecture.md §14). Values come from .env (copy
# .env.example); compose and the `local` profile both read it.
#
#   make up run     # start Postgres + SeaweedFS, then the app on 127.0.0.1:8080
#   make test       # full build: format, unit, integration, coverage, SpotBugs
#   make down       # stop the stack (volumes are kept)

GRADLE  := ./gradlew
COMPOSE := docker compose

# Testcontainers needs the colima socket spelled out (architecture.md §14). Set only when
# colima is in use and the caller hasn't chosen a Docker host already.
COLIMA_SOCK := $(HOME)/.colima/default/docker.sock
ifneq ($(wildcard $(COLIMA_SOCK)),)
  DOCKER_HOST ?= unix://$(COLIMA_SOCK)
  TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE ?= /var/run/docker.sock
  export DOCKER_HOST TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE
endif

.PHONY: help up run test down

help: ## List targets
	@grep -E '^[a-z]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-6s %s\n", $$1, $$2}'

up: ## Start Postgres and SeaweedFS and wait until they're healthy
	$(COMPOSE) up -d --wait

run: ## Run the app with the local profile (needs `make up`)
	$(GRADLE) :app:bootRun --args='--spring.profiles.active=local'

test: ## Compile and run all tests with the coverage gate (same as CI's build)
	$(GRADLE) build

down: ## Stop the stack; data volumes are kept
	$(COMPOSE) down
