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

package com.kaltura.playkit.plugins.imadai;

import android.text.TextUtils;
import android.view.View;

import com.google.ads.interactivemedia.v3.api.StreamRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by gilad.nadav on 17/11/2016.
 */

public class IMADAIConfig {

    private static final int DEFAULT_AD_LOAD_TIMEOUT = 8;
    private static final int DEFAULT_CUE_POINTS_CHANGED_DELAY = 2000;
    private static final int DEFAULT_AD_LOAD_COUNT_DOWN_TICK = 250;

    private String assetTitle;
    private String assetKey;
    private String apiKey;
    private String contentSourceId;
    private String videoId;
    private StreamRequest.StreamFormat streamFormat;
    private String licenseUrl;

    private String language;
    private boolean adAttribution;
    private boolean adCountDown;
    private boolean enableDebugMode;
    private boolean alwaysStartWithPreroll;
    private int adLoadTimeOut; // in sec
    private int maxRedirects;
    private transient List<View> controlsOverlayList;

    // Map adTagParameters = new HashMap();
    private boolean disablePersonalizedAds; // adTagParameters.put("npa", 1);
    private boolean enableAgeRestriction; // adTagParameters.put("tfua", 1);

    //should not be used needed for reflection in kaltura player
    public IMADAIConfig() {}

    private IMADAIConfig(String assetTitle,
                         String assetKey, // null for VOD
                         String contentSourceId, // null for Live
                         String videoId, // null for Live
                         String apiKey, // seems to be always null in demos
                         StreamRequest.StreamFormat streamFormat,
                         String licenseUrl) {

        this.assetKey = assetKey;
        this.contentSourceId = contentSourceId;
        this.videoId = videoId;
        this.assetTitle = assetTitle;
        this.apiKey = apiKey;
        this.streamFormat = streamFormat;
        this.licenseUrl = licenseUrl;
        this.language                  = "en";
        this.adAttribution             = true;
        this.adCountDown               = true;
        this.adLoadTimeOut             = DEFAULT_AD_LOAD_TIMEOUT;
        this.enableDebugMode           = false;
        this.alwaysStartWithPreroll   = false;
    }

    //VOD Factory
    public static IMADAIConfig getVodIMADAIConfig(String assetTitle,
                                                  String contentSourceId,
                                                  String videoId,
                                                  String apiKey,
                                                  StreamRequest.StreamFormat streamFormat,
                                                  String licenseUrl) {

        return new IMADAIConfig(assetTitle,
                null,
                contentSourceId,
                videoId ,
                apiKey,
                streamFormat,
                licenseUrl);
    }

    //Live Factory
    public static IMADAIConfig getLiveIMADAIConfig(String assetTitle,
                                                  String assetKey,
                                                  String apiKey,
                                                  StreamRequest.StreamFormat streamFormat,
                                                  String licenseUrl) {
        return new IMADAIConfig(assetTitle,
                assetKey,
                null,
                null,
                apiKey,
                streamFormat,
                licenseUrl);
    }

    public String getAssetTitle() {
        return assetTitle;
    }

    public String getAssetKey() {
        return assetKey;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getContentSourceId() {
        return contentSourceId;
    }

    public String getVideoId() {
        return videoId;
    }

    public StreamRequest.StreamFormat getStreamFormat() {
        return streamFormat;
    }

    public String getLicenseUrl() {
        return licenseUrl;
    }

    public String getLanguage() {
        return language;
    }

    // Language - default is en.
    public IMADAIConfig setLanguage(String language) {
        this.language = language;
        return this;
    }

    public int getMaxRedirects() {
        return maxRedirects;
    }

    public boolean getAdAttribution() {
        return adAttribution;
    }

    //ad attribution true is required for a countdown timer to be displayed
    // default is true
    public IMADAIConfig setAdAttribution(boolean adAttribution) {
        this.adAttribution = adAttribution;
        return this;
    }

    public boolean getAdCountDown() {
        return adCountDown;
    }

    // set if ad countdown will be shown or not.
    // default is true
    public IMADAIConfig setAdCountDown(boolean adCountDown) {
        this.adCountDown = adCountDown;
        return this;
    }

    public int getAdLoadTimeOut() {
        return adLoadTimeOut;
    }

    public IMADAIConfig setAdLoadTimeOut(int adLoadTimeOut) {
        this.adLoadTimeOut = adLoadTimeOut;
        return this;
    }

    public IMADAIConfig setMaxRedirects(int maxRedirects) {
        this.maxRedirects = maxRedirects;
        return this;
    }

    public IMADAIConfig enableDebugMode(boolean enableDebugMode) {
        this.enableDebugMode = enableDebugMode;
        return this;
    }

    public IMADAIConfig setAlwaysStartWithPreroll(boolean alwaysStartWithPreroll) {
        this.alwaysStartWithPreroll = alwaysStartWithPreroll;
        return this;
    }

    public IMADAIConfig setControlsOverlayList(List<View> controlsOverlayList) {
        this.controlsOverlayList = controlsOverlayList;
        return this;
    }

    public IMADAIConfig addControlsOverlay(View controlsOverlay) {
        if (this.controlsOverlayList == null) {
            this.controlsOverlayList = new ArrayList<>();
        }
        if (controlsOverlay != null) {
            this.controlsOverlayList.add(controlsOverlay);
        }
        return this;
    }

    public List<View> getControlsOverlayList() {
        return controlsOverlayList;
    }

    public boolean isDebugMode() {
        return enableDebugMode;
    }

    public boolean isAlwaysStartWithPreroll() {
        return alwaysStartWithPreroll;
    }

    public boolean isLiveDAI() {
        return !TextUtils.isEmpty(assetKey);
    }
}