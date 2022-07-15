package com.kaltura.playkit.plugins.ima;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;

import com.google.ads.interactivemedia.v3.api.AdPodInfo;
import com.google.ads.interactivemedia.v3.api.player.AdMediaInfo;
import com.google.ads.interactivemedia.v3.api.player.ContentProgressProvider;
import com.google.ads.interactivemedia.v3.api.player.VideoAdPlayer;
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate;
import com.kaltura.android.exoplayer2.C;
import com.kaltura.android.exoplayer2.DefaultRenderersFactory;
import com.kaltura.android.exoplayer2.ExoPlayer;
import com.kaltura.android.exoplayer2.Format;
import com.kaltura.android.exoplayer2.MediaItem;
import com.kaltura.android.exoplayer2.PlaybackException;
import com.kaltura.android.exoplayer2.PlaybackParameters;
import com.kaltura.android.exoplayer2.Player;
import com.kaltura.android.exoplayer2.Timeline;
import com.kaltura.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.kaltura.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.kaltura.android.exoplayer2.ui.StyledPlayerView;
import com.kaltura.android.exoplayer2.upstream.DataSource;
import com.kaltura.android.exoplayer2.upstream.DefaultDataSource;
import com.kaltura.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.kaltura.android.exoplayer2.upstream.HttpDataSource;
import com.kaltura.android.exoplayer2.util.Log;
import com.kaltura.android.exoplayer2.util.Util;
import com.kaltura.playkit.PKLog;
import com.kaltura.playkit.PKMediaFormat;
import com.kaltura.playkit.PlayerState;
import com.kaltura.playkit.player.MediaSupport;
import com.kaltura.playkit.player.PKAspectRatioResizeMode;
import com.kaltura.playkit.plugins.ads.AdCuePoints;
import com.kaltura.playkit.utils.Consts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.kaltura.android.exoplayer2.util.Log.LOG_LEVEL_OFF;

/**
 * Video adPlayer that can play content video and ads.
 */
public class ExoPlayerWithAdPlayback extends RelativeLayout implements Player.Listener, ExoAdPlaybackAnalyticsListener.VideoFormatChangedListener {
    private static final PKLog log = PKLog.get("ExoPlayerWithAdPlayback");
    private static final int AD_PROGRESS_UPDATE_INTERVAL_MS = 100;

    public enum IMAAdState {
        IMA_AD_STATE_NONE,
        IMA_AD_STATE_PLAYING,
        IMA_AD_STATE_PAUSED
    }

    private DefaultTrackSelector trackSelector;
    private ExoAdPlaybackAnalyticsListener eventLogger;
    private DefaultRenderersFactory renderersFactory;
    private ExoPlayer adPlayer;
    private PlayerState lastPlayerState;

    private DataSource.Factory mediaDataSourceFactory;
    private Context mContext;
    private com.kaltura.playkit.Player contentPlayer;
    private boolean isPlayerReady = false;
    private AdCuePoints adCuePoints;
    private int adLoadTimeout = 8000; // mili sec
    private boolean debugEnabled;

    // The wrapped video adPlayerView.
    private StyledPlayerView adVideoPlayerView;

    // The SDK will render ad playback UI elements into this ViewGroup.
    private ViewGroup adUiContainer;

    // Used to track if the current video is an ad (as opposed to a content video).
    private boolean isAdDisplayed;

    // VideoAdPlayer interface implementation for the SDK to send ad play/pause type events.
    private VideoAdPlayer imaVideoAdPlayer;

    // ContentProgressProvider interface implementation for the SDK to check content progress.
    private ContentProgressProvider contentProgressProvider;

    private boolean isAdFirstPlay;

    private String lastKnownAdURL;
    private long lastKnownAdPosition;

    private final List<VideoAdPlayer.VideoAdPlayerCallback> adCallbacks = new ArrayList<>();

    private ExoPlayerWithAdPlayback.OnAdPlayBackListener onAdPlayBackListener;
    private AdMediaInfo lastAdMediaInfo;
    private Handler handler = null;
    private Runnable updateAdProgressRunnable = null;
    private boolean playWhenReady;
    private IMAAdState imaAdState = IMAAdState.IMA_AD_STATE_NONE;

    public interface OnAdPlayBackListener {
        void onBufferStart();

        void onBufferEnd();

