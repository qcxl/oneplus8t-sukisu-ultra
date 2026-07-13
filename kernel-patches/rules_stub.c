// SPDX-License-Identifier: GPL-2.0
/*
 * rules.c - Kernel 4.19 compatibility stubs
 *
 * SukiSU-Ultra's rules.c uses kernel 5.x SELinux APIs not available in 4.19.
 * This file provides stub implementations for kernel 4.19.
 */

#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/printk.h>

void apply_kernelsu_rules(void)
{
    pr_info("ksu: apply_kernelsu_rules stub (kernel 4.19)\n");
}

void restore_sepolicy(void)
{
    pr_info("ksu: restore_sepolicy stub (kernel 4.19)\n");
}
