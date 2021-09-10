package com.kaltura.playkit.plugins.imadai;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import com.google.ads.interactivemedia.v3.api.Ad;
import com.google.ads.interactivemedia.v3.api.AdError;
import com.google.ads.interactivemedia.v3.api.AdErrorEvent;
import com.google.ads.interactivemedia.v3.api.AdsLoader;
import com.google.ads.interactivemedia.v3.api.AdsRenderingSettings;
import com.google.ads.interactivemedia.v3.api.CuePoint;
import com.google.ads.interactivemedia.v3.api.FriendlyObstruction;
import com.google.ads.interactivemedia.v3.api.ImaSdkFactory;
import com.google.ads.interactivemedia.v3.api.ImaSdkSettings;
import com.google.ads.interactivemedia.v3.api.StreamDisplayContainer;
import com.google.ads.interactivemedia.v3.api.StreamManager;
import com.google.ads.interactivemedia.v3.api.StreamRequest;
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate;
import com.google.ads.interactivemedia.v3.api.player.VideoStreamPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kaltura.android.exoplayer2.C;
import com.kaltura.playkit.MessageBus;
import com.kaltura.playkit.PKDrmParams;
import com.kaltura.playkit.PKError;
import com.kaltura.playkit.PKErrorCategory;
import com.kaltura.playkit.PKEvent;
import com.kaltura.playkit.PKLog;
import com.kaltura.playkit.PKMediaConfig;
import com.kaltura.playkit.PKMediaEntry;
import com.kaltura.playkit.PKMediaFormat;
import com.kaltura.playkit.PKMediaSource;
import com.kaltura.playkit.PKPlugin;
import com.kaltura.playkit.Player;
import com.kaltura.playkit.PlayerEngineWrapper;
import com.kaltura.playkit.PlayerEvent;
import com.kaltura.playkit.PlayerState;
import com.kaltura.playkit.ads.AdsDAIPlayerEngineWrapper;
import com.kaltura.playkit.ads.PKAdErrorType;
import com.kaltura.playkit.ads.PKAdInfo;
import com.kaltura.playkit.ads.PKAdPluginType;
import com.kaltura.playkit.ads.PKAdProviderListener;
import com.kaltura.playkit.player.BaseExoplayerView;
import com.kaltura.playkit.player.PKMediaSourceConfig;
import com.kaltura.playkit.player.PlayerEngine;
import com.kaltura.playkit.player.PlayerSettings;
import com.kaltura.playkit.player.metadata.PKMetadata;
import com.kaltura.playkit.player.metadata.PKTextInformationFrame;
import com.kaltura.playkit.plugin.ima.BuildConfig;
import com.kaltura.playkit.plugins.ads.AdCuePoints;
import com.kaltura.playkit.plugins.ads.AdEvent;
import com.kaltura.playkit.plugins.ads.AdInfo;
import com.kaltura.playkit.plugins.ads.AdsProvider;
import com.kaltura.playkit.plugins.ima.PKFriendlyObstruction;
import com.kaltura.playkit.utils.Consts;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

public class IMADAIPlugin extends PKPlugin implements com.google.ads.interactivemedia.v3.api.AdEvent.AdEventListener, AdErrorEvent.AdErrorListener, AdsProvider {
    private static final PKLog log = PKLog.get("IMADAIPlugin");
    private static final int KB_MULTIPLIER = 1024;
    private static final String TXXX = "TXXX";

    private Player player;
    private Context context;
    private MessageBus messageBus;
    private AdInfo adInfo;
    private IMADAIConfig adConfig;
    private PKMediaConfig mediaConfig;
    private PKMediaSourceConfig pkMediaSourceConfig;
    //////////////////////

    private VideoStreamPlayer videoStreamPlayer;
    private boolean isAdDisplayed;
    private boolean isAdIsPaused;
    private boolean isAdRequested;
    private boolean isAdError;
    private PKAdProviderListener pkAdProviderListener;
    private boolean appIsInBackground;
    private boolean isAutoPlay;
    private boolean isContentPrepared;
    private Long playbackStartPosition;
    private long playbackCurrentPosition = Consts.POSITION_UNSET;
    private Long playbackDuration = Consts.TIME_UNSET;

    private ImaSdkSettings imaSdkSettings;
    private AdsRenderingSettings renderingSettings;
    private ImaSdkFactory sdkFactory;
    private AdsLoader adsLoader;
    private AdsLoader.AdsLoadedListener adsLoadedListener;
    private StreamManager streamManager;
    private StreamDisplayContainer displayContainer;
    private List<VideoStreamPlayer.VideoStreamPlayerCallback> mPlayerCallbacks;
    private List<CuePoint> pluginCuePoints;
    private AdCuePoints playkitAdCuePoints;
    private PlayerEvent.Type lastPlaybackPlayerState;
    private com.kaltura.playkit.plugins.ads.AdEvent.Type lastAdEventReceived;
    private PlayerEngineWrapper adsPlayerEngineWrapper;
    private boolean shouldPrepareOnResume;
    private Map<com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType, com.kaltura.playkit.plugins.ads.AdEvent.Type> adEventsMap;
    private ViewGroup mAdUiContainer;
    private View savedPlayerView;

    //private double mBookMarkContentTime; // Bookmarked content time, in seconds.
    private long mSnapBackTime; // Stream time to snap back to, in miliseconds.

    public static final Factory factory = new Factory() {
        @Override
        public String getName() {
            return "imadai";
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
            //ImaSdkFactory.getInstance().createAdsLoader(context, null);
        }
    };

    @Override
    protected void onLoad(final Player player, Object config, MessageBus messageBus, Context context) {
        log.d("onLoad");
        this.context = context;
        this.player = player;
        mPlayerCallbacks = new ArrayList<>();
        if (player == null) {
            log.e("Error, player instance is null.");
            return;
        }
        adEventsMap = buildAdsEventMap();
        adConfig = parseConfig(config);
        if (adConfig == null) {
            log.e("Error, adConfig instance is null.");
            return;
        }
        mAdUiContainer = player.getView();
        this.messageBus = messageBus;

        addListeners();
    }

