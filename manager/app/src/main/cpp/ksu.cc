//
// Created by weishu on 2022/12/9.
//

#include <sys/prctl.h>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <unistd.h>
#include <utility>
#include <android/log.h>
#include <dirent.h>
#include <cstdlib>

#include <unistd.h>
#include <sys/stat.h>
#include <climits>
#include <cerrno>
#include "ksu.h"

static int fd = -1;

static int preset_fd = -1;

void set_ksu_fd(int ksu_fd) {
    preset_fd = ksu_fd;
    if (ksu_fd >= 0) {
        fd = ksu_fd;
    }
}

static bool is_ksu_present() {
    struct stat st;
    return stat("/sys/module/kernelsu", &st) == 0 && S_ISDIR(st.st_mode);
}

static inline int scan_driver_fd() {
    // First: scan /proc/self/fd for [ksu_driver] anon inode
    const char *kName = "[ksu_driver]";
    DIR *dir = opendir("/proc/self/fd");
    if (dir) {
        struct dirent *de;
        char path[64];
        char target[PATH_MAX];

        while ((de = readdir(dir)) != nullptr) {
            if (de->d_name[0] == '.') continue;

            char *endptr = nullptr;
            long fd_long = strtol(de->d_name, &endptr, 10);
            if (!de->d_name[0] || *endptr != '\0' || fd_long < 0 || fd_long > INT_MAX) continue;

            snprintf(path, sizeof(path), "/proc/self/fd/%s", de->d_name);
            ssize_t n = readlink(path, target, sizeof(target) - 1);
            if (n < 0) continue;
            target[n] = '\0';

            const char *base = strrchr(target, '/');
            base = base ? base + 1 : target;

            if (strstr(base, kName)) {
                closedir(dir);
                return (int)fd_long;
            }
        }
        closedir(dir);
    }

    // Fallback 1: preset fd set via JNI (setKsuFd from Kotlin ksu_fd_helper)
    if (preset_fd >= 0) {
        return preset_fd;
    }

    // Fallback 2: request fd via prctl(0xDEADBEEF, 0xCAFEBABE, ...)
    // NOTE: 0xCAFEBABE must be passed as unsigned long (zero-extended to 64-bit on arm64).
    // Passing as int causes sign-extension to 0xFFFFFFFFCAFEBABE, which != kernel's
    // KSU_INSTALL_MAGIC2 (0x00000000CAFEBABE) → comparison fails → fd never installed.
    {
        int ksu_fd = -1;
        prctl(static_cast<int>(0xDEADBEEF),
              static_cast<unsigned long>(0xCAFEBABE),
              reinterpret_cast<unsigned long>(&ksu_fd), 0, 0);
        if (ksu_fd >= 0) return ksu_fd;
    }

    // Fallback 3: read from well-known file (written by root shell helper)
    FILE *f = fopen("/data/local/tmp/ksu_fd", "r");
    if (f) {
        int file_fd = -1;
        if (fscanf(f, "%d", &file_fd) == 1 && file_fd >= 0) {
            fclose(f);
            return file_fd;
        }
        fclose(f);
    }

    return -1;
}

template<typename... Args>
static int ksuctl(unsigned long op, Args &&... args) {
    if (fd < 0) {
        fd = scan_driver_fd();
    }
    static_assert(sizeof...(Args) <= 1, "ioctl expects at most one extra argument");
    return ioctl(fd, op, std::forward<Args>(args)...);
}

static struct ksu_get_info_cmd g_version {};

struct ksu_get_info_cmd get_info() {
    if (!g_version.version) {
        if (ksuctl(KSU_IOCTL_GET_INFO, &g_version) < 0) {
            ksuctl(KSU_IOCTL_GET_INFO_LEGACY, &g_version);
            g_version.uapi_version = 0;
        }
    }
    return g_version;
}

uint32_t get_kernel_uapi_version() {
    auto info = get_info();
    return info.uapi_version;
}

uint32_t get_manager_uapi_version() {
    return KERNEL_SU_UAPI_VERSION;
}

uint32_t get_version() {
    auto info = get_info();
    return info.version;
}

bool get_allow_list(struct ksu_new_get_allow_list_cmd *cmd) {
    return ksuctl(KSU_IOCTL_NEW_GET_ALLOW_LIST, cmd) == 0;
}

bool is_safe_mode() {
    struct ksu_check_safemode_cmd cmd = {};
    ksuctl(KSU_IOCTL_CHECK_SAFEMODE, &cmd);
    return cmd.in_safe_mode;
}

bool is_lkm_mode() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_LKM) != 0;
    }
    return (legacy_get_info().second & KSU_GET_INFO_FLAG_LKM) != 0;
}

bool is_late_load_mode() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_LATE_LOAD) != 0;
    }
    return false;
}

bool is_manager() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_MANAGER) != 0;
    }
    return legacy_get_info().first > 0;
}

bool is_pr_build() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_PR_BUILD) != 0;
    }
    return false;
}

