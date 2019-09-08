/*
 * ============================================================================
 * Copyright (C) 2017 Kaltura Inc.
 *
 * Licensed under the AGPLv3 license, unless a different license for a
 * particular library is specified in the applicable library path.
 *
 * You may obtain a copy of the License at
 * https://www.gnu.org/licenses/agpl-3.0.html
 * ============================================================================
 */

package com.kaltura.playkit.plugins.ima;

import android.content.Context;
import android.os.CountDownTimer;
import android.os.Handler;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.google.ads.interactivemedia.v3.api.Ad;
import com.google.ads.interactivemedia.v3.api.AdDisplayContainer;
import com.google.ads.interactivemedia.v3.api.AdError;
import com.google.ads.interactivemedia.v3.api.AdErrorEvent;
import com.google.ads.interactivemedia.v3.api.AdPodInfo;
import com.google.ads.interactivemedia.v3.api.AdsLoader;
import com.google.ads.interactivemedia.v3.api.AdsManager;
import com.google.ads.interactivemedia.v3.api.AdsRenderingSettings;
import com.google.ads.interactivemedia.v3.api.AdsRequest;
import com.google.ads.interactivemedia.v3.api.CompanionAdSlot;
import com.google.ads.interactivemedia.v3.api.ImaSdkFactory;
import com.google.ads.interactivemedia.v3.api.ImaSdkSettings;
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

import com.kaltura.playkit.PlayerEngineWrapper;
import com.kaltura.playkit.PlayerEvent;

import com.kaltura.playkit.ads.AdTagType;
import com.kaltura.playkit.ads.AdsPlayerEngineWrapper;
import com.kaltura.playkit.ads.PKAdErrorType;
import com.kaltura.playkit.ads.PKAdInfo;
import com.kaltura.playkit.ads.PKAdPluginType;
import com.kaltura.playkit.ads.PKAdProviderListener;

import com.kaltura.playkit.player.PlayerEngine;
import com.kaltura.playkit.player.PlayerSettings;
import com.kaltura.playkit.plugin.ima.BuildConfig;
import com.kaltura.playkit.plugins.ads.AdCuePoints;
import com.kaltura.playkit.plugins.ads.AdEvent;
import com.kaltura.playkit.plugins.ads.AdInfo;
import com.kaltura.playkit.plugins.ads.AdPositionType;
import com.kaltura.playkit.plugins.ads.AdsProvider;
import com.kaltura.playkit.utils.Consts;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by gilad.nadav on 17/11/2016.
 */

public class IMAPlugin extends PKPlugin implements AdsProvider, com.google.ads.interactivemedia.v3.api.AdEvent.AdEventListener, AdErrorEvent.AdErrorListener, ExoPlayerWithAdPlayback.OnAdPlayBackListener {
    private static final PKLog log = PKLog.get("IMAPlugin");
    private static final int KB_MULTIPLIER = 1024;

    private Player player;
    private Context context;
    private MessageBus messageBus;
    private AdInfo adInfo;
    private IMAConfig adConfig;
    private PKMediaConfig mediaConfig;
    //////////////////////

    // Container with references to video player and ad UI ViewGroup.
    private AdDisplayContainer adDisplayContainer;

    // AdsManager exposes methods to control ad playback and listen to ad events.
    private AdsManager adsManager;
    private CountDownTimer adManagerTimer;

    // Factory class for creating SDK objects.
    private ImaSdkFactory sdkFactory;

    // Ad-enabled video player.
    private ExoPlayerWithAdPlayback videoPlayerWithAdPlayback;

    // ViewGroup to render an associated companion ad into.
    private ViewGroup mCompanionViewGroup;

    // The AdsLoader instance exposes the requestAds method.
    private AdsLoader adsLoader;
    private AdsLoader.AdsLoadedListener adsLoadedListener;

    private ImaSdkSettings imaSdkSettings;
    private AdsRenderingSettings renderingSettings;
    private AdCuePoints adTagCuePoints;
    private boolean isAdDisplayed;
    private boolean isAdIsPaused;
    private boolean isAdRequested;
    private boolean isInitWaiting;
    private boolean isAllAdsCompleted;
    private boolean isAdError;
    private boolean adPlaybackCancelled;
    private boolean appIsInBackground;
    private boolean isContentEndedBeforeMidroll;
    private boolean isReleaseContentPlayerRequired;

    private boolean isContentPrepared;
    private boolean isAutoPlay;
    private boolean appInBackgroundDuringAdLoad;
    private long playerLastPositionForBG = -1;
    private PlayerEvent.Type lastPlaybackPlayerState;
    private AdEvent.Type lastAdEventReceived;
    private boolean adManagerInitDuringBackground;
    private PKAdProviderListener pkAdProviderListener;
    private Long playbackStartPosition;
    private PlayerEngineWrapper adsPlayerEngineWrapper;
    private Boolean playerPlayingBeforeAdArrived;

    private Map<com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType, AdEvent.Type> adEventsMap;

