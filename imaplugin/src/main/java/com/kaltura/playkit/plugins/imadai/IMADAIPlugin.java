package com.kaltura.playkit.plugins.imadai;

import android.content.Context;
import android.view.ViewGroup;

import com.google.ads.interactivemedia.v3.api.AdError;
import com.google.ads.interactivemedia.v3.api.AdErrorEvent;
import com.google.ads.interactivemedia.v3.api.AdEvent;
import com.google.ads.interactivemedia.v3.api.AdsLoader;
import com.google.ads.interactivemedia.v3.api.AdsManagerLoadedEvent;
import com.google.ads.interactivemedia.v3.api.AdsRenderingSettings;
import com.google.ads.interactivemedia.v3.api.CuePoint;
import com.google.ads.interactivemedia.v3.api.ImaSdkFactory;
import com.google.ads.interactivemedia.v3.api.ImaSdkSettings;
import com.google.ads.interactivemedia.v3.api.StreamDisplayContainer;
import com.google.ads.interactivemedia.v3.api.StreamManager;
import com.google.ads.interactivemedia.v3.api.StreamRequest;
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate;
import com.google.ads.interactivemedia.v3.api.player.VideoStreamPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kaltura.playkit.MessageBus;
import com.kaltura.playkit.PKError;
import com.kaltura.playkit.PKEvent;
import com.kaltura.playkit.PKLog;
import com.kaltura.playkit.PKMediaConfig;
import com.kaltura.playkit.PKMediaFormat;
import com.kaltura.playkit.PKPlugin;
import com.kaltura.playkit.Player;
import com.kaltura.playkit.PlayerDecorator;
import com.kaltura.playkit.PlayerEvent;
import com.kaltura.playkit.ads.AdDAIEnabledPlayerController;
import com.kaltura.playkit.ads.AdEnabledPlayerController;
import com.kaltura.playkit.ads.PKAdErrorType;
import com.kaltura.playkit.ads.PKAdInfo;
import com.kaltura.playkit.ads.PKAdProviderListener;
import com.kaltura.playkit.player.PlayerSettings;
import com.kaltura.playkit.plugin.ima.BuildConfig;
import com.kaltura.playkit.plugins.ads.AdInfo;
import com.kaltura.playkit.plugins.ads.AdsProvider;
import com.kaltura.playkit.utils.Consts;

import java.util.HashMap;
import java.util.List;

public class IMADAIPlugin extends PKPlugin implements AdEvent.AdEventListener, AdErrorEvent.AdErrorListener, AdsProvider {
    private static final PKLog log = PKLog.get("IMAPluginDAI");

    private Player player;
    private Context context;
    private MessageBus messageBus;
    private AdInfo adInfo;
    private IMADAIConfig adConfig;
    private PKMediaConfig mediaConfig;
    //////////////////////

    private boolean isAdDisplayed;
    private boolean isAdIsPaused;
    private boolean isAdRequested;
    private boolean isAdError;
    private PKAdProviderListener pkAdProviderListener;
    private boolean appIsInBackground;
    private boolean isAutoPlay;
    private boolean isContentPrepared;

    private ImaSdkSettings imaSdkSettings;
    private AdsRenderingSettings renderingSettings;
    private ImaSdkFactory sdkFactory;
    private AdsLoader adsLoader;
    private AdsLoader.AdsLoadedListener adsLoadedListener;
    private StreamManager streamManager;
    private StreamDisplayContainer displayContainer;
    private List<VideoStreamPlayer.VideoStreamPlayerCallback> mPlayerCallbacks;
    private List<CuePoint> cuePoints;

    private Context mContext;
    private ViewGroup mAdUiContainer;

    private double mBookMarkContentTime; // Bookmarked content time, in seconds.
    private double mSnapBackTime; // Stream time to snap back to, in seconds.

    //private String mFallbackUrl;


    @Override
    protected PlayerDecorator getPlayerDecorator() {
        return new AdDAIEnabledPlayerController(this);
    }


