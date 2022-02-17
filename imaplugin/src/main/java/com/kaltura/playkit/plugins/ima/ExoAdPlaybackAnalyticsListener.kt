package com.kaltura.playkit.plugins.ima

import androidx.annotation.Nullable
import com.kaltura.android.exoplayer2.Format
import com.kaltura.android.exoplayer2.analytics.AnalyticsListener
import com.kaltura.android.exoplayer2.decoder.DecoderReuseEvaluation
import com.kaltura.android.exoplayer2.trackselection.DefaultTrackSelector
import com.kaltura.android.exoplayer2.util.EventLogger

class ExoAdPlaybackAnalyticsListener(trackSelector: DefaultTrackSelector): EventLogger(trackSelector) {

    interface VideoFormatChangedListener {
        fun videoFormatChanged(format: Format)
    }

    @Nullable
    private var videoFormatChangedListener: VideoFormatChangedListener? = null

    fun setListener(@Nullable videoFormatChangedListener: VideoFormatChangedListener) {
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
