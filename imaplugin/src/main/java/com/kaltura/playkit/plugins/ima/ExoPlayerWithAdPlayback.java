package com.kaltura.playkit.plugins.ima;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import com.google.ads.interactivemedia.v3.api.player.ContentProgressProvider;
import com.google.ads.interactivemedia.v3.api.player.VideoAdPlayer;
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.PlaybackPreparer;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.util.EventLogger;
import com.google.android.exoplayer2.util.Util;
import com.kaltura.playkit.PKError;
import com.kaltura.playkit.PKLog;
import com.kaltura.playkit.PlayerState;
import com.kaltura.playkit.drm.DeferredDrmSessionManager;
import com.kaltura.playkit.player.MediaSupport;
import com.kaltura.playkit.plugins.ads.AdCuePoints;
import com.kaltura.playkit.utils.Consts;

import java.util.ArrayList;
import java.util.List;

/**
 * Video player that can play content video and ads.
 */
public class ExoPlayerWithAdPlayback extends RelativeLayout implements PlaybackPreparer, Player.EventListener {
    private static final PKLog log = PKLog.get("ExoPlayerWithAdPlayback");

    private static final DefaultBandwidthMeter BANDWIDTH_METER = new DefaultBandwidthMeter();
    private DefaultTrackSelector trackSelector;
    private EventLogger eventLogger;
    private DefaultRenderersFactory renderersFactory;
    private SimpleExoPlayer player;
    private PlayerState lastPlayerState;

    private DataSource.Factory mediaDataSourceFactory;
    private Context mContext;
    private com.kaltura.playkit.Player contentPlayer;
    private boolean isPlayerReady = false;
    private AdCuePoints adCuePoints;
    private int adLoadTimeout = 8000; // mili sec

    // The wrapped video player.
    private PlayerView mVideoPlayer;

    // The SDK will render ad playback UI elements into this ViewGroup.
    private ViewGroup mAdUiContainer;

    // Used to track if the current video is an ad (as opposed to a content video).
    private boolean mIsAdDisplayed;

    // VideoAdPlayer interface implementation for the SDK to send ad play/pause type events.
    private VideoAdPlayer mVideoAdPlayer;

    // ContentProgressProvider interface implementation for the SDK to check content progress.
    private ContentProgressProvider mContentProgressProvider;

    private boolean isAdFirstPlay;

    private String lastKnownAdURL;
    private long lastKnownAdPosition;

    private final List<VideoAdPlayer.VideoAdPlayerCallback> mAdCallbacks = new ArrayList<>();

    private ExoPlayerWithAdPlayback.OnAdPlayBackListener onAdPlayBackListener;

    public interface OnAdPlayBackListener {
        void onBufferStart();

        void onBufferEnd();

        void onSourceError(Exception exoPlayerException);

        void adFirstPlayStarted();
    }

    public ExoPlayerWithAdPlayback(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        this.mContext = context;
    }

    public ExoPlayerWithAdPlayback(Context context, AttributeSet attrs) {
        super(context, attrs, 0);
    }

    public ExoPlayerWithAdPlayback(Context context, int adLoadTimeout) {
        super(context, null);
        this.mContext = context;
        if (adLoadTimeout < Consts.MILLISECONDS_MULTIPLIER) {
            this.adLoadTimeout = adLoadTimeout * 1000;
        }
        init();
    }

    public ViewGroup getAdUiContainer() {
        return mAdUiContainer;
    }

    public PlayerView getExoPlayerView() {
        return mVideoPlayer;
    }

    private DeferredDrmSessionManager.DrmSessionListener initDrmSessionListener() {
        return new DeferredDrmSessionManager.DrmSessionListener() {
            @Override
            public void onError(PKError error) {
            }
        };
    }