        void onSourceError(Exception exoPlayerException);

        void adFirstPlayStarted();

        void adPlaybackInfoUpdated(int width, int height, int bitrate);
    }

    public ExoPlayerWithAdPlayback(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        this.mContext = context;
    }

    public ExoPlayerWithAdPlayback(Context context, AttributeSet attrs) {
        super(context, attrs, 0);
    }

    public ExoPlayerWithAdPlayback(Context context, int adLoadTimeout, boolean debugEnabled) {
        super(context, null);
        this.mContext = context;
        if (adLoadTimeout < Consts.MILLISECONDS_MULTIPLIER) {
            this.adLoadTimeout = adLoadTimeout * 1000;
        }
        this.debugEnabled = debugEnabled;

        handler = Util.createHandler(getImaLooper(), /* callback= */ null);
        updateAdProgressRunnable = this::updateAdProgress;

        init();
    }

    private StyledPlayerView createAdPlayerView() {
        adVideoPlayerView = new StyledPlayerView(getContext());
        adVideoPlayerView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int id = 123456789;
        adVideoPlayerView.setId(id);
        adVideoPlayerView.setUseController(false);
        return adVideoPlayerView;
    }

    public ViewGroup getAdUiContainer() {
        return adUiContainer;
    }

    public StyledPlayerView getAdPlayerView() {
        return adVideoPlayerView;
    }

    public void createNewAdPlayerView() {
        adVideoPlayerView = null;
        adVideoPlayerView = createAdPlayerView();
        adVideoPlayerView.setPlayer(adPlayer);
        adUiContainer.removeAllViews();
        adUiContainer = null;
        adUiContainer = adVideoPlayerView;
    }

    private void init() {
        isAdDisplayed = false;
        lastKnownAdPosition = 0;
        adVideoPlayerView = createAdPlayerView();
        if (adPlayer == null) {
            mediaDataSourceFactory = buildDataSourceFactory();
            renderersFactory = getRenderersFactory();
            trackSelector = getTrackSelector();
            eventLogger = getEventLogger();
            initAdPlayer();
        }

        adUiContainer = adVideoPlayerView;

        // Define VideoAdPlayer connector.
        imaVideoAdPlayer = new VideoAdPlayer() {
            @Override
            public int getVolume() {
                log.d("getVolume");
                if (adPlayer != null) {
                    int volume = (int) adPlayer.getVolume() * 100;
                    for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
                        if (lastAdMediaInfo != null) {
                            callback.onVolumeChanged(lastAdMediaInfo, volume);
                        }
                    }
                    return volume;
                }
                return 0;
            }


            @Override
            public void loadAd(AdMediaInfo adMediaInfo, AdPodInfo adPodInfo) {
                lastAdMediaInfo = adMediaInfo;

                String adUrl = adMediaInfo.getUrl();
                log.d("loadAd = " + adUrl);

                if (adVideoPlayerView == null) {   // FEM-2600
                    log.d("IMA Plugin destroyed; avoiding Ad Playback");
                    return;
                }

                for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
                    log.d("onLoaded");
                    if (adMediaInfo != null) {
                        callback.onLoaded(adMediaInfo);
                    }
                }

                lastKnownAdPosition = 0;
                lastKnownAdURL = adUrl;
                isPlayerReady = false;
                isAdFirstPlay = false;
                initializePlayer(lastKnownAdURL, true);
            }

