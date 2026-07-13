// SPDX-License-Identifier: GPL-2.0
/*
 * event.c - Kernel 4.19 compatibility stubs
 *
 * SukiSU-Ultra's event.c uses kernel 5.x APIs not available in 4.19.
 * This file provides stub implementations for kernel 4.19.
 */

#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/printk.h>

int __init ksu_sulog_events_init(void)
{
    pr_info("ksu: sulog events init stub (kernel 4.19)\n");
    return 0;
}

void __exit ksu_sulog_events_exit(void)
{
    pr_info("ksu: sulog events exit stub (kernel 4.19)\n");
}

struct ksu_sulog_pending_event *ksu_sulog_capture_root_execve(const char __user *filename_user,
                                                              const char __user *const __user *argv_user, gfp_t gfp)
{
    return NULL;
}

struct ksu_sulog_pending_event *ksu_sulog_capture_sucompat(const char __user *filename_user,
                                                           const char __user *const __user *argv_user, gfp_t gfp)
{
    return NULL;
}

void ksu_sulog_emit_pending(struct ksu_sulog_pending_event *pending, int retval, gfp_t gfp)
{
}

int ksu_sulog_emit_grant_root(int retval, __u32 uid, __u32 euid, gfp_t gfp)
{
    return 0;
}

struct ksu_event_queue *ksu_sulog_get_queue(void)
{
    return NULL;
}