    public static final Factory factory = new Factory() {
        @Override
        public String getName() {
            return "IMADAI";
        }

        @Override
        public PKPlugin newInstance() {
            return new IMADAIPlugin();
        }

        @Override
        public String getVersion() {
            return BuildConfig.VERSION_NAME;
        }

        @Override
        public void warmUp(Context context) {
            log.d("warmUp started");
            ImaSdkFactory.getInstance().createAdsLoader(context);
        }
    };
    @Override
    protected void onLoad(final Player player, Object config, MessageBus messageBus, Context context) {
        log.d("onLoad");
        this.player = player;
        if (player == null) {
            log.e("Error, player instance is null.");
            return;
        }

        adConfig = parseConfig(config);
        if (adConfig == null) {
            log.e("Error, adConfig instance is null.");
            return;
        }
        mAdUiContainer = player.getView();
        this.context = context;
        this.messageBus = messageBus;

        this.messageBus.listen(new PKEvent.Listener() {
            @Override
            public void onEvent(PKEvent event) {
                log.d("XXX Received:PlayerEvent:" + event.eventType().name());
                if (event.eventType() == PlayerEvent.Type.ENDED) {

                } else if (event.eventType() == PlayerEvent.Type.PLAYING) {

                } else if (event.eventType() == PlayerEvent.Type.SEEKING) {
//                    PlayerEvent.Seeking seeking = (PlayerEvent.Seeking) event;
//                    double timeToSeek = (double) seeking.targetPosition;
//                    if (streamManager != null) {
//                        CuePoint cuePoint = streamManager.getPreviousCuePointForStreamTime(timeToSeek / Consts.MILLISECONDS_MULTIPLIER);
//                        double bookMarkStreamTime =
//                                streamManager.getStreamTimeForContentTime(mBookMarkContentTime);
//                        if (cuePoint != null && !cuePoint.isPlayed() && cuePoint.getEndTime() > bookMarkStreamTime) {
//                            //mSnapBackTime = timeToSeek / 1000.0; // Update snap back time.
//                            // Missed cue point, so snap back to the beginning of cue point.
//                            timeToSeek = cuePoint.getStartTime() * Consts.MILLISECONDS_MULTIPLIER;
//                            log.d("SnapBack to " + timeToSeek);
//                            player.seekTo(Math.round(timeToSeek));
//                            return;
//                        }
//                    }
//                    player.seekTo(Math.round(timeToSeek));
                }
            }
        }, PlayerEvent.Type.ENDED, PlayerEvent.Type.PLAYING, PlayerEvent.Type.SEEKING);
    }

    @Override
    protected void onUpdateMedia(PKMediaConfig mediaConfig) {
        log.d("Start onUpdateMedia");
        isAutoPlay = false;
        isContentPrepared = false;
        isAdRequested = false;
        isAdDisplayed = false;
        isAdIsPaused  = false;
        this.mediaConfig = mediaConfig;
        imaSetup();
        displayContainer = sdkFactory.createStreamDisplayContainer();

        adsLoader.requestStream(buildStreamRequest());

    }

    ////////Ads Plugin
    private void imaSetup() {
        log.d("imaSetup start");
        imaSettingSetup();
        if (sdkFactory == null) {
            sdkFactory = ImaSdkFactory.getInstance();
        }
        if (adsLoader == null) {
            adsLoader = sdkFactory.createAdsLoader(context, imaSdkSettings);
            // Add listeners for when ads are loaded and for errors.
            adsLoader.addAdErrorListener(IMADAIPlugin.this);
            adsLoader.addAdsLoadedListener(getAdsLoadedListener());
        }
    }

    private void imaSettingSetup() {
        if (imaSdkSettings == null) {
            imaSdkSettings = ImaSdkFactory.getInstance().createImaSdkSettings();
        }
        // Tell the SDK we want to control ad break playback.
        //imaSdkSettings.setAutoPlayAdBreaks(true);
        if (adConfig.getMaxRedirects() > 0) {
            imaSdkSettings.setMaxRedirects(adConfig.getMaxRedirects());
        }
        imaSdkSettings.setLanguage(adConfig.getLanguage());
        imaSdkSettings.setDebugMode(adConfig.isDebugMode());
        imaSdkSettings.setEnableOmidExperimentally(adConfig.isOMIDExperimentalEnabled());
    }

    private AdsLoader.AdsLoadedListener getAdsLoadedListener() {
        if (adsLoadedListener != null) {
            return adsLoadedListener;
        }
        adsLoadedListener = new AdsLoader.AdsLoadedListener() {
            @Override
            public void onAdsManagerLoaded(AdsManagerLoadedEvent adsManagerLoadedEvent) {
                log.d("AdsManager loaded");
                isAdRequested = true;
                streamManager = adsManagerLoadedEvent.getStreamManager();
                streamManager.addAdErrorListener(IMADAIPlugin.this);
                streamManager.addAdEventListener(IMADAIPlugin.this);
                streamManager.init();
            }
        };
        return adsLoadedListener;
    }

