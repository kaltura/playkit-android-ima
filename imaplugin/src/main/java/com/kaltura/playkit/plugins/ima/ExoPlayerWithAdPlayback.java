package com.kaltura.playkit.plugins.ima;

import android.content.Context;
import android.net.Uri;

import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;

import com.google.ads.interactivemedia.v3.api.player.ContentProgressProvider;
import com.google.ads.interactivemedia.v3.api.player.VideoAdPlayer;
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate;
import com.kaltura.android.exoplayer2.C;
import com.kaltura.android.exoplayer2.DefaultRenderersFactory;
import com.kaltura.android.exoplayer2.ExoPlaybackException;
import com.kaltura.android.exoplayer2.ExoPlayerFactory;
import com.kaltura.android.exoplayer2.Format;
import com.kaltura.android.exoplayer2.PlaybackParameters;
import com.kaltura.android.exoplayer2.PlaybackPreparer;
import com.kaltura.android.exoplayer2.Player;
import com.kaltura.android.exoplayer2.SimpleExoPlayer;
import com.kaltura.android.exoplayer2.Timeline;
import com.kaltura.android.exoplayer2.source.MediaSource;
import com.kaltura.android.exoplayer2.source.ProgressiveMediaSource;
import com.kaltura.android.exoplayer2.source.TrackGroupArray;
import com.kaltura.android.exoplayer2.source.dash.DashMediaSource;
import com.kaltura.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.kaltura.android.exoplayer2.source.hls.HlsMediaSource;
import com.kaltura.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.kaltura.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.kaltura.android.exoplayer2.trackselection.TrackSelection;
import com.kaltura.android.exoplayer2.trackselection.TrackSelectionArray;
import com.kaltura.android.exoplayer2.ui.PlayerView;
import com.kaltura.android.exoplayer2.upstream.DataSource;
import com.kaltura.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.kaltura.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.kaltura.android.exoplayer2.upstream.HttpDataSource;
import com.kaltura.android.exoplayer2.util.EventLogger;
import com.kaltura.android.exoplayer2.util.Log;
import com.kaltura.android.exoplayer2.util.Util;
import com.kaltura.playkit.PKLog;
import com.kaltura.playkit.PlayerState;
import com.kaltura.playkit.drm.DeferredDrmSessionManager;
import com.kaltura.playkit.player.MediaSupport;
import com.kaltura.playkit.plugins.ads.AdCuePoints;
import com.kaltura.playkit.utils.Consts;

import java.util.ArrayList;
import java.util.List;

import static com.kaltura.android.exoplayer2.C.SELECTION_REASON_ADAPTIVE;
import static com.kaltura.android.exoplayer2.C.SELECTION_REASON_INITIAL;
import static com.kaltura.android.exoplayer2.util.Log.LOG_LEVEL_OFF;

/**
 * Video adPlayer that can play content video and ads.
 */
public class ExoPlayerWithAdPlayback extends RelativeLayout implements PlaybackPreparer, Player.EventListener {
    private static final PKLog log = PKLog.get("ExoPlayerWithAdPlayback");

    private DefaultTrackSelector trackSelector;
    private EventLogger eventLogger;
    private DefaultRenderersFactory renderersFactory;
    private SimpleExoPlayer adPlayer;
    private PlayerState lastPlayerState;

    private DataSource.Factory mediaDataSourceFactory;
    private Context mContext;
    private com.kaltura.playkit.Player contentPlayer;
    private boolean isPlayerReady = false;
    private AdCuePoints adCuePoints;
    private int adLoadTimeout = 8000; // mili sec
    private boolean debugEnabled;

