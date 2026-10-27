package dev.hiberno

/** Pure decision logic for the wake-up cut-off, kept separate so it is unit-testable off-device. */
class CutoffPolicy {
    private val awake = HashSet<String>()
    private var stamp = Long.MIN_VALUE

    /**
     * @param hostingType AMS HostingRecord type ("activity", "service", "broadcast", ...), null if unknown
     * @param stamp changes every hibernation round; resets which apps the user re-opened
     */
    @Synchronized
    fun shouldBlock(pkg: String, hostingType: String?, hibernated: Set<String>, stamp: Long): Boolean {
        if (stamp != this.stamp) {
            this.stamp = stamp
            awake.clear()
        }
        if (hostingType == null || pkg !in hibernated || pkg in awake) return false // fail open
        if ("activity" in hostingType) { // opened on screen: wake it for real until the next round
            awake += pkg
            return false
        }
        return true
    }
}
