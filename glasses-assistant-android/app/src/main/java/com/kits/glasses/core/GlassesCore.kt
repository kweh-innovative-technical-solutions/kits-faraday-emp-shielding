package com.kits.glasses.core

/**
 * Device-agnostic capability contract for any pair of "glasses".
 *
 * A driver is whatever turns a Bluetooth device into audio in/out. The runtime
 * loop only ever talks to this interface, so a vendor SDK (Ray-Ban Meta, etc.)
 * and the generic A2DP/HFP fallback are interchangeable behind it. The generic
 * fallback needs no SDK at all — it is the floor every brand meets.
 */
interface GlassesCore {

    /** True once [connect] has succeeded and output is ready. */
    val isConnected: Boolean

    /**
     * Prepare the device for a turn (open TTS, confirm an audio route, etc.).
     * @return true if the device is ready to speak.
     */
    suspend fun connect(): Boolean

    /** Release any held audio/session resources. Safe to call repeatedly. */
    suspend fun disconnect()

    /** Speak [text] out the glasses/headset, suspending until playback finishes. */
    suspend fun speak(text: String)

    /** Play a short cue so the user knows what phase the turn is in. */
    suspend fun cue(cue: Cue)
}

/** Non-verbal (or minimal) signals for the turn lifecycle. */
enum class Cue {
    LISTENING,
    THINKING,
    DONE,
    ERROR
}