    private StreamRequest buildStreamRequest() {
//
        VideoStreamPlayer videoStreamPlayer = createVideoStreamPlayer();
//
//        // Set the license URL.
//        mVideoPlayer.setLicenseUrl(videoListItem.getLicenseUrl());
//        mVideoPlayer.setSampleVideoPlayerCallback(
//                new SampleVideoPlayer.SampleVideoPlayerCallback() {
//                    @Override
//                    public void onUserTextReceived(String userText) {
//                        for (VideoStreamPlayer.VideoStreamPlayerCallback callback : mPlayerCallbacks) {
//                            callback.onUserTextReceived(userText);
//                        }
//                    }
//                    @Override
//                    public void onSeek(int windowIndex, long positionMs) {
//                        double timeToSeek = positionMs;
//                        if (mStreamManager != null) {
//                            CuePoint cuePoint = mStreamManager.getPreviousCuePointForStreamTime(positionMs / 1000);
//                            double bookMarkStreamTime =
//                                    mStreamManager.getStreamTimeForContentTime(mBookMarkContentTime);
//                            if (cuePoint != null && !cuePoint.isPlayed() && cuePoint.getEndTime() > bookMarkStreamTime) {
//                                mSnapBackTime = timeToSeek / 1000.0; // Update snap back time.
//                                // Missed cue point, so snap back to the beginning of cue point.
//                                timeToSeek = cuePoint.getStartTime() * 1000;
//                                Log.i("IMA", "SnapBack to " + timeToSeek);
//                                mVideoPlayer.seekTo(windowIndex, Math.round(timeToSeek));
//                                mVideoPlayer.setCanSeek(false);
//
//                                return;
//                            }
//                        }
//                        mVideoPlayer.seekTo(windowIndex, Math.round(timeToSeek));
//                    }
//                });
        displayContainer.setVideoStreamPlayer(videoStreamPlayer);
        displayContainer.setAdContainer(mAdUiContainer);

        StreamRequest request;
        // Live stream request.
        if (adConfig.getAssetKey() != null) {
            request = sdkFactory.createLiveStreamRequest(adConfig.getAssetKey(),
                    adConfig.getApiKey(), displayContainer);
        } else { // VOD request.
            request = sdkFactory.createVodStreamRequest(adConfig.getContentSourceId(),
                    adConfig.getVideoId(), adConfig.getApiKey(), displayContainer);
        }
        // Set the stream format (HLS or DASH).
        request.setFormat(adConfig.getStreamFormat());

        return request;
    }

    private VideoStreamPlayer createVideoStreamPlayer() {
        return new VideoStreamPlayer() {
            @Override
            public void loadUrl(String url, List<HashMap<String, String>> subtitles) {
                log.d("loadUrl = " + url);

                mediaConfig.getMediaEntry().getSources().get(0).setUrl(url).setMediaFormat(PKMediaFormat.hls).setDrmData(null);

                player.prepare(mediaConfig);
                player.play();
//                mVideoPlayer.setStreamUrl(url);
//                mVideoPlayer.play();
//
//                // Bookmarking
//                if (mBookMarkContentTime > 0) {
//                    double streamTime =
//                            mStreamManager.getStreamTimeForContentTime(mBookMarkContentTime);
//                    mVideoPlayer.seekTo((long) (streamTime * 1000.0)); // s to ms.
//                }
            }

            @Override
            public int getVolume() {
                return 1;
            }

            @Override
            public void addCallback(VideoStreamPlayerCallback videoStreamPlayerCallback) {
                //mPlayerCallbacks.add(videoStreamPlayerCallback);
            }

            @Override
            public void removeCallback(VideoStreamPlayerCallback videoStreamPlayerCallback) {
                //mPlayerCallbacks.remove(videoStreamPlayerCallback);
            }

            @Override
            public void onAdBreakStarted() {
                log.d(" Started");
                // Disable player controls.
//                mVideoPlayer.setCanSeek(false);
//                mVideoPlayer.enableControls(false);

            }

            @Override
            public void onAdBreakEnded() {
                log.d("Ad Break Ended");
                // Re-enable player controls.
//                mVideoPlayer.setCanSeek(true);
//                mVideoPlayer.enableControls(true);
                if (mSnapBackTime > 0) {
                    log.d("onAdBreakEnded seeking " + mSnapBackTime);
                    player.seekTo(Math.round(mSnapBackTime * Consts.MILLISECONDS_MULTIPLIER));
                }
                mSnapBackTime = 0;
            }

            @Override
            public VideoProgressUpdate getContentProgress() {
                if (player == null) {
                    return VideoProgressUpdate.VIDEO_TIME_NOT_READY;
                }
                long position = player.getCurrentPosition();
                long duration = player.getDuration();
                if (position < 0 || duration < 0) {
                    return VideoProgressUpdate.VIDEO_TIME_NOT_READY;
                }
                return new VideoProgressUpdate(position, duration);
            }
        };
    }