    private void addListeners() {

        messageBus.addListener(this, PlayerEvent.ended, event -> {
            log.d("Received:PlayerEvent:" + event.eventType().name());
            lastPlaybackPlayerState = PlayerEvent.Type.ENDED;
            if (getPlayerEngine() != null) {
                long currentPosMs = getPlayerEngine().getCurrentPosition();
                if (streamManager != null) {
                    CuePoint prevCuePoint = streamManager.getPreviousCuePointForStreamTimeMs(currentPosMs);
                    if (getPlayerEngine().getCurrentPosition() >= getPlayerEngine().getDuration() && prevCuePoint != null && currentPosMs >= prevCuePoint.getEndTimeMs()) {
                        if (pluginCuePoints != null && !pluginCuePoints.isEmpty() && Math.floor(pluginCuePoints.get(pluginCuePoints.size() - 1).getEndTimeMs()) == getPlayerEngine().getDuration()) {
                            if (!pluginCuePoints.get(pluginCuePoints.size() - 1).isPlayed()) {
                                getPlayerEngine().seekTo(pluginCuePoints.get(pluginCuePoints.size() - 1).getStartTimeMs());
                            }
                        }
                    }
                }
            }
        });

        messageBus.addListener(this, PlayerEvent.stateChanged, event -> {
            if (isAdDisplayed) {
                log.d("State changed from " + event.oldState + " to " + event.newState);
                if (event.newState == PlayerState.BUFFERING) {
                    log.d("AD onBufferStart adPosition = " + getCurrentPosition());
                    messageBus.post(new AdEvent.AdBufferStart(getCurrentPosition()));
                }
                if (event.oldState == PlayerState.BUFFERING && event.newState == PlayerState.READY) {
                    log.d("AD onBufferEnd adPosition = " + getCurrentPosition() + " appIsInBackground = " + appIsInBackground);
                    messageBus.post(new AdEvent.AdBufferEnd(getCurrentPosition()));
                }
            }
        });

        messageBus.addListener(this, PlayerEvent.playing, event -> {
            log.d("Received:PlayerEvent:" + event.eventType().name());
            lastPlaybackPlayerState = PlayerEvent.Type.PLAYING;
        });

        messageBus.addListener(this, PlayerEvent.seeking, event -> {
            log.d("Received:PlayerEvent:" + event.eventType().name());
            lastPlaybackPlayerState = PlayerEvent.Type.SEEKING;
        });

        messageBus.addListener(this, PlayerEvent.metadataAvailable, event -> {
            log.d("Received:PlayerEvent:" + event.eventType().name());
            if (mPlayerCallbacks != null) {
                for (VideoStreamPlayer.VideoStreamPlayerCallback callback : mPlayerCallbacks) {
                    for (PKMetadata pkMetadata : event.metadataList){
                        if (pkMetadata instanceof PKTextInformationFrame) {
                            PKTextInformationFrame textFrame = (PKTextInformationFrame) pkMetadata;
                            if (TXXX.equals(textFrame.id)) {
                                log.d("Received user text: " + textFrame.value);
                                callback.onUserTextReceived(textFrame.value);
                            }
                        }
                    }
                }
            }
        });
    }
    @Override
    protected void onUpdateMedia(PKMediaConfig mediaConfig) {
        log.d("Start onUpdateMedia");
        if (mediaConfig != null && mediaConfig.getMediaEntry() != null &&
                mediaConfig.getMediaEntry().hasSources() &&
                (mediaConfig.getMediaEntry().getSources().get(0).getUrl().contains("dai.google.com") ||
                        mediaConfig.getMediaEntry().getSources().get(0).getUrl().contains("pubads.g.doubleclick.net"))) {
            return;
        }

        if (mediaConfig != null) {
            playbackStartPosition = mediaConfig.getStartPosition();
            log.d("mediaConfig start pos = " + playbackStartPosition);
        }

        playbackCurrentPosition = Consts.POSITION_UNSET;
        playbackDuration = Consts.TIME_UNSET;

        pkMediaSourceConfig = null;
        playkitAdCuePoints = null;
        pluginCuePoints = null;
        isAutoPlay = false;
        isContentPrepared = false;
        isAdRequested = false;
        isAdDisplayed = false;
        isAdIsPaused  = false;
        isAdError     = false;
        shouldPrepareOnResume = false;
        this.mediaConfig = mediaConfig;
        savePlayerView();
        clearAdsLoader();
        imaSetup();
        log.d("Event: " + AdEvent.Type.AD_REQUESTED);
        requestAdFromIMADAI();
    }

    private void requestAdFromIMADAI() {

        if (adConfig == null || adConfig.isEmpty()) {
            log.d("adConfig is null or empty DAI config. Calling prepare");
            isAdRequested = true;
            preparePlayer(true);
            return;
        }

        String adRequestInfo = adConfig.getAssetKey();

        if (adConfig.getAssetKey() == null) {
            adRequestInfo = adConfig.getContentSourceId() + "/" + adConfig.getVideoId();
        }

        adsLoader.requestStream(buildStreamRequest());
        messageBus.post(new AdEvent.AdRequestedEvent(adRequestInfo, isAutoPlay));
    }

    ////////Ads Plugin
    private void imaSetup() {
        log.d("imaSetup start");
        imaSettingSetup();
        if (sdkFactory == null) {
            sdkFactory = ImaSdkFactory.getInstance();
        } else {
            player.getView().addView(savedPlayerView);
            mAdUiContainer = player.getView();
        }
        if (adsLoader == null) {
            adsLoader = sdkFactory.createAdsLoader(context, imaSdkSettings, createStreamDisplayContainer());
            // Add listeners for when ads are loaded and for errors.
            adsLoader.addAdErrorListener(IMADAIPlugin.this);
            adsLoader.addAdsLoadedListener(getAdsLoadedListener());
        }
    }

    private void imaSettingSetup() {
        if (imaSdkSettings == null) {
            imaSdkSettings = ImaSdkFactory.getInstance().createImaSdkSettings();
        }
        if (adConfig.getMaxRedirects() > 0) {
            imaSdkSettings.setMaxRedirects(adConfig.getMaxRedirects());
        }

        imaSdkSettings.setLanguage(adConfig.getLanguage());
        imaSdkSettings.setDebugMode(adConfig.isDebugMode());
        imaSdkSettings.setPlayerType(adConfig.getPlayerType());
        imaSdkSettings.setPlayerVersion(adConfig.getPlayerVersion());
        if (adConfig.getSessionId() != null) {
            imaSdkSettings.setSessionId(adConfig.getSessionId());
        }
    }

    private AdsLoader.AdsLoadedListener getAdsLoadedListener() {

        adsLoadedListener = adsManagerLoadedEvent -> {
            log.d("onAdsManager loaded");
            if (streamManager != null) {
                destroyStreamManager();
            }
            streamManager = adsManagerLoadedEvent.getStreamManager();
            streamManager.addAdErrorListener(IMADAIPlugin.this);
            streamManager.addAdEventListener(IMADAIPlugin.this);
            isAdRequested = true;
            streamManager.init(getRenderingSettings());
        };
        return adsLoadedListener;
    }

    private void resetIMA(){
        log.d("resetIMA");
        destroyDisplayContainer();
        destroyStreamManager();
    }

    private void destroyDisplayContainer() {
        if (displayContainer != null) {
            log.d("destroyDisplayContainer");
            displayContainer.unregisterAllFriendlyObstructions();
            displayContainer = null;
        }
    }

    private void destroyStreamManager() {
        if (streamManager != null) {
            log.d("destroyStreamManager");
            streamManager.removeAdErrorListener(IMADAIPlugin.this);
            streamManager.removeAdEventListener(IMADAIPlugin.this);
            streamManager.destroy();
            streamManager = null;
        }
    }

    private void clearAdsLoader() {
        if (adsLoader != null) {
            adsLoader.removeAdErrorListener(this);
            adsLoader.removeAdsLoadedListener(adsLoadedListener);
            adsLoader.release();
            adsLoadedListener = null;
            adsLoader = null;
            destroyDisplayContainer();
        }
    }

