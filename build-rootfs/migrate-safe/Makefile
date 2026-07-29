# Statically linked guest migration helper. Built for aarch64 in Dockerfile.rootfs.
CC      ?= gcc
CFLAGS  ?= -O2 -Wall -Wextra -Werror -static \
           -fstack-protector-strong -D_FORTIFY_SOURCE=2
LDFLAGS ?=

BIN = podroid-migrate-safe

all: $(BIN)

$(BIN): podroid-migrate-safe.c
	$(CC) $(CFLAGS) -o $@ $< $(LDFLAGS)

clean:
	rm -f $(BIN)

.PHONY: all clean