    @Override
    protected void onUpdateConfig(Object config) {
        log.d("Start onUpdateConfig");
        adConfig = parseConfig(config);
    }

    @Override
    protected void onApplicationPaused() {
        appIsInBackground = true;
        if (isAdDisplayed) {
            isAdIsPaused = true;
        }
    }

    @Override
    protected void onApplicationResumed() {
        appIsInBackground = false;
        if (isAdShouldAutoPlayOnResume()) {
            player.play();
        }
    }

    @Override
    protected void onDestroy() {
        isAdRequested = false;
    }

    @Override
    public void onAdEvent(com.google.ads.interactivemedia.v3.api.AdEvent adEvent) {
        if (streamManager == null) {
            log.w("WARNING, streamManager == null");
            return;
        }

        if (adEvent.getAdData() != null) {
            log.i("XXX EventData: " + adEvent.getAdData().toString());
        }
        if (adEvent.getType() != AdEvent.AdEventType.AD_PROGRESS) {
            log.d("XXX Event: " + adEvent.getType());
        }
        switch (adEvent.getType()) {
            case LOADED: // Fired when the stream manifest is available.
                log.d("AD LOADED");
                break;
            case AD_BREAK_STARTED: //Fired when an ad break starts.
                log.d("AD AD_BREAK_STARTED");
                break;
            case AD_BREAK_ENDED: //Fired when an ad break ends.
                log.d("AD AD_BREAK_ENDED");
                isAdDisplayed = false;
                if (pkAdProviderListener != null && !appIsInBackground) {
                    log.d("preparePlayer and play");
                    preparePlayer(true);
                }
                break;
            case CUEPOINTS_CHANGED: //Dispatched for on-demand streams when the cuepoints change.
                log.d("AD CUEPOINTS_CHANGED");
                cuePoints = streamManager.getCuePoints();
                if (cuePoints != null) {
                    for (CuePoint cue : cuePoints) {
                        log.d(String.format("XXX Cue: %s\n", cue.getStartTime() + " " + cue.getEndTime() + " " + cue.isPlayed()));
                    }
                }
                isAdDisplayed = true;
                break;
            case AD_PROGRESS: //Fired when there is an update to an ad's progress.
                break;
            case CLICKED: //Dispatched when the click element is clicked or tapped while an ad is being played.
                log.d("AD CLICKED");
                break;
            case STARTED: //Fired when an ad starts.
                log.d("AD STARTED");
                break;
            case FIRST_QUARTILE: //Fired when an ad reaches its first quartile.
                log.d("AD FIRST_QUARTILE");
                break;
            case MIDPOINT: //Fired when an ad reaches its midpoint.
                log.d("AD MIDPOINT");
                break;
            case THIRD_QUARTILE: //Fired when an ad reaches its third quartile.
                log.d("AD THIRD_QUARTILE");
                break;
            case PAUSED:
                log.d("AD PAUSED");
                break;
            case RESUMED:
                log.d("AD RESUMED");
                break;
            case COMPLETED: //Fired when an ad is complete.
                log.d("AD COMPLETED");
                break;
            case TAPPED:
                log.d("AD TAPPED");
                break;
            case ICON_TAPPED:
                log.d("AD ICON_TAPPED");
            case LOG:
                log.d("AD LOG ERROR");
                break;
            default:
                break;
        }
    }

