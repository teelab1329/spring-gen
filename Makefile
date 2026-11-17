# spring-gen developer tasks. Run `make verify` before opening a merge request.

BINARY  := spring-gen
BIN_DIR := bin
MODULE  := github.com/teelab1329/spring-gen
VER_PKG := $(MODULE)/internal/version

GO ?= go

# Override on the command line for releases, e.g. `make build VERSION=v0.1.0`.
VERSION ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
COMMIT  ?= $(shell git rev-parse --short=12 HEAD 2>/dev/null || echo unknown)
DATE    ?= $(shell date -u +%Y-%m-%dT%H:%M:%SZ)

LDFLAGS := -X $(VER_PKG).Version=$(VERSION) -X $(VER_PKG).Commit=$(COMMIT) -X $(VER_PKG).Date=$(DATE)

.PHONY: all verify fmt fmt-check vet test build clean

all: verify

# What CI and a merge request must pass.
verify: fmt-check vet test

fmt:
	$(GO) fmt ./...
	gofmt -w .

fmt-check:
	@out="$$(gofmt -l .)"; if [ -n "$$out" ]; then \
		echo "gofmt required for:"; echo "$$out"; exit 1; \
	fi

vet:
	$(GO) vet ./...

test:
	$(GO) test ./...

build:
	@mkdir -p $(BIN_DIR)
	$(GO) build -trimpath -ldflags "$(LDFLAGS)" -o $(BIN_DIR)/$(BINARY) ./cmd/$(BINARY)

clean:
	rm -rf $(BIN_DIR)