bool uid_should_umount(int uid) {
    struct ksu_uid_should_umount_cmd cmd = {};
    cmd.uid = uid;
    ksuctl(KSU_IOCTL_UID_SHOULD_UMOUNT, &cmd);
    return cmd.should_umount;
}

bool set_app_profile(const app_profile *profile) {
    struct ksu_set_app_profile_cmd cmd = {};
    cmd.profile = *profile;
    return ksuctl(KSU_IOCTL_SET_APP_PROFILE, &cmd) == 0;
}

int get_app_profile(app_profile *profile) {
    struct ksu_get_app_profile_cmd cmd = {.profile = *profile};
    int ret = ksuctl(KSU_IOCTL_GET_APP_PROFILE, &cmd);
    *profile = cmd.profile;
    return ret;
}

bool set_su_enabled(bool enabled) {
    struct ksu_set_feature_cmd cmd = {};
    cmd.feature_id = KSU_FEATURE_SU_COMPAT;
    cmd.value = enabled ? 1 : 0;
    return ksuctl(KSU_IOCTL_SET_FEATURE, &cmd) == 0;
}

bool is_su_enabled() {
    struct ksu_get_feature_cmd cmd = {};
    cmd.feature_id = KSU_FEATURE_SU_COMPAT;
    if (ksuctl(KSU_IOCTL_GET_FEATURE, &cmd) != 0) {
        return false;
    }
    if (!cmd.supported) {
        return false;
    }
    return cmd.value != 0;
}

static inline bool get_feature(uint32_t feature_id, uint64_t *out_value, bool *out_supported) {
    struct ksu_get_feature_cmd cmd = {};
    cmd.feature_id = feature_id;
    if (ksuctl(KSU_IOCTL_GET_FEATURE, &cmd) != 0) {
        return false;
    }
    if (out_value) *out_value = cmd.value;
    if (out_supported) *out_supported = cmd.supported;
    return true;
}

static inline bool set_feature(uint32_t feature_id, uint64_t value) {
    struct ksu_set_feature_cmd cmd = {};
    cmd.feature_id = feature_id;
    cmd.value = value;
    return ksuctl(KSU_IOCTL_SET_FEATURE, &cmd) == 0;
}

bool set_kernel_umount_enabled(bool enabled) {
    return set_feature(KSU_FEATURE_KERNEL_UMOUNT, enabled ? 1 : 0);
}

bool is_kernel_umount_enabled() {
    uint64_t value = 0;
    bool supported = false;
    if (!get_feature(KSU_FEATURE_KERNEL_UMOUNT, &value, &supported)) {
        return false;
    }
    if (!supported) {
        return false;
    }
    return value != 0;
}

int set_selinux_hide_enabled(bool enabled) {
    if (!set_feature(KSU_FEATURE_SELINUX_HIDE, enabled ? 1 : 0)) {
        return -errno;
    }
    return 0;
}

bool is_selinux_hide_enabled() {
    uint64_t value = 0;
    bool supported = false;
    if (!get_feature(KSU_FEATURE_SELINUX_HIDE, &value, &supported)) {
        return false;
    }
    if (!supported) {
        return false;
    }
    return value != 0;
}

bool set_selinux_enforce(bool enforce) {
    return set_feature(KSU_FEATURE_SET_SELINUX_ENFORCE, enforce ? 1 : 0);
}

bool is_selinux_enforce() {
    uint64_t value = 0;
    bool supported = false;
    if (!get_feature(KSU_FEATURE_SET_SELINUX_ENFORCE, &value, &supported)) {
        return true;
    }
    if (!supported) {
        return true;
    }
    return value != 0;
}

// SU Log (KSU_FEATURE_SULOG = 2)
bool set_sulog_enabled(bool enabled) {
    return set_feature(KSU_FEATURE_SULOG, enabled ? 1 : 0);
}

bool is_sulog_enabled() {
    uint64_t value = 0;
    bool supported = false;
    if (!get_feature(KSU_FEATURE_SULOG, &value, &supported)) {
        return false;
    }
    if (!supported) {
        return false;
    }
    return value != 0;
}

// ADB Root (KSU_FEATURE_ADB_ROOT = 3)
bool set_adb_root_enabled(bool enabled) {
    return set_feature(KSU_FEATURE_ADB_ROOT, enabled ? 1 : 0);
}

bool is_adb_root_enabled() {
    uint64_t value = 0;
    bool supported = false;
    if (!get_feature(KSU_FEATURE_ADB_ROOT, &value, &supported)) {
        return false;
    }
    if (!supported) {
        return false;
    }
    return value != 0;
}

// Custom
DEFINE_CACHED_GETTER(full_version, KSU_IOCTL_GET_FULL_VERSION, ksu_get_full_version_cmd, version_full, 255)
DEFINE_CACHED_GETTER(hook_type, KSU_IOCTL_HOOK_TYPE, ksu_hook_type_cmd, hook_type, 32)
