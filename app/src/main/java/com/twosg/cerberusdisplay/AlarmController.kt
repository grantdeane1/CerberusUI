package com.twosg.cerberusdisplay

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Controls the audible flow-alarm lifecycle.
 *
 * Policy (IEC 60601-1-8 fluid-delivery, High priority):
 *   - [startAlarm]  starts looping playback if not already active and not snoozed.
 *   - [stopAlarm]   stops and releases the player unconditionally.
 *   - [snooze]      pauses playback and sets [isSnoozed] = true for
 *                   [AlertConfig.ALARM_SNOOZE_MS] ms.  When the snooze timer
 *                   expires, [isSnoozed] returns to false.  The next incoming
 *                   status packet with a non-Normal flow state will call
 *                   [startAlarm] again, producing the auto-resume behaviour.
 *   - [release]     full cleanup; call from Activity onDestroy or DisposableEffect.
 *
 * Audio asset: app/src/main/res/raw/fluid_delivery_alarm.wav
 *   Generate with audio-specifications/generate_alarm.py if not present.
 *   If the resource is absent at runtime the alarm is silently skipped so
 *   the build and non-alarm code paths are unaffected.
 */
class AlarmController(private val context: Context) {

    private var player: MediaPlayer? = null

    /** Compose-observable snooze flag; true while the alarm is silenced. */
    var isSnoozed by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var snoozeJob: Job? = null

    /**
     * Start looped alarm playback.
     * No-op if the player is already active or the alarm is currently snoozed.
     */
    fun startAlarm() {
        if (isSnoozed || player?.isPlaying == true) return
        val resId = context.resources.getIdentifier(
            "fluid_delivery_alarm", "raw", context.packageName
        )
        if (resId == 0) return           // WAV asset not yet present
        player?.release()
        val mp = MediaPlayer.create(context, resId) ?: return
        mp.isLooping = true
        mp.start()
        player = mp
    }

    /**
     * Stop playback immediately and cancel any pending snooze timer.
     * Safe to call when no alarm is active.
     */
    fun stopAlarm() {
        snoozeJob?.cancel()
        isSnoozed = false
        releasePlayer()
    }

    /**
     * Silence the alarm for [AlertConfig.ALARM_SNOOZE_MS] ms.
     * After the timeout, [isSnoozed] resets to false.  The alarm auto-resumes
     * on the next incoming packet if the flow state is still non-Normal.
     */
    fun snooze() {
        try { player?.let { if (it.isPlaying) it.pause() } } catch (_: Exception) {}
        isSnoozed = true
        snoozeJob?.cancel()
        snoozeJob = scope.launch {
            delay(AlertConfig.ALARM_SNOOZE_MS.toLong())
            isSnoozed = false
            // Auto-resume is driven by the next status packet via startAlarm().
        }
    }

    /**
     * Release all resources.
     * Must be called when the owning composable or activity is destroyed.
     */
    fun release() {
        snoozeJob?.cancel()
        scope.cancel()
        releasePlayer()
    }

    private fun releasePlayer() {
        try {
            player?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        } catch (_: Exception) {}
        player = null
    }
}
