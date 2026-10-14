package xyz.surendrajat.smalilsp

import com.sun.management.GarbageCollectionNotificationInfo
import com.sun.management.HotSpotDiagnosticMXBean
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import javax.management.NotificationEmitter
import javax.management.openmbean.CompositeData

/**
 * Heap settings a language server wants and no client passes on the command line, applied to
 * the running JVM through the HotSpot diagnostic bean (they are "manageable" flags).
 *
 * By default G1 keeps up to 70% of the heap free after a collection and never gives memory
 * back, so a process whose live data is 300 MB sits at 1.2 GB after the initial scan. With a
 * free ratio of 10..30% and a periodic concurrent cycle while idle, G1 returns the scan's
 * garbage to the OS within a minute of indexing ending; measured scan time is unchanged and
 * the only pause is the cycle's remark, a few milliseconds.
 *
 * A periodic cycle marks the whole live heap (about 0.4 s of CPU per gigabyte), so it is not
 * left running forever: it is armed at startup and re-armed by every request or notification,
 * and disarmed after a cycle that followed no activity. A JVM without these flags is left as
 * it is.
 */
object JvmTuning {
    private val logger = LoggerFactory.getLogger(JvmTuning::class.java)

    /** Milliseconds without a collection after which an armed G1 runs a concurrent cycle and returns free memory. */
    const val PERIODIC_GC_INTERVAL_MS = 30_000L

    private const val PERIODIC_CAUSE = "G1 Periodic Collection"

    @Volatile private var bean: HotSpotDiagnosticMXBean? = null
    @Volatile private var armed = false
    @Volatile private var activitySinceLastCycle = false

    fun applyHeapSettings() {
        val diagnostics = try {
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
        } catch (e: Throwable) {
            logger.debug("No HotSpot diagnostic bean; heap settings left as they are", e)
            return
        } ?: return
        // The minimum must drop before the maximum: HotSpot requires min <= max at every step
        if (!set(diagnostics, "MinHeapFreeRatio", "10") || !set(diagnostics, "MaxHeapFreeRatio", "30")) return
        bean = diagnostics
        listenForPeriodicCycles()
        noteActivity()
    }

    /** A request or notification arrived: the heap may have grown, so a periodic cycle should follow the next idle minute. */
    fun noteActivity() {
        activitySinceLastCycle = true
        if (!armed) arm()
    }

    private fun arm() {
        val b = bean ?: return
        if (set(b, "G1PeriodicGCInterval", PERIODIC_GC_INTERVAL_MS.toString())) armed = true
    }

    private fun disarm() {
        val b = bean ?: return
        if (set(b, "G1PeriodicGCInterval", "0")) armed = false
    }

    private fun set(bean: HotSpotDiagnosticMXBean, flag: String, value: String): Boolean = try {
        bean.setVMOption(flag, value)
        true
    } catch (e: Exception) {
        logger.debug("Cannot set {}={}: {}", flag, value, e.toString())
        false
    }

    /** After a periodic cycle that no activity preceded, the heap is as small as it gets: stop cycling until something happens. */
    private fun listenForPeriodicCycles() {
        try {
            for (gc in ManagementFactory.getGarbageCollectorMXBeans()) {
                (gc as? NotificationEmitter)?.addNotificationListener({ notification, _ ->
                    val data = notification.userData as? CompositeData ?: return@addNotificationListener
                    if (GarbageCollectionNotificationInfo.from(data).gcCause != PERIODIC_CAUSE) return@addNotificationListener
                    if (activitySinceLastCycle) activitySinceLastCycle = false else disarm()
                }, null, null)
            }
        } catch (e: Throwable) {
            logger.debug("No GC notifications; the periodic cycle stays armed", e)
        }
    }
}
