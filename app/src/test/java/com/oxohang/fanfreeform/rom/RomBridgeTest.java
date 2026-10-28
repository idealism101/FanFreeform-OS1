package com.oxohang.fanfreeform.rom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 对接层的纯 JVM 单元测试：不触碰 Android 与 Xposed，只校验数据结构与探测语义。 */
public class RomBridgeTest {

    // ---------- BridgeResult ----------

    @Test public void bridgeResultOkCarriesValue() {
        BridgeResult<String> result = BridgeResult.ok("value");
        assertTrue(result.isOk());
        assertEquals(BridgeResult.Code.OK, result.code());
        assertEquals("value", result.value());
        // 成功时取到真值，不走兜底。
        assertEquals("value", result.isOk() ? result.value() : "fallback");
    }

    @Test public void bridgeResultFailCarriesCodeAndDetail() {
        BridgeResult<String> result = BridgeResult.fail(
                BridgeResult.Code.CLASS_MISSING, "target class not found");
        assertFalse(result.isOk());
        assertEquals(BridgeResult.Code.CLASS_MISSING, result.code());
        assertEquals("target class not found", result.detail());
        assertNull(result.value());
        // 失败时值不可取，调用方据此降级到兜底值。
        assertEquals("fallback", result.isOk() ? result.value() : "fallback");
    }

    @Test public void bridgeResultFailCarriesCause() {
        RuntimeException cause = new RuntimeException("boom");
        BridgeResult<Void> result = BridgeResult.fail(
                BridgeResult.Code.INVOCATION_FAILED, "reflect call", cause);
        assertEquals(cause, result.cause());
    }

    // ---------- RomTargets ----------

    @Test public void firstExistingPicksFirstPresentCandidate() {
        RomTargets.ClassExistsProbe probe = name -> name.startsWith("com.exists.");
        String found = RomTargets.firstExisting(probe,
                "com.nope.A", "com.exists.B", "com.exists.C");
        assertEquals("com.exists.B", found);
    }

    @Test public void firstExistingReturnsNullWhenNothingPresent() {
        RomTargets.ClassExistsProbe probe = name -> false;
        assertNull(RomTargets.firstExisting(probe, "a.B", "c.D"));
    }

    @Test public void firstExistingIgnoresNullProbeAndCandidates() {
        assertNull(RomTargets.firstExisting((RomTargets.ClassExistsProbe) null, "a.B"));
        assertNull(RomTargets.firstExisting(name -> true, (String[]) null));
    }

    @Test public void os1FreeformFlatVariantIsInCandidates() {
        // OS1 真实包名必须在候选里（真机 dex 核验：无 multitasking 前缀）。
        assertTrue(contains(RomTargets.FREEFORM_CONTROLLER,
                "com.android.wm.shell.miuifreeform.MiuiFreeformModeController"));
    }

    @Test public void os1ControlCenterVariantIsInCandidates() {
        assertTrue(contains(RomTargets.CONTROL_CENTER,
                "com.android.systemui.controlcenter.ControlCenter"));
    }

    private static boolean contains(String[] array, String value) {
        for (String item : array) {
            if (value.equals(item)) return true;
        }
        return false;
    }

    // ---------- Os1Capabilities ----------

    @Test public void capabilitiesSnapshotReflectsResolvedNames() {
        Os1Capabilities caps = Os1Capabilities.builder()
                .freeformController("com.android.wm.shell.miuifreeform.MiuiFreeformModeController")
                .controlCenter("com.android.systemui.controlcenter.ControlCenter")
                .build();
        assertTrue(caps.hasFreeformController());
        assertTrue(caps.hasControlCenter());
        // shellEventController 现在是公开快照字段（OS1 上为 null），不再有 has* 判定方法。
        assertNull(caps.shellEventController);
        assertFalse(caps.hasRecentsContainer());
    }

    @Test public void capabilitiesDescribeMarksMissingWithDash() {
        Os1Capabilities caps = Os1Capabilities.builder()
                .freeformController("x.Y").build();
        String describe = caps.describe();
        assertTrue(describe.contains("freeformController=x.Y"));
        assertTrue(describe.contains("shellEventController=-"));
    }
}
