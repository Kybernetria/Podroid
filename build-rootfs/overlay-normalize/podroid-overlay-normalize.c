/*
 * Fail-closed normalization of a legacy Podroid overlay upper.
 *
 * Production usage:
 *   podroid-overlay-normalize PERSIST_ROOT
 *
 * PERSIST_ROOT must contain upper/ and work/. The helper removes work/index,
 * removes metadata-only upper entries, clears directory redirects, and only
 * then atomically publishes .podroid/normalized. All traversal and mutation is
 * anchored to no-follow directory descriptors so an existing symlink cannot
 * redirect normalization outside the persistent filesystem.
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

static char metacopy_xattr[128];
static char redirect_xattr[128];

#ifdef PODROID_NORMALIZE_TESTING
static bool injected_failure(const char *operation) {
    const char *requested = getenv("PODROID_NORMALIZE_FAIL");
    if (requested != NULL && strcmp(requested, operation) == 0) {
        errno = EIO;
        return true;
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

    if (injected_failure("open"))
        return report_error("open", "/");
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
    if (injected_failure("lstat"))
        return report_error("lstat", name);
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
    if (injected_failure("xattr"))
        return report_error("read xattr", name);
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
    if (injected_failure("remove"))
        return report_error("remove xattr", name);
    if (lremovexattr(path, xattr) != 0)
        return report_error("remove xattr", name);
    return 0;
}

static int unlink_at(int parent, const char *name, bool directory) {
    const char *operation = directory ? "rmdir" : "unlink";
    if (injected_failure(operation))
        return report_error(operation, name);
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

static int remove_tree_contents(int directory) {
    return read_directory(directory, remove_tree_entry);
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

static int normalize_directory(int directory) {
    return read_directory(directory, normalize_entry);
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
    } else if (!S_ISDIR(status.st_mode)) {
        errno = ELOOP;
        return report_error("reject non-directory metadata path", ".podroid");
    }
    return open_named_dir(persist, ".podroid");
}

/* Return 1 for a valid existing marker, 0 for absence, and -1 for hostility. */
static int inspect_marker(int metadata) {
    struct stat status;
    int present = stat_at(metadata, "normalized", &status, true);
    if (present <= 0) return present;
    if (!S_ISREG(status.st_mode)) {
        errno = ELOOP;
        return report_error("reject non-regular marker", "normalized");
    }
    return 1;
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
        ssize_t count = write(fd, data + offset, size - offset);
        if (count < 0) {
            if (errno == EINTR) continue;
            return report_error("write marker temporary", "normalized");
        }
        offset += (size_t)count;
    }
    return 0;
}

static int rollback_marker(const char *persist_root) {
    int persist = open_absolute_dir(persist_root);
    if (persist < 0) return -1;
    int metadata = open_named_dir(persist, ".podroid");
    if (metadata < 0) {
        close_after_error(persist, persist_root);
        return -1;
    }
    int result = 0;
    if (unlinkat(metadata, "normalized", 0) != 0 && errno != ENOENT)
        result = report_error("rollback marker", "normalized");
    if (close_checked(metadata, ".podroid") != 0) result = -1;
    if (close_checked(persist, persist_root) != 0) result = -1;
    return result;
}

static int remove_published_marker(int metadata) {
    if (unlinkat(metadata, "normalized", 0) != 0 && errno != ENOENT)
        return report_error("rollback marker", "normalized");
    struct stat status;
    int present = stat_at(metadata, "normalized", &status, true);
    if (present < 0) return -1;
    if (present != 0) {
        errno = EIO;
        return report_error("verify marker rollback", "normalized");
    }
    return 0;
}

static int publish_marker(int metadata, const char *persist_root) {
    char temporary[NAME_MAX + 1];
    if (random_temp_name(temporary, sizeof(temporary)) != 0) return -1;
    if (injected_failure("open"))
        return report_error("open marker temporary", temporary);
    int marker = openat(metadata, temporary,
                        O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (marker < 0) return report_error("open marker temporary", temporary);

    int result = 0;
    static const char content[] = "normalized\n";
    if (write_all(marker, content, sizeof(content) - 1) != 0 ||
        fchmod(marker, 0600) != 0 || fsync(marker) != 0)
        result = report_error("prepare marker temporary", temporary);
    if (close_checked(marker, temporary) != 0) result = -1;
    if (result != 0) {
        if (unlink_at(metadata, temporary, false) != 0) result = -1;
        return result;
    }

    int existing = inspect_marker(metadata);
    if (existing != 0) {
        if (existing > 0) errno = EEXIST;
        unlink_at(metadata, temporary, false);
        return existing > 0 ? report_error("refuse to replace marker", "normalized") : -1;
    }
    int rollback_descriptor = dup(metadata);
    if (rollback_descriptor < 0) {
        unlink_at(metadata, temporary, false);
        return report_error("duplicate marker directory for rollback", ".podroid");
    }
    if (syscall(SYS_renameat2, metadata, temporary, metadata, "normalized",
                RENAME_NOREPLACE) != 0) {
        int saved = errno;
        unlink_at(metadata, temporary, false);
        close_after_error(rollback_descriptor, ".podroid rollback");
        errno = saved;
        return report_error("atomically publish marker", "normalized");
    }

    bool publish_sync_failed = injected_failure("publish-sync");
    if (publish_sync_failed || fsync(metadata) != 0) {
        if (publish_sync_failed) errno = EIO;
        report_error("sync marker directory", ".podroid");
        remove_published_marker(rollback_descriptor);
        close_after_error(rollback_descriptor, ".podroid rollback");
        return -1;
    }

    /* Keep an independent descriptor until the publication descriptor closes.
     * If that close fails, remove and verify the marker before returning. */
    bool publish_close_failed = injected_failure("publish-close");
    int metadata_close = close(metadata);
    if (publish_close_failed || metadata_close != 0) {
        if (publish_close_failed) errno = EIO;
        report_error("close marker directory", ".podroid");
        int rollback_result = remove_published_marker(rollback_descriptor);
        if (close_checked(rollback_descriptor, ".podroid rollback") != 0)
            rollback_result = -1;
        if (rollback_result != 0) rollback_marker(persist_root);
        return -2; /* metadata is closed; tell the caller not to close it again. */
    }
    if (close_checked(rollback_descriptor, ".podroid rollback") != 0) {
        if (rollback_marker(persist_root) != 0)
            report_error("marker rollback after close failure", "normalized");
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
        normalize_directory(upper) != 0)
        result = 1;
    if (close_checked(work, "work") != 0) result = 1;
    if (close_checked(upper, "upper") != 0) result = 1;
    if (close_checked(persist, argv[1]) != 0) result = 1;
    if (result != 0) {
        close_after_error(metadata, ".podroid");
        return 1;
    }

    int published = publish_marker(metadata, argv[1]);
    if (published == -1) {
        close_after_error(metadata, ".podroid");
        return 1;
    }
    return published == 0 ? 0 : 1;
}