    /**
     * Applicable on changeMedia: in adsLoader.release() IMA is removing the playerview that's why we are saving the PlayerView
     * before calling adsLoader.release()  and pass it to IMA by creating an AdDisplayContainer
     */
    private void savePlayerView() {
        if (mAdUiContainer != null && mAdUiContainer.getChildCount() > 0) {
            for (int childPosition = 0; childPosition <= mAdUiContainer.getChildCount(); childPosition++) {
                if (mAdUiContainer.getChildAt(childPosition) != null && mAdUiContainer.getChildAt(childPosition) instanceof BaseExoplayerView) {
                    savedPlayerView = mAdUiContainer.getChildAt(childPosition);
                }
            }
        }
    }

    private AdsRenderingSettings getRenderingSettings() {

        renderingSettings = ImaSdkFactory.getInstance().createAdsRenderingSettings();
        renderingSettings.setFocusSkipButtonWhenAvailable(adConfig.isEnableFocusSkipButton());
        if (playbackStartPosition != null && playbackStartPosition > 0) {
            renderingSettings.setPlayAdsAfterTime(playbackStartPosition);
        }

        //if both are false we remove the support int ad count down in ad
        if (!adConfig.getAdAttribution() && !adConfig.getAdCountDown()) {
            renderingSettings.setUiElements(Collections.emptySet());
        }

        return renderingSettings;
    }
    private StreamRequest buildStreamRequest() {
        StreamRequest request;
        // Live stream request.
        if (adConfig.getAssetKey() != null) {
            request = sdkFactory.createLiveStreamRequest(adConfig.getAssetKey(),
                    adConfig.getApiKey());
        } else { // VOD request.
            request = sdkFactory.createVodStreamRequest(adConfig.getContentSourceId(),
                    adConfig.getVideoId(), adConfig.getApiKey());
        }
        // Set the stream format (HLS or DASH).
        if (adConfig.getStreamFormat() != null) {
            request.setFormat(adConfig.getStreamFormat());
        }
        if (adConfig.getAdTagParams() != null) {
            request.setAdTagParameters(adConfig.getAdTagParams());
        }
        if (!TextUtils.isEmpty(adConfig.getStreamActivityMonitorId())) {
            request.setStreamActivityMonitorId(adConfig.getStreamActivityMonitorId());
        }
        if (!TextUtils.isEmpty(adConfig.getAuthToken())) {
            request.setAuthToken(adConfig.getAuthToken());
        }

        return request;
    }

    private StreamDisplayContainer createStreamDisplayContainer() {
        log.d("createStreamDisplayContainer");
        if (videoStreamPlayer == null) {
            videoStreamPlayer = createVideoStreamPlayer();
        }

        displayContainer = ImaSdkFactory.createStreamDisplayContainer(mAdUiContainer, videoStreamPlayer);
        registerFriendlyOverlays();

        return displayContainer;
    }

    private void registerFriendlyOverlays() {
        List<PKFriendlyObstruction> friendlyObstructions = adConfig.getFriendlyObstructions();
        if (friendlyObstructions != null && displayContainer != null) {
            for (FriendlyObstruction friendlyObstruction : friendlyObstructions) {
                displayContainer.registerFriendlyObstruction(friendlyObstruction);
            }
        }
    }

    private VideoStreamPlayer createVideoStreamPlayer() {
        return new VideoStreamPlayer() {
            @Override
            public void loadUrl(String url, List<HashMap<String, String>> subtitles) {
                log.d("loadUrl = " + url + " lastAdEventReceived = " + lastAdEventReceived);
                messageBus.post(new AdEvent.DAISourceSelected(url));
                pkMediaSourceConfig = toPKMediaSourceConfig(url);
                if (isAdError) {
                    log.e("ERROR when calling loadUrl = " + url + " lastAdEventReceived = " + lastAdEventReceived);
                    preparePlayer(true);
                    getPlayerEngineWrapper().play();
                    return;
                }

                if (!appIsInBackground) {
                    if (mediaConfig.getStartPosition() != null && mediaConfig.getStartPosition() > 0) {
                        long fakeStartPos = streamManager.getStreamTimeMsForContentTimeMs(mediaConfig.getStartPosition() * Consts.MILLISECONDS_MULTIPLIER);
                        if (adConfig.isAlwaysStartWithPreroll()) {
                            mSnapBackTime = fakeStartPos;
                            mediaConfig.setStartPosition(0L);
                        } else {
                            mediaConfig.setStartPosition(fakeStartPos / Consts.MILLISECONDS_MULTIPLIER);
                        }
                    }

                    if (getPlayerEngineWrapper() != null && pkMediaSourceConfig != null) {
                        getPlayerEngineWrapper().load(pkMediaSourceConfig);
                        if (getPlayerEngine() != null && isAutoPlay) {
                            getPlayerEngine().play();
                        }
                        isContentPrepared = true;
                    } else {
                        log.e("ERROR when calling loadUrl = " + url + " pkMediaSourceConfig = null");
                    }
                } else {
                    shouldPrepareOnResume = true;
                }
            }

            @Override
            public void pause() {
                if (getPlayerEngineWrapper() != null) {
                    getPlayerEngineWrapper().pause();
                }
            }

            @Override
            public void resume() {
                if (getPlayerEngineWrapper() != null) {
                    getPlayerEngineWrapper().play();
                }
            }

            @Override
            public int getVolume() {
                return 1;
            }

            @Override
            public void addCallback(VideoStreamPlayerCallback videoStreamPlayerCallback) {
                mPlayerCallbacks.add(videoStreamPlayerCallback);
            }

            @Override
            public void removeCallback(VideoStreamPlayerCallback videoStreamPlayerCallback) {
                mPlayerCallbacks.remove(videoStreamPlayerCallback);
            }

            @Override
            public void onAdBreakStarted() {
                log.d("VideoStreamPlayer onAdBreakStarted");
            }

            @Override
            public void onAdBreakEnded() {
                log.d("VideoStreamPlayer onAdBreakEnded");

                if (mSnapBackTime > 0) {
                    long snapBackTimeMili = mSnapBackTime;
                    log.d("onAdBreakEnded seekTo: " + snapBackTimeMili);
                    if (getPlayerEngine() != null) {
                        getPlayerEngine().seekTo(snapBackTimeMili);
                        if (snapBackTimeMili != getPlayerEngine().getDuration()) {
                            getPlayerEngine().play();
                        }
                    }
                }
                mSnapBackTime = 0;
            }

            @Override
            public void onAdPeriodStarted() {
                log.d("VideoStreamPlayer onAdPeriodStarted");
            }

            @Override
            public void onAdPeriodEnded() {
                log.d("VideoStreamPlayer onAdPeriodEnded");
            }

            @Override
            public void seek(long seekPosition) {
                log.d("VideoStreamPlayer onSeekTo " + seekPosition);
            }

            @Override
            public VideoProgressUpdate getContentProgress() {
                if (getPlayerEngine() == null || streamManager == null) {
                    return VideoProgressUpdate.VIDEO_TIME_NOT_READY;
                }

                long position = streamManager.getStreamTimeMsForContentTimeMs(getPlayerEngine().getCurrentPosition());
                boolean isLiveDAI = adConfig.isLiveDAI();
                if (isLiveDAI) {
                    long pos = getPlayerEngine().getCurrentPosition();
                    pos -= getPlayerEngine().getPositionInWindowMs();
                    position = Math.round(streamManager.getStreamTimeMsForContentTimeMs(pos));
                }
                long duration = Math.round(streamManager.getStreamTimeMsForContentTimeMs(getPlayerEngine().getDuration()));

                // When app goes in background and it is a VOD DAI asset; return VideoProgressUpdate with the saved position and duration
                if (!isLiveDAI && appIsInBackground && isAdDisplayed && isAdIsPaused && !isAdError &&
                        playbackCurrentPosition != Consts.POSITION_UNSET && playbackDuration != Consts.TIME_UNSET &&
                        (position < 0 || duration < 0)) {
                    return new VideoProgressUpdate(playbackCurrentPosition, playbackDuration);
                }

                if (isLiveDAI && (position < playbackCurrentPosition || position < 0 || duration < 0)) {
                    return new VideoProgressUpdate(playbackCurrentPosition, playbackDuration);
                }

                if (isLiveDAI) {
                    playbackCurrentPosition = position;
                    playbackDuration = duration;
                    return new VideoProgressUpdate(position, duration);
                } else {
                    playbackCurrentPosition = getPlayerEngine().getCurrentPosition();
                    playbackDuration = getPlayerEngine().getDuration();
                }

                return new VideoProgressUpdate(getPlayerEngine().getCurrentPosition(), getPlayerEngine().getDuration());
            }
        };
    }