    public static final Factory factory = new Factory() {
        @Override
        public String getName() {
            return "ima";
        }

        @Override
        public PKPlugin newInstance() {
            return new IMAPlugin();
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

    ////////PKPlugin

    ///////////END PKPlugin
    @Override
    protected void onLoad(final Player player, Object config, final MessageBus messageBus, Context context) {
        log.d("onLoad");
        this.player = player;

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

        videoPlayerWithAdPlayback = new ExoPlayerWithAdPlayback(context, adConfig.getAdLoadTimeOut(), adConfig.isDebugMode());
        videoPlayerWithAdPlayback.addAdPlaybackEventListener(this);
        player.getView().addView(videoPlayerWithAdPlayback.getAdPlayerView());
        this.context = context;
        this.messageBus = messageBus;
        addListeners(player);
    }

    private void addListeners(Player player) {
        messageBus.addListener(this, PlayerEvent.ended, event -> {
            log.d("Received:PlayerEvent:" + event.eventType().name() + " lastAdEventReceived = " + lastAdEventReceived);
            AdCuePoints adCuePoints = new AdCuePoints(getAdCuePointsList());
            adCuePoints.setAdPluginName(IMAPlugin.factory.getName());
            if (!isContentPrepared && !isReleaseContentPlayerRequired) {
                log.d("Event: ENDED ignored content is not prepared");
                return;
            }
            lastPlaybackPlayerState = PlayerEvent.Type.ENDED;
            if (adInfo != null) {
                log.d("Event: ENDED adInfo.getAdIndexInPod() = " + adInfo.getAdIndexInPod() + " -  adInfo.getTotalAdsInPod() = " + adInfo.getTotalAdsInPod());
            }
            boolean isLastMidRollPlayed = (adInfo == null) || !adCuePoints.hasMidRoll() || (adCuePoints.getAdCuePoints().size() >= 2 && adCuePoints.hasPostRoll() && adInfo != null && adInfo.getAdPodTimeOffset() == adCuePoints.getAdCuePoints().get(adCuePoints.getAdCuePoints().size() - 2));
            boolean isLastMidRollInValid = (adCuePoints.getAdCuePoints().size() >= 2 && adCuePoints.hasPostRoll() && adInfo != null && getPlayerEngine() != null && adCuePoints.getAdCuePoints().get(adCuePoints.getAdCuePoints().size() - 2) > getPlayerEngine().getDuration());
            log.d("contentCompleted isLastMidRollPlayed = " + isLastMidRollPlayed + " isLastMidRollInValid = " + isLastMidRollInValid);

            if (!isAdDisplayed && (!adCuePoints.hasPostRoll() || isAllAdsCompleted || isLastMidRollPlayed || isLastMidRollInValid)) {
                log.d("contentCompleted on ended");
                contentCompleted();
            } else {
                log.d("contentCompleted delayed");
                isContentEndedBeforeMidroll = true;
            }
        });

        messageBus.addListener(this, PlayerEvent.loadedMetadata, event -> {
            log.d("Received:PlayerEvent:" + event.eventType().name() + " lastAdEventReceived = " + lastAdEventReceived);
            if (player != null && player.getView() != null) {
                player.getView().hideVideoSurface(); // make sure video surface is set to GONE
            }
        });

        messageBus.addListener(this, PlayerEvent.playing, event -> {
            log.d("Received:PlayerEvent:" + event.eventType().name() + " lastAdEventReceived = " + lastAdEventReceived);
            displayContent();
            if (mediaConfig != null && mediaConfig.getMediaEntry() != null && getPlayerEngine() != null) {
                //log.d("PlayerDuration = " + getPlayerEngine().getDuration());
                //log.d("PlayerDuration Metadata = " + mediaConfig.getMediaEntry().getDuration());
                mediaConfig.getMediaEntry().setDuration(getPlayerEngine().getDuration());
                lastAdEventReceived = null;
            }
        });
    }

    private static IMAConfig parseConfig(Object config) {
        if (config instanceof IMAConfig) {
            return ((IMAConfig) config);

        } else if (config instanceof JsonObject) {
            return new Gson().fromJson(((JsonObject) config), IMAConfig.class);
        }
        return null;
    }

    @Override
    protected void onUpdateMedia(PKMediaConfig mediaConfig) {
        log.d("Start onUpdateMedia");
        this.mediaConfig = mediaConfig;

        if (mediaConfig != null) {
            playbackStartPosition = mediaConfig.getStartPosition();
            log.d("mediaConfig start pos = " + playbackStartPosition);
        }

        if (adsManager != null) {
            adsManager.destroy();
        }
        contentCompleted();
        isContentPrepared = false;
        isAutoPlay = false;
        isAdDisplayed = false;
        isAllAdsCompleted = false;
        isContentEndedBeforeMidroll = false;
        lastPlaybackPlayerState = null;
        lastAdEventReceived = null;

        if (videoPlayerWithAdPlayback == null) {
            log.d("onUpdateMedia videoPlayerWithAdPlayback = null recreating it");
            videoPlayerWithAdPlayback = new ExoPlayerWithAdPlayback(context, adConfig.getAdLoadTimeOut(), adConfig.isDebugMode());
            videoPlayerWithAdPlayback.addAdPlaybackEventListener(this);
        }
        videoPlayerWithAdPlayback.setContentProgressProvider(player);

        clearAdsLoader();
        imaSetup();
        log.d("adtag = " + adConfig.getAdTagUrl());
        requestAdsFromIMA(adConfig.getAdTagUrl());
    }

    private AdDisplayContainer createAdDisplayContainer() {
        if (adDisplayContainer != null) {
            log.d("adDisplayContainer != null return current adDisplayContainer");
            adDisplayContainer.unregisterAllVideoControlsOverlays();
            registerControlsOverlays();
            return adDisplayContainer;
        }

        adDisplayContainer = sdkFactory.createAdDisplayContainer();

        // Set up spots for companions.
        ViewGroup adCompanionViewGroup = null;
        if (adCompanionViewGroup != null) {
            CompanionAdSlot companionAdSlot = sdkFactory.createCompanionAdSlot();
            companionAdSlot.setContainer(adCompanionViewGroup);
            companionAdSlot.setSize(728, 90);
            ArrayList<CompanionAdSlot> companionAdSlots = new ArrayList<>();
            companionAdSlots.add(companionAdSlot);
            adDisplayContainer.setCompanionSlots(companionAdSlots);
        }
        registerControlsOverlays();
        return adDisplayContainer;
    }

    private void registerControlsOverlays() {
        if (adConfig.getControlsOverlayList() != null) {
            for (View controlView : adConfig.getControlsOverlayList()) {
                adDisplayContainer.registerVideoControlsOverlay(controlView);
            }
        }
    }

    @Override
    protected void onUpdateConfig(Object config) {
        log.d("Start onUpdateConfig");

        adConfig = parseConfig(config);
        if (adConfig == null) {
            log.e("Error adConfig Incorrect or null");
            adConfig = new IMAConfig().setAdTagUrl("");
        }
    }

    private void clearAdsLoader() {
        cancelAdManagerTimer();
        if (adsLoader != null) {
            adsLoader.removeAdErrorListener(this);
            adsLoader.removeAdsLoadedListener(adsLoadedListener);
            adsLoadedListener = null;
            adsLoader = null;
        }
    }

    ////////Ads Plugin
    private void imaSetup() {
        log.d("imaSetup start");
        imaSettingSetup();
        if (sdkFactory == null) {
            sdkFactory = ImaSdkFactory.getInstance();
        }
        if (adsLoader == null) {
            adsLoader = sdkFactory.createAdsLoader(context, imaSdkSettings, createAdDisplayContainer());
            // Add listeners for when ads are loaded and for errors.
            adsLoader.addAdErrorListener(this);
            adsLoader.addAdsLoadedListener(getAdsLoadedListener());
        }
    }

    private AdsRenderingSettings getRenderingSettings() {

        renderingSettings = ImaSdkFactory.getInstance().createAdsRenderingSettings();

        if (!adConfig.isAlwaysStartWithPreroll() && playbackStartPosition != null && playbackStartPosition > 0) {
            renderingSettings.setPlayAdsAfterTime(playbackStartPosition);
        }

        if (adConfig.getVideoMimeTypes() != null && adConfig.getVideoMimeTypes().size() > 0) {
            renderingSettings.setMimeTypes(adConfig.getVideoMimeTypes());
        } else {
            List<String> defaultMimeType = new ArrayList<>();
            defaultMimeType.add(PKMediaFormat.mp4.mimeType);
            renderingSettings.setMimeTypes(defaultMimeType);
        }

        //if both are false we remove the support int ad count down in ad
        if (!adConfig.getAdAttribution() && !adConfig.getAdCountDown()) {
            renderingSettings.setUiElements(Collections.emptySet());
        }

        if (adConfig.getVideoBitrate() != -1) {
            renderingSettings.setBitrateKbps(adConfig.getVideoBitrate());
        }
        return renderingSettings;
    }

    private void imaSettingSetup() {
        if (imaSdkSettings == null) {
            imaSdkSettings = ImaSdkFactory.getInstance().createImaSdkSettings();
        }
        imaSdkSettings.setPlayerType(adConfig.getPlayerType());
        imaSdkSettings.setPlayerVersion(adConfig.getPlayerVersion());
        // Tell the SDK we want to control ad break playback.
        //imaSdkSettings.setAutoPlayAdBreaks(true);
        if (adConfig.getMaxRedirects() > 0) {
            imaSdkSettings.setMaxRedirects(adConfig.getMaxRedirects());
        }
        imaSdkSettings.setLanguage(adConfig.getLanguage());
        imaSdkSettings.setDebugMode(adConfig.isDebugMode());
    }

    @Override
    protected void onApplicationPaused() {
        log.d("onApplicationPaused");
        if (getPlayerEngine() != null) {
            playerLastPositionForBG = getPlayerEngine().getCurrentPosition();
        }
        if (videoPlayerWithAdPlayback != null) {
            videoPlayerWithAdPlayback.setIsAppInBackground(true);
        }
        appIsInBackground = true;
        if (adsManager == null) {
            cancelAdManagerTimer();
        }

        if (getPlayerEngine() != null) {
            if (!isAdDisplayed) {
                if (getPlayerEngine().isPlaying()) {
                    lastPlaybackPlayerState = PlayerEvent.Type.PLAYING;
                } else {
                    lastPlaybackPlayerState = PlayerEvent.Type.PAUSE;
                }
            } else {
                lastPlaybackPlayerState = PlayerEvent.Type.PAUSE;
            }
        }
        pause();
    }

    @Override
    protected void onApplicationResumed() {
        log.d("onApplicationResumed isAdDisplayed = " + isAdDisplayed + ", lastPlaybackPlayerState = " + lastPlaybackPlayerState + " ,lastAdEventReceived = " + lastAdEventReceived);
        long playerLastPositionTmp =  playerLastPositionForBG;
        playerLastPositionForBG = -1;
        if (videoPlayerWithAdPlayback != null) {
            videoPlayerWithAdPlayback.setIsAppInBackground(false);
        }
        appIsInBackground = false;
        if (isAdDisplayed) {
            if (adsManager == null) {
                return;
            }
            displayAd();
            log.d("onApplicationResumed ad state = " + lastAdEventReceived);

            if (appInBackgroundDuringAdLoad && lastAdEventReceived == AdEvent.Type.LOADED) {
                log.d("onApplicationResumed - appInBackgroundDuringAdLoad so start adManager");
                clearAdLoadingInBackground();
                adsManager.start();
            } else {
                if (isAdShouldAutoPlayOnResume()) {
                    log.d("onApplicationResumed resume ad playback");
                    clearAdLoadingInBackground();
                    adsManager.resume();
                    if (videoPlayerWithAdPlayback != null && videoPlayerWithAdPlayback.getVideoAdPlayer() != null) {
                        videoPlayerWithAdPlayback.getVideoAdPlayer().playAd();
                    }
                }
            }
        } else if (isAdError || (player != null && lastPlaybackPlayerState == PlayerEvent.Type.PLAYING)) {
            log.d("onApplicationResumed lastPlaybackPlayerState == PlayerEvent.Type.PLAYING ");
            isAdError = false;
            displayContent();
            if (isAdShouldAutoPlayOnResume() && getPlayerEngine() != null) {
                getPlayerEngine().play();
            }
        } else if (player != null && lastPlaybackPlayerState == PlayerEvent.Type.PAUSE && getPlayerEngine() != null) {
            log.d("onApplicationResumed lastPlaybackPlayerState == PlayerEvent.Type.PAUSE pos = " + getPlayerEngine().getCurrentPosition());
            if (playerLastPositionTmp <= 0) {
                if (isContentPrepared) {
                    log.d("content prepared!");
                    displayContent();
                    getPlayerEngine().play();
                } else {
                    log.d("content NOT prepared!");
                    clearAdLoadingInBackground();
                    if (mediaConfig != null) {
                        log.d("onApplicationResumed unprepared..... request Ad");
                        onUpdateMedia(mediaConfig);
                        if (isAutoPlay) {
                            start();
                        }
                    }
                }
            }
        } else {
            log.d("onApplicationResumed Default..... lastAdEventReceived = " + lastAdEventReceived);
            if (adsManager != null) {
                adsManager.resume();
                if (lastAdEventReceived != AdEvent.Type.ALL_ADS_COMPLETED) {
                    log.d("onApplicationResumed Default..... adsManager.resume()");
                    adsManager.resume();
                    return;
                }
            }

            clearAdLoadingInBackground();
            if (mediaConfig != null) {
                log.d("onApplicationResumed Default..... request Ad");
                onUpdateMedia(mediaConfig);
                start();
            } else {
                log.e("Error: mediaConfig == null during on resume");
            }
        }
    }

    private boolean isAdShouldAutoPlayOnResume() {
        return player.getSettings() instanceof PlayerSettings && ((PlayerSettings) player.getSettings()).isAdAutoPlayOnResume();
    }

    /**
     * Function to check if content player is not required during the AD playback
     * once the AD finishes then only content player is prepared
     */
    private boolean isReleaseContentPlayerRequired() {
        return player.getSettings() instanceof PlayerSettings && ((PlayerSettings) player.getSettings()).isForceSinglePlayerEngine();
    }

    private void clearAdLoadingInBackground() {
        appInBackgroundDuringAdLoad = false;
        adManagerInitDuringBackground = false;
    }

    @Override
    protected void onDestroy() {
        boolean adManagerInitialized = (adsManager != null); // FEM-2600
        log.d("IMA onDestroy adManagerInitialized = " + adManagerInitialized);
        destroyIMA();
        if (adDisplayContainer != null && adManagerInitialized) {
            adDisplayContainer.destroy();
        }
        adDisplayContainer = null;
        if (videoPlayerWithAdPlayback != null) {
            videoPlayerWithAdPlayback.removeAdPlaybackEventListener();
            videoPlayerWithAdPlayback.releasePlayer();
            videoPlayerWithAdPlayback = null;
        }
        if (messageBus != null) {
            messageBus.removeListeners(this);
        }
    }

    private void destroyIMA() {
        clearAdsLoader();
        resetIMA();
    }

    protected void resetIMA() {
        log.d("Start resetIMA");
        isAdError = false;
        isAdDisplayed = false;
        isAdRequested = false;
        lastPlaybackPlayerState = null;
        lastAdEventReceived = null;

        cancelAdManagerTimer();
        adTagCuePoints = null;
        adPlaybackCancelled = false;
        if (adDisplayContainer != null) {
            adDisplayContainer.setPlayer(null);
            adDisplayContainer.unregisterAllVideoControlsOverlays();
        }
        if (adsManager != null) {
            adsManager.destroy();
            adsManager = null;
        }
    }

    private void cancelAdManagerTimer() {
        if (adManagerTimer != null) {
            log.d("cancelAdManagerTimer");
            adManagerTimer.cancel();
            adManagerTimer = null;
        }
    }


    private void requestAdsFromIMA(String adTagUrl) {
        String adTagResponse = null;
        if (adConfig != null) {
            adTagResponse = adConfig.getAdTagResponse();
        }
        if (TextUtils.isEmpty(adTagUrl) && TextUtils.isEmpty(adTagResponse)) {
            log.d("AdTag is empty avoiding ad request");
            isAdRequested = true;
            displayContent();
            preparePlayer(false);
            return;
        }
        resetIMA();

        log.d("Do requestAdsFromIMA");
        if (adDisplayContainer != null && videoPlayerWithAdPlayback != null) {
            adDisplayContainer.setPlayer(videoPlayerWithAdPlayback.getVideoAdPlayer());
            adDisplayContainer.setAdContainer(videoPlayerWithAdPlayback.getAdUiContainer());
        }

        // Create the ads request.
        final AdsRequest request = sdkFactory.createAdsRequest();
        if (TextUtils.isEmpty(adTagUrl)) {
            request.setAdsResponse(adTagResponse);
        } else{
            request.setAdTagUrl(adTagUrl);
        }

        if (adConfig.getAdLoadTimeOut() > 0 && adConfig.getAdLoadTimeOut() < Consts.MILLISECONDS_MULTIPLIER && adConfig.getAdLoadTimeOut() != IMAConfig.DEFAULT_AD_LOAD_TIMEOUT) {
            request.setVastLoadTimeout(adConfig.getAdLoadTimeOut() * Consts.MILLISECONDS_MULTIPLIER);
        }
        if (videoPlayerWithAdPlayback != null) {
            request.setContentProgressProvider(videoPlayerWithAdPlayback.getContentProgressProvider());
        }

        // Request the ad. After the ad is loaded, onAdsManagerLoaded() will be called.
        adManagerTimer = getCountDownTimer();
        adsLoader.requestAds(request);
        adManagerTimer.start();
    }

    @NonNull
    private CountDownTimer getCountDownTimer() {
        return new CountDownTimer(adConfig.getAdLoadTimeOut() * Consts.MILLISECONDS_MULTIPLIER, IMAConfig.DEFAULT_AD_LOAD_COUNT_DOWN_TICK) {
            @Override
            public void onTick(long millisUntilFinished) {
                log.d("adManagerTimer.onTick, adsManager=" + adsManager);
                if (adsManager != null) {
                    log.d("cancelling adManagerTimer");
                    this.cancel();
                }
            }

            @Override
            public void onFinish() {
                log.d("adManagerTimer.onFinish, adsManager=" + adsManager);
                if (adsManager == null) {
                    if (adConfig.getAdLoadTimeOut() == 0) {
                        isAdRequested = true;
                    }
                    log.d("adsManager is null, will play content");
                    preparePlayer(false);
                    if (getPlayerEngine() != null) {
                        getPlayerEngine().play();
                    }
                    messageBus.post(new AdEvent(AdEvent.Type.AD_BREAK_IGNORED));
                    if (isAdRequested) {
                        adPlaybackCancelled = true;
                    }
                }
            }
        };
    }

    @Override
    public void start() {
        log.d("IMA start was called");

        isAutoPlay = true; // start will be called only on first time media is played programmatically
        isAdRequested = true;
        if (adsManager != null) {
            log.d("IMA start adsManager != null");
            if (appIsInBackground) {
                log.d("Start: Ad Manager Init appIsInBackground : " + adManagerInitDuringBackground);
                adManagerInitDuringBackground = true;
            } else {
                log.d("IMA adsManager.init called");
                adsManager.init(getRenderingSettings());
            }
        } else {
            log.d("IMA start isInitWaiting = true");
            isInitWaiting = true;
        }
        //videoPlayerWithAdPlayback.getVideoAdPlayer().playAd();
    }

    @Override
    public void destroyAdsManager() {
        isAdRequested = false;
        isAdDisplayed = false;
        if (adsManager == null) {
            return;
        }
        log.d("IMA Start destroyAdsManager");
        if (videoPlayerWithAdPlayback != null) {
            videoPlayerWithAdPlayback.stop();
        }
        adsManager.destroy();
        contentCompleted();
        isAdDisplayed = false;
        adPlaybackCancelled = false;
    }

    @Override
    public void resume() {
        log.d("AD Event resume mIsAdDisplayed = " + isAdDisplayed);
        if (videoPlayerWithAdPlayback != null && (isAdDisplayed || lastAdEventReceived == AdEvent.Type.PAUSED)) {
            videoPlayerWithAdPlayback.play();
        }
    }

    @Override
    public void pause() {
        log.d("AD Event pause mIsAdDisplayed = " + isAdDisplayed);
        if (isAdDisplayed || (adsManager != null && !isAllAdsCompleted /*&& !player.isPlaying()*/)) {
            log.d("AD Manager pause");
            if (videoPlayerWithAdPlayback != null) {
                videoPlayerWithAdPlayback.pause();
            }
            if (adsManager != null) {
                adsManager.pause();
            }
            isAdIsPaused = true;
        }

        if (getPlayerEngine() != null && getPlayerEngine().isPlaying()) {
            log.d("Content player pause");
            getPlayerEngine().pause();
        }
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
    public PKAdPluginType getAdPluginType() {
        return PKAdPluginType.client;
    }

    @Override
    public boolean isContentPrepared() {
        return isContentPrepared;
    }

    @Override
    public boolean isAdDisplayed() {
        //log.d("isAdDisplayed: " + mIsAdDisplayed);
        return isAdDisplayed;
    }

    @Override
    public boolean isAdPaused() {
        log.d("isAdPaused: " + isAdIsPaused);
        return isAdIsPaused;
    }

    @Override
    public boolean isAdRequested() {
        log.d("isAdRequested: " + isAdRequested);
        return isAdRequested;
    }

    @Override
    public boolean isAllAdsCompleted() {
        log.d("isAllAdsCompleted: " + isAllAdsCompleted);
        return isAllAdsCompleted;
    }

    @Override
    public boolean isAdError() {
        return isAdError;
    }

    @Override
    public long getDuration() {
        if (videoPlayerWithAdPlayback == null) {
            return Consts.TIME_UNSET;
        }

        long duration = (long) Math.ceil(videoPlayerWithAdPlayback.getVideoAdPlayer().getAdProgress().getDuration());
        //log.d("getDuration: " + duration);
        return duration;
    }

    @Override
    public long getCurrentPosition() {
        if (videoPlayerWithAdPlayback == null) {
            return Consts.POSITION_UNSET;
        }

        long currPos = (long) Math.ceil(videoPlayerWithAdPlayback.getVideoAdPlayer().getAdProgress().getCurrentTime());
        //log.d("IMA Add getCurrentPosition: " + currPos);
        messageBus.post(new AdEvent.AdPlayHeadEvent(currPos));
        return currPos;
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
    public void removeAdProviderListener() {
        if (adConfig != null && !isReleaseContentPlayerRequired) {
            log.d("removeAdProviderListener");
            pkAdProviderListener = null;
        }
    }

    @Override
    public void skipAd() {
        if (adsManager != null) {
            adsManager.skip();
        }
    }

    @Override
    public void seekTo(long position) {
        log.v("seekTo not supported");
    }

    @Override
    public AdCuePoints getCuePoints() {
        return adTagCuePoints;
    }

    private List<Long> getAdCuePointsList() {
        List<Long> adCuePoints = new ArrayList<>();
        if (adsManager != null && adsManager.getAdCuePoints() != null) {
            for (Float cuePoint : adsManager.getAdCuePoints()) {
                if (cuePoint >= 0) {
                    adCuePoints.add(cuePoint.longValue() * 1000);
                } else {
                    adCuePoints.add(cuePoint.longValue());
                }
            }
            if (adCuePoints.size() == 0) { // For Vast ad as Pre-Roll
                adCuePoints.add(0L);
            }
        }
        return adCuePoints;
    }

    private AdInfo createAdInfo(Ad ad) {

        String adDescription = ad.getDescription();
        long adDuration = (long) ad.getDuration() * Consts.MILLISECONDS_MULTIPLIER;
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
        int podCount = (adsManager != null && adsManager.getAdCuePoints() != null) ? adsManager.getAdCuePoints().size() : 0;

        int podIndex = (ad.getAdPodInfo().getPodIndex() >= 0) ? ad.getAdPodInfo().getPodIndex() + 1 : podCount; // index starts in 0
        if (podIndex == 1 && podCount == 0) { // For Vast
            podCount = 1;
        }
        boolean isBumper = ad.getAdPodInfo().isBumper();
        long adPodTimeOffset = (long) ad.getAdPodInfo().getTimeOffset() * Consts.MILLISECONDS_MULTIPLIER;

        if (!PKMediaFormat.mp4.mimeType.equals(ad.getContentType()) && adInfo != null) {
            adHeight = adInfo.getAdHeight();
            adWidth = adInfo.getAdWidth();
            mediaBitrate = adInfo.getMediaBitrate();
        }

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
                (adPodTimeOffset < 0) ? -1 : adPodTimeOffset);

        log.v("AdInfo: " + adInfo.toString());
        return adInfo;
    }

    @Override
    public void onAdError(AdErrorEvent adErrorEvent) {
        log.e("Event: onAdError" + adErrorEvent.getError().getErrorCode());
        resetFlagsOnError();

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
        if (adConfig != null) {
            messageBus.post(new AdEvent.AdRequestedEvent(!TextUtils.isEmpty(adConfig.getAdTagUrl()) ? adConfig.getAdTagUrl() : adConfig.getAdTagResponse()));
        }
        sendError(errorType, errorMessage, adException);
        preparePlayer(isAutoPlay);
    }

    private void displayAd() {
        log.d("displayAd");
        if (videoPlayerWithAdPlayback != null && videoPlayerWithAdPlayback.getAdPlayerView() != null) {
            videoPlayerWithAdPlayback.getAdPlayerView().setVisibility(View.VISIBLE);
        }
        if (player != null &&  player.getView() != null) {
            log.d("displayAd -> hideVideoSurface");
            player.getView().hideVideoSurface();
        }
    }

    private void displayContent() {
        log.d("displayContent");
        if (videoPlayerWithAdPlayback != null && videoPlayerWithAdPlayback.getAdPlayerView() != null) {
            videoPlayerWithAdPlayback.getAdPlayerView().setVisibility(View.GONE);
        }
        if (player != null && player.getView() != null) {
            log.d("displayContent -> showVideoSurface");
            player.getView().showVideoSurface();
        }
    }

    private void preparePlayer(boolean doPlay) {
        log.d("IMA prepare");
        if (!appIsInBackground) {
            if (pkAdProviderListener != null) {
                log.d("IMA prepare player");
                isContentPrepared = true;
                pkAdProviderListener.onAdLoadingFinished();

            }
            if (isContentPrepared && doPlay) {
                messageBus.addListener(this, PlayerEvent.durationChanged, new PKEvent.Listener<PlayerEvent.DurationChanged>() {
                    @Override
                    public void onEvent(PlayerEvent.DurationChanged event) {
                        log.d("IMA DURATION_CHANGE received calling play");
                        if (player != null && player.getView() != null && !IMAPlugin.this.isAdDisplayed()) {

                            log.d("In IMA prepare player onEvent isReleaseContentPlayerRequired = " + isReleaseContentPlayerRequired);
                            if (isReleaseContentPlayerRequired && player.getDuration() > 0) {
                                player.onApplicationResumed();
                            }

                            IMAPlugin.this.displayContent();
                            if (IMAPlugin.this.getPlayerEngine() != null) {
                                IMAPlugin.this.getPlayerEngine().play();
                            }
                        }
                        messageBus.removeListener(this);
                    }
                });
            }
        }
    }

    private void resetFlagsOnError() {
        isAdError = true;
        adPlaybackCancelled = true;
        isAdRequested = true;
        isAdDisplayed = false;
        cancelAdManagerTimer();
    }

    private void sendError(Enum errorType, String message, Throwable exception) {
        log.e("Ad Error: " + errorType.name() + " with message " + message);
        AdEvent errorEvent = new AdEvent.Error(new PKError(errorType, message, exception));
        messageBus.post(errorEvent);
    }

    @Override
    public void onAdEvent(com.google.ads.interactivemedia.v3.api.AdEvent adEvent) {
        if (adsManager == null) {
            log.w("WARNING, adsManager == null");
            return;
        }

        isReleaseContentPlayerRequired = isReleaseContentPlayerRequired();

        if(adEventsMap == null){
            log.e("ERROR, adEventsMap == null");
            return;
        }

        if (adEventsMap.containsKey(adEvent.getType())) {
            lastAdEventReceived = adEventsMap.get(adEvent.getType());
        }
        if (lastAdEventReceived != AdEvent.Type.AD_PROGRESS) {
            log.d("onAdEvent EventName: " + lastAdEventReceived);
        }

        if (adEvent.getAdData() != null) {
            log.i("EventData: " + adEvent.getAdData().toString());
        }

        switch (adEvent.getType()) {
            case LOADED:
                adInfo = createAdInfo(adEvent.getAd());
                if (appIsInBackground) {
                    appInBackgroundDuringAdLoad = true;
                    if (adsManager != null) {
                        log.d("LOADED call adsManager.pause()");
                        messageBus.post(new AdEvent.AdLoadedEvent(adInfo));
                        pause();
                    }
                } else {
                    if (adPlaybackCancelled) {
                        log.d("discarding ad break");
                        adsManager.discardAdBreak();
                    } else {
                        messageBus.post(new AdEvent.AdLoadedEvent(adInfo));
                        if (AdTagType.VMAP != adConfig.getAdTagType()) {
                            adsManager.start();
                        }
                    }
                }
                break;
            case CONTENT_PAUSE_REQUESTED:
                log.d("CONTENT_PAUSE_REQUESTED appIsInBackground = " + appIsInBackground);
                playerPlayingBeforeAdArrived = getPlayerEngine().isPlaying() || lastPlaybackPlayerState == PlayerEvent.Type.ENDED;
                if (getPlayerEngine() != null) {
                    getPlayerEngine().pause();
                }
                if (player != null && player.getView() != null) {
                    player.getView().hideVideoSurface();
                }
                if (appIsInBackground) {
                    log.d("CONTENT_PAUSE_REQUESTED isAdDisplayed = true");
                    isAdDisplayed = true;
                    appInBackgroundDuringAdLoad = true;
                    if (adsManager != null) {
                        pause();
                    }
                } else {
                    displayAd();
                }

                log.d("CONTENT_PAUSE_REQUESTED isReleaseContentPlayerRequired = " + isReleaseContentPlayerRequired);

                if (isReleaseContentPlayerRequired && player != null && player.getDuration() > 0) {
                    player.onApplicationPaused();
                }

                messageBus.post(new AdEvent(AdEvent.Type.CONTENT_PAUSE_REQUESTED));
                break;
            case CONTENT_RESUME_REQUESTED:
                log.d("AD REQUEST AD_CONTENT_RESUME_REQUESTED");
                if (checkIfDiscardAdRequired()) {
                    for (Long cuePoint : adTagCuePoints.getAdCuePoints()) {
                        if (cuePoint != 0 && cuePoint != -1 && ((cuePoint / Consts.MILLISECONDS_MULTIPLIER_FLOAT) < playbackStartPosition)) {
                            log.d("discardAdBreak");
                            adsManager.discardAdBreak();
                            playbackStartPosition = null; // making sure it will nu be done again.
                            break;
                        }
                    }
                }
                // AdEventType.CONTENT_RESUME_REQUESTED is fired when the ad is completed
                // and you should start playing your content.
                if (isContentEndedBeforeMidroll && !isAllAdsCompleted && getPlayerEngine() != null && getPlayerEngine().getCurrentPosition() >= getPlayerEngine().getDuration()) {
                    log.d("AD REQUEST AD_CONTENT_RESUME_REQUESTED - contentCompleted");
                    contentCompleted();
                    return;
                }

                if (getPlayerEngine().getDuration() != Consts.TIME_UNSET) {
                    log.d("CONTENT_RESUME_REQUESTED DISPLAY CONTENT");
                    displayContent();
                }

                messageBus.post(new AdEvent(AdEvent.Type.CONTENT_RESUME_REQUESTED));
                isAdDisplayed = false;
                if (videoPlayerWithAdPlayback != null) {
                    videoPlayerWithAdPlayback.resumeContentAfterAdPlayback();
                }
                if (!isContentPrepared) {
                    log.d("Content not prepared.. Preparing and calling play.");
                    if (!appIsInBackground) {
                        if (lastPlaybackPlayerState != PlayerEvent.Type.ENDED || pkAdProviderListener != null) {
                            log.d("preparePlayer and play");
                            if (pkAdProviderListener != null && lastPlaybackPlayerState == null) {
                                preparePlayer(false);
                            }
                            if (getPlayerEngine() != null) {
                                getPlayerEngine().play();
                            }
                        }
                    }
                } else if (getPlayerEngine() != null) {
                    long duration = getPlayerEngine().getDuration();
                    long position = getPlayerEngine().getCurrentPosition();
                    log.d("Content prepared.. lastPlaybackPlayerState = " + lastPlaybackPlayerState + ", time = " + position + "/" + duration);
                    if (duration < 0) {
                        preparePlayer(false);
                        getPlayerEngine().play();
                    } else if (lastPlaybackPlayerState != PlayerEvent.Type.ENDED && position <= duration) {
                        if (adInfo == null || (adInfo.getAdPositionType() != AdPositionType.POST_ROLL)) {
                            log.d("Content prepared.. Play called.");
                            getPlayerEngine().play();
                        }
                    }
                }
                adPlaybackCancelled = false;

                log.d("CONTENT_RESUME_REQUESTED isReleaseContentPlayerRequired = " + isReleaseContentPlayerRequired);

                if (isReleaseContentPlayerRequired) {
                    displayContent();
                    player.onApplicationResumed();
                    player.play();
                }

                if (isReleaseContentPlayerRequired && videoPlayerWithAdPlayback != null) {
                    videoPlayerWithAdPlayback.stop();
                }

                break;
            case ALL_ADS_COMPLETED:
                log.d("AD_ALL_ADS_COMPLETED");
                isAllAdsCompleted = true;
                messageBus.post(new AdEvent(AdEvent.Type.ALL_ADS_COMPLETED));
                displayContent();

                if (adsManager != null) {
                    log.d("AD_ALL_ADS_COMPLETED onDestroy");
                    destroyIMA();
                }
                break;
            case STARTED:
                log.d("AD STARTED isAdDisplayed = true");
                adInfo = createAdInfo(adEvent.getAd());
                if (adInfo.getAdPositionType() != AdPositionType.PRE_ROLL && !playerPlayingBeforeAdArrived) {
                    pause();
                    playerPlayingBeforeAdArrived = true;
                } else {
                    isAdIsPaused = false;
                }

                isAdDisplayed = true;
                messageBus.post(new AdEvent.AdStartedEvent(adInfo));
                if (adsManager != null && appIsInBackground) {
                    log.d("AD STARTED and pause");
                    pause();
                    return;
                }

                log.d("STARTED isReleaseContentPlayerRequired = " + isReleaseContentPlayerRequired);

                if (!isReleaseContentPlayerRequired) {
                    preparePlayer(false);
                }

                if (adTagCuePoints == null) {
                    Handler handler = new Handler();
                    handler.postDelayed(() -> {
                        log.d("AD CUEPOINTS CHANGED TRIGGERED WITH DELAY");
                        sendCuePointsUpdateEvent();

                    }, IMAConfig.DEFAULT_CUE_POINTS_CHANGED_DELAY);
                }
                break;
            case PAUSED:
                log.d("AD PAUSED isAdIsPaused = true");
                isAdIsPaused = true;
                adInfo.setAdPlayHead(getCurrentPosition() * Consts.MILLISECONDS_MULTIPLIER);
                messageBus.post(new AdEvent.AdPausedEvent(adInfo));
                break;
            case RESUMED:
                log.d("AD RESUMED");
                isAdIsPaused = false;
                adInfo.setAdPlayHead(getCurrentPosition() * Consts.MILLISECONDS_MULTIPLIER);
                messageBus.post(new AdEvent.AdResumedEvent(adInfo));
                break;
            case COMPLETED:
                log.d("AD COMPLETED");
                messageBus.post(new AdEvent(AdEvent.Type.COMPLETED));
                break;
            case FIRST_QUARTILE:
                messageBus.post(new AdEvent(AdEvent.Type.FIRST_QUARTILE));
                break;
            case MIDPOINT:
                messageBus.post(new AdEvent(AdEvent.Type.MIDPOINT));
                break;
            case THIRD_QUARTILE:
                messageBus.post(new AdEvent(AdEvent.Type.THIRD_QUARTILE));
                break;
            case SKIPPED:
                adInfo.setAdPlayHead(getCurrentPosition() * Consts.MILLISECONDS_MULTIPLIER);
                messageBus.post(new AdEvent.AdSkippedEvent(adInfo));
                isAdDisplayed = false;
                break;
            case SKIPPABLE_STATE_CHANGED:
                messageBus.post(new AdEvent(AdEvent.Type.SKIPPABLE_STATE_CHANGED));
                break;
            case CLICKED:
                isAdIsPaused = true;
                sendAdClickedEvent(adEvent);
                break;
            case TAPPED:
                messageBus.post(new AdEvent(AdEvent.Type.TAPPED));
                break;
            case ICON_TAPPED:
                messageBus.post(new AdEvent(AdEvent.Type.ICON_TAPPED));
                break;
            case AD_BREAK_READY:
                adsManager.start();
                messageBus.post(new AdEvent(AdEvent.Type.AD_BREAK_READY));
                break;
            case AD_PROGRESS:
                messageBus.post(new AdEvent(AdEvent.Type.AD_PROGRESS));
                break;
            case AD_BREAK_STARTED:
                messageBus.post(new AdEvent(AdEvent.Type.AD_BREAK_STARTED));
                break;
            case AD_BREAK_ENDED:
                messageBus.post(new AdEvent(AdEvent.Type.AD_BREAK_ENDED));
                break;
            case CUEPOINTS_CHANGED:
                sendCuePointsUpdateEvent();
                break;
            case LOG:
                isAdRequested = true;
                //for this case no AD ERROR is fired need to show view {type=adLoadError, errorCode=1009, errorMessage=The response does not contain any valid ads.}
                preparePlayer(false);
                Ad adInfo = adEvent.getAd();
                if (adInfo != null) {
                    //in case one ad in the pod fails to play we want next one to be played
                    AdPodInfo adPodInfo = adInfo.getAdPodInfo();
                    log.d("adPodInfo.getAdPosition() = " + adPodInfo.getAdPosition() + " adPodInfo.getTotalAds() = " + adPodInfo.getTotalAds());
                    if (adPodInfo.getTotalAds() > 1 && adPodInfo.getAdPosition() < adPodInfo.getTotalAds()) {
                        log.d("LOG Error but continue to next ad in pod");
                        return;
                    } else {
                        adsManager.discardAdBreak();
                    }
                }
                String error = "Non-fatal Error";
                if (adEvent.getAdData() != null) {
                    if (adEvent.getAdData().containsKey("errorMessage")) {
                        error = adEvent.getAdData().get("errorMessage");
                    }
                }

                sendError(PKAdErrorType.QUIET_LOG_ERROR, error, null);
            default:
                break;
        }
    }

    private void sendAdClickedEvent(com.google.ads.interactivemedia.v3.api.AdEvent adEvent) {
        String clickThruUrl;
        Ad ad = adEvent.getAd();
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
        messageBus.post(new AdEvent.AdClickedEvent(null));
    }

    private void sendCuePointsUpdateEvent() {
        adTagCuePoints = new AdCuePoints(getAdCuePointsList());
        adTagCuePoints.setAdPluginName(IMAPlugin.factory.getName());
        logCuePointsData();
        if (videoPlayerWithAdPlayback != null) {
            videoPlayerWithAdPlayback.setAdCuePoints(adTagCuePoints);
        }
        messageBus.post(new AdEvent.AdCuePointsUpdateEvent(adTagCuePoints));
    }

    private void logCuePointsData() {
        StringBuilder cuePointBuilder = new StringBuilder();
        if (adTagCuePoints != null && adTagCuePoints.getAdCuePoints() != null) {
            for (Long cuePoint : adTagCuePoints.getAdCuePoints()) {
                cuePointBuilder.append(cuePoint).append("|");
            }
            log.d("sendCuePointsUpdate cuePoints = " + cuePointBuilder.toString());
        }
    }

    private boolean checkIfDiscardAdRequired() {
        if (!adConfig.isAlwaysStartWithPreroll() || adInfo == null || adTagCuePoints == null || mediaConfig == null || playbackStartPosition == null) {
            return false;
        }
        log.d("getAdPositionType = " + adInfo.getAdPositionType().name());
        log.d("playbackStartPosition = " +  playbackStartPosition);
        return adInfo.getAdPositionType() == AdPositionType.PRE_ROLL && playbackStartPosition > 0;
    }

    private AdsLoader.AdsLoadedListener getAdsLoadedListener() {
        if (adsLoadedListener != null) {
            return adsLoadedListener;
        }
        adsLoadedListener = adsManagerLoadedEvent -> {
            log.d("AdsManager loaded");
            messageBus.post(new AdEvent.AdRequestedEvent(!TextUtils.isEmpty(adConfig.getAdTagUrl()) ? adConfig.getAdTagUrl() : adConfig.getAdTagResponse()));
            cancelAdManagerTimer();
            // Ads were successfully loaded, so get the AdsManager instance. AdsManager has
            // events for ad playback and errors.
            adsManager = adsManagerLoadedEvent.getAdsManager();

            //Attach event and error event listeners.

            adsManager.addAdErrorListener(IMAPlugin.this);
            adsManager.addAdEventListener(IMAPlugin.this);
            sendCuePointsUpdateEvent();

            if (isInitWaiting) {
                adsManager.init(getRenderingSettings());
                isInitWaiting = false;
            }
        };
        return adsLoadedListener;
    }

    @Override
    public void onBufferStart() {
        isAdDisplayed = true;
        if (lastAdEventReceived == AdEvent.Type.AD_BUFFER_START) {
            return;
        }
        lastAdEventReceived = AdEvent.Type.AD_BUFFER_START;
        long adPosition = videoPlayerWithAdPlayback != null ? videoPlayerWithAdPlayback.getAdPosition() : -1;
        log.d("AD onBufferStart adPosition = " + adPosition);
        messageBus.post(new AdEvent.AdBufferStart(adPosition));
    }

    @Override
    public void onBufferEnd() {
        isAdDisplayed = true;
        if (lastAdEventReceived == AdEvent.Type.AD_BUFFER_END) {
            return;
        }
        lastAdEventReceived = AdEvent.Type.AD_BUFFER_END;
        long adPosition = videoPlayerWithAdPlayback != null ? videoPlayerWithAdPlayback.getAdPosition() : -1;
        log.d("AD onBufferEnd adPosition = " + adPosition + " appIsInBackground = " + appIsInBackground);
        messageBus.post(new AdEvent.AdBufferEnd(adPosition));
        if (appIsInBackground) {
            log.d("AD onBufferEnd pausing adManager");
            pause();
        }
    }

    @Override
    public void onSourceError(Exception exoPlayerException) {
        log.d(" onSourceError " + ", message = " + exoPlayerException.getMessage());
        if (lastAdEventReceived == AdEvent.Type.AD_BUFFER_START) { // in case there is ad playback error need to remove the buffer started since buffer end is not fired
            onBufferEnd();
        }

        isAdDisplayed = false;
        isAdError = true;

        String errorMsg = "Unknown Error";
        if (exoPlayerException.getMessage() != null) {
            errorMsg = exoPlayerException.getMessage();
        } else if (exoPlayerException.getCause() != null && exoPlayerException.getCause().getMessage() != null) {
            errorMsg = exoPlayerException.getCause().getMessage();
        }
        sendError(PKAdErrorType.VIDEO_PLAY_ERROR, errorMsg, exoPlayerException);
    }

    @Override
    public void adFirstPlayStarted() {
        log.d("AD adFirstPlayStarted");
        messageBus.post(new AdEvent(AdEvent.Type.AD_FIRST_PLAY));
    }

    @Override
    public void adPlaybackInfoUpdated(int width, int height, int bitrate) {
        log.d("AD adPlaybackInfoUpdated");
        if (adInfo != null) {
            adInfo.setAdWidth(width);
            adInfo.setAdHeight(height);
            adInfo.setMediaBitrate(bitrate);
        }
        messageBus.post(new AdEvent.AdPlaybackInfoUpdated(width, height, bitrate));
    }

    private Map<com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType, AdEvent.Type> buildAdsEventMap() {
        adEventsMap = new HashMap<>();
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.ALL_ADS_COMPLETED, AdEvent.Type.ALL_ADS_COMPLETED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.CLICKED, AdEvent.Type.CLICKED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.COMPLETED, AdEvent.Type.COMPLETED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.CUEPOINTS_CHANGED, AdEvent.Type.CUEPOINTS_CHANGED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.CONTENT_PAUSE_REQUESTED, AdEvent.Type.CONTENT_PAUSE_REQUESTED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.CONTENT_RESUME_REQUESTED, AdEvent.Type.CONTENT_RESUME_REQUESTED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.FIRST_QUARTILE, AdEvent.Type.FIRST_QUARTILE);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.MIDPOINT, AdEvent.Type.MIDPOINT);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.THIRD_QUARTILE, AdEvent.Type.THIRD_QUARTILE);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.PAUSED, AdEvent.Type.PAUSED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.RESUMED, AdEvent.Type.RESUMED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.STARTED, AdEvent.Type.STARTED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.SKIPPED, AdEvent.Type.SKIPPED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.LOADED, AdEvent.Type.LOADED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.AD_PROGRESS, AdEvent.Type.AD_PROGRESS);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.AD_BREAK_STARTED, AdEvent.Type.AD_BREAK_STARTED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.AD_BREAK_ENDED, AdEvent.Type.AD_BREAK_ENDED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.AD_BREAK_READY, AdEvent.Type.AD_BREAK_READY);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.TAPPED, AdEvent.Type.TAPPED);
        adEventsMap.put(com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.ICON_TAPPED, AdEvent.Type.ICON_TAPPED);
        return adEventsMap;
    }

    @Override
    protected PlayerEngineWrapper getPlayerEngineWrapper() {
        if (adsPlayerEngineWrapper == null) {
            adsPlayerEngineWrapper = new AdsPlayerEngineWrapper(context, this);
        }
        return adsPlayerEngineWrapper;
    }

    private PlayerEngine getPlayerEngine() {
        return adsPlayerEngineWrapper.getPlayerEngine();
    }
}
