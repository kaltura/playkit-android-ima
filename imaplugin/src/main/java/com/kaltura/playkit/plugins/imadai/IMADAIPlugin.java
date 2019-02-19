package com.kaltura.playkit.plugins.imadai;

import android.content.Context;
import android.util.Pair;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import com.google.ads.interactivemedia.v3.api.Ad;
import com.google.ads.interactivemedia.v3.api.AdError;
import com.google.ads.interactivemedia.v3.api.AdErrorEvent;
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
import com.google.android.exoplayer2.C;
import com.kaltura.playkit.MessageBus;
import com.kaltura.playkit.PKDrmParams;
import com.kaltura.playkit.PKError;
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
import com.kaltura.playkit.ads.PKAdPlugin;
import com.kaltura.playkit.player.PlayerEngine;
import com.kaltura.playkit.plugins.ads.AdCuePoints;
import com.kaltura.playkit.plugins.ads.AdEvent;
import com.kaltura.playkit.ads.PKAdProviderListener;
import com.kaltura.playkit.player.PlayerSettings;
import com.kaltura.playkit.player.metadata.PKMetadata;
import com.kaltura.playkit.player.metadata.PKTextInformationFrame;
import com.kaltura.playkit.plugin.ima.BuildConfig;
import com.kaltura.playkit.plugins.ads.AdInfo;
import com.kaltura.playkit.plugins.ads.AdPositionType;
import com.kaltura.playkit.plugins.ads.AdsProvider;
import com.kaltura.playkit.utils.Consts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IMADAIPlugin extends PKPlugin implements com.google.ads.interactivemedia.v3.api.AdEvent.AdEventListener, AdErrorEvent.AdErrorListener, AdsProvider {
    private static final PKLog log = PKLog.get("IMADAIPlugin");
    private static final int KB_MULTIPLIER = 1024;

    private Player player;
    private Context context;
    private MessageBus messageBus;
    private AdInfo adInfo;
    private IMADAIConfig adConfig;
    private PKMediaConfig mediaConfig;
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
    private Map<com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType, com.kaltura.playkit.plugins.ads.AdEvent.Type> adEventsMap;
    private Context mContext;
    private ViewGroup mAdUiContainer;

    private double mBookMarkContentTime; // Bookmarked content time, in seconds.
    private double mSnapBackTime; // Stream time to snap back to, in seconds.

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
        mContext = context;
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
        this.context = context;
        this.messageBus = messageBus;

        addListeners();
    }

    private void addListeners() {

        messageBus.addListener(this, PlayerEvent.ended, event -> {
            log.d("Received:PlayerEvent:" + event.eventType().name());
            lastPlaybackPlayerState = PlayerEvent.Type.ENDED;
            long currentPosSec = getPlayerEngine().getCurrentPosition() / Consts.MILLISECONDS_MULTIPLIER;
            if (streamManager != null) {
                CuePoint prevCuePoint = streamManager.getPreviousCuePointForStreamTime(currentPosSec);
                if (getPlayerEngine().getCurrentPosition() >= getPlayerEngine().getDuration() && prevCuePoint != null && currentPosSec >= prevCuePoint.getEndTime()) {
                    if (pluginCuePoints != null && pluginCuePoints.size() > 0 && Math.floor(pluginCuePoints.get(pluginCuePoints.size() - 1).getEndTime()) == Math.floor(getPlayerEngine().getDuration() / Consts.MILLISECONDS_MULTIPLIER)) {
                        if (!pluginCuePoints.get(pluginCuePoints.size() - 1).isPlayed()) {
                            getPlayerEngine().seekTo((long) (pluginCuePoints.get(pluginCuePoints.size() - 1).getStartTime() * Consts.MILLISECONDS_MULTIPLIER));
                        }
                    }
                }
            }
        });

        messageBus.addListener(this, PlayerEvent.stateChanged, new PKEvent.Listener<PlayerEvent.StateChanged>() {
            @Override
            public void onEvent(PlayerEvent.StateChanged event) {
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
            PlayerEvent.MetadataAvailable metadataAvailableEvent = event;
            for (VideoStreamPlayer.VideoStreamPlayerCallback callback : mPlayerCallbacks) {
                for (PKMetadata pkMetadata : metadataAvailableEvent.metadataList){
                    if (pkMetadata instanceof PKTextInformationFrame) {
                        PKTextInformationFrame textFrame = (PKTextInformationFrame) pkMetadata;
                        if ("TXXX".equals(textFrame.id)) {
                            log.d("Received user text: " + textFrame.value);
                            if (mPlayerCallbacks != null) {
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
                mediaConfig.getMediaEntry().getSources().get(0).getUrl().contains("dai.google.com")) {
            return;
        }

        if (mediaConfig != null) {
            playbackStartPosition = (mediaConfig.getStartPosition() != null) ? mediaConfig.getStartPosition() : null;
            log.d("mediaConfig start pos = " + playbackStartPosition);
        }

        playkitAdCuePoints = null;
        pluginCuePoints = null;
        isAutoPlay = false;
        isContentPrepared = false;
        isAdRequested = false;
        isAdDisplayed = false;
        isAdIsPaused  = false;
        isAdError     = false;
        this.mediaConfig = mediaConfig;
        clearAdsLoader();
        imaSetup();
        log.d("Event: " + AdEvent.Type.AD_REQUESTED);
        requestAdFromIMADAI();
    }

    private void requestAdFromIMADAI() {
        String adRequestInfo = adConfig.getAssetKey();
        if (adConfig.getAssetKey() == null) {
            adRequestInfo = adConfig.getContentSourceId() + "/" + adConfig.getVideoId();
        }
        adsLoader.requestStream(buildStreamRequest());
        messageBus.post(new AdEvent.AdRequestedEvent(PKAdPlugin.ima_dai, adRequestInfo));
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
        } else {
            // NOT sure if needed - adsLoader.contentComplete();
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
        imaSdkSettings.setEnableOmidExperimentally(true);
    }

    private AdsLoader.AdsLoadedListener getAdsLoadedListener() {

        adsLoadedListener = new AdsLoader.AdsLoadedListener() {
            @Override
            public void onAdsManagerLoaded(AdsManagerLoadedEvent adsManagerLoadedEvent) {
                log.d("onAdsManager loaded");

                if (streamManager != null) {
                    resetIMA();
                }
                streamManager = adsManagerLoadedEvent.getStreamManager();
                streamManager.addAdErrorListener(IMADAIPlugin.this);
                streamManager.addAdEventListener(IMADAIPlugin.this);
                isAdRequested = true;
                streamManager.init(getRenderingSettings());
            }
        };
        return adsLoadedListener;
    }

    private void resetIMA(){
        if (displayContainer != null) {
            displayContainer.unregisterAllVideoControlsOverlays();
            if (streamManager != null) {
                streamManager.removeAdErrorListener(IMADAIPlugin.this);
                streamManager.removeAdEventListener(IMADAIPlugin.this);
                streamManager.destroy();
                streamManager = null;
            }
        }
    }

    private void clearAdsLoader() {
        if (adsLoader != null) {
            adsLoader.removeAdErrorListener(this);
            adsLoader.removeAdsLoadedListener(adsLoadedListener);
            adsLoadedListener = null;
            adsLoader = null;
        }
    }

    private AdsRenderingSettings getRenderingSettings() {

        renderingSettings = ImaSdkFactory.getInstance().createAdsRenderingSettings();

        //if (!adConfig.isAlwaysStartWithPreroll() && playbackStartPosition != null && playbackStartPosition > 0) {
        //    renderingSettings.setPlayAdsAfterTime(playbackStartPosition);
        //}

        if (playbackStartPosition != null && playbackStartPosition > 0) {
            renderingSettings.setPlayAdsAfterTime(mediaConfig.getStartPosition());
        }

        //if both are false we remove the support int ad count down in ad
        if (!adConfig.getAdAttribution() && !adConfig.getAdCountDown()) {
            renderingSettings.setUiElements(Collections.emptySet());
        }

        return renderingSettings;
    }
    private StreamRequest buildStreamRequest() {
        if (videoStreamPlayer == null) {
            videoStreamPlayer = createVideoStreamPlayer();
            displayContainer = sdkFactory.createStreamDisplayContainer();
            displayContainer.setVideoStreamPlayer(videoStreamPlayer);
            displayContainer.setAdContainer(mAdUiContainer);
            if (adConfig.getControlsOverlayList() != null) {
                for (View controlView : adConfig.getControlsOverlayList()) {
                    displayContainer.registerVideoControlsOverlay(controlView);
                }
            }
        }

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
                log.d("loadUrl = " + url + " lastAdEventReceived = " + lastAdEventReceived);
                messageBus.post(new com.kaltura.playkit.plugins.ads.AdEvent.DAISourceSelected(url));
                if (isAdError) {
                    log.e("ERROR when calling loadUrl = " + url + " lastAdEventReceived = " + lastAdEventReceived);
                    preparePlayer(true);
                    getPlayerEngineWrapper().play();
                    return;
                }
                updateMediaConfig(url);

                if (isAutoPlay) {
                    player.prepare(mediaConfig);
                    player.play();
                } else {
                    player.prepare(mediaConfig);
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
                log.d(" onAdBreakStarted");
            }

            @Override
            public void onAdBreakEnded() {
                log.d("onAdBreakEnded");

                if (mSnapBackTime > 0) {
                    long snapBackTimeMili = (long) mSnapBackTime * Consts.MILLISECONDS_MULTIPLIER;
                    log.d("onAdBreakEnded seekTo: " + snapBackTimeMili);
                    getPlayerEngine().seekTo(snapBackTimeMili);
                    if (snapBackTimeMili != getPlayerEngine().getDuration()) {
                        getPlayerEngine().play();
                    }
                }
                mSnapBackTime = 0;
            }

            @Override
            public VideoProgressUpdate getContentProgress() {
                if (getPlayerEngine() == null || streamManager == null) {
                    return VideoProgressUpdate.VIDEO_TIME_NOT_READY;
                }
                long position = (long) streamManager.getStreamTimeForContentTime(getPlayerEngine().getCurrentPosition());
                if (getPlayerEngine().isLive()) {
                    long pos = getPlayerEngine().getCurrentPosition();
                    pos -= getPlayerEngine().getPositionInWindowMs();
                    position = Math.round(streamManager.getStreamTimeForContentTime(pos));
                }
                long duration = Math.round(streamManager.getStreamTimeForContentTime(getPlayerEngine().getDuration()));
                if (position < 0 || duration < 0) {
                    return VideoProgressUpdate.VIDEO_TIME_NOT_READY;
                }
                return new VideoProgressUpdate(position, duration);
            }
        };
    }

    private void updateMediaConfig(String url) {
        List<PKDrmParams> drmData = null;
        if (!TextUtils.isEmpty(adConfig.getLicenseUrl())) {
            drmData = new ArrayList<>();
            PKDrmParams pkDrmParams = new PKDrmParams(adConfig.getLicenseUrl(), PKDrmParams.Scheme.WidevineCENC);
            drmData.add(pkDrmParams);
        }

        if (mediaConfig.getMediaEntry() != null && mediaConfig.getMediaEntry().getSources() != null) {
            if (adConfig.isLiveDAI()) {
                if (PKMediaEntry.MediaEntryType.Vod.equals(mediaConfig.getMediaEntry().getMediaType())){
                    mediaConfig.getMediaEntry().setMediaType(PKMediaEntry.MediaEntryType.Live);
                }
            }

            for (PKMediaSource source : mediaConfig.getMediaEntry().getSources()) {
                source.setUrl(url).setMediaFormat(PKMediaFormat.valueOfUrl(url)).setDrmData(drmData);
            }
            if (mediaConfig.getStartPosition() != null && mediaConfig.getStartPosition() > 0) {
                double fakeStartPos = streamManager.getStreamTimeForContentTime(mediaConfig.getStartPosition());
                if (adConfig.isAlwaysStartWithPreroll()) {
                    mSnapBackTime = fakeStartPos;
                    mediaConfig.setStartPosition(0L);
                } else {
                    mediaConfig.setStartPosition((long) fakeStartPos);
                }
            }
        }
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
        if (isAdShouldAutoPlayOnResume() && isAdDisplayed) {
            getPlayerEngine().play();
        }
    }

    @Override
    protected void onDestroy() {
        isAdRequested = false;
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
            case AD_BREAK_STARTED: //Fired when an ad break starts.
                log.d("AD AD_BREAK_STARTED");
                isAdDisplayed = true;
                messageBus.post(new AdEvent(AdEvent.Type.AD_BREAK_STARTED));
                messageBus.post(new AdEvent(AdEvent.Type.CONTENT_PAUSE_REQUESTED));
                break;
            case AD_BREAK_ENDED: //Fired when an ad break ends.
                log.d("AD AD_BREAK_ENDED");
                messageBus.post(new AdEvent(AdEvent.Type.AD_BREAK_ENDED));
                messageBus.post(new AdEvent(AdEvent.Type.CONTENT_RESUME_REQUESTED));
                boolean allAdsPlayed = true;
                boolean lastAdPlayed = true;
                List<CuePoint> cuesList = streamManager.getCuePoints();
                if (cuesList != null) {
                    for (int i = 0 ; i < cuesList.size() ; i++) {
                        if (!cuesList.get(i).isPlayed()) {
                            allAdsPlayed = false;
                        }
                        if (i == cuesList.size() -1) {
                            lastAdPlayed = cuesList.get(i).isPlayed();
                        }
                    }

                    if (allAdsPlayed || lastAdPlayed && getPlayerEngine().getCurrentPosition() >= getPlayerEngine().getDuration()) {
                        messageBus.post(new AdEvent(AdEvent.Type.ALL_ADS_COMPLETED));
                    }
                }
                isAdDisplayed = false;
                break;
            case CUEPOINTS_CHANGED: //Dispatched for on-demand streams when the cuepoints change.
                pluginCuePoints = streamManager.getCuePoints();
                if (pluginCuePoints != null && getFakePlayerDuration() > 0) {
                    for (CuePoint cue : pluginCuePoints) {
                        log.d(String.format("Cue: %s\n", cue.getStartTime() + " " + cue.getEndTime() + " " + cue.isPlayed()));
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
                messageBus.post(new AdEvent(AdEvent.Type.TAPPED));
                messageBus.post(new AdEvent(AdEvent.Type.CLICKED));
                break;
            case STARTED: //Fired when an ad starts.
                log.d("AD STARTED");
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
                adInfo.setAdPlayHead(getCurrentPosition() * Consts.MILLISECONDS_MULTIPLIER);
                messageBus.post(new AdEvent.AdPausedEvent(adInfo));
                break;
            case RESUMED:
                log.d("AD RESUMED");
                isAdIsPaused = false;
                adInfo.setAdPlayHead(getCurrentPosition() * Consts.MILLISECONDS_MULTIPLIER);
                messageBus.post(new AdEvent.AdResumedEvent(adInfo));
                break;
            case COMPLETED: //Fired when an ad is complete.
                log.d("AD COMPLETED");
                messageBus.post(new AdEvent(AdEvent.Type.COMPLETED));
                break;
            case TAPPED:
                log.d("AD TAPPED");
                messageBus.post(new AdEvent(AdEvent.Type.TAPPED));
                break;
            case ICON_TAPPED:
                log.d("AD ICON_TAPPED");
                messageBus.post(new AdEvent(AdEvent.Type.ICON_TAPPED));
            case LOG:
                log.e("AD LOG ERROR");
                String error = "Non-fatal Error";
                if (adEvent.getAdData() != null) {
                    if (adEvent.getAdData().containsKey("errorMessage")) {
                        error = adEvent.getAdData().get("errorMessage");
                    }
                }
                sendError(PKAdErrorType.QUIET_LOG_ERROR, error, null);
                break;
            default:
                break;
        }
    }

    private boolean checkIfDiscardAdRequired() {
        if (!adConfig.isAlwaysStartWithPreroll() || adInfo == null || pluginCuePoints == null || mediaConfig == null || playbackStartPosition == null) {
            return false;
        }
        log.d("getAdPositionType = " + adInfo.getAdPositionType().name());
        log.d("playbackStartPosition = " +  playbackStartPosition);
        return adInfo.getAdPositionType() == AdPositionType.PRE_ROLL && playbackStartPosition > 0;
    }

    @Override
    public void onAdError(AdErrorEvent adErrorEvent) {
        log.e("Event: onAdError" + adErrorEvent.getError().getErrorCode());
        isAdError = true;
        isAdRequested = true;
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
        preparePlayer(true);
    }

    private void sendError(Enum errorType, String message, Throwable exception) {
        log.e("Ad Error: " + errorType.name() + " with message " + message);
        com.kaltura.playkit.plugins.ads.AdEvent errorEvent = new com.kaltura.playkit.plugins.ads.AdEvent.Error(new PKError(errorType, message, exception));
        messageBus.post(errorEvent);
    }

    private void sendCuePointsUpdate() {
        List<Long> cuePointsList = new ArrayList<>();
        List<Pair<Long,Long>> daiAdsList = new ArrayList<>();
        StringBuilder cuePointBuilder = new StringBuilder();
        if (pluginCuePoints != null) {
            int cuePointIndex = 1;
            for (CuePoint cuePoint : pluginCuePoints) {
                long cuePointVal = (long) streamManager.getContentTimeForStreamTime(cuePoint.getStartTime());

                if (cuePointIndex == pluginCuePoints.size() && cuePointVal * Consts.MILLISECONDS_MULTIPLIER == getFakePlayerDuration()) {
                    cuePointBuilder.append(-1).append("|");
                    cuePointsList.add((-1L));
                } else {
                    cuePointBuilder.append(cuePointVal).append("|");
                    cuePointsList.add((cuePointVal * Consts.MILLISECONDS_MULTIPLIER));
                }
                daiAdsList.add(new Pair((long)cuePoint.getStartTime() * Consts.MILLISECONDS_MULTIPLIER, (long)(cuePoint.getEndTime() - cuePoint.getStartTime()) * Consts.MILLISECONDS_MULTIPLIER));
                cuePointIndex++;
            }
            log.d("sendCuePointsUpdate pluginCuePoints = " + cuePointBuilder.toString());

            if (cuePointsList.size() > 0) {
                messageBus.post(new AdEvent.AdCuePointsUpdateEvent(new AdCuePoints(cuePointsList, daiAdsList)));
            }
        }
    }

    private AdInfo createAdInfo(Ad ad) {
        String adDescription = ad.getDescription();
        long adDuration = (long) (ad.getDuration() * Consts.MILLISECONDS_MULTIPLIER);
        long adPlayHead = getCurrentPosition() * Consts.MILLISECONDS_MULTIPLIER;
        String adTitle = ad.getTitle();
        boolean isAdSkippable = ad.isSkippable();
        long skipTimeOffset = (long) ad.getSkipTimeOffset() * Consts.MILLISECONDS_MULTIPLIER;
        String contentType = ad.getContentType();
        String adId = ad.getAdId();
        String adSystem = ad.getAdSystem();
        int adHeight = ad.isLinear() ? ad.getVastMediaHeight() : ad.getHeight();
        int adWidth  = ad.isLinear() ? ad.getVastMediaWidth() : ad.getWidth();
        int mediaBitrate = ad.getVastMediaBitrate() != 0 ? ad.getVastMediaBitrate() * KB_MULTIPLIER : -1;
        int totalAdsInPod = ad.getAdPodInfo().getTotalAds();
        int adIndexInPod = ad.getAdPodInfo().getAdPosition();   // index starts in 1
        int podCount = (streamManager != null && streamManager.getCuePoints() != null) ? streamManager.getCuePoints().size() : 0;
        int podIndex = (ad.getAdPodInfo().getPodIndex() >= 0) ? ad.getAdPodInfo().getPodIndex() + 1 : podCount; // index starts in 0
        boolean isBumper = ad.getAdPodInfo().isBumper();
        long adPodTimeOffset = (long) (ad.getAdPodInfo().getTimeOffset() * Consts.MILLISECONDS_MULTIPLIER);

        AdInfo adInfo = new AdInfo(adDescription, adDuration, adPlayHead,
                adTitle, isAdSkippable, skipTimeOffset,
                contentType, adId,
                adSystem,
                adHeight,
                adWidth,
                mediaBitrate,
                totalAdsInPod,
                adIndexInPod,
                podIndex,
                podCount,
                isBumper,
                isAdInfoPostRoll(adPodTimeOffset) ? -1 : adPodTimeOffset);
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
        if (adPodTimeOffset < pluginCuePoints.get(pluginCuePoints.size()-1).getStartTime() * Consts.MILLISECONDS_MULTIPLIER) {
            return false;
        }
        return pluginCuePoints.get(pluginCuePoints.size()-1).getEndTime() * Consts.MILLISECONDS_MULTIPLIER == getPlayerEngine().getDuration();
    }

    @Override
    public void start() {
        isAutoPlay = true; // start will be called only on first time media is played programmatically
        isAdRequested = true;
        if (!getPlayerEngine().isPlaying()) {
            getPlayerEngine().play();
        }
    }

    @Override
    public void destroyAdsManager() {
        isAdRequested = false;
        if (streamManager == null) {
            return;
        }
        log.d("IMADAI Start destroyAdsManager");
        streamManager.destroy();
        contentCompleted();
        streamManager = null;
        isAdDisplayed = false;
    }

    @Override
    public void resume() {
        isAdIsPaused = false;
        getPlayerEngine().play();
    }

    @Override
    public void pause() {
        isAdIsPaused = true;
        getPlayerEngine().pause();
    }

    @Override
    public void contentCompleted() {
        if (adsLoader != null) {
            adsLoader.contentComplete();
        }
    }

    @Override
    public PKAdInfo getAdInfo() {
        return adInfo;
    }

    @Override
    public AdCuePoints getCuePoints() {
        if (playkitAdCuePoints != null) {
            return playkitAdCuePoints;
        }
        List<Long> cuePointsList = new ArrayList<>();
        List<Pair<Long,Long>> daiAdsList = new ArrayList<>();
        StringBuilder cuePointBuilder = new StringBuilder();
        if (pluginCuePoints != null) {
            int cuePointIndex = 1;
            for (CuePoint cuePoint : pluginCuePoints) {
                if (cuePoint == null) {
                    continue;
                }
                long cuePointVal = (long) streamManager.getContentTimeForStreamTime(cuePoint.getStartTime());

                if (cuePointIndex == pluginCuePoints.size() && cuePointVal * Consts.MILLISECONDS_MULTIPLIER == getFakePlayerDuration()) {
                    cuePointBuilder.append(-1).append("|");
                    cuePointsList.add((-1L));
                } else {
                    cuePointBuilder.append(cuePointVal).append("|");
                    cuePointsList.add((cuePointVal * Consts.MILLISECONDS_MULTIPLIER));
                }
                daiAdsList.add(new Pair((long)cuePoint.getStartTime() * Consts.MILLISECONDS_MULTIPLIER, (long)(cuePoint.getEndTime() - cuePoint.getStartTime()) * Consts.MILLISECONDS_MULTIPLIER));
                cuePointIndex++;
            }
        }
        playkitAdCuePoints =  new AdCuePoints(cuePointsList, daiAdsList);
        return playkitAdCuePoints;
    }

    @Override
    public boolean isAdDisplayed() {
        //log.d("isAdDisplayed = " + isAdDisplayed);
        return isAdDisplayed;
    }

    @Override
    public boolean isAdPaused() {
        return isAdIsPaused || !getPlayerEngine().isPlaying();
    }

    @Override
    public boolean isAdRequested() {
        return isAdRequested;
    }

    @Override
    public boolean isAllAdsCompleted() {
        if (pluginCuePoints != null && pluginCuePoints.size() > 0) {
            CuePoint cuePoint = pluginCuePoints.get(pluginCuePoints.size() -1);
            if (cuePoint.isPlayed()) {
                return true;
            }
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
                if (streamManager.getCuePoints().size() > 0 && adDuration > getPlayerEngine().getDuration()) {
                    adDuration = getPlayerEngine().getDuration();
                }
                return adDuration;
            } else {
                return 0;
            }
        }
        return getFakePlayerDuration();

    }

    private long getFakePlayerDuration() {
        long playerDuration = getPlayerEngine().getDuration();
        if (playerDuration < 0 || playerDuration == C.TIME_UNSET) {
            return 0;
        }

        if (streamManager != null) {
            long val = (long) streamManager.getContentTimeForStreamTime(Math.floor(playerDuration / Consts.MILLISECONDS_MULTIPLIER)) * Consts.MILLISECONDS_MULTIPLIER;
            //log.d("Duration = " + val);
            return  val;
        }

        if (pluginCuePoints != null) {
            for (CuePoint cuePoint : pluginCuePoints) {
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
        // works for case that ad is diaplayed!
        if (isAdDisplayed) {
            long adPosition = 0;
            if (streamManager != null && streamManager.getAdProgressInfo() != null) {
                adPosition = Math.round(streamManager.getAdProgressInfo().getCurrentTime());
                if (streamManager.getCuePoints().size() > 0 && adPosition > getPlayerEngine().getCurrentPosition()) {
                    adPosition = 0; // vod error case
                }
                log.d("getCurrentPosition = " + adPosition);
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
    public long getFakePlayerPosition(long realPlayerPosition) {

        if (realPlayerPosition < 0 || realPlayerPosition == C.TIME_UNSET) {
            return 0;
        }
        if (streamManager != null) {
            long contentTimeForStreamTime = (long) (streamManager.getContentTimeForStreamTime(Math.floor(realPlayerPosition / Consts.MILLISECONDS_MULTIPLIER))) * Consts.MILLISECONDS_MULTIPLIER;
            log.d("Position = " + Math.round(realPlayerPosition / Consts.MILLISECONDS_MULTIPLIER_FLOAT));
            int totalAdsDuration = 0;
            if (pluginCuePoints != null) {
                for (CuePoint cuePoint : pluginCuePoints) {
                    totalAdsDuration += (cuePoint.getEndTime() - cuePoint.getStartTime());
                    if (!isAdDisplayed && cuePoint.isPlayed() && cuePoint.getStartTime() == Math.round(realPlayerPosition / Consts.MILLISECONDS_MULTIPLIER_FLOAT)) {
                        log.d("AD PLAYED - SKIP");
                        long newPositionToSeek = (long) Math.floor(cuePoint.getEndTime() * Consts.MILLISECONDS_MULTIPLIER);
                        seekTo(newPositionToSeek - (totalAdsDuration * Consts.MILLISECONDS_MULTIPLIER));
                        return (long) ((cuePoint.getStartTime() * Consts.MILLISECONDS_MULTIPLIER) - (totalAdsDuration * Consts.MILLISECONDS_MULTIPLIER));
                    }
                }
            }
            log.d("contentTimeForStreamTime = " + contentTimeForStreamTime);
            return contentTimeForStreamTime;
        }

        long playerPositionSec = (long) Math.round(realPlayerPosition / Consts.MILLISECONDS_MULTIPLIER);
        if (pluginCuePoints != null) {
            for (CuePoint cuePoint : pluginCuePoints) {
                if (cuePoint.isPlayed() && cuePoint.getEndTime() <= playerPositionSec) {
                    realPlayerPosition -= Consts.MILLISECONDS_MULTIPLIER * (cuePoint.getEndTime() - cuePoint.getStartTime());
                }
            }
        }
        if (realPlayerPosition > getFakePlayerDuration()) {
            return getFakePlayerDuration();
        }
        if (realPlayerPosition < 0) {
            return 0;
        }
        return realPlayerPosition;
    }

    @Override
    public void removeAdProviderListener() {
        pkAdProviderListener = null;
    }

    @Override
    public void skipAd() {
        log.w("skipAd is not supported");
        return;
    }

    @Override
    public void seekTo(long position) {
        log.d("seekTo " + position);

        long newPositionToSeek = -1;
        if (streamManager != null) {
            if (pluginCuePoints != null) {
                CuePoint candidateCuePoint = streamManager.getPreviousCuePointForStreamTime(streamManager.getStreamTimeForContentTime(Math.floor(position / Consts.MILLISECONDS_MULTIPLIER)));
                if (candidateCuePoint != null && !candidateCuePoint.isPlayed()) {
                    newPositionToSeek = (long) Math.floor(candidateCuePoint.getStartTime() * Consts.MILLISECONDS_MULTIPLIER);
                    getPlayerEngine().seekTo(newPositionToSeek);
                    mSnapBackTime = streamManager.getStreamTimeForContentTime(Math.floor(position / Consts.MILLISECONDS_MULTIPLIER));
                    return;
                } else {
                    newPositionToSeek = position;
                    if (isPositionInBetweenCuePoint(position)) {
                        newPositionToSeek = getCuePointEndTime(position);
                    }
                    newPositionToSeek = (long) streamManager.getStreamTimeForContentTime(Math.floor(newPositionToSeek / Consts.MILLISECONDS_MULTIPLIER)) * Consts.MILLISECONDS_MULTIPLIER;
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

    private boolean isPositionInBetweenCuePoint(long position) {

        if (pluginCuePoints != null) {
            for (CuePoint cuePoint : pluginCuePoints) {
                if (cuePoint.getStartTime() * Consts.MILLISECONDS_MULTIPLIER >= position && cuePoint.getEndTime() * Consts.MILLISECONDS_MULTIPLIER <= position) {
                    return true;
                }
            }
        }
        return false;
    }

    private long getCuePointEndTime(long position) {
        if (pluginCuePoints != null) {
            for (CuePoint cuePoint : pluginCuePoints) {
                if (cuePoint.getStartTime() * Consts.MILLISECONDS_MULTIPLIER >= position && position <= cuePoint.getEndTime() * Consts.MILLISECONDS_MULTIPLIER) {
                    return (long) (cuePoint.getEndTime() * Consts.MILLISECONDS_MULTIPLIER);
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

//    private PKMediaConfig createMediaConfig(String url) {
//
//        PKMediaConfig adMediaConfig = new PKMediaConfig();//.setStartPosition(daiStartPosition);
//        long daiStartPosition = (mediaConfig != null && mediaConfig.getStartPosition() != null && mediaConfig.getStartPosition() > 0) ? mediaConfig.getStartPosition() : 0;
//        if (streamManager != null && daiStartPosition > 0) {
//            daiStartPosition = (long) streamManager.getStreamTimeForContentTime(daiStartPosition);
//            adMediaConfig.setStartPosition(daiStartPosition);
//        }
//
//        PKMediaEntry adMediaEntry = createMediaEntry(url);
//        adMediaConfig.setMediaEntry(adMediaEntry);
//        return adMediaConfig;
//    }
//
//    private PKMediaEntry createMediaEntry(String url) {
//
//        PKMediaEntry mediaEntry = new PKMediaEntry();
//        mediaEntry.setId("adId");
//        if (adConfig.getVideoId() == null) {
//            mediaEntry.setMediaType(PKMediaEntry.MediaEntryType.Live);
//        } else {
//            mediaEntry.setMediaType(PKMediaEntry.MediaEntryType.Vod);
//        }
//
//        List<PKMediaSource> mediaSources = createMediaSources(url);
//        mediaEntry.setSources(mediaSources);
//
//        return mediaEntry;
//    }
//
//    private List<PKMediaSource> createMediaSources(String url) {
//
//        List<PKMediaSource> mediaSources = new ArrayList<>();
//        PKMediaSource mediaSource = new PKMediaSource();
//        mediaSource.setId("adId");
//        mediaSource.setUrl(url);
//        mediaSource.setMediaFormat(PKMediaFormat.valueOfUrl(url));
//
//        if (adConfig.getLicenseUrl() !=null) {
//            List<PKDrmParams> drmData = new ArrayList<>();
//            PKDrmParams pkDrmParams = new PKDrmParams(adConfig.getLicenseUrl(), PKDrmParams.Scheme.WidevineCENC);
//            drmData.add(pkDrmParams);
//            mediaSource.setDrmData(drmData);
//        }
//
//        mediaSources.add(mediaSource);
//        return mediaSources;
//    }

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
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.AD_BREAK_READY, com.kaltura.playkit.plugins.ads.AdEvent.Type.AD_BREAK_READY);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.TAPPED, com.kaltura.playkit.plugins.ads.AdEvent.Type.TAPPED);
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
}