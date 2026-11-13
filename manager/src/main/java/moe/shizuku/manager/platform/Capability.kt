package moe.shizuku.manager.platform

/**
 * Every capability the platform reports has to say how it is obtained and whether it is really
 * available on this device. Nothing outside this file decides that: the registry is the single
 * source of truth, and the UI reads it instead of guessing.
 */
enum class Tier(val label: String) {
    /** Runs through the Shizuku service, which executes as the shell user. No root involved. */
    SHIZUKU_ADB("Shizuku / ADB"),

    /** Runs with a normal Android runtime permission the user granted. */
    ANDROID_PERMISSION("Android permission"),

    /** Needs a manufacturer-specific interface or setting. */
    MANUFACTURER("Manufacturer support"),

    /** Android does not allow this without root or a privileged install. */
    UNSUPPORTED("Unsupported without root")
}

enum class CapabilityState { AVAILABLE, UNAVAILABLE, UNKNOWN }

/**
 * @param detail the evidence behind the state. For AVAILABLE and UNAVAILABLE it is the probe result;
 * for UNKNOWN it says what could not be determined, so nothing is presented as a guess.
 */
data class Capability(
    val id: String,
    val label: String,
    val tier: Tier,
    val state: CapabilityState,
    val detail: String
)
