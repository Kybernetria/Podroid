/*
 * Fail-closed normalization of a legacy Podroid overlay upper.
 *
 * Production usage:
 *   podroid-overlay-normalize PERSIST_ROOT
 *
 * PERSIST_ROOT must contain upper/ and work/. The helper removes work/index,
 * unlinks metadata-only upper entries, clears directory redirects, durably
 * syncs every traversed upper/work directory, and only then atomically
 * publishes the exact versioned .podroid/normalized payload. All traversal and
 * mutation is anchored to no-follow directory descriptors so an existing
 * symlink cannot redirect normalization outside the persistent filesystem.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/random.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/xattr.h>
#include <unistd.h>

#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE (1U << 0)
#endif

static const char marker_payload[] = "podroid-overlay-normalize-v1\n";
static char metacopy_xattr[128];
static char redirect_xattr[128];

#ifdef PODROID_NORMALIZE_TESTING
static bool injected_failure(const char *operation) {
    const char *requested = getenv("PODROID_NORMALIZE_FAIL");
    if (requested == NULL) return false;
    size_t operation_length = strlen(operation);
    while (*requested != '\0') {
        while (*requested == ',') requested++;
        const char *end = strchr(requested, ',');
        size_t length = end == NULL ? strlen(requested) : (size_t)(end - requested);
        if (length == operation_length &&
            memcmp(requested, operation, operation_length) == 0) {
            errno = EIO;
            return true;
        }
        if (end == NULL) break;
        requested = end + 1;
    }
    return false;
}
#else
static bool injected_failure(const char *operation) {
    (void)operation;
    return false;
}
#endif

static int report_error(const char *operation, const char *path) {
    fprintf(stderr, "podroid-overlay-normalize: %s %s: %s\n", operation,
            path, strerror(errno));
    return -1;
}

static int close_checked(int fd, const char *path) {
    bool injected = injected_failure("close");
    int result = close(fd);
    if (injected) {
        errno = EIO;
        return report_error("close", path);
    }
    if (result != 0) return report_error("close", path);
    return 0;
}

static int close_after_error(int fd, const char *path) {
    int saved = errno;
    if (close(fd) != 0)
        fprintf(stderr, "podroid-overlay-normalize: close %s while handling an error: %s\n",
                path, strerror(errno));
    errno = saved;
    return -1;
}

static int sync_directory(int fd, const char *path, const char *failure_name) {
    if (injected_failure(failure_name))
        return report_error("sync directory", path);
    if (fsync(fd) != 0) return report_error("sync directory", path);
    return 0;
}

static bool valid_component(const char *component) {
    size_t length = strlen(component);
    return length > 0 && length <= NAME_MAX && strcmp(component, ".") != 0 &&
           strcmp(component, "..") != 0 && strchr(component, '/') == NULL;
}

/* Open an absolute directory without following any path component symlink. */
static int open_absolute_dir(const char *path) {
    if (path == NULL || path[0] != '/') {
        errno = EINVAL;
        return report_error("validate absolute directory", path ? path : "(null)");
    }
    size_t length = strnlen(path, PATH_MAX);
    if (length == PATH_MAX) {
        errno = ENAMETOOLONG;
        return report_error("validate path length", path);
    }

    if (injected_failure("open")) return report_error("open", "/");
    int current = open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (current < 0) return report_error("open", "/");

    char copy[PATH_MAX];
    memcpy(copy, path, length + 1);
    char *cursor = copy;
    while (*cursor == '/') cursor++;
    while (*cursor != '\0') {
        char *slash = strchr(cursor, '/');
        if (slash != NULL) *slash = '\0';
        if (!valid_component(cursor)) {
            errno = ENAMETOOLONG;
            close_after_error(current, path);
            return report_error("validate path component", path);
        }
        if (injected_failure("open")) {
            close_after_error(current, path);
            return report_error("open directory without following symlinks", path);
        }
        int next = openat(current, cursor,
                          O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        if (next < 0) {
            close_after_error(current, path);
            return report_error("open directory without following symlinks", path);
        }
        if (close_checked(current, path) != 0) {
            close_after_error(next, path);
            return -1;
        }
        current = next;
        if (slash == NULL) break;
        cursor = slash + 1;
        while (*cursor == '/') cursor++;
    }
    return current;
}

static int stat_at(int parent, const char *name, struct stat *status,
                   bool absent_ok) {
    if (injected_failure("lstat")) return report_error("lstat", name);
    if (fstatat(parent, name, status, AT_SYMLINK_NOFOLLOW) == 0) return 1;
    if (absent_ok && errno == ENOENT) return 0;
    return report_error("lstat", name);
}

static int proc_entry_path(char *output, size_t output_size, int parent,
                           const char *name) {
    if (injected_failure("path")) {
        errno = ENAMETOOLONG;
        return report_error("construct descriptor-relative path", name);
    }
    int length = snprintf(output, output_size, "/proc/self/fd/%d/%s", parent, name);
    if (length < 0 || (size_t)length >= output_size) {
        errno = ENAMETOOLONG;
        return report_error("construct descriptor-relative path", name);
    }
    return 0;
}

/* Return 1 when present, 0 when absent, and -1 for a real xattr error. */
static int has_xattr_at(int parent, const char *name, const char *xattr) {
    char path[64 + NAME_MAX];
    if (proc_entry_path(path, sizeof(path), parent, name) != 0) return -1;
    if (injected_failure("xattr")) return report_error("read xattr", name);
    ssize_t result = lgetxattr(path, xattr, NULL, 0);
    if (result >= 0) return 1;
    if (errno == ENODATA) return 0;
#ifdef ENOATTR
    if (errno == ENOATTR) return 0;
#endif
    return report_error("read xattr", name);
}

static int remove_xattr_at(int parent, const char *name, const char *xattr) {
    char path[64 + NAME_MAX];
    if (proc_entry_path(path, sizeof(path), parent, name) != 0) return -1;
    if (injected_failure("remove")) return report_error("remove xattr", name);
    if (lremovexattr(path, xattr) != 0) return report_error("remove xattr", name);
    return 0;
}

static int unlink_at(int parent, const char *name, bool directory) {
    const char *operation = directory ? "rmdir" : "unlink";
    if (injected_failure(operation)) return report_error(operation, name);
    if (unlinkat(parent, name, directory ? AT_REMOVEDIR : 0) != 0)
        return report_error(operation, name);
    return 0;
}

static int remove_tree_contents(int directory);

static int remove_tree_entry(int parent, const char *name,
                             const struct stat *status) {
    if (!S_ISDIR(status->st_mode)) return unlink_at(parent, name, false);
    if (injected_failure("open"))
        return report_error("open directory without following symlinks", name);
    int child = openat(parent, name,
                       O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (child < 0)
        return report_error("open directory without following symlinks", name);
    if (remove_tree_contents(child) != 0) {
        close_after_error(child, name);
        return -1;
    }
    if (close_checked(child, name) != 0) return -1;
    return unlink_at(parent, name, true);
}

static int read_directory(int directory,
                          int (*visit)(int, const char *, const struct stat *)) {
    if (injected_failure("open"))
        return report_error("duplicate directory descriptor", "directory");
    int duplicate = dup(directory);
    if (duplicate < 0)
        return report_error("duplicate directory descriptor", "directory");
    DIR *stream = fdopendir(duplicate);
    if (stream == NULL) {
        close_after_error(duplicate, "directory stream");
        return report_error("open directory stream", "directory");
    }

    int result = 0;
    for (;;) {
        errno = 0;
        struct dirent *entry = injected_failure("read") ? NULL : readdir(stream);
        if (entry == NULL) {
            if (injected_failure("read")) errno = EIO;
            if (errno != 0) result = report_error("read directory", "directory");
            break;
        }
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0)
            continue;
        if (!valid_component(entry->d_name)) {
            errno = ENAMETOOLONG;
            result = report_error("validate directory entry", entry->d_name);
            break;
        }
        struct stat status;
        int present = stat_at(directory, entry->d_name, &status, false);
        if (present < 0 || visit(directory, entry->d_name, &status) != 0) {
            result = -1;
            break;
        }
    }
    if (injected_failure("close")) {
        errno = EIO;
        if (closedir(stream) != 0) { /* still release the descriptor */ }
        if (result == 0) result = report_error("close directory stream", "directory");
    } else if (closedir(stream) != 0 && result == 0) {
        result = report_error("close directory stream", "directory");
    }
    return result;
}

/* Sync before returning so every successful child removal is durable before
 * its now-empty directory is removed by the parent. */
static int remove_tree_contents(int directory) {
    if (read_directory(directory, remove_tree_entry) != 0) return -1;
    return sync_directory(directory, "work/index", "cleanup-sync");
}

static int normalize_directory(int directory);

static int normalize_entry(int parent, const char *name,
                           const struct stat *status) {
    if (!S_ISDIR(status->st_mode)) {
        int metacopy = has_xattr_at(parent, name, metacopy_xattr);
        if (metacopy < 0) return -1;
        return metacopy == 1 ? unlink_at(parent, name, false) : 0;
    }

    int redirect = has_xattr_at(parent, name, redirect_xattr);
    if (redirect < 0) return -1;
    if (redirect == 1 && remove_xattr_at(parent, name, redirect_xattr) != 0)
        return -1;
    if (injected_failure("open"))
        return report_error("open upper directory without following symlinks", name);
    int child = openat(parent, name,
                       O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (child < 0)
        return report_error("open upper directory without following symlinks", name);
    if (normalize_directory(child) != 0) {
        close_after_error(child, name);
        return -1;
    }
    return close_checked(child, name);
}

/* Sync every traversed upper directory. This includes directories whose own
 * redirect xattr changed and parents from which metadata-only entries were
 * unlinked, without relying on an error-prone mutation bookkeeping side path. */
static int normalize_directory(int directory) {
    if (read_directory(directory, normalize_entry) != 0) return -1;
    return sync_directory(directory, "upper", "cleanup-sync");
}

static int open_named_dir(int parent, const char *name) {
    if (injected_failure("open"))
        return report_error("open directory without following symlinks", name);
    int fd = openat(parent, name,
                    O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0)
        return report_error("open directory without following symlinks", name);
    return fd;
}

static int open_metadata_dir(int persist) {
    struct stat status;
    int present = stat_at(persist, ".podroid", &status, true);
    if (present < 0) return -1;
    if (present == 0) {
        if (mkdirat(persist, ".podroid", 0700) != 0)
            return report_error("mkdir", ".podroid");
        if (sync_directory(persist, "persistent root", "metadata-sync") != 0)
            return -1;
    } else if (!S_ISDIR(status.st_mode)) {
        errno = ELOOP;
        return report_error("reject non-directory metadata path", ".podroid");
    }
    return open_named_dir(persist, ".podroid");
}

/* Return 1 only for the exact completed version payload, 0 for absence, 2 for
 * a legacy/unknown regular marker, and -1 for hostility or an I/O failure. */
static int inspect_marker(int metadata) {
    struct stat status;
    int present = stat_at(metadata, "normalized", &status, true);
    if (present <= 0) return present;
    if (!S_ISREG(status.st_mode)) {
        errno = ELOOP;
        return report_error("reject non-regular marker", "normalized");
    }
    if (injected_failure("open")) return report_error("open marker", "normalized");
    int marker = openat(metadata, "normalized", O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (marker < 0) return report_error("open marker", "normalized");
    if (fstat(marker, &status) != 0 || !S_ISREG(status.st_mode)) {
        if (errno == 0) errno = ELOOP;
        close_after_error(marker, "normalized");
        return report_error("validate opened marker", "normalized");
    }

    unsigned char content[sizeof(marker_payload)];
    size_t offset = 0;
    while (offset < sizeof(content)) {
        if (injected_failure("marker-read")) {
            close_after_error(marker, "normalized");
            return report_error("read marker", "normalized");
        }
        ssize_t count = read(marker, content + offset, sizeof(content) - offset);
        if (count < 0) {
            if (errno == EINTR) continue;
            close_after_error(marker, "normalized");
            return report_error("read marker", "normalized");
        }
        if (count == 0) break;
        offset += (size_t)count;
    }
    if (close_checked(marker, "normalized") != 0) return -1;
    return offset == sizeof(marker_payload) - 1 &&
                   memcmp(content, marker_payload, sizeof(marker_payload) - 1) == 0
               ? 1
               : 2;
}

static int remove_legacy_marker(int metadata) {
    if (unlink_at(metadata, "normalized", false) != 0) return -1;
    return sync_directory(metadata, ".podroid", "legacy-sync");
}

static int configure_xattr_names(void) {
    const char *name_space = getenv("PODROID_NORMALIZE_NS");
    if (name_space == NULL || *name_space == '\0') name_space = "trusted.overlay.";
    size_t length = strnlen(name_space, sizeof(metacopy_xattr));
    if (length == sizeof(metacopy_xattr) ||
        length + strlen("metacopy") + 1 > sizeof(metacopy_xattr) ||
        length + strlen("redirect") + 1 > sizeof(redirect_xattr)) {
        errno = ENAMETOOLONG;
        return report_error("validate xattr namespace length", name_space);
    }
    memcpy(metacopy_xattr, name_space, length);
    memcpy(metacopy_xattr + length, "metacopy", strlen("metacopy") + 1);
    memcpy(redirect_xattr, name_space, length);
    memcpy(redirect_xattr + length, "redirect", strlen("redirect") + 1);
    return 0;
}

static int random_temp_name(char *output, size_t output_size) {
    unsigned char bytes[12];
    if (getrandom(bytes, sizeof(bytes), 0) != (ssize_t)sizeof(bytes)) {
        if (errno == 0) errno = EIO;
        return report_error("generate marker temporary name", "normalized");
    }
    int length = snprintf(output, output_size,
                          ".normalized.%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x",
                          bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5],
                          bytes[6], bytes[7], bytes[8], bytes[9], bytes[10], bytes[11]);
    if (length < 0 || (size_t)length >= output_size) {
        errno = ENAMETOOLONG;
        return report_error("construct marker temporary name", "normalized");
    }
    return 0;
}

static int write_all(int fd, const char *data, size_t size) {
    size_t offset = 0;
    while (offset < size) {
        if (injected_failure("publish-write"))
            return report_error("write marker temporary", "normalized");
        ssize_t count = write(fd, data + offset, size - offset);
        if (count < 0) {
            if (errno == EINTR) continue;
            return report_error("write marker temporary", "normalized");
        }
        offset += (size_t)count;
    }
    return 0;
}

/* Roll back only an unpublished temporary. A published valid marker is never
 * removed: cleanup was fully synced before rename, so retaining the marker is
 * safe even when the publication directory sync reports an error. */
static int rollback_temporary(int metadata, const char *temporary) {
    if (injected_failure("rollback-unlink"))
        return report_error("rollback marker temporary", temporary);
    if (unlinkat(metadata, temporary, 0) != 0)
        return report_error("rollback marker temporary", temporary);
    return sync_directory(metadata, ".podroid", "rollback-sync");
}

/* Return 0 for fully reported success, -1 before publication, and -2 when an
 * exact valid marker may survive a reported post-publication sync failure. */
static int publish_marker(int metadata) {
    char temporary[NAME_MAX + 1];
    if (random_temp_name(temporary, sizeof(temporary)) != 0) return -1;
    if (injected_failure("open"))
        return report_error("open marker temporary", temporary);
    int marker = openat(metadata, temporary,
                        O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (marker < 0) return report_error("open marker temporary", temporary);

    int prepared = 0;
    if (injected_failure("publish-prepare")) {
        prepared = report_error("prepare marker temporary", temporary);
    } else if (write_all(marker, marker_payload, sizeof(marker_payload) - 1) != 0) {
        prepared = -1;
    } else if (fchmod(marker, 0600) != 0) {
        prepared = report_error("set marker mode", temporary);
    } else if (fsync(marker) != 0) {
        prepared = report_error("sync marker temporary", temporary);
    }
    if (close_checked(marker, temporary) != 0) prepared = -1;
    if (prepared != 0) {
        if (rollback_temporary(metadata, temporary) != 0)
            report_error("marker temporary rollback incomplete", temporary);
        return -1;
    }

    int existing = inspect_marker(metadata);
    if (existing != 0) {
        if (existing > 0) errno = EEXIST;
        if (rollback_temporary(metadata, temporary) != 0)
            report_error("marker temporary rollback incomplete", temporary);
        return existing > 0 ? report_error("refuse to replace marker", "normalized") : -1;
    }
    if (injected_failure("publish-rename") ||
        syscall(SYS_renameat2, metadata, temporary, metadata, "normalized",
                RENAME_NOREPLACE) != 0) {
        if (errno == 0) errno = EIO;
        int saved = errno;
        report_error("atomically publish marker", "normalized");
        if (rollback_temporary(metadata, temporary) != 0)
            report_error("marker temporary rollback incomplete", temporary);
        errno = saved;
        return -1;
    }

    if (sync_directory(metadata, ".podroid", "publish-sync") != 0) {
        /* The exact marker may remain visible. This is safe because upper/work
         * cleanup was durably synced before publication; a lost marker merely
         * causes the idempotent cleanup to run again after reboot. */
        return -2;
    }
    return 0;
}

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: podroid-overlay-normalize PERSIST_ROOT\n");
        return 2;
    }
    if (configure_xattr_names() != 0) return 1;

    int persist = open_absolute_dir(argv[1]);
    if (persist < 0) return 1;
    int metadata = open_metadata_dir(persist);
    if (metadata < 0) {
        close_after_error(persist, argv[1]);
        return 1;
    }
    int marker = inspect_marker(metadata);
    if (marker < 0) {
        close_after_error(metadata, ".podroid");
        close_after_error(persist, argv[1]);
        return 1;
    }
    if (marker == 1) {
        int result = 0;
        if (close_checked(metadata, ".podroid") != 0) result = 1;
        if (close_checked(persist, argv[1]) != 0) result = 1;
        return result;
    }
    if (marker == 2 && remove_legacy_marker(metadata) != 0) {
        close_after_error(metadata, ".podroid");
        close_after_error(persist, argv[1]);
        return 1;
    }

    int upper = open_named_dir(persist, "upper");
    int work = upper >= 0 ? open_named_dir(persist, "work") : -1;
    if (upper < 0 || work < 0) {
        if (upper >= 0) close_after_error(upper, "upper");
        close_after_error(metadata, ".podroid");
        close_after_error(persist, argv[1]);
        return 1;
    }

    int result = 0;
    struct stat index_status;
    int index_present = stat_at(work, "index", &index_status, true);
    if (index_present < 0 ||
        (index_present == 1 && remove_tree_entry(work, "index", &index_status) != 0) ||
        sync_directory(work, "work", "cleanup-sync") != 0 ||
        normalize_directory(upper) != 0)
        result = 1;
    if (close_checked(work, "work") != 0) result = 1;
    if (close_checked(upper, "upper") != 0) result = 1;
    if (close_checked(persist, argv[1]) != 0) result = 1;
    if (result != 0) {
        close_after_error(metadata, ".podroid");
        return 1;
    }

    int published = publish_marker(metadata);
    if (close_checked(metadata, ".podroid") != 0) return 1;
    return published == 0 ? 0 : 1;
}