    @Override
    public void onAdError(AdErrorEvent adErrorEvent) {
        log.e("Event: onAdError" + adErrorEvent.getError().getErrorCode());
        isAdError = true;
        //resetFlagsOnError();

        AdError adException = adErrorEvent.getError();
        String errorMessage = (adException == null) ? "Unknown Error" : adException.getMessage();
        Enum errorType = PKAdErrorType.UNKNOWN_ERROR;

        if (adException != null) {

            switch (adException.getErrorCode()) {
                case INTERNAL_ERROR:
                    errorType = PKAdErrorType.INTERNAL_ERROR;
                    break;
                case VAST_MALFORMED_RESPONSE:
                    errorType = PKAdErrorType.VAST_MALFORMED_RESPONSE;
                    break;
                case UNKNOWN_AD_RESPONSE:
                    errorType = PKAdErrorType.UNKNOWN_AD_RESPONSE;
                    break;
                case VAST_LOAD_TIMEOUT:
                    errorType = PKAdErrorType.VAST_LOAD_TIMEOUT;
                    break;
                case VAST_TOO_MANY_REDIRECTS:
                    errorType = PKAdErrorType.VAST_TOO_MANY_REDIRECTS;
                    break;
                case VIDEO_PLAY_ERROR:
                    errorType = PKAdErrorType.VIDEO_PLAY_ERROR;
                    break;
                case VAST_MEDIA_LOAD_TIMEOUT:
                    errorType = PKAdErrorType.VAST_MEDIA_LOAD_TIMEOUT;
                    break;
                case VAST_LINEAR_ASSET_MISMATCH:
                    errorType = PKAdErrorType.VAST_LINEAR_ASSET_MISMATCH;
                    break;
                case OVERLAY_AD_PLAYING_FAILED:
                    errorType = PKAdErrorType.OVERLAY_AD_PLAYING_FAILED;
                    break;
                case OVERLAY_AD_LOADING_FAILED:
                    errorType = PKAdErrorType.OVERLAY_AD_LOADING_FAILED;
                    break;
                case VAST_NONLINEAR_ASSET_MISMATCH:
                    errorType = PKAdErrorType.VAST_NONLINEAR_ASSET_MISMATCH;
                    break;
                case COMPANION_AD_LOADING_FAILED:
                    errorType = PKAdErrorType.COMPANION_AD_LOADING_FAILED;
                    break;
                case UNKNOWN_ERROR:
                    errorType = PKAdErrorType.UNKNOWN_ERROR;
                    break;
                case VAST_EMPTY_RESPONSE:
                    errorType = PKAdErrorType.VAST_EMPTY_RESPONSE;
                    break;
                case FAILED_TO_REQUEST_ADS:
                    errorType = PKAdErrorType.FAILED_TO_REQUEST_ADS;
                    break;
                case VAST_ASSET_NOT_FOUND:
                    errorType = PKAdErrorType.VAST_ASSET_NOT_FOUND;
                    break;
                case ADS_REQUEST_NETWORK_ERROR:
                    errorType = PKAdErrorType.ADS_REQUEST_NETWORK_ERROR;
                    break;
                case INVALID_ARGUMENTS:
                    errorType = PKAdErrorType.INVALID_ARGUMENTS;
                    break;
                case PLAYLIST_NO_CONTENT_TRACKING:
                    errorType = PKAdErrorType.PLAYLIST_NO_CONTENT_TRACKING;
                    break;
            }
            if (errorMessage == null) {
                errorMessage = "Error code = " + adException.getErrorCode();
            }
        }

        sendError(errorType, errorMessage, adException);
        //displayContent();
        preparePlayer(isAutoPlay);
    }

    private void sendError(Enum errorType, String message, Throwable exception) {
        log.e("Ad Error: " + errorType.name() + " with message " + message);
        com.kaltura.playkit.plugins.ads.AdEvent errorEvent = new com.kaltura.playkit.plugins.ads.AdEvent.Error(new PKError(errorType, message, exception));
        messageBus.post(errorEvent);
    }

    @Override
    public void start() {
        isAutoPlay = true;
    }

    @Override
    public void destroyAdsManager() {

    }

    @Override
    public void resume() {
        isAdIsPaused = false;
        player.play();
    }

    @Override
    public void pause() {
        isAdIsPaused = true;
        player.pause();
    }

    @Override
    public void contentCompleted() {

    }

    @Override
    public PKAdInfo getAdInfo() {
        return null;
    }

    @Override
    public boolean isAdDisplayed() {
        return isAdDisplayed;
    }

    @Override
    public boolean isAdPaused() {
        return isAdIsPaused;
    }

    @Override
    public boolean isAdRequested() {
        return isAdRequested;
    }

    @Override
    public boolean isAllAdsCompleted() {
        return false;
    }

    @Override
    public boolean isAdError() {
        return isAdError;
    }