    // The wrapped video adPlayerView.
    private PlayerView adVideoPlayerView;

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
        init();
    }

    public ViewGroup getAdUiContainer() {
        return adUiContainer;
    }

    public PlayerView getAdPlayerView() {
        return adVideoPlayerView;
    }

    private void init() {
        isAdDisplayed = false;
        lastKnownAdPosition = 0;
        adVideoPlayerView = new PlayerView(getContext());
        adVideoPlayerView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int id = 123456789;
        adVideoPlayerView.setId(id);
        adVideoPlayerView.setUseController(false);
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
                if (adPlayer != null) {
                    return (int) (adPlayer.getVolume() * 100);
                }
                return 0;
            }

            @Override
            public void playAd() {
                log.d("playAd isAdDisplayed = " + isAdDisplayed);
                if (isAdDisplayed && isPlayerReady) {
                    for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
                        log.d("playAd->onResume");
                        callback.onResume();
                        if (isAdPlayerPlaying()) {
                            play();
                        }
                        return;
                    }
                } else {
                    isAdDisplayed = true;
                    for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
                        log.d("playAd->onPlay");
                        callback.onPlay();
                        isAdFirstPlay = true;
                        return;
                    }
                }

                //Make sure events will be fired after pause
                for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
                    callback.onPlay();
                }
            }

            @Override
            public void loadAd(String url) {
                log.d("loadAd = " + url);

                if (adVideoPlayerView == null) {   // FEM-2600
                    log.d("IMA Plugin destroyed; avoiding Ad Playback");
                    return;
                }

                lastKnownAdPosition = 0;
                lastKnownAdURL = url;
                isPlayerReady = false;
                isAdFirstPlay = false;
                initializePlayer(lastKnownAdURL, true);
            }

            @Override
            public void stopAd() {
                log.d("stopAd");
                isPlayerReady = false;
                isAdDisplayed = false;
                if (adPlayer != null) {
                    adPlayer.stop();
                }
            }

            @Override
            public void pauseAd() {
                log.d("pauseAd");
                if (isAdPlayerPlaying()) {
                    return;
                }
                for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
                    callback.onPause();
                }
                if (adVideoPlayerView != null && adVideoPlayerView.getPlayer() != null) {
                    adVideoPlayerView.getPlayer().setPlayWhenReady(false);
                }
            }

            @Override
            public void resumeAd() {
                log.d("resumeAd");
                //playAd(); --> resumeAd method is deprecated in IMA so nothing should be called.

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
    private EventLogger getEventLogger() {
        if (eventLogger == null) {
            eventLogger = new EventLogger(getTrackSelector());
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
        return adPlayer == null || !adPlayer.getPlayWhenReady() || !isPlayerReady;
    }

    @Override
    public void preparePlayback() {
        log.d("preparePlayback");
    }

    @Override
    public void onTimelineChanged(Timeline timeline, int reason) {
        log.d("onTimelineChanged");
    }

    @Override
    public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
        log.d("onLoadingChanged");
        if (trackSelections != null && trackSelections.length > 0) {
            TrackSelection trackSelection = trackSelections.get(Consts.TRACK_TYPE_VIDEO);
            if (trackSelection != null) {
                log.d("onLoadingChanged trackSelection.getSelectionReason() = " + trackSelection.getSelectionReason());
                if (trackSelection.getSelectionReason() == SELECTION_REASON_INITIAL || trackSelection.getSelectionReason() == SELECTION_REASON_ADAPTIVE) {
                    Format trackFormat = trackSelection.getFormat(trackSelection.getSelectedIndex());
                    onAdPlayBackListener.adPlaybackInfoUpdated(trackFormat.width, trackFormat.height, trackFormat.bitrate);
                }
            }
        }
    }

    @Override
    public void onLoadingChanged(boolean isLoading) {
        log.d("onTracksChanged");
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        log.d("onPlayerStateChanged " + playbackState + " lastPlayerState = " + lastPlayerState);
        switch (playbackState) {
            case Player.STATE_IDLE:
                log.d("onPlayerStateChanged. IDLE. playWhenReady => " + playWhenReady);
                lastPlayerState = PlayerState.IDLE;
                break;
            case Player.STATE_BUFFERING:
                log.d("onPlayerStateChanged. BUFFERING. playWhenReady => " + playWhenReady);
                lastPlayerState = PlayerState.BUFFERING;
                if (onAdPlayBackListener != null) {
                    onAdPlayBackListener.onBufferStart();
                }
                break;
            case Player.STATE_READY:
                log.d("onPlayerStateChanged. READY. playWhenReady => " + playWhenReady);
                if (lastPlayerState == PlayerState.BUFFERING && onAdPlayBackListener != null) {
                    onAdPlayBackListener.onBufferEnd();
                    if (isAdFirstPlay && onAdPlayBackListener != null) {
                        onAdPlayBackListener.adFirstPlayStarted();
                        isAdFirstPlay = false;
                    }
                }
                lastPlayerState = PlayerState.READY;
                isPlayerReady = true;
                if (playWhenReady) {
                    if (adVideoPlayerView.getPlayer().getDuration() > 0) {
                        for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
                            callback.onResume();
                        }
                    } else {
                        for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
                            callback.onPlay();
                        }
                    }
                } else {
                    for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
                        callback.onPause();
                    }
                }
                break;
            case Player.STATE_ENDED:
                log.d("onPlayerStateChanged. ENDED. playWhenReady => " + playWhenReady);
                isPlayerReady = false;
                if (isAdDisplayed) {
                    for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
                        callback.onEnded();
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
    public void onPlayerError(ExoPlaybackException error) {
        log.d("onPlayerError error = " + error.getMessage());
        sendSourceError(error);
    }

    @Override
    public void onPositionDiscontinuity(int reason) {
        log.d("onPositionDiscontinuity");
    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
        log.d("onPlaybackParametersChanged");

    }

    @Override
    public void onSeekProcessed() {
        log.d("onSeekProcessed");
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
            if (position > 0 && duration > 0 && position >= duration && adCuePoints != null && !adCuePoints.hasPostRoll()) {
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
        if (adVideoPlayerView != null && adVideoPlayerView.getPlayer() != null) {
            adVideoPlayerView.getPlayer().setPlayWhenReady(false);
            adVideoPlayerView.getPlayer().stop(isResetRequired);
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

    public ContentProgressProvider getContentProgressProvider() {
        return contentProgressProvider;
    }

    public void setAdCuePoints(AdCuePoints adCuePoints) {
        this.adCuePoints = adCuePoints;
    }

    private void initializePlayer(String adUrl, boolean adShouldAutoPlay) {
        log.d("ExoPlayerWithAdPlayback initializePlayer");
        if (TextUtils.isEmpty(adUrl)) {
            sendSourceError(new IllegalArgumentException("Error, Ad playback url cannot be empty or null"));
            return;
        }

        Uri currentAdUri = Uri.parse(adUrl);
        if (adPlayer == null) {
            initAdPlayer();
        }

        if (adVideoPlayerView != null && adVideoPlayerView.getPlayer() != null) {
            MediaSource mediaSource = buildMediaSource(currentAdUri);
            adVideoPlayerView.getPlayer().stop();
            adPlayer.prepare(mediaSource);
            adVideoPlayerView.getPlayer().setPlayWhenReady(adShouldAutoPlay);
        }
    }

    private void sendSourceError(Exception sourceException) {
        if (onAdPlayBackListener != null) {
            onAdPlayBackListener.onSourceError(sourceException);
        }

        for (VideoAdPlayer.VideoAdPlayerCallback callback : adCallbacks) {
            log.d("onPlayerError calling callback.onError()");
            callback.onError();
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

        adPlayer = ExoPlayerFactory.newSimpleInstance(mContext, getRenderersFactory(), getTrackSelector());
        adPlayer.addAnalyticsListener(getEventLogger());
        if (adVideoPlayerView != null) {  // FEM-2600
            adVideoPlayerView.setPlayer(adPlayer);
        }
    }

    private MediaSource buildMediaSource(Uri uri) {

        switch (Util.inferContentType(uri)) {
            case C.TYPE_DASH:
                return new DashMediaSource.Factory(
                        new DefaultDashChunkSource.Factory(mediaDataSourceFactory),
                        buildDataSourceFactory())
                        .createMediaSource(uri);
            case C.TYPE_HLS:
                return new HlsMediaSource.Factory(mediaDataSourceFactory)
                        .createMediaSource(uri);
            case C.TYPE_OTHER:
                return new ProgressiveMediaSource.Factory(mediaDataSourceFactory)
                        .createMediaSource(uri);
            default: {
                throw new IllegalStateException("Unsupported type: " + Util.inferContentType(uri));
            }
        }
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
        if (adPlayer != null) {
            adPlayer.clearVideoSurface();
            adPlayer.release();
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
        return new DefaultDataSourceFactory(getContext(),
                buildHttpDataSourceFactory());
    }

    private HttpDataSource.Factory buildHttpDataSourceFactory() {
        return new DefaultHttpDataSourceFactory(Util.getUserAgent(getContext(), "AdPlayKit"),
                adLoadTimeout,
                adLoadTimeout, true);
    }
}