    @Override
    protected void onUpdateConfig(Object config) {
        log.d("Start onUpdateConfig");
        adConfig = parseConfig(config);
        if (adConfig == null || adConfig.isEmpty()) {
            log.e("Error adConfig Incorrect or null");
            isAdError = true;
            if (adConfig == null) {
                adConfig = new IMADAIConfig();
            }
        }
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
        if (shouldPrepareOnResume) {
            if (pkMediaSourceConfig != null) {
                getPlayerEngineWrapper().load(pkMediaSourceConfig);
                if (getPlayerEngine() != null && isAutoPlay) {
                    getPlayerEngine().play();
                }
                isContentPrepared = true;
            } else {
                player.prepare(mediaConfig);
                if (isAutoPlay) {
                    player.play();
                }
            }
            shouldPrepareOnResume = false;
            return;
        }
        if (getPlayerEngineWrapper() != null && (isAdShouldAutoPlayOnResume() && (adConfig != null && adConfig.isLiveDAI()) || isAdDisplayed)) {
            getPlayerEngineWrapper().play();
        }
    }

    @Override
    protected void onDestroy() {
        isAdRequested = false;
        savedPlayerView = null;
        clearAdsLoader();
        resetIMA();
    }

    @Override
    public void onAdEvent(com.google.ads.interactivemedia.v3.api.AdEvent adEvent) {
        if (streamManager == null) {
            log.w("WARNING, streamManager == null");
            return;
        }

        if(adEventsMap == null){
            log.e("ERROR, adEventsMap == null");
            return;
        }

        lastAdEventReceived = adEventsMap.get(adEvent.getType());
        if (adEvent.getType() != com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.AD_PROGRESS) {
            log.d("Event: " + adEvent.getType());
        }
        switch (adEvent.getType()) {
            case AD_PERIOD_STARTED:
                log.d("AD AD_PERIOD_STARTED");
                onAdBreakStarted();
                break;
            case AD_BREAK_STARTED: //Fired when an ad break starts.
                if (adConfig != null && adConfig.isLiveDAI()) {
                    return;
                }
                log.d("AD AD_BREAK_STARTED");
                onAdBreakStarted();
                break;
            case AD_PERIOD_ENDED: //Fired when an ad break ends.
                log.d("AD AD_PERIOD_ENDED");
                onAdBreakEnded();
                break;
            case AD_BREAK_ENDED: //Fired when an ad break ends.
                if (adConfig != null && adConfig.isLiveDAI()) {
                    return;
                }
                playbackCurrentPosition = Consts.POSITION_UNSET;
                playbackDuration = Consts.TIME_UNSET;
                log.d("AD AD_BREAK_ENDED");
                onAdBreakEnded();
                break;
            case CUEPOINTS_CHANGED: //Dispatched for on-demand streams when the cuepoints change.
                pluginCuePoints = streamManager.getCuePoints();
                if (pluginCuePoints != null && getPlayerEngine() != null && getPlayerEngine().getDuration() > 0) {
                    for (CuePoint cue : pluginCuePoints) {
                        log.d(String.format("Cue: %s\n", cue.getStartTimeMs() + " " + cue.getEndTimeMs() + " " + cue.isPlayed()));
                    }
                    log.d("AD CUEPOINTS_CHANGED");
                    sendCuePointsUpdate();
                }
                break;
            case AD_PROGRESS: //Fired when there is an update to an ad's progress.
                //messageBus.post(new AdEvent(AdEvent.Type.AD_PROGRESS));
                break;
            case CLICKED: //Dispatched when the click element is clicked or tapped while an ad is being played.
                log.d("AD CLICKED");
                sendAdClickedEvent(adEvent);
                break;
            case STARTED: //Fired when an ad starts.
                log.d("AD STARTED");
                if (streamManager != null && renderingSettings != null) {
                    renderingSettings.setFocusSkipButtonWhenAvailable(adConfig.isEnableFocusSkipButton());
                    streamManager.focus();
                }
                adInfo = createAdInfo(adEvent.getAd());
                messageBus.post(new AdEvent.AdLoadedEvent(adInfo));
                messageBus.post(new AdEvent.AdStartedEvent(adInfo));
                break;
            case FIRST_QUARTILE: //Fired when an ad reaches its first quartile.
                log.d("AD FIRST_QUARTILE");
                messageBus.post(new AdEvent(AdEvent.Type.FIRST_QUARTILE));
                break;
            case MIDPOINT: //Fired when an ad reaches its midpoint.
                log.d("AD MIDPOINT");
                messageBus.post(new AdEvent(AdEvent.Type.MIDPOINT));
                break;
            case THIRD_QUARTILE: //Fired when an ad reaches its third quartile.
                log.d("AD THIRD_QUARTILE");
                messageBus.post(new AdEvent(AdEvent.Type.THIRD_QUARTILE));
                break;
            case PAUSED:
                log.d("AD PAUSED");
                isAdIsPaused = true;
                isAdDisplayed = true;
                if (adInfo != null) {
                    adInfo.setAdPlayHead(getCurrentPosition());
                    messageBus.post(new AdEvent.AdPausedEvent(adInfo));
                }
                break;
            case RESUMED:
                log.d("AD RESUMED");
                isAdIsPaused = false;
                if (adInfo != null) {
                    adInfo.setAdPlayHead(getCurrentPosition());
                    messageBus.post(new AdEvent.AdResumedEvent(adInfo));
                }
                break;
            case COMPLETED: //Fired when an ad is complete.
                log.d("AD COMPLETED");
                messageBus.post(new AdEvent(AdEvent.Type.COMPLETED));
                break;
            case TAPPED:
                log.d("AD TAPPED");
                messageBus.post(new AdEvent(AdEvent.Type.TAPPED));
                break;
            case ICON_FALLBACK_IMAGE_CLOSED:
                log.d("ICON_FALLBACK_IMAGE_CLOSED");
                messageBus.post(new AdEvent(AdEvent.Type.ICON_FALLBACK_IMAGE_CLOSED));
                break;
            case ICON_TAPPED:
                log.d("AD ICON_TAPPED");
                messageBus.post(new AdEvent(AdEvent.Type.ICON_TAPPED));
                break;
            case AD_BREAK_FETCH_ERROR:
                log.d("AD AD_BREAK_FETCH_ERROR");
                messageBus.post(new AdEvent(AdEvent.Type.AD_BREAK_FETCH_ERROR));
                break;
            case LOG:
                log.e("AD LOG ERROR");
                String error = "Non-fatal Error";
                if (adEvent.getAdData() != null) {
                    if (adEvent.getAdData().containsKey("errorMessage")) {
                        error = adEvent.getAdData().get("errorMessage");
                    }
                }
                sendError(PKErrorCategory.LOAD, PKAdErrorType.QUIET_LOG_ERROR, error, null);
                break;
            default:
                break;
        }
    }