    @Override
    public long getDuration() {
        if (streamManager != null && streamManager.getAdProgressInfo() != null && isAdDisplayed) {
            return (long) Math.ceil(streamManager.getAdProgressInfo().getDuration());
        }
        return getFakePlayerDuration();

    }

    private long getFakePlayerDuration() {
        long playerDuration = player.getDuration();
        if (playerDuration < 0) {
            return 0;
        }
        if (cuePoints != null) {
            for (CuePoint cuePoint : cuePoints) {
                playerDuration -= Consts.MILLISECONDS_MULTIPLIER * (cuePoint.getEndTime() - cuePoint.getStartTime());
            }
        }
        if (playerDuration < 0) {
            return 0;
        }
        return  playerDuration;
    }

    @Override
    public long getCurrentPosition() {
        if (streamManager != null && streamManager.getAdProgressInfo() != null && isAdDisplayed) {
            return (long) Math.ceil(streamManager.getAdProgressInfo().getCurrentTime());
        }
        return getFakePlayerPosition();
    }

    private long getFakePlayerPosition() {
        long playerPosition = player.getCurrentPosition();
        if (playerPosition < 0) {
            return 0;
        }
        if (cuePoints != null) {
            for (CuePoint cuePoint : cuePoints) {
                if (cuePoint.isPlayed()) {
                    playerPosition -= Consts.MILLISECONDS_MULTIPLIER * (cuePoint.getEndTime() - cuePoint.getStartTime());
                }
            }
        }
        if (playerPosition > getFakePlayerDuration()) {
            return getFakePlayerDuration();
        }
        if (playerPosition < 0) {
            return 0;
        }
        return playerPosition;
    }

    @Override
    public void setAdProviderListener(AdEnabledPlayerController adEnabledPlayerController) {
        pkAdProviderListener = adEnabledPlayerController;
    }

    @Override
    public void removeAdProviderListener() {
        pkAdProviderListener = null;
    }

    @Override
    public void skipAd() {

    }

    @Override
    public void seekTo(long position) {
        double totalPlayedAdsDuration = 0;
        if (cuePoints != null) {
            for (CuePoint cuePoint : cuePoints) {
                if (cuePoint.getEndTime() * Consts.MILLISECONDS_MULTIPLIER < position || (cuePoint.getStartTime() * Consts.MILLISECONDS_MULTIPLIER == position && cuePoint.isPlayed())) {
                    totalPlayedAdsDuration += (cuePoint.getEndTime() - cuePoint.getStartTime());
                }
            }
        }
        if (streamManager != null) {
            CuePoint candidateCuePoint = streamManager.getPreviousCuePointForStreamTime(position / Consts.MILLISECONDS_MULTIPLIER);
            if (candidateCuePoint != null && !candidateCuePoint.isPlayed()) {
                player.seekTo(Math.round(candidateCuePoint.getStartTime() * Consts.MILLISECONDS_MULTIPLIER));
                mSnapBackTime = (position + totalPlayedAdsDuration * Consts.MILLISECONDS_MULTIPLIER) / Consts.MILLISECONDS_MULTIPLIER;
                return;
            }
        }
        player.seekTo(Math.round(position + (totalPlayedAdsDuration * Consts.MILLISECONDS_MULTIPLIER)));
    }

    private static IMADAIConfig parseConfig(Object config) {
        if (config instanceof IMADAIConfig) {
            return ((IMADAIConfig) config);

        } else if (config instanceof JsonObject) {
            return new Gson().fromJson(((JsonObject) config), IMADAIConfig.class);
        }
        return null;
    }

    private boolean isAdShouldAutoPlayOnResume() {
        return player.getSettings() instanceof PlayerSettings && ((PlayerSettings) player.getSettings()).isAdAutoPlayOnResume();
    }

    private void preparePlayer(boolean doPlay) {
        log.d("IMA prepare");
        if (pkAdProviderListener != null && !appIsInBackground) {
            log.d("IMA prepare player");
            isContentPrepared = true;
            pkAdProviderListener.onAdLoadingFinished();
            if (doPlay) {
                messageBus.listen(new PKEvent.Listener() {
                    @Override
                    public void onEvent(PKEvent event) {
                        log.d("IMA DURATION_CHANGE received calling play");
                        if (player != null && player.getView() != null && !isAdDisplayed()) {
                            //displayContent();
                            player.play();
                        }
                        messageBus.remove(this, PlayerEvent.Type.DURATION_CHANGE);
                    }
                }, PlayerEvent.Type.DURATION_CHANGE);
            }
        }
    }
}
