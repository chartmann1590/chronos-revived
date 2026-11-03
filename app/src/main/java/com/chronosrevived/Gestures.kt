package com.chronosrevived

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.view.KeyEvent

/** Phone-side actions for taps the disc reports in its status bits (same mapping as the original app). */
object Gestures {
    private var ringtone: Ringtone? = null

    fun onStatus(ctx: Context, old: Int, new: Int) {
        fun rose(bit: Int) = new and bit != 0 && old and bit == 0
        val repeated = new and Proto.FACE_REPEATED_TAP != 0
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (!repeated && rose(Proto.CROWN_DOUBLE_TAP)) key(audio, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        if (!repeated && rose(Proto.CROWN_TRIPLE_TAP)) key(audio, KeyEvent.KEYCODE_MEDIA_NEXT)
        if (!repeated && rose(Proto.FACE_DOUBLE_TAP)) Chronos.say("face double tap")
        // Find phone: ring while the disc reports repeated face taps.
        if (repeated && ringtone?.isPlaying != true) ring(ctx)
        if (!repeated) ringtone?.stop()
    }

    private fun key(audio: AudioManager, code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        Chronos.say("media key $code")
    }

    private fun ring(ctx: Context) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(ctx, uri)?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            play()
        }
        Chronos.say("find phone: ringing")
    }
}