    private void sendAdClickedEvent(com.google.ads.interactivemedia.v3.api.AdEvent adEvent) {
        String clickThruUrl;
        Ad ad = adEvent.getAd();

        if (ad != null) {
            try {
                Method clickThroughMethod = ad.getClass().getMethod("getClickThruUrl");
                clickThruUrl = (String) clickThroughMethod.invoke(ad);
                messageBus.post(new AdEvent.AdClickedEvent(clickThruUrl));
                return;
            } catch (NoSuchMethodException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e) {
                e.printStackTrace();
            }
        }
        messageBus.post(new AdEvent.AdClickedEvent(null));
    }

    private void onAdBreakEnded() {
        messageBus.post(new AdEvent(AdEvent.Type.AD_BREAK_ENDED));
        messageBus.post(new AdEvent(AdEvent.Type.CONTENT_RESUME_REQUESTED));
        boolean allAdsPlayed = true;
        boolean lastAdPlayed = true;
        List<CuePoint> cuesList = streamManager.getCuePoints();
        if (cuesList != null) {
            for (ListIterator<CuePoint> iter = cuesList.listIterator(); iter.hasNext(); ) {
                CuePoint cue = iter.next();
                if (cue != null) {
                    if (!cue.isPlayed()) {
                        allAdsPlayed = false;
                    }
                    if (!iter.hasNext()) {
                        lastAdPlayed = cue.isPlayed();
                    }
                }
            }
            if (adConfig != null && !adConfig.isLiveDAI()) {
                if (allAdsPlayed || lastAdPlayed && getPlayerEngine() != null && getPlayerEngine().getCurrentPosition() >= getPlayerEngine().getDuration()) {
                    messageBus.post(new AdEvent(AdEvent.Type.ALL_ADS_COMPLETED));
                }
            }
        }

        isAdDisplayed = false;
    }

    private void onAdBreakStarted() {
        isAdDisplayed = true;
        messageBus.post(new AdEvent(AdEvent.Type.AD_BREAK_STARTED));
        messageBus.post(new AdEvent(AdEvent.Type.CONTENT_PAUSE_REQUESTED));
    }

//    private boolean checkIfDiscardAdRequired() {
//        if (!adConfig.isAlwaysStartWithPreroll() || adInfo == null || pluginCuePoints == null || mediaConfig == null || playbackStartPosition == null) {
//            return false;
//        }
//        log.d("getAdPositionType = " + adInfo.getAdPositionType().name());
//        log.d("playbackStartPosition = " +  playbackStartPosition);
//        return adInfo.getAdPositionType() == AdPositionType.PRE_ROLL && playbackStartPosition > 0;
//    }

