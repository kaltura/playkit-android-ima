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
import com.google.gson.JsonObject;
import com.kaltura.playkit.plugins.ima.IMAConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by gilad.nadav on 17/11/2016.
 */

public class IMADAIConfig {

    private static final int DEFAULT_AD_LOAD_TIMEOUT = 8;
    private static final int DEFAULT_CUE_POINTS_CHANGED_DELAY = 2000;
    private static final int DEFAULT_AD_LOAD_COUNT_DOWN_TICK = 250;

    private static final String AD_TAG_LANGUAGE     = "language";
    //public static final String AD_VIDEO_BITRATE    = "videoBitrate";
    private static final String AD_ATTRIBUTION_UIELEMENT = "adAttribution";
    private static final String AD_COUNTDOWN_UIELEMENT   = "adCountDown";
    private static final String AD_LOAD_TIMEOUT          = "adLoadTimeOut";
    private static final String AD_MAX_REDIRECTS         = "adMaxRedirects";
    private static final String AD_ENABLE_DEBUG_MODE     = "enableDebugMode";
    private static final String AD_ALWAYES_START_WITH_PREROLL = "alwaysStartWithPreroll";

    private String assetTitle;
    private String assetKey;
    private String apiKey;
    private String contentSourceId;
    private String videoId;
    private StreamRequest.StreamFormat streamFormat;
    private String licenseUrl;

    private String language;
    //private int videoBitrate; // in KB
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
    // request.setAdTagParameters(adTagParameters);


    public IMADAIConfig(String assetTitle,
                        String assetKey, // null for VOD
                        String contentSourceId, // null for Live
                        String apiKey, // seems to be always null in demos
                        String videoId, // null for Live
                        StreamRequest.StreamFormat streamFormat,
                        String licenseUrl) {
        this.assetTitle = assetTitle;
        this.assetKey = assetKey;
        this.apiKey = apiKey;
        this.contentSourceId = contentSourceId;
        this.videoId = videoId;
        this.streamFormat = streamFormat;
        this.licenseUrl = licenseUrl;

        this.language                  = "en";
        //this.videoBitrate              = -1;
        this.adAttribution             = true;
        this.adCountDown               = true;
        this.adLoadTimeOut             = DEFAULT_AD_LOAD_TIMEOUT;
        this.enableDebugMode           = false;
        this.alwaysStartWithPreroll   = false;
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

//    public int getVideoBitrate() {
//        return videoBitrate;
//    }

    // Maximum recommended bitrate. The value is in kbit/s.
    // The IMA SDK will pick media with bitrate below the specified max, or the closest bitrate if there is no media with lower bitrate found.
    // Default value, -1, means the bitrate will be selected by the IMA SDK.
//    public IMADAIConfig setVideoBitrate(int videoBitrate) {
//        this.videoBitrate = videoBitrate;
//        return this;
//    }

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

//    public JsonObject toJSONObject() {
//        JsonObject jsonObject = new JsonObject();
//        jsonObject.addProperty(AD_TAG_LANGUAGE, language);
//        //jsonObject.addProperty(AD_VIDEO_BITRATE, videoBitrate);
//        jsonObject.addProperty(AD_ATTRIBUTION_UIELEMENT, adAttribution);
//        jsonObject.addProperty(AD_COUNTDOWN_UIELEMENT, adCountDown);
//        jsonObject.addProperty(AD_LOAD_TIMEOUT, adLoadTimeOut);
//        jsonObject.addProperty(AD_ENABLE_DEBUG_MODE, enableDebugMode);
//        jsonObject.addProperty(AD_MAX_REDIRECTS, maxRedirects);
//        return jsonObject;
//    }
}