    private void init() {
        mIsAdDisplayed = false;
        lastKnownAdPosition = 0;
        mVideoPlayer = new PlayerView(getContext());
        mVideoPlayer.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int id = 123456789;
        mVideoPlayer.setId(id);
        mVideoPlayer.setUseController(false);
        if (player == null) {

            mediaDataSourceFactory = buildDataSourceFactory(true);

            renderersFactory = new DefaultRenderersFactory(mContext,
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);

            TrackSelection.Factory adaptiveTrackSelectionFactory =
                    new AdaptiveTrackSelection.Factory(BANDWIDTH_METER);
            trackSelector = new DefaultTrackSelector(adaptiveTrackSelectionFactory);
            eventLogger = new EventLogger(trackSelector);
            initAdPlayer();
        }

        mAdUiContainer = mVideoPlayer;

        // Define VideoAdPlayer connector.
        mVideoAdPlayer = new VideoAdPlayer() {
            @Override
            public int getVolume() {
                if (player != null) {
                    return (int) (player.getVolume() * 100);
                }
                return 0;
            }

            @Override
            public void playAd() {
                log.d("playAd mIsAdDisplayed = " + mIsAdDisplayed);
                if (mIsAdDisplayed && isPlayerReady) {
                    for (VideoAdPlayer.VideoAdPlayerCallback callback : mAdCallbacks) {
                        log.d("playAd->onResume");
                        callback.onResume();
                        if (isAdPlayerPlaying()) {
                            play();
                        }
                        return;
                    }
                } else {
                    mIsAdDisplayed = true;
                    for (VideoAdPlayer.VideoAdPlayerCallback callback : mAdCallbacks) {
                        log.d("playAd->onPlay");
                        callback.onPlay();
                        isAdFirstPlay = true;
                        return;
                    }
                }

                //Make sure events will be fired ater pause
                for (VideoAdPlayer.VideoAdPlayerCallback callback : mAdCallbacks) {
                    callback.onPlay();
                }
            }

            @Override
            public void loadAd(String url) {
                log.d("loadAd = " + url);
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
                mIsAdDisplayed = false;
                if (mVideoPlayer.getPlayer() != null) {
                    mVideoPlayer.getPlayer().stop();
                }
            }

            @Override
            public void pauseAd() {
                log.d("pauseAd");
                if (isAdPlayerPlaying()) {
                    return;
                }
                for (VideoAdPlayer.VideoAdPlayerCallback callback : mAdCallbacks) {
                    callback.onPause();
                }
                if (mVideoPlayer.getPlayer() != null) {
                    mVideoPlayer.getPlayer().setPlayWhenReady(false);
                }
            }

            @Override
            public void resumeAd() {
                log.d("resumeAd");
                playAd();

            }

            @Override
            public void addCallback(VideoAdPlayerCallback videoAdPlayerCallback) {
                mAdCallbacks.add(videoAdPlayerCallback);
            }

            @Override
            public void removeCallback(VideoAdPlayerCallback videoAdPlayerCallback) {
                mAdCallbacks.remove(videoAdPlayerCallback);
            }

            @Override
            public VideoProgressUpdate getAdProgress() {
                if (mVideoPlayer == null || mVideoPlayer.getPlayer() == null) {
                    return VideoProgressUpdate.VIDEO_TIME_NOT_READY;
                }
                long duration = mVideoPlayer.getPlayer().getDuration();
                long position = mVideoPlayer.getPlayer().getCurrentPosition();
                if (!isPlayerReady || !mIsAdDisplayed || duration < 0 || position < 0) {
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
        mVideoPlayer.getPlayer().addListener(this);
    }

    private boolean isAdPlayerPlaying() {
        return player == null || !player.getPlayWhenReady() || !isPlayerReady;
    }

    @Override
    public void preparePlayback() {
        log.d("preparePlayback");
    }

    @Override
    public void onTimelineChanged(Timeline timeline, Object manifest, int reason) {
        log.d("onTimelineChanged");
    }

    @Override
    public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
        log.d("onLoadingChanged");
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
                    if (mVideoPlayer.getPlayer().getDuration() > 0) {
                        for (VideoAdPlayer.VideoAdPlayerCallback callback : mAdCallbacks) {
                            callback.onResume();
                        }
                    } else {
                        for (VideoAdPlayer.VideoAdPlayerCallback callback : mAdCallbacks) {
                            callback.onPlay();
                        }
                    }
                } else {
                    for (VideoAdPlayer.VideoAdPlayerCallback callback : mAdCallbacks) {
                        callback.onPause();
                    }
                }
                break;
            case Player.STATE_ENDED:
                log.d("onPlayerStateChanged. ENDED. playWhenReady => " + playWhenReady);
                isPlayerReady = false;
                if (mIsAdDisplayed) {
                    for (VideoAdPlayer.VideoAdPlayerCallback callback : mAdCallbacks) {
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
        mContentProgressProvider = new ContentProgressProvider() {
            @Override
            public VideoProgressUpdate getContentProgress() {

                if (contentPlayer.getDuration() <= 0) {
                    return VideoProgressUpdate.VIDEO_TIME_NOT_READY;
                }
                long duration = contentPlayer.getDuration();
                long position = contentPlayer.getCurrentPosition();
                //log.d("xxx getContentProgress getDuration " +  duration);
                //log.d("xxx getContentProgress getCurrentPosition " + position);
                if (position > 0 && duration > 0 && position >= duration && adCuePoints != null && !adCuePoints.hasPostRoll()) {
                    mContentProgressProvider = null;
                    return VideoProgressUpdate.VIDEO_TIME_NOT_READY;
                }
                return new VideoProgressUpdate(contentPlayer.getCurrentPosition(),
                        duration);
            }
        };
    }

    public void addAdPlaybackEventListener(ExoPlayerWithAdPlayback.OnAdPlayBackListener onAdPlayBackListener) {
        this.onAdPlayBackListener = onAdPlayBackListener;
    }

    public void removeAdPlaybackEventListener() {
        onAdPlayBackListener = null;
    }

    public void pause() {
        if (mVideoPlayer != null && mVideoPlayer.getPlayer() != null) {
            mVideoPlayer.getPlayer().setPlayWhenReady(false);
        }
    }

    public void stop() {
        isPlayerReady = false;
        if (mVideoPlayer != null && mVideoPlayer.getPlayer() != null) {
            mVideoPlayer.getPlayer().setPlayWhenReady(false);
            mVideoPlayer.getPlayer().stop(true);
        }
    }

    public void play() {
        if (mVideoPlayer != null && mVideoPlayer.getPlayer() != null) {
            mVideoPlayer.getPlayer().setPlayWhenReady(true);
        }
    }

    public long getAdPosition() {
        if (mVideoPlayer != null && mVideoPlayer.getPlayer() != null) {
            if (mVideoPlayer.getPlayer().getContentPosition() > 0) {
                return mVideoPlayer.getPlayer().getContentPosition();
            }
        }
        return Consts.POSITION_UNSET;
    }

    public long getAdDuration() {
        if (mVideoPlayer != null && mVideoPlayer.getPlayer() != null) {
            if (mVideoPlayer.getPlayer().getDuration() > 0) {
                return mVideoPlayer.getPlayer().getDuration();
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
        return mVideoAdPlayer;
    }

    /**
     * Returns if an ad is displayed.
     *
     * @return the isAdDisplayed
     */
    public boolean getIsAdDisplayed() {
        return mIsAdDisplayed;
    }

    public ContentProgressProvider getContentProgressProvider() {
        return mContentProgressProvider;
    }

    public void setAdCuePoints(AdCuePoints adCuePoints) {
        this.adCuePoints = adCuePoints;
    }

    private void initializePlayer(String adUrl, boolean adShouldAutoPlay) {
        if (TextUtils.isEmpty(adUrl)) {
            sendSourceError(new IllegalArgumentException("Error, Ad playback url cannot be empty or null"));
            return;
        }

        Uri currentAdUri = Uri.parse(adUrl);
        if (player == null) {
            initAdPlayer();
        }

        MediaSource mediaSource = buildMediaSource(currentAdUri);
        mVideoPlayer.getPlayer().stop();
        player.prepare(mediaSource);
        mVideoPlayer.getPlayer().setPlayWhenReady(adShouldAutoPlay);
    }

    private void sendSourceError(Exception sourceException) {
        if (onAdPlayBackListener != null) {
            onAdPlayBackListener.onSourceError(sourceException);
        }
        if (mAdCallbacks != null) {
            for (VideoAdPlayer.VideoAdPlayerCallback callback : mAdCallbacks) {
                log.d("onPlayerError calling callback.onError()");
                callback.onError();
            }
        }
    }

    private void initAdPlayer() {
        player = ExoPlayerFactory.newSimpleInstance(renderersFactory, trackSelector);
        player.addAnalyticsListener(eventLogger);
        mVideoPlayer.setPlayer(player);
    }

    private MediaSource buildMediaSource(Uri uri) {

        switch (Util.inferContentType(uri)) {
            case C.TYPE_DASH:
                return new DashMediaSource.Factory(
                        new DefaultDashChunkSource.Factory(mediaDataSourceFactory),
                        buildDataSourceFactory(false))
                        .createMediaSource(uri);
            case C.TYPE_HLS:
                return new HlsMediaSource.Factory(mediaDataSourceFactory)
                        .createMediaSource(uri);
            case C.TYPE_OTHER:
                return new ExtractorMediaSource.Factory(mediaDataSourceFactory)
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
                stop();
            } else {
                pause();
            }
        } else {
            if (deviceRequiresDecoderRelease()) {
                if (lastKnownAdURL != null) {
                    initializePlayer(lastKnownAdURL, false);
                    isPlayerReady = true;
                    player.seekTo(lastKnownAdPosition);
                }
            }
        }
    }

    private boolean deviceRequiresDecoderRelease() {
        return ("mt6735").equals(MediaSupport.DEVICE_CHIPSET); // LYF (LS-5017) device chipset
    }

    public void resumeContentAfterAdPlayback() {
        pause();
        mIsAdDisplayed = false;
        isPlayerReady = false;
        isAdFirstPlay = false;
    }

    public void releasePlayer() {
        if (player != null) {
            player.clearVideoSurface();
            player.release();
            player = null;
            if (mVideoPlayer != null) {
                mVideoPlayer.setPlayer(null);
                mVideoPlayer = null;
            }
            mAdUiContainer = null;
            trackSelector = null;
            eventLogger = null;
            isAdFirstPlay = false;
        }
    }

    private DataSource.Factory buildDataSourceFactory(boolean useBandwidthMeter) {
        return new DefaultDataSourceFactory(getContext(), useBandwidthMeter ? BANDWIDTH_METER : null,
                buildHttpDataSourceFactory(useBandwidthMeter));
    }

    private HttpDataSource.Factory buildHttpDataSourceFactory(boolean useBandwidthMeter) {
        return new DefaultHttpDataSourceFactory(Util.getUserAgent(getContext(), "AdPlayKit"), useBandwidthMeter ? BANDWIDTH_METER : null,
                adLoadTimeout,
                adLoadTimeout, true);
    }
}