    @Override
    public void onAdError(AdErrorEvent adErrorEvent) {
        log.e("Event: onAdError" + adErrorEvent.getError().getErrorCode());
        isAdError = true;
        isAdDisplayed = false;
        isAdRequested = true;
        playbackCurrentPosition = Consts.POSITION_UNSET;
        playbackDuration = Consts.TIME_UNSET;
        //resetFlagsOnError();

        AdError adException = adErrorEvent.getError();
        String errorMessage = (adException == null) ? "Unknown Error" : adException.getMessage();
        Enum errorType = PKAdErrorType.UNKNOWN_ERROR;
        Enum errorCategory = PKErrorCategory.UNKNOWN;

        if (adException != null) {

            AdError.AdErrorType adErrorCategory = adException.getErrorType();

            switch (adErrorCategory) {
                case LOAD:
                    errorCategory = PKErrorCategory.LOAD;
                    break;
                case PLAY:
                    errorCategory = PKErrorCategory.PLAY;
                    break;
                default:
                    errorCategory = PKErrorCategory.UNKNOWN;
                    break;
            }

            switch (adException.getErrorCode()) {
                case INTERNAL_ERROR:
                    errorType = PKAdErrorType.INTERNAL_ERROR;
                    break;
                case VIDEO_PLAY_ERROR:
                    errorType = PKAdErrorType.VIDEO_PLAY_ERROR;
                    break;
                case COMPANION_AD_LOADING_FAILED:
                    errorType = PKAdErrorType.COMPANION_AD_LOADING_FAILED;
                    break;
                case UNKNOWN_ERROR:
                    errorType = PKAdErrorType.UNKNOWN_ERROR;
                    break;
                case FAILED_TO_REQUEST_ADS:
                    errorType = PKAdErrorType.FAILED_TO_REQUEST_ADS;
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

        sendError(errorCategory, errorType, errorMessage, adException);
        preparePlayer(true);
    }

    private void sendError(Enum errorCategory, Enum errorType, String message, Throwable exception) {
        log.e("Ad Error: " + errorType.name() + " with message " + message);

        PKError.Severity adErrorSeverity = PKError.Severity.Fatal;
        if (PKAdErrorType.QUIET_LOG_ERROR.equals(errorType)) {
            adErrorSeverity = PKError.Severity.Recoverable;
        }
        com.kaltura.playkit.plugins.ads.AdEvent errorEvent = new com.kaltura.playkit.plugins.ads.AdEvent.Error(new PKError(errorCategory, errorType, adErrorSeverity, message, exception));
        messageBus.post(errorEvent);
    }

    private void sendCuePointsUpdate() {
        log.d("sendCuePointsUpdate");
        List<Long> cuePointsList = buildCuePointsList();
        if (!cuePointsList.isEmpty()) {
            AdCuePoints adCuePointsForEvent = new AdCuePoints(cuePointsList);
            adCuePointsForEvent.setAdPluginName(IMADAIPlugin.factory.getName());
            messageBus.post(new AdEvent.AdCuePointsUpdateEvent(adCuePointsForEvent));
        }
    }

    private List<Long> buildCuePointsList() {
        List<Long> cuePointsList = new ArrayList<>();
        StringBuilder cuePointBuilder = new StringBuilder();
        if (streamManager == null) {
            return cuePointsList;
        }

        pluginCuePoints = streamManager.getCuePoints();
        if (pluginCuePoints != null) {
            int cuePointIndex = 1;
            for (CuePoint cuePoint : pluginCuePoints) {
                long cuePointVal = streamManager.getContentTimeMsForStreamTimeMs(cuePoint.getStartTimeMs());
                if (getPlayerEngine() != null && cuePointIndex == pluginCuePoints.size() && cuePoint.getEndTimeMs() == getPlayerEngine().getDuration()) {
                    cuePointBuilder.append(-1).append("|");
                    cuePointsList.add((-1L));
                } else {
                    cuePointBuilder.append(cuePointVal).append("|");
                    cuePointsList.add(cuePointVal);
                }
                cuePointIndex++;
            }
            log.d("buildCuePointsList pluginCuePoints = " + cuePointBuilder.toString());
        }
        return cuePointsList;
    }

    private AdInfo createAdInfo(Ad ad) {

        String adDescription = ad.getDescription() != null ? ad.getDescription() : "";
        long adDuration = (long) (ad.getDuration() * Consts.MILLISECONDS_MULTIPLIER);
        long adPlayHead = getCurrentPosition();
        String adTitle = ad.getTitle();
        boolean isAdSkippable = ad.isSkippable();
        long skipTimeOffset = (long) ad.getSkipTimeOffset() * Consts.MILLISECONDS_MULTIPLIER;
        String contentType = ad.getContentType() != null ? ad.getContentType() : "";
        String adId = ad.getAdId();
        String adSystem = ad.getAdSystem();
        String creativeId = ad.getCreativeId();
        String creativeAdId = ad.getCreativeAdId();
        String advertiserName = ad.getAdvertiserName();
        String dealId = ad.getDealId();
        String surveyUrl = ad.getSurveyUrl() != null ? ad.getSurveyUrl() : "";
        String traffickingParams = ad.getTraffickingParameters();
        List<String> adWrapperCreativeIds = ad.getAdWrapperCreativeIds() != null ? Arrays.asList(ad.getAdWrapperCreativeIds()) : Collections.emptyList();
        List<String> adWrapperIds = ad.getAdWrapperIds() != null ? Arrays.asList(ad.getAdWrapperIds()) : Collections.emptyList();
        List<String> adWrapperSystems = ad.getAdWrapperSystems() != null ? Arrays.asList(ad.getAdWrapperSystems()) : Collections.emptyList();
        int adHeight = ad.isLinear() ? ad.getVastMediaHeight() : ad.getHeight();
        int adWidth  = ad.isLinear() ? ad.getVastMediaWidth() : ad.getWidth();
        int mediaBitrate = ad.getVastMediaBitrate() != 0 ? ad.getVastMediaBitrate() * KB_MULTIPLIER : -1;
        int totalAdsInPod = ad.getAdPodInfo().getTotalAds();
        int adIndexInPod = ad.getAdPodInfo().getAdPosition();   // index starts in 1
        int podCount = (streamManager != null && streamManager.getCuePoints() != null) ? streamManager.getCuePoints().size() : 0;
        int podIndex = (ad.getAdPodInfo().getPodIndex() >= 0) ? ad.getAdPodInfo().getPodIndex() + 1 : podCount; // index starts in 0
        boolean isBumper = ad.getAdPodInfo().isBumper();
        long adPodTimeOffset = (long) (ad.getAdPodInfo().getTimeOffset() * Consts.MILLISECONDS_MULTIPLIER);
        String streamId = streamManager != null ? streamManager.getStreamId() : "";

        AdInfo adInfo = new AdInfo(adDescription, adDuration, adPlayHead,
                adTitle, isAdSkippable, skipTimeOffset,
                contentType, adId, adSystem,
                creativeId, creativeAdId, advertiserName,
                dealId, surveyUrl, traffickingParams,
                adWrapperCreativeIds, adWrapperIds, adWrapperSystems,
                adHeight,
                adWidth,
                mediaBitrate,
                totalAdsInPod,
                adIndexInPod,
                podIndex,
                podCount,
                isBumper,
                isAdInfoPostRoll(adPodTimeOffset) ? -1 : adPodTimeOffset);
        adInfo.setStreamId(streamId);

        log.v("AdInfo: " + adInfo.toString());
        return adInfo;

    }

    private boolean isAdInfoPostRoll(long adPodTimeOffset) {
        if(pluginCuePoints == null || pluginCuePoints.isEmpty()) {
            return false;
        }
        if (getPlayerEngine() == null) {
            return false;
        }
        if (adPodTimeOffset < pluginCuePoints.get(pluginCuePoints.size()-1).getStartTimeMs()) {
            return false;
        }
        return pluginCuePoints.get(pluginCuePoints.size()-1).getEndTimeMs() == getPlayerEngine().getDuration();
    }

    @Override
    public void start() {
        isAutoPlay = true; // start will be called only on first time media is played programmatically
        isAdRequested = true;
        if (getPlayerEngine() != null) {
            if (!getPlayerEngine().isPlaying()) {
                getPlayerEngine().play();
            }
        }
    }

    @Override
    public void destroyAdsManager() {
        isAdRequested = false;
        if (streamManager == null) {
            return;
        }
        log.d("IMADAI Start destroyAdsManager");
        destroyStreamManager();
        contentCompleted();
        playbackCurrentPosition = Consts.POSITION_UNSET;
        playbackDuration = Consts.TIME_UNSET;
        isAdDisplayed = false;
        isAdError = false;
        isContentPrepared = false;
        pluginCuePoints = null;
        adInfo = null;
    }

    @Override
    public void resume() {
        isAdIsPaused = false;
        if (getPlayerEngine() != null) {
            getPlayerEngine().play();
        }
        if (mPlayerCallbacks != null) {
            for (VideoStreamPlayer.VideoStreamPlayerCallback callback : mPlayerCallbacks) {
                callback.onResume();
            }
        }
    }

    @Override
    public void pause() {
        isAdIsPaused = true;
        if (getPlayerEngine() != null) {
            getPlayerEngine().pause();
        }
        if (mPlayerCallbacks != null) {
            for (VideoStreamPlayer.VideoStreamPlayerCallback callback : mPlayerCallbacks) {
                callback.onPause();
            }
        }
    }

    @Override
    public void contentCompleted() {
        if (mPlayerCallbacks != null) {
            for (VideoStreamPlayer.VideoStreamPlayerCallback callback : mPlayerCallbacks) {
                callback.onContentComplete();
            }
        }
    }

    @Override
    public PKAdInfo getAdInfo() {
        return adInfo;
    }

    @Override
    public Long getPlaybackStartPosition() {
        return playbackStartPosition;
    }

    @Override
    public boolean isAlwaysStartWithPreroll() {
        return (adConfig != null) && adConfig.isAlwaysStartWithPreroll();
    }

    @Override
    public AdCuePoints getCuePoints() {
        //in change media it might take some time to populate cuepoints so if playkitAdCuePoints.getAdCuePoints().isEmpty() we may try again to create the cuepoints
        if (playkitAdCuePoints != null && playkitAdCuePoints.getAdCuePoints() != null && (!playkitAdCuePoints.getAdCuePoints().isEmpty() || isAdError || (adConfig != null && (adConfig.isLiveDAI() || adConfig.isEmpty())))) {
            return playkitAdCuePoints;
        }

        log.d("create new AdCuePoints");
        playkitAdCuePoints = new AdCuePoints(buildCuePointsList());
        playkitAdCuePoints.setAdPluginName(IMADAIPlugin.factory.getName());
        return playkitAdCuePoints;
    }

    @Override
    public boolean isAdDisplayed() {
        //log.d("isAdDisplayed = " + isAdDisplayed);
        return isAdDisplayed;
    }

    @Override
    public boolean isAdPaused() {
        return isAdIsPaused || (getPlayerEngine() != null && !getPlayerEngine().isPlaying());
    }

    @Override
    public boolean isAdRequested() {
        return isAdRequested;
    }

    @Override
    public boolean isAllAdsCompleted() {
        if (pluginCuePoints != null && !pluginCuePoints.isEmpty()) {
            CuePoint cuePoint = pluginCuePoints.get(pluginCuePoints.size() -1);
            return cuePoint.isPlayed() && !isAdDisplayed;
        }
        return false;
    }

    @Override
    public boolean isAdError() {
        return isAdError;
    }

    @Override
    public long getDuration() {
        if (isAdDisplayed) {
            if (streamManager != null && streamManager.getAdProgressInfo() != null) {
                //log.d("AD Duration = " + streamManager.getAdProgressInfo().getDuration());
                long adDuration = Math.max(0, (long) Math.floor(streamManager.getAdProgressInfo().getDuration()));
                if (getPlayerEngine() != null) {
                    if (!streamManager.getCuePoints().isEmpty() && adDuration > getPlayerEngine().getDuration()) {
                        adDuration = getPlayerEngine().getDuration();
                    }
                }
                return adDuration;
            } else {
                return 0;
            }
        }
        return 0;

    }

    @Override
    public long getFakePlayerDuration(long currentPlayerDuration) {
        if (currentPlayerDuration < 0 || currentPlayerDuration == C.TIME_UNSET) {
            return 0;
        }

        if (streamManager != null) {
            long contentTimeForStreamTimeDuration = streamManager.getContentTimeMsForStreamTimeMs(currentPlayerDuration);
            //log.d("Duration = " + contentTimeForStreamTimeDuration);
            return contentTimeForStreamTimeDuration;
        }

        if (pluginCuePoints != null) {
            for (CuePoint cuePoint : pluginCuePoints) {
                currentPlayerDuration -= cuePoint.getEndTimeMs() - cuePoint.getStartTimeMs();
            }
        }
        if (currentPlayerDuration < 0) {
            return 0;
        }
        return  currentPlayerDuration;
    }

    @Override
    public long getCurrentPosition() {
        // works for case that ad is diaplayed!
        if (isAdDisplayed) {
            long adPosition;
            if (streamManager != null && streamManager.getAdProgressInfo() != null) {
                adPosition = Math.round(streamManager.getAdProgressInfo().getCurrentTime());
                if (!streamManager.getCuePoints().isEmpty() && getPlayerEngine() != null && adPosition > getPlayerEngine().getCurrentPosition()) {
                    adPosition = 0; // vod error case
                }
                //log.d("getCurrentPosition = " + adPosition);
                messageBus.post(new AdEvent.AdPlayHeadEvent(adPosition));
                return adPosition;
            } else {
                return 0;
            }
        }
        return 0;
    }

    @Override
    public void setAdProviderListener(PKAdProviderListener adProviderListener) {
        pkAdProviderListener = adProviderListener;
    }

    @Override
    public void setAdRequested(boolean isAdRequested) {
        this.isAdRequested = isAdRequested;
    }

    @Override
    public void resetPluginFlags() {
        isAdError = false;
        isAdDisplayed = false;
        pluginCuePoints = null;
    }

    @Override
    public long getFakePlayerPosition(long realPlayerPosition) {

        if (realPlayerPosition < 0 || realPlayerPosition == C.POSITION_UNSET) {
            return 0;
        }
        if (streamManager != null) {
            long contentTimeForStreamTime = streamManager.getContentTimeMsForStreamTimeMs(realPlayerPosition);
            // log.d("Position = " + realPlayerPosition);
            int totalAdsDuration = 0;
            if (pluginCuePoints != null) {
                for (CuePoint cuePoint : pluginCuePoints) {
                    totalAdsDuration += cuePoint.getEndTimeMs() - cuePoint.getStartTimeMs();
                    if (!isAdDisplayed && cuePoint.isPlayed() && cuePoint.getStartTimeMs() == realPlayerPosition) {
                        log.d("AD PLAYED - SKIP");
                        long newPositionToSeek = cuePoint.getEndTimeMs();
                        seekTo(newPositionToSeek - totalAdsDuration);
                        return cuePoint.getStartTimeMs() - totalAdsDuration;
                    }
                }
            }
            //log.d("contentTimeForStreamTime = " + contentTimeForStreamTime);
            return contentTimeForStreamTime;
        }

        long playerPositionSec = realPlayerPosition;
        if (pluginCuePoints != null) {
            for (CuePoint cuePoint : pluginCuePoints) {
                if (cuePoint.isPlayed() && cuePoint.getEndTimeMs() <= playerPositionSec) {
                    realPlayerPosition -= cuePoint.getEndTimeMs() - cuePoint.getStartTimeMs();
                }
            }
        }
        if (getPlayerEngine() != null) {
            long playerEngineDuration = getPlayerEngine().getDuration();
            if (realPlayerPosition > getPlayerEngine().getDuration()) {
                realPlayerPosition = playerEngineDuration;
            }
        }
        return (realPlayerPosition < 0) ? 0 : realPlayerPosition;
    }

    @Override
    public void removeAdProviderListener() {
        pkAdProviderListener = null;
    }

    @Override
    public void skipAd() {
        log.w("skipAd is not supported");
    }

    @Override
    public void seekTo(long position) {
        log.d("seekTo " + position);
        if (getPlayerEngine() == null) {
            return;
        }
        long newPositionToSeek = -1;
        if (streamManager != null) {
            if (pluginCuePoints != null) {
                CuePoint candidateCuePoint = streamManager.getPreviousCuePointForStreamTimeMs(streamManager.getStreamTimeMsForContentTimeMs(position));
                if (candidateCuePoint != null && !candidateCuePoint.isPlayed() && (position == 0 || getPlayerEngineWrapper() != null && position >= getPlayerEngineWrapper().getCurrentPosition())) {
                    newPositionToSeek = candidateCuePoint.getStartTimeMs();
                    getPlayerEngine().seekTo(newPositionToSeek);
                    mSnapBackTime = streamManager.getStreamTimeMsForContentTimeMs(position);
                    return;
                } else {
                    newPositionToSeek = position;
                    if (isPositionInBetweenCuePoint(position)) {
                        newPositionToSeek = getCuePointEndTime(position);
                    }
                    newPositionToSeek = streamManager.getStreamTimeMsForContentTimeMs(newPositionToSeek);
                    isAdDisplayed = false;
                    getPlayerEngine().seekTo(newPositionToSeek);
                    mSnapBackTime = 0;
                    return;
                }
            }
            newPositionToSeek = position;
            getPlayerEngine().seekTo(newPositionToSeek);
            mSnapBackTime = 0;
            return;
        }
        log.d("streamManager = null seekTo " + position);
        getPlayerEngine().seekTo(position);
    }

    @Override
    public PKAdPluginType getAdPluginType() {
        return PKAdPluginType.server;
    }

    @Override
    public boolean isContentPrepared() {
        return isContentPrepared;
    }

    private boolean isPositionInBetweenCuePoint(long position) {

        if (pluginCuePoints != null) {
            for (CuePoint cuePoint : pluginCuePoints) {
                if (cuePoint.getStartTimeMs() >= position && cuePoint.getEndTimeMs() <= position) {
                    return true;
                }
            }
        }
        return false;
    }

    private long getCuePointEndTime(long position) {
        if (pluginCuePoints != null) {
            for (CuePoint cuePoint : pluginCuePoints) {
                if (cuePoint.getStartTimeMs() >= position && position <= cuePoint.getEndTimeMs()) {
                    return cuePoint.getEndTimeMs();
                }
            }
        }
        return -1;
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
        log.d("IMADAI prepare");
        if (pkAdProviderListener != null && !appIsInBackground) {
            log.d("IMADAI prepare player");
            isContentPrepared = true;
            pkAdProviderListener.onAdLoadingFinished();
            if (doPlay) {
                messageBus.addListener(this, PlayerEvent.durationChanged, new PKEvent.Listener<PlayerEvent.DurationChanged>() {
                    @Override
                    public void onEvent(PlayerEvent.DurationChanged event) {
                        log.d("IMA DURATION_CHANGE received calling play");
                        if (player != null && player.getView() != null && !IMADAIPlugin.this.isAdDisplayed()) {
                            if (IMADAIPlugin.this.getPlayerEngine() != null) {
                                IMADAIPlugin.this.getPlayerEngine().play();
                            }
                        }
                        messageBus.removeListener(this);
                    }
                });
            }
        }
    }

    private PKMediaEntry.MediaEntryType getDAIMediaType() {

        if (adConfig.getVideoId() == null) {
            if (mediaConfig.getMediaEntry() != null && mediaConfig.getMediaEntry().getMediaType() == PKMediaEntry.MediaEntryType.DvrLive) {
                return PKMediaEntry.MediaEntryType.DvrLive;
            } else {
                return PKMediaEntry.MediaEntryType.Live;
            }
        } else {
            return PKMediaEntry.MediaEntryType.Vod;
        }
    }

    private PKMediaSource createDAIMediaSource(String url, String mediaSourceId) {
        PKMediaSource mediaSource = new PKMediaSource();
        String id = (adConfig.getContentSourceId() != null) ? adConfig.getContentSourceId() : adConfig.getAssetKey();
        if (id == null) {
            id = (mediaSourceId != null) ? mediaSourceId : "";
        }
        mediaSource.setId(id);
        mediaSource.setUrl(url);
        mediaSource.setMediaFormat(adConfig.getStreamFormat() == StreamRequest.StreamFormat.DASH ? PKMediaFormat.dash : PKMediaFormat.hls);
        if (adConfig.getLicenseUrl() != null) {
            List<PKDrmParams> drmData = new ArrayList<>();
            PKDrmParams pkDrmParams = new PKDrmParams(adConfig.getLicenseUrl(), PKDrmParams.Scheme.WidevineCENC);
            drmData.add(pkDrmParams);
            mediaSource.setDrmData(drmData);
        }
        return mediaSource;
    }

    private Map<com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType, com.kaltura.playkit.plugins.ads.AdEvent.Type> buildAdsEventMap() {
        adEventsMap = new HashMap<>();
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.CLICKED, com.kaltura.playkit.plugins.ads.AdEvent.Type.CLICKED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.COMPLETED, com.kaltura.playkit.plugins.ads.AdEvent.Type.COMPLETED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.CUEPOINTS_CHANGED, com.kaltura.playkit.plugins.ads.AdEvent.Type.CUEPOINTS_CHANGED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.FIRST_QUARTILE, com.kaltura.playkit.plugins.ads.AdEvent.Type.FIRST_QUARTILE);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.MIDPOINT, com.kaltura.playkit.plugins.ads.AdEvent.Type.MIDPOINT);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.THIRD_QUARTILE, com.kaltura.playkit.plugins.ads.AdEvent.Type.THIRD_QUARTILE);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.PAUSED, com.kaltura.playkit.plugins.ads.AdEvent.Type.PAUSED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.RESUMED, com.kaltura.playkit.plugins.ads.AdEvent.Type.RESUMED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.STARTED, com.kaltura.playkit.plugins.ads.AdEvent.Type.STARTED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.SKIPPED, com.kaltura.playkit.plugins.ads.AdEvent.Type.SKIPPED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.AD_PROGRESS, com.kaltura.playkit.plugins.ads.AdEvent.Type.AD_PROGRESS);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.AD_BREAK_STARTED, com.kaltura.playkit.plugins.ads.AdEvent.Type.AD_BREAK_STARTED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.AD_BREAK_ENDED, com.kaltura.playkit.plugins.ads.AdEvent.Type.AD_BREAK_ENDED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.AD_BREAK_FETCH_ERROR, com.kaltura.playkit.plugins.ads.AdEvent.Type.AD_BREAK_FETCH_ERROR);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.AD_BREAK_READY, com.kaltura.playkit.plugins.ads.AdEvent.Type.AD_BREAK_READY);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.TAPPED, com.kaltura.playkit.plugins.ads.AdEvent.Type.TAPPED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.ICON_FALLBACK_IMAGE_CLOSED, AdEvent.Type.ICON_FALLBACK_IMAGE_CLOSED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.ICON_TAPPED, com.kaltura.playkit.plugins.ads.AdEvent.Type.ICON_TAPPED);
        return adEventsMap;
    }

    @Override
    protected PlayerEngineWrapper getPlayerEngineWrapper() {
        if (adsPlayerEngineWrapper == null) {
            adsPlayerEngineWrapper = new AdsDAIPlayerEngineWrapper(context, this);
        }
        return adsPlayerEngineWrapper;
    }

    private PlayerEngine getPlayerEngine() {
        if (adsPlayerEngineWrapper == null) {
            return  null;
        }
        return adsPlayerEngineWrapper.getPlayerEngine();
    }

    private PKMediaSourceConfig toPKMediaSourceConfig(String daiUrl) {
        PKMediaSourceConfig sourceConfig = null;
        if (mediaConfig != null && mediaConfig.getMediaEntry() != null) {
            PKMediaSource daiMediaSource = createDAIMediaSource(daiUrl, mediaConfig.getMediaEntry().getId());
            if (!TextUtils.isEmpty(daiMediaSource.getUrl())) {
                sourceConfig = new PKMediaSourceConfig(daiMediaSource, getDAIMediaType(), mediaConfig.getMediaEntry().getExternalSubtitleList(), mediaConfig.getMediaEntry().getExternalVttThumbnailUrl(), (PlayerSettings) player.getSettings());
            }
        }
        return sourceConfig;
    }
}