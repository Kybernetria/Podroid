/*
 * Descriptor-relative, no-follow filesystem operations for Podroid migrations.
 *
 * Production callers pass the immutable mount roots explicitly. Every path
 * component is opened beneath an already-open directory descriptor with
 * O_NOFOLLOW; obsolete leaves are removed with unlinkat and replacement files
 * are committed with renameat on the same filesystem.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/random.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define MAX_MARKER_BYTES 32

static const char *obsolete_paths[] = {
    "/etc/runlevels/default/podroid-x11",
    "/etc/runlevels/default/docker",
    "/etc/runlevels/default/lxc",
    "/etc/runlevels/default/dnsmasq.lxcbr0",
    "/etc/runlevels/default/pulseaudio",
    "/etc/init.d/podroid-x11",
    "/etc/init.d/pulseaudio",
    "/etc/profile.d/podroid-x11.sh",
    "/etc/containers/storage.conf",
    "/usr/local/bin/podroid-backup",
    "/usr/local/bin/podroid-update-stats",
    "/usr/share/podroid/logo.png",
};

static int error_path(const char *operation, const char *path) {
    fprintf(stderr, "podroid-migrate-safe: %s %s: %s\n", operation, path,
            strerror(errno));
    return -1;
}

static bool valid_component(const char *component) {
    return component[0] != '\0' && strcmp(component, ".") != 0 &&
           strcmp(component, "..") != 0 && strchr(component, '/') == NULL;
}

/* Open an absolute directory path without following any component symlink. */
static int open_absolute_dir(const char *path) {
    if (path == NULL || path[0] != '/' || strlen(path) >= PATH_MAX) {
        errno = EINVAL;
        return error_path("invalid absolute directory", path ? path : "(null)");
    }

    int current = open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (current < 0) return error_path("open", "/");

    char copy[PATH_MAX];
    memcpy(copy, path, strlen(path) + 1);
    char *cursor = copy;
    while (*cursor == '/') cursor++;
    while (*cursor != '\0') {
        char *slash = strchr(cursor, '/');
        if (slash != NULL) *slash = '\0';
        if (!valid_component(cursor)) {
            close(current);
            errno = EINVAL;
            return error_path("invalid path component in", path);
        }
        int next = openat(current, cursor,
                          O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        if (next < 0) {
            close(current);
            return error_path("open directory without following symlinks", path);
        }
        close(current);
        current = next;
        if (slash == NULL) break;
        cursor = slash + 1;
        while (*cursor == '/') cursor++;
    }
    return current;
}

/* Open a hard-coded absolute path's parent beneath root_fd. */
static int open_parent_at(int root_fd, const char *path, char *leaf,
                          size_t leaf_size, bool missing_ok) {
    if (path == NULL || path[0] != '/' || strlen(path) >= PATH_MAX) {
        errno = EINVAL;
        return error_path("invalid migration path", path ? path : "(null)");
    }
    char copy[PATH_MAX];
    memcpy(copy, path + 1, strlen(path));
    char *last = strrchr(copy, '/');
    char *leaf_source = copy;
    if (last != NULL) {
        *last = '\0';
        leaf_source = last + 1;
    }
    if (!valid_component(leaf_source) || strlen(leaf_source) >= leaf_size) {
        errno = EINVAL;
        return error_path("invalid migration leaf", path);
    }
    memcpy(leaf, leaf_source, strlen(leaf_source) + 1);

    int current = dup(root_fd);
    if (current < 0) return error_path("dup root for", path);
    if (last == NULL) return current;

    char *cursor = copy;
    while (*cursor != '\0') {
        char *slash = strchr(cursor, '/');
        if (slash != NULL) *slash = '\0';
        if (!valid_component(cursor)) {
            close(current);
            errno = EINVAL;
            return error_path("invalid parent component in", path);
        }
        int next = openat(current, cursor,
                          O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        if (next < 0) {
            int saved = errno;
            close(current);
            errno = saved;
            if (missing_ok && errno == ENOENT) return -2;
            return error_path("open parent without following symlinks", path);
        }
        close(current);
        current = next;
        if (slash == NULL) break;
        cursor = slash + 1;
    }
    return current;
}

static int remove_leaf(int root_fd, const char *path) {
    char leaf[NAME_MAX + 1];
    int parent = open_parent_at(root_fd, path, leaf, sizeof(leaf), true);
    if (parent == -2) return 0;
    if (parent < 0) return -1;
    if (unlinkat(parent, leaf, 0) != 0 && errno != ENOENT) {
        int saved = errno;
        close(parent);
        errno = saved;
        return error_path("unlinkat", path);
    }
    close(parent);
    return 0;
}

static int random_temp_name(char *output, size_t output_size, const char *prefix) {
    unsigned char random_bytes[12];
    ssize_t count = getrandom(random_bytes, sizeof(random_bytes), 0);
    if (count != (ssize_t)sizeof(random_bytes)) {
        if (count >= 0) errno = EIO;
        return -1;
    }
    int written = snprintf(
        output, output_size,
        ".%s.%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x", prefix,
        random_bytes[0], random_bytes[1], random_bytes[2], random_bytes[3],
        random_bytes[4], random_bytes[5], random_bytes[6], random_bytes[7],
        random_bytes[8], random_bytes[9], random_bytes[10], random_bytes[11]);
    if (written < 0 || (size_t)written >= output_size) {
        errno = ENAMETOOLONG;
        return -1;
    }
    return 0;
}

static int write_all(int fd, const char *data, size_t size) {
    size_t offset = 0;
    while (offset < size) {
        ssize_t written = write(fd, data + offset, size - offset);
        if (written < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        offset += (size_t)written;
    }
    return 0;
}

static int replace_at(int parent, const char *leaf, const char *data,
                      mode_t mode) {
    char temporary[NAME_MAX + 1];
    if (random_temp_name(temporary, sizeof(temporary), leaf) != 0)
        return error_path("create random temporary name for", leaf);

    int fd = openat(parent, temporary,
                    O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, mode);
    if (fd < 0) return error_path("openat temporary for", leaf);

    int result = 0;
    if (fchmod(fd, mode) != 0 || write_all(fd, data, strlen(data)) != 0 ||
        fsync(fd) != 0) {
        result = error_path("write temporary for", leaf);
    }
    if (close(fd) != 0 && result == 0)
        result = error_path("close temporary for", leaf);
    if (result == 0 && renameat(parent, temporary, parent, leaf) != 0)
        result = error_path("renameat replacement for", leaf);
    if (result == 0 && fsync(parent) != 0)
        result = error_path("fsync parent for", leaf);
    if (result != 0) unlinkat(parent, temporary, 0);
    return result;
}

static int apply_migration_31(const char *root) {
    int root_fd = open_absolute_dir(root);
    if (root_fd < 0) return 1;
    int result = 0;
    for (size_t i = 0; i < sizeof(obsolete_paths) / sizeof(obsolete_paths[0]); i++) {
        if (remove_leaf(root_fd, obsolete_paths[i]) != 0) {
            result = 1;
            break;
        }
    }
    if (result == 0) {
        char leaf[NAME_MAX + 1];
        int parent = open_parent_at(root_fd, "/etc/podroid/forwards.conf", leaf,
                                    sizeof(leaf), false);
        if (parent < 0) {
            result = 1;
        } else {
            if (replace_at(parent, leaf, "9100 ctl\n", 0644) != 0) result = 1;
            close(parent);
        }
    }
    close(root_fd);
    return result;
}

static int open_metadata_dir(int persist_fd, bool create) {
    int metadata = openat(persist_fd, ".podroid",
                          O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (metadata < 0) {
        if (errno != ENOENT || !create)
            return errno == ENOENT ? -2 : error_path("open metadata directory", ".podroid");
        if (mkdirat(persist_fd, ".podroid", 0700) != 0 && errno != EEXIST)
            return error_path("mkdirat", ".podroid");
        metadata = openat(persist_fd, ".podroid",
                          O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        if (metadata < 0)
            return error_path("open created metadata directory", ".podroid");
    }
    if (create && fchmod(metadata, 0700) != 0) {
        close(metadata);
        return error_path("chmod metadata directory", ".podroid");
    }
    return metadata;
}

static int read_applied(const char *persist_root) {
    int persist = open_absolute_dir(persist_root);
    if (persist < 0) return 1;
    int metadata = open_metadata_dir(persist, false);
    close(persist);
    if (metadata == -2) return 0;
    if (metadata < 0) return 1;

    int marker = openat(metadata, "applied-version", O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (marker < 0) {
        int saved = errno;
        close(metadata);
        if (saved == ENOENT) return 0;
        errno = saved;
        return error_path("open marker without following symlinks", "applied-version") != 0;
    }
    struct stat status;
    if (fstat(marker, &status) != 0) {
        close(marker);
        close(metadata);
        return error_path("stat marker", "applied-version") != 0;
    }
    if (!S_ISREG(status.st_mode) || status.st_size < 0 ||
        status.st_size > MAX_MARKER_BYTES) {
        errno = EINVAL;
        close(marker);
        close(metadata);
        return error_path("validate marker", "applied-version") != 0;
    }
    char buffer[MAX_MARKER_BYTES + 1];
    ssize_t count = read(marker, buffer, MAX_MARKER_BYTES + 1);
    if (count < 0 || count > MAX_MARKER_BYTES) {
        if (count > MAX_MARKER_BYTES) errno = EFBIG;
        close(marker);
        close(metadata);
        return error_path("read marker", "applied-version") != 0;
    }
    if (write_all(STDOUT_FILENO, buffer, (size_t)count) != 0) {
        close(marker);
        close(metadata);
        return error_path("write marker output", "stdout") != 0;
    }
    close(marker);
    close(metadata);
    return 0;
}

static bool valid_version(const char *version) {
    size_t length = strlen(version);
    if (length == 0 || length > 9 || (length > 1 && version[0] == '0')) return false;
    for (size_t i = 0; i < length; i++)
        if (version[i] < '0' || version[i] > '9') return false;
    return true;
}

static int write_applied(const char *persist_root, const char *version) {
    if (!valid_version(version)) {
        errno = EINVAL;
        return error_path("invalid applied version", version) != 0;
    }
    int persist = open_absolute_dir(persist_root);
    if (persist < 0) return 1;
    int metadata = open_metadata_dir(persist, true);
    close(persist);
    if (metadata < 0) return 1;

    char content[16];
    int length = snprintf(content, sizeof(content), "%s\n", version);
    int result = length <= 0 || (size_t)length >= sizeof(content)
                     ? 1
                     : replace_at(metadata, "applied-version", content, 0600);
    close(metadata);
    return result != 0;
}

int main(int argc, char **argv) {
    if (argc == 3 && strcmp(argv[1], "apply-31") == 0)
        return apply_migration_31(argv[2]);
    if (argc == 3 && strcmp(argv[1], "read-applied") == 0)
        return read_applied(argv[2]);
    if (argc == 4 && strcmp(argv[1], "write-applied") == 0)
        return write_applied(argv[2], argv[3]);
    fprintf(stderr,
            "usage: podroid-migrate-safe apply-31 ROOT\n"
            "       podroid-migrate-safe read-applied PERSIST_ROOT\n"
            "       podroid-migrate-safe write-applied PERSIST_ROOT VERSION\n");
    return 2;
}
