package com.ahmadnayfeh.reel

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory

object Players {
    /**
     * Hardware decoders first; FFmpeg software decoders (AC3, E-AC3, DTS, TrueHD, …) as fallback,
     * so files that are silent in other players still have sound.
     */
    fun build(ctx: Context, music: Boolean): ExoPlayer {
        val renderers = DefaultRenderersFactory(ctx)
            .setExtensionRendererMode(
                if (Settings.softwareAudio(ctx)) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
            .setEnableDecoderFallback(true)
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(if (music) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        return ExoPlayer.Builder(ctx, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx, extractors))
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(Settings.doubleTapMs(ctx))
            .setSeekForwardIncrementMs(Settings.doubleTapMs(ctx))
            .build()
    }
}