            @Override
            public void playAd(AdMediaInfo adMediaInfo) {
                updateAdProgress();
                log.d("playAd isAdDisplayed = " + isAdDisplayed + " imaAdState = " + imaAdState);
                imaAdState = IMAAdState.IMA_AD_STATE_PLAYING;
                if (isAdDisplayed && isPlayerReady) {
                    for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
                        log.d("playAd->onResume");
                        if (adMediaInfo != null) {
                            callback.onResume(adMediaInfo);
                        }
                        if (!isAdPlayerPlaying()) {
                            play();
                        }
                        return;
                    }
                } else {
                    isAdDisplayed = true;
                    for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
                        log.d("playAd->onPlay");
                        if (adMediaInfo != null) {
                            callback.onPlay(adMediaInfo);
                        }
                        isAdFirstPlay = true;
                        return;
                    }
                }

                //Make sure events will be fired after pause
                for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
                    if (adMediaInfo != null) {
                        callback.onPlay(adMediaInfo);
                    }
                }
            }

            @Override
            public void pauseAd(AdMediaInfo adMediaInfo) {
                log.d("pauseAd");
                stopUpdatingAdProgress();

                log.d("pauseAd imaAdState = " + imaAdState);
                if (imaAdState == IMAAdState.IMA_AD_STATE_PAUSED) {
                    return;
                }

                for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
                    if (adMediaInfo != null) {
                        callback.onPause(adMediaInfo);
                    }
                }
                if (adVideoPlayerView != null && adVideoPlayerView.getPlayer() != null) {
                    adVideoPlayerView.getPlayer().setPlayWhenReady(false);
                }
                imaAdState = IMAAdState.IMA_AD_STATE_PAUSED;
            }

            @Override
            public void stopAd(AdMediaInfo adMediaInfo) {
                log.d("stopAd");
                stopUpdatingAdProgress();
                lastAdMediaInfo = null;
                isPlayerReady = false;
                isAdDisplayed = false;
                if (adPlayer != null) {
                    adPlayer.stop();
                }
            }

            @Override
            public void release() {
                stopUpdatingAdProgress();
                lastAdMediaInfo = null;
                isPlayerReady = false;
                isAdDisplayed = false;
            }

            @Override
            public void addCallback(VideoAdPlayerCallback videoAdPlayerCallback) {
                adCallbacks.add(videoAdPlayerCallback);
            }

            @Override
            public void removeCallback(VideoAdPlayerCallback videoAdPlayerCallback) {
                adCallbacks.remove(videoAdPlayerCallback);
            }

            @Override
            public VideoProgressUpdate getAdProgress() {
                if (adVideoPlayerView == null || adVideoPlayerView.getPlayer() == null) {
                    return VideoProgressUpdate.VIDEO_TIME_NOT_READY;
                }
                long duration = adVideoPlayerView.getPlayer().getDuration();
                long position = adVideoPlayerView.getPlayer().getCurrentPosition();
                if (!isPlayerReady || !isAdDisplayed || duration < 0 || position < 0) {
                    return VideoProgressUpdate.VIDEO_TIME_NOT_READY;
                }

                //log.d("getAdProgress getDuration " +  duration);
                //log.d("getAdProgress getCurrentPosition " +  position);
                if (position > duration) {
                    position = duration;
                }
                return new VideoProgressUpdate(position, duration);
            }
        };

        if (adVideoPlayerView.getPlayer() != null) {
            adVideoPlayerView.getPlayer().addListener(this);
        }
    }

    @NonNull
    private ExoAdPlaybackAnalyticsListener getEventLogger() {
        if (eventLogger == null) {
            eventLogger = new ExoAdPlaybackAnalyticsListener(getTrackSelector());
            eventLogger.setListener(this);
        }

        return eventLogger;
    }

    @NonNull
    private DefaultTrackSelector getTrackSelector() {
        if (trackSelector == null) {
            trackSelector = new DefaultTrackSelector(mContext, new AdaptiveTrackSelection.Factory());
            DefaultTrackSelector.ParametersBuilder builder = new DefaultTrackSelector.ParametersBuilder(mContext);
            trackSelector.setParameters(builder.build());
        }
        return trackSelector;
    }

    @NonNull
    private DefaultRenderersFactory getRenderersFactory() {
        if (renderersFactory == null) {
            renderersFactory = new DefaultRenderersFactory(mContext);
        }
        return renderersFactory;
    }

    private boolean isAdPlayerPlaying() {
        return adPlayer != null && adPlayer.isPlaying();
    }

    @Override
    public void onTimelineChanged(Timeline timeline, int reason) {
        log.d("onTimelineChanged");
    }

    @Override
    public void videoFormatChanged(Format trackFormat) {
        log.d("videoFormatChanged " + trackFormat);
        if (trackFormat != null && onAdPlayBackListener != null) {
            onAdPlayBackListener.adPlaybackInfoUpdated(trackFormat.width, trackFormat.height, trackFormat.bitrate);
        }
    }

    @Override
    public void onIsLoadingChanged(boolean isLoading) {
        log.d("onIsLoadingChanged");
    }

    @Override
    public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
        this.playWhenReady = playWhenReady;
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        log.d("onPlayerStateChanged " + playbackState + " lastPlayerState = " + lastPlayerState);
        switch (playbackState) {
            case Player.STATE_IDLE:
                log.d("onPlayerStateChanged. IDLE.");
                lastPlayerState = PlayerState.IDLE;
                break;
            case Player.STATE_BUFFERING:
                log.d("onPlayerStateChanged. BUFFERING");
                if (lastPlayerState != PlayerState.BUFFERING) {
                    lastPlayerState = PlayerState.BUFFERING;
                    if (onAdPlayBackListener != null) {
                        stopUpdatingAdProgress();
                        for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
                            if (lastAdMediaInfo != null) {
                                callback.onBuffering(lastAdMediaInfo);
                            }
                        }
                        onAdPlayBackListener.onBufferStart();
                    }
                }
                break;
            case Player.STATE_READY:
                log.d("onPlayerStateChanged. READY.");
                if (lastPlayerState == PlayerState.BUFFERING && onAdPlayBackListener != null) {
                    updateAdProgress();
                    onAdPlayBackListener.onBufferEnd();
                    if (isAdFirstPlay && onAdPlayBackListener != null) {
                        onAdPlayBackListener.adFirstPlayStarted();
                        isAdFirstPlay = false;
                    }
                }
                lastPlayerState = PlayerState.READY;
                isPlayerReady = true;
                if (playWhenReady) {
                    if (adVideoPlayerView != null &&
                            adVideoPlayerView.getPlayer() != null &&
                            adVideoPlayerView.getPlayer().getDuration() > 0) {
                        updateAdProgress();
                        for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
                            if (lastAdMediaInfo != null) {
                                callback.onResume(lastAdMediaInfo);
                            }
                        }
                    } else {
                        updateAdProgress();
                        for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
                            if (lastAdMediaInfo != null) {
                                callback.onPlay(lastAdMediaInfo);
                            }
                        }
                    }
                } else {
                    for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
                        stopUpdatingAdProgress();
                        if (lastAdMediaInfo != null) {
                            callback.onPause(lastAdMediaInfo);
                        }
                    }
                }
                break;
            case Player.STATE_ENDED:
                log.d("onPlayerStateChanged. ENDED. playWhenReady => " + playWhenReady);
                stopUpdatingAdProgress();
                isPlayerReady = false;
                if (isAdDisplayed) {
                    for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
                        if (lastAdMediaInfo != null) {
                            callback.onEnded(lastAdMediaInfo);
                        }
                    }
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {
        log.d("onRepeatModeChanged");
    }

    @Override
    public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
        log.d("onShuffleModeEnabledChanged");
    }

    @Override
    public void onPlayerError(PlaybackException playbackException) {
        log.d("onPlayerError error = " + playbackException.getMessage());
        stopUpdatingAdProgress();
        sendSourceError(playbackException);
    }

    @Override
    public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, @Player.DiscontinuityReason int reason) {
        log.d("onPositionDiscontinuity");
    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
        log.d("onPlaybackParametersChanged");

    }

    public void setContentProgressProvider(final com.kaltura.playkit.Player contentPlayer) {
        this.contentPlayer = contentPlayer;
        contentProgressProvider = () -> {

            if (contentPlayer.getDuration() <= 0) {
                return VideoProgressUpdate.VIDEO_TIME_NOT_READY;
            }
            long duration = contentPlayer.getDuration();
            long position = contentPlayer.getCurrentPosition();
            //log.d("getContentProgress getDuration " +  duration);
            //log.d("getContentProgress getCurrentPosition " + position);
            if (duration > 0 && position >= duration && adCuePoints != null && !adCuePoints.hasPostRoll()) {
                contentProgressProvider = null;
                return VideoProgressUpdate.VIDEO_TIME_NOT_READY;
            }
            return new VideoProgressUpdate(contentPlayer.getCurrentPosition(),
                    duration);
        };
    }

    public void addAdPlaybackEventListener(ExoPlayerWithAdPlayback.OnAdPlayBackListener onAdPlayBackListener) {
        this.onAdPlayBackListener = onAdPlayBackListener;
    }

    public void removeAdPlaybackEventListener() {
        onAdPlayBackListener = null;
    }

    public void pause() {
        if (adVideoPlayerView != null && adVideoPlayerView.getPlayer() != null) {
            adVideoPlayerView.getPlayer().setPlayWhenReady(false);
        }
    }

    public void stop(boolean isResetRequired) {
        isPlayerReady = false;
        lastAdMediaInfo = null;
        if (adVideoPlayerView != null && adVideoPlayerView.getPlayer() != null) {
            adVideoPlayerView.getPlayer().setPlayWhenReady(false);
            adVideoPlayerView.getPlayer().stop();
            if (isResetRequired) {
                adVideoPlayerView.getPlayer().clearMediaItems();
            }
        }
    }

    public void play() {
        if (adVideoPlayerView != null && adVideoPlayerView.getPlayer() != null) {
            adVideoPlayerView.getPlayer().setPlayWhenReady(true);
        }
    }

    public long getAdPosition() {
        if (adVideoPlayerView != null && adVideoPlayerView.getPlayer() != null) {
            if (adVideoPlayerView.getPlayer().getContentPosition() > 0) {
                return adVideoPlayerView.getPlayer().getContentPosition();
            }
        }
        return Consts.POSITION_UNSET;
    }

    public long getAdDuration() {
        if (adVideoPlayerView != null && adVideoPlayerView.getPlayer() != null) {
            if (adVideoPlayerView.getPlayer().getDuration() > 0) {
                return adVideoPlayerView.getPlayer().getDuration();
            }
        }
        return Consts.TIME_UNSET;
    }

    public void contentComplete() {
        for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
            callback.onContentComplete();
        }
    }

    /**
     * Returns an implementation of the SDK's VideoAdPlayer interface.
     *
     * @return the videoAdPlayer
     */
    public VideoAdPlayer getVideoAdPlayer() {
        return imaVideoAdPlayer;
    }

    /**
     * Returns if an ad is displayed.
     *
     * @return the isAdDisplayed
     */
    public boolean getIsAdDisplayed() {
        return isAdDisplayed;
    }

    public AdMediaInfo getLastAdMediaInfo() {
        return lastAdMediaInfo;
    }

    public ContentProgressProvider getContentProgressProvider() {
        return contentProgressProvider;
    }

    public void setAdCuePoints(AdCuePoints adCuePoints) {
        this.adCuePoints = adCuePoints;
    }

    public void setVolume(float volume) {
        log.d("setVolume to: " + volume);

        if (adPlayer == null) {
            return;
        }
        adPlayer.setVolume(volume);
    }

    public void setSurfaceAspectRatioResizeMode(PKAspectRatioResizeMode resizeMode, boolean isUpdateResizeMode) {
        if (resizeMode == null) {
            return;
        }
        if (adVideoPlayerView != null) {
            log.d("Ad surfaceAspectRatioResizeMode: " + resizeMode.name());
            adVideoPlayerView.setResizeMode(PKAspectRatioResizeMode.getExoPlayerAspectRatioResizeMode(resizeMode));
        }
    }

    private void initializePlayer(String adUrl, boolean adShouldAutoPlay) {
        log.d("ExoPlayerWithAdPlayback initializePlayer");
        if (TextUtils.isEmpty(adUrl)) {
            stopUpdatingAdProgress();
            sendSourceError(new IllegalArgumentException("Error, Ad playback url cannot be empty or null"));
            return;
        }

        Uri currentAdUri = Uri.parse(adUrl);
        if (adPlayer == null) {
            initAdPlayer();
        }

        if (adVideoPlayerView != null && adVideoPlayerView.getPlayer() != null) {
            MediaItem mediaItem = buildMediaItem(currentAdUri);
            adVideoPlayerView.getPlayer().stop();
            adPlayer.setMediaItems(Collections.singletonList(mediaItem), 0, C.TIME_UNSET);
            adPlayer.prepare();
            adVideoPlayerView.getPlayer().setPlayWhenReady(adShouldAutoPlay);
        }
    }

    private void sendSourceError(Exception sourceException) {
        if (onAdPlayBackListener != null) {
            onAdPlayBackListener.onSourceError(sourceException);
        }

        for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
            log.d("onPlayerError calling callback.onError()");
            if (lastAdMediaInfo != null) {
                callback.onError(lastAdMediaInfo);
            }
        }

    }

    private void initAdPlayer() {
        if (debugEnabled) {
            Log.setLogLevel(Log.LOG_LEVEL_ALL);
            Log.setLogStackTraces(true);
        } else {
            Log.setLogLevel(LOG_LEVEL_OFF);
            Log.setLogStackTraces(false);
        }

        adPlayer = new ExoPlayer.Builder(mContext, getRenderersFactory())
                .setTrackSelector(getTrackSelector()).build();
        adPlayer.addAnalyticsListener(getEventLogger());
        if (adVideoPlayerView != null) {  // FEM-2600
            adVideoPlayerView.setPlayer(adPlayer);
        }
    }

    private MediaItem buildMediaItem(Uri uri) {
        MediaItem.ClippingConfiguration clippingConfiguration = new MediaItem.ClippingConfiguration
                .Builder()
                .setStartPositionMs(0L)
                .setEndPositionMs(C.TIME_END_OF_SOURCE)
                .build();

        MediaItem.Builder builder =
                new MediaItem.Builder()
                        .setUri(uri)
                        .setSubtitleConfigurations(Collections.emptyList())
                        .setClippingConfiguration(clippingConfiguration);

        switch (Util.inferContentType(uri)) {
            case C.TYPE_DASH:
                builder.setMimeType(PKMediaFormat.dash.mimeType);
                break;
            case C.TYPE_HLS:
                builder.setMimeType(PKMediaFormat.hls.mimeType);
                break;
            case C.TYPE_OTHER:
                builder.setMimeType(PKMediaFormat.mp4.mimeType);
                break;
            default:
                throw new IllegalStateException("Unsupported type: " + Util.inferContentType(uri));
        }
        return builder.build();
    }

    public void setIsAppInBackground(boolean isAppInBackground) {
        if (isAppInBackground) {
            lastKnownAdPosition = getAdPosition();
            if (deviceRequiresDecoderRelease()) {
                stop(true);
            } else {
                pause();
            }
        } else {
            if (deviceRequiresDecoderRelease()) {
                if (lastKnownAdURL != null) {
                    initializePlayer(lastKnownAdURL, false);
                    isPlayerReady = true;
                    adPlayer.seekTo(lastKnownAdPosition);
                }
            }
        }
    }

    private boolean deviceRequiresDecoderRelease() {
        return ("mt6735").equals(MediaSupport.DEVICE_CHIPSET); // LYF (LS-5017) device chipset
    }

    public void resumeContentAfterAdPlayback() {
        pause();
        isAdDisplayed = false;
        isPlayerReady = false;
        isAdFirstPlay = false;
    }

    public void releasePlayer() {
        stopUpdatingAdProgress();
        handler = null;
        if (adPlayer != null) {
            adPlayer.clearVideoSurface();
            adPlayer.release();
            eventLogger.setListener(null);
            adPlayer = null;
            if (adVideoPlayerView != null) {
                adVideoPlayerView.setPlayer(null);
                adVideoPlayerView = null;
            }
            adUiContainer = null;
            trackSelector = null;
            eventLogger = null;
            isAdFirstPlay = false;
        }
    }

    private DataSource.Factory buildDataSourceFactory() {
        return new DefaultDataSource.Factory(getContext(), buildHttpDataSourceFactory());
    }

    private HttpDataSource.Factory buildHttpDataSourceFactory() {
        return new DefaultHttpDataSource.Factory()
                .setUserAgent(Util.getUserAgent(getContext(), "AdPlayKit"))
                .setConnectTimeoutMs(adLoadTimeout)
                .setReadTimeoutMs(adLoadTimeout)
                .setAllowCrossProtocolRedirects(true);
    }

    private static Looper getImaLooper() {
        return Looper.getMainLooper();
    }

    private void updateAdProgress() {
        sendAdProgressCallback();
        if (handler != null) {
            handler.removeCallbacks(updateAdProgressRunnable);
            handler.postDelayed(updateAdProgressRunnable, AD_PROGRESS_UPDATE_INTERVAL_MS);
        }
    }

    private void stopUpdatingAdProgress() {
        if (handler != null) {
            handler.removeCallbacks(updateAdProgressRunnable);
        }
    }

    private void sendAdProgressCallback() {
        for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
            if (lastAdMediaInfo != null && imaVideoAdPlayer != null) {
                callback.onAdProgress(lastAdMediaInfo, imaVideoAdPlayer.getAdProgress());
            }
        }
    }

}
