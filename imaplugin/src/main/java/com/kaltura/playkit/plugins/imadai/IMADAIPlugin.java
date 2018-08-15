package com.kaltura.playkit.plugins.imadai;

import android.content.Context;
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
import com.google.ads.interactivemedia.v3.api.UiElement;
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate;
import com.google.ads.interactivemedia.v3.api.player.VideoStreamPlayer;
import com.google.android.exoplayer2.C;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
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
import com.kaltura.playkit.PlayerDecorator;
import com.kaltura.playkit.PlayerEvent;
import com.kaltura.playkit.ads.AdDAIEnabledPlayerController;
import com.kaltura.playkit.ads.AdEnabledPlayerController;
import com.kaltura.playkit.ads.PKAdErrorType;
import com.kaltura.playkit.ads.PKAdInfo;
import com.kaltura.playkit.plugins.ads.AdCuePoints;
import com.kaltura.playkit.plugins.ads.AdEvent;
import com.kaltura.playkit.ads.PKAdProviderListener;
import com.kaltura.playkit.player.PlayerSettings;
import com.kaltura.playkit.player.metadata.PKMetadata;
import com.kaltura.playkit.player.metadata.PKTextInformationFrame;
import com.kaltura.playkit.plugin.ima.BuildConfig;
import com.kaltura.playkit.plugins.ads.AdInfo;
import com.kaltura.playkit.plugins.ads.AdsProvider;
import com.kaltura.playkit.utils.Consts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IMADAIPlugin extends PKPlugin implements com.google.ads.interactivemedia.v3.api.AdEvent.AdEventListener, AdErrorEvent.AdErrorListener, AdsProvider {
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
    private PlayerEvent.Type lastPlaybackPlayerState;
    private com.kaltura.playkit.plugins.ads.AdEvent.Type lastAdEventReceived;
    private Map<com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType, com.kaltura.playkit.plugins.ads.AdEvent.Type> adEventsMap;
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

        this.messageBus.listen(new PKEvent.Listener() {
            @Override
            public void onEvent(PKEvent event) {
                log.d("XXX Received:PlayerEvent:" + event.eventType().name());
                if (event.eventType() == PlayerEvent.Type.ENDED) {
                    long currentPosSec = player.getCurrentPosition() / Consts.MILLISECONDS_MULTIPLIER;
                    if (streamManager != null) {
                        CuePoint prevCuePoint = streamManager.getPreviousCuePointForStreamTime(currentPosSec);
                        if (player.getCurrentPosition() >= player.getDuration() && prevCuePoint != null && currentPosSec >= prevCuePoint.getEndTime()) {
                            if (cuePoints != null && cuePoints.size() > 0 && Math.floor(cuePoints.get(cuePoints.size() - 1).getEndTime()) == Math.floor(player.getDuration() / Consts.MILLISECONDS_MULTIPLIER)) {
                                player.seekTo((long) (cuePoints.get(cuePoints.size() - 1).getStartTime() * Consts.MILLISECONDS_MULTIPLIER));
                                player.pause();
                            }
                        }
                    }
                } else if (event.eventType() == PlayerEvent.Type.PLAYING) {

                } else if (event.eventType() == PlayerEvent.Type.SEEKING) {

                } else if (event.eventType() == PlayerEvent.Type.METADATA_AVAILABLE) {
                    PlayerEvent.MetadataAvailable metadataAvailableEvent = (PlayerEvent.MetadataAvailable) event;
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
                }
            }
        }, PlayerEvent.Type.ENDED, PlayerEvent.Type.PLAYING, PlayerEvent.Type.SEEKING, PlayerEvent.Type.METADATA_AVAILABLE);
    }

    @Override
    protected void onUpdateMedia(PKMediaConfig mediaConfig) {
        log.d("Start onUpdateMedia");
        if (mediaConfig != null && mediaConfig.getMediaEntry() != null &&
                mediaConfig.getMediaEntry().hasSources() &&
                mediaConfig.getMediaEntry().getSources().get(0).getUrl().contains("dai.google.com")) {
            return;
        }
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
                streamManager.init(getRenderingSettings());
            }
        };
        return adsLoadedListener;
    }

    private AdsRenderingSettings getRenderingSettings() {

        renderingSettings = ImaSdkFactory.getInstance().createAdsRenderingSettings();

        if (mediaConfig != null && mediaConfig.getStartPosition() > 0) {
            renderingSettings.setPlayAdsAfterTime(mediaConfig.getStartPosition());
        }

        //if both are false we remove the support int ad count down in ad
        if (!adConfig.getAdAttribution() && !adConfig.getAdCountDown()) {
            renderingSettings.setUiElements(Collections.<UiElement>emptySet());
        }

        return renderingSettings;
    }
    private StreamRequest buildStreamRequest() {

        VideoStreamPlayer videoStreamPlayer = createVideoStreamPlayer();
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
                log.d("XXX loadUrl = " + url + " lastAdEventReceived = " + lastAdEventReceived);
                messageBus.post(new com.kaltura.playkit.plugins.ads.AdEvent.AdDAISourceSelected(createMediaConfig(url)));
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
                    log.d("XXX onAdBreakEnded seekTo: " + snapBackTimeMili);
                    player.seekTo(snapBackTimeMili);
                    if (snapBackTimeMili != player.getDuration()) {
                        player.play();
                    }
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
        if (isAdShouldAutoPlayOnResume() && isAdDisplayed) {
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

        if(adEventsMap == null){
            log.e("ERROR, adEventsMap == null");
            return;
        }

        lastAdEventReceived = adEventsMap.get(adEvent.getType());
        if (adEvent.getType() != com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.AD_PROGRESS) {
            log.d("XXX Event: " + adEvent.getType());
        }
        switch (adEvent.getType()) {
            case AD_BREAK_STARTED: //Fired when an ad break starts.
                log.d("AD AD_BREAK_STARTED");
                isAdDisplayed = true;
                messageBus.post(new AdEvent(AdEvent.Type.AD_BREAK_STARTED));
                break;
            case AD_BREAK_ENDED: //Fired when an ad break ends.
                log.d("AD AD_BREAK_ENDED");
                messageBus.post(new AdEvent(AdEvent.Type.AD_BREAK_ENDED));
                isAdDisplayed = false;
                break;
            case CUEPOINTS_CHANGED: //Dispatched for on-demand streams when the cuepoints change.
                cuePoints = streamManager.getCuePoints();
                if (cuePoints != null && getFakePlayerDuration() > 0) {
                    for (CuePoint cue : cuePoints) {
                        log.d(String.format("XXX Cue: %s\n", cue.getStartTime() + " " + cue.getEndTime() + " " + cue.isPlayed()));
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
                messageBus.post(new AdEvent(AdEvent.Type.CLICKED));
                break;
            case STARTED: //Fired when an ad starts.
                log.d("AD STARTED");
                adInfo = createAdInfo(adEvent.getAd());
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

    @Override
    public void onAdError(AdErrorEvent adErrorEvent) {
        log.e("Event: onAdError" + adErrorEvent.getError().getErrorCode());
        isAdError = true;
        //isAdRequested = true;
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
        StringBuilder cuePointBuilder = new StringBuilder();
        if (cuePoints != null) {
            int cuePointIndex = 1;
            for (CuePoint cuePoint : cuePoints) {
                long cuePointVal = (long) streamManager.getContentTimeForStreamTime(cuePoint.getStartTime());

                if (cuePointIndex == cuePoints.size() && cuePointVal * Consts.MILLISECONDS_MULTIPLIER == getFakePlayerDuration()) {
                    cuePointBuilder.append(-1).append("|");
                    cuePointsList.add((-1L));
                } else {
                    cuePointBuilder.append(cuePointVal).append("|");
                    cuePointsList.add((cuePointVal * Consts.MILLISECONDS_MULTIPLIER));
                }
                cuePointIndex++;
            }
            log.d("sendCuePointsUpdate cuePoints = " + cuePointBuilder.toString());

            if (cuePointsList.size() > 0) {
                messageBus.post(new AdEvent.AdCuePointsUpdateEvent(new AdCuePoints(cuePointsList)));
            }
        }
    }

    private AdInfo createAdInfo(Ad ad) {
        String adDescription = ad.getDescription();
        long adDuration = (long) (ad.getDuration() * Consts.MILLISECONDS_MULTIPLIER);
        long adPlayHead = getCurrentPosition() * Consts.MILLISECONDS_MULTIPLIER;
        String adTitle = ad.getTitle();
        boolean isAdSkippable = ad.isSkippable();
        String contentType = ad.getContentType();
        String adId = ad.getAdId();
        String adSystem = ad.getAdSystem();
        int adHeight = ad.getHeight();
        int adWidth = ad.getWidth();
        int totalAdsInPod = ad.getAdPodInfo().getTotalAds();
        int adIndexInPod = ad.getAdPodInfo().getAdPosition();   // index starts in 1
        int podCount = (streamManager != null && streamManager.getCuePoints() != null) ? streamManager.getCuePoints().size() : 0;
        int podIndex = (ad.getAdPodInfo().getPodIndex() >= 0) ? ad.getAdPodInfo().getPodIndex() + 1 : podCount; // index starts in 0
        boolean isBumper = ad.getAdPodInfo().isBumper();
        long adPodTimeOffset = (long) (ad.getAdPodInfo().getTimeOffset() * Consts.MILLISECONDS_MULTIPLIER);


        AdInfo adInfo = new AdInfo(adDescription, adDuration, adPlayHead,
                adTitle, isAdSkippable,
                contentType, adId,
                adSystem, adHeight,
                adWidth,
                totalAdsInPod,
                adIndexInPod,
                podIndex,
                podCount,
                isBumper,
                (adPodTimeOffset < 0) ? -1 : adPodTimeOffset);

        log.v("AdInfo: " + adInfo.toString());
        return adInfo;

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
        if (isAdDisplayed) {
            if (streamManager != null && streamManager.getAdProgressInfo() != null) {
                long adDuration = Math.max(0, (long) Math.floor(streamManager.getAdProgressInfo().getDuration()));
                if (adDuration > player.getDuration()) {
                    adDuration = player.getDuration();
                }
                return adDuration;
            } else {
                return 0;
            }
        }
        return getFakePlayerDuration();

    }

    private long getFakePlayerDuration() {
        long playerDuration = player.getDuration();
        if (playerDuration < 0 || playerDuration == C.TIME_UNSET) {
            return 0;
        }

        if (streamManager != null) {
            long val = (long) streamManager.getContentTimeForStreamTime(Math.floor(playerDuration / Consts.MILLISECONDS_MULTIPLIER)) * Consts.MILLISECONDS_MULTIPLIER;
            //log.d("XXX Duration = " + val);
            return  val;
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
        if (isAdDisplayed) {
            if (streamManager != null && streamManager.getAdProgressInfo() != null) {
                long adPosition = Math.max(0, Math.round(streamManager.getAdProgressInfo().getCurrentTime()));
                if (adPosition > player.getCurrentPosition()) {
                    adPosition = 0;
                }
                return adPosition;
            } else {
                return 0;
            }
        }
        return getFakePlayerPosition();
    }

    private long getFakePlayerPosition() {
        long playerPosition = player.getCurrentPosition();
        if (playerPosition < 0 || playerPosition == C.TIME_UNSET) {
            return 0;
        }
        if (streamManager != null) {
            long val = (long) (streamManager.getContentTimeForStreamTime(Math.floor(playerPosition / Consts.MILLISECONDS_MULTIPLIER))) * Consts.MILLISECONDS_MULTIPLIER;
            //log.d("XXX Position = " + val);
            return val;
        }

        long playerPositionSec = (long) Math.round(playerPosition / Consts.MILLISECONDS_MULTIPLIER);
        if (cuePoints != null) {
            for (CuePoint cuePoint : cuePoints) {
                if (cuePoint.isPlayed() && cuePoint.getEndTime() <= playerPositionSec) {
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
        log.d("XXX SEEKTO " + position);

        long newPositionToSeek = -1;
        if (streamManager != null) {
            if (cuePoints != null) {
                CuePoint candidateCuePoint = streamManager.getPreviousCuePointForStreamTime(streamManager.getStreamTimeForContentTime(Math.floor(position / Consts.MILLISECONDS_MULTIPLIER)));
                if (candidateCuePoint != null && !candidateCuePoint.isPlayed()) {
                    newPositionToSeek = (long) Math.floor(candidateCuePoint.getStartTime() * Consts.MILLISECONDS_MULTIPLIER);
                    log.d("XXX SEEKTO NEWPOS1 = " + newPositionToSeek);
                    player.seekTo(newPositionToSeek);
                    mSnapBackTime = streamManager.getStreamTimeForContentTime(Math.floor(position / Consts.MILLISECONDS_MULTIPLIER));
                    return;
                } else {
                    newPositionToSeek = position;
                    if (isPositionInBetweenCuePoint(position)) {
                        newPositionToSeek = getCuePointEndTime(position);
                    }
                    newPositionToSeek = (long) streamManager.getStreamTimeForContentTime(Math.floor(newPositionToSeek / Consts.MILLISECONDS_MULTIPLIER)) * Consts.MILLISECONDS_MULTIPLIER;
                    log.d("XXX SEEKTO NEWPOS2 = " + newPositionToSeek);
                    player.seekTo(newPositionToSeek);
                    mSnapBackTime = 0;
                    return;
                }
            }
            newPositionToSeek = position;
            log.d("XXX SEEKTO NEWPOS3 = " + newPositionToSeek);
            player.seekTo(newPositionToSeek);
            mSnapBackTime = 0;
            return;
        }
        log.d("XXX streamManager = null SEEKTO " + position);
//        double totalPlayedAdsDuration = 0;
//        if (cuePoints != null) {
//            for (CuePoint cuePoint : cuePoints) {
//                if (cuePoint.getEndTime() * Consts.MILLISECONDS_MULTIPLIER < position || (cuePoint.getStartTime() * Consts.MILLISECONDS_MULTIPLIER == position && cuePoint.isPlayed())) {
//                    totalPlayedAdsDuration += (cuePoint.getEndTime() - cuePoint.getStartTime());
//                }
//            }
//        }
//        long pos = (long) Math.floor(position + (totalPlayedAdsDuration * Consts.MILLISECONDS_MULTIPLIER));
//        if (isPositionInBetweenCuePoint(pos)) {
//            long newPos = getCuePointEndTime(pos);
//            pos = newPos > 0 ? newPos : pos;
//        }
        log.d("XXX SEEKTO NEWPOS4 = " + position);
        player.seekTo(position);
    }

    private boolean isPositionInBetweenCuePoint(long position) {

        if (cuePoints != null) {
            for (CuePoint cuePoint : cuePoints) {
                if (cuePoint.getStartTime() * Consts.MILLISECONDS_MULTIPLIER >= position && cuePoint.getEndTime() * Consts.MILLISECONDS_MULTIPLIER <= position) {
                    return true;
                }
            }
        }
        return false;
    }

    private long getCuePointEndTime(long position) {
        if (cuePoints != null) {
            for (CuePoint cuePoint : cuePoints) {
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

    private PKMediaConfig createMediaConfig(String url) {

        PKMediaConfig adMediaConfig = new PKMediaConfig();//.setStartPosition(daiStartPosition);
        long daiStartPosition = (mediaConfig != null && mediaConfig.getStartPosition() > 0) ? mediaConfig.getStartPosition() : 0;
        if (streamManager != null && daiStartPosition > 0) {
            daiStartPosition = (long) streamManager.getStreamTimeForContentTime(daiStartPosition);
            adMediaConfig.setStartPosition(daiStartPosition);
        }

        PKMediaEntry adMediaEntry = createMediaEntry(url);
        adMediaConfig.setMediaEntry(adMediaEntry);
        return adMediaConfig;
    }

    private PKMediaEntry createMediaEntry(String url) {

        PKMediaEntry mediaEntry = new PKMediaEntry();
        mediaEntry.setId("adId");
        if (adConfig.getVideoId() == null) {
            mediaEntry.setMediaType(PKMediaEntry.MediaEntryType.Live);
        } else {
            mediaEntry.setMediaType(PKMediaEntry.MediaEntryType.Vod);
        }

        List<PKMediaSource> mediaSources = createMediaSources(url);
        mediaEntry.setSources(mediaSources);

        return mediaEntry;
    }

    private List<PKMediaSource> createMediaSources(String url) {

        List<PKMediaSource> mediaSources = new ArrayList<>();
        PKMediaSource mediaSource = new PKMediaSource();
        mediaSource.setId("adId");
        mediaSource.setUrl(url);
        mediaSource.setMediaFormat(PKMediaFormat.valueOfUrl(url));

        if (adConfig.getLicenseUrl() !=null) {
            List<PKDrmParams> drmData = new ArrayList();
            PKDrmParams pkDrmParams = new PKDrmParams(adConfig.getLicenseUrl(), PKDrmParams.Scheme.WidevineCENC);
            drmData.add(pkDrmParams);
            mediaSource.setDrmData(drmData);
        }

        mediaSources.add(mediaSource);
        return mediaSources;
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
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.AD_BREAK_READY, com.kaltura.playkit.plugins.ads.AdEvent.Type.AD_BREAK_READY);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.TAPPED, com.kaltura.playkit.plugins.ads.AdEvent.Type.TAPPED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.ICON_TAPPED, com.kaltura.playkit.plugins.ads.AdEvent.Type.ICON_TAPPED);
        return adEventsMap;
    }
}
