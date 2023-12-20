package com.kaltura.playkit.plugins.ima

import androidx.annotation.Nullable
import com.kaltura.androidx.media3.common.Format
import com.kaltura.androidx.media3.exoplayer.analytics.AnalyticsListener
import com.kaltura.androidx.media3.exoplayer.DecoderReuseEvaluation
import com.kaltura.androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.kaltura.androidx.media3.exoplayer.util.EventLogger

class ExoAdPlaybackAnalyticsListener: EventLogger() {

    interface VideoFormatChangedListener {
        fun videoFormatChanged(format: Format)
    }

    @Nullable
    private var videoFormatChangedListener: VideoFormatChangedListener? = null

    fun setListener(@Nullable videoFormatChangedListener: VideoFormatChangedListener?) {
        this.videoFormatChangedListener = videoFormatChangedListener
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
        super.onVideoInputFormatChanged(eventTime, format, decoderReuseEvaluation)
        videoFormatChangedListener?.videoFormatChanged(format)
    }
}
