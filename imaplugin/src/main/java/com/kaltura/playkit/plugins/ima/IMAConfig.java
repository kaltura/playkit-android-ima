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

import android.view.View;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.kaltura.playkit.PKMediaFormat;
import com.kaltura.playkit.PlayKitManager;
import com.kaltura.playkit.ads.AdTagType;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by gilad.nadav on 17/11/2016.
 */

public class IMAConfig {

    public static final int DEFAULT_AD_LOAD_TIMEOUT = 8;
    public static final int DEFAULT_CUE_POINTS_CHANGED_DELAY = 2000;
    public static final int DEFAULT_AD_LOAD_COUNT_DOWN_TICK = 250;
    public static final String AD_PLAYER_TYPE = "kaltura-vp-android";
    public static final String AD_PLAYER_VERSION = PlayKitManager.VERSION_STRING;

    public static final String AD_TAG_LANGUAGE_KEY           = "language";
    public static final String AD_TAG_TYPE_KEY               = "adTagType";
    public static final String AD_TAG_URL_KEY                = "adTagURL";
    public static final String ENABLE_BG_PLAYBACK_KEY        = "enableBackgroundPlayback";
    public static final String AD_VIDEO_BITRATE_KEY          = "videoBitrate";
    public static final String AD_VIDEO_MIME_TYPES_KEY       = "videoMimeTypes";
    //public static final String AD_TAG_TIMES_KEY            = "tagsTimes";
    public static final String AD_ATTRIBUTION_UIELEMENT_KEY  = "adAttribution";
    public static final String AD_COUNTDOWN_UIELEMENT_KEY    = "adCountDown";
    public static final String AD_LOAD_TIMEOUT_KEY           = "adLoadTimeOut";
    public static final String AD_MAX_REDIRECTS_KEY          = "adMaxRedirects";
    public static final String AD_ENABLE_DEBUG_MODE_KEY      = "enableDebugMode";
    public static final String AD_PLAYER_TYPE_KEY            = "playerType";
    public static final String AD_PLAYER_VERSION_KEY         = "playerVersion";

    private String language;
    private String adTagURL;
    private AdTagType adTagType;
    private boolean enableBackgroundPlayback;
    private int videoBitrate; // in KB
    private boolean adAttribution;
    private boolean adCountDown;
    private boolean enableDebugMode;
    private int adLoadTimeOut; // in sec
    private int maxRedirects;
    private String playerType;
    private String playerVersion;
    private List<String> videoMimeTypes;
    private transient List<View> controlsOverlayList;
    //private Map<Double,String> tagsTimes; // <AdTime,URL_to_execute>

    //View companionView;

    public IMAConfig() {
        this.language                 = "en";
        this.adTagType = AdTagType.VAST;
        this.enableBackgroundPlayback = false;
        this.videoBitrate             = -1;
        this.adAttribution            = true;
        this.adCountDown              = true;
        this.adLoadTimeOut            = DEFAULT_AD_LOAD_TIMEOUT;
        this.enableDebugMode          = false;
        this.videoMimeTypes           = new ArrayList<>();
        this.videoMimeTypes.add(PKMediaFormat.mp4.mimeType);
        this.adTagURL = null;         //=> must be set via setter
        this.playerType                = AD_PLAYER_TYPE;
        this.playerVersion             = AD_PLAYER_VERSION;

        //if (tagTimes == null) {
        //    tagTimes = new HashMap<>();
        //}
        //this.tagsTimes = tagTimes;
        //this.companionView = companionView;
    }

    public String getLanguage() {
        return language;
    }

    // Language - default is en.
    public IMAConfig setLanguage(String language) {
        this.language = language;
        return this;
    }

    public IMAConfig setAdTagType(AdTagType adTagType) {
        this.adTagType = adTagType;
        return this;
    }

    public boolean getEnableBackgroundPlayback() {
        return enableBackgroundPlayback;
    }

    // default is false
    public IMAConfig setEnableBackgroundPlayback(boolean enableBackgroundPlayback) {
        this.enableBackgroundPlayback = enableBackgroundPlayback;
        return this;
    }

    public int getVideoBitrate() {
        return videoBitrate;
    }

    // Maximum recommended bitrate. The value is in kbit/s.
    // The IMA SDK will pick media with bitrate below the specified max, or the closest bitrate if there is no media with lower bitrate found.
    // Default value, -1, means the bitrate will be selected by the IMA SDK.
    public IMAConfig setVideoBitrate(int videoBitrate) {
        this.videoBitrate = videoBitrate;
        return this;
    }

    public List<String> getVideoMimeTypes() {
        return videoMimeTypes;
    }

    public int getMaxRedirects() {
        return maxRedirects;
    }

    // select the MIME TYPE that IMA will play instead of letting it select it by itself
    // default selected MIME TYPE by plugin is MP4
    // if null or empty list is set then it will be selected automatically
    // if MIME TYPE is sent it will try playing one of the given MIME TYPE in the list i.e "video/mp4", "video/webm", "video/3gpp"
    public IMAConfig setVideoMimeTypes(List<String> videoMimeTypes) {
        this.videoMimeTypes = videoMimeTypes;
        return this;
    }

    public String getAdTagURL() {
        return adTagURL;
    }

    // set the adTag URL to be used
    public IMAConfig setAdTagURL(String adTagURL) {
        this.adTagURL = adTagURL;
        return this;
    }

    public boolean getAdAttribution() {
        return adAttribution;
    }

    public AdTagType getAdTagType() {
        return adTagType;
    }


    //ad attribution true is required for a countdown timer to be displayed
    // default is true
    public IMAConfig setAdAttribution(boolean adAttribution) {
        this.adAttribution = adAttribution;
        return this;
    }

    public boolean getAdCountDown() {
        return adCountDown;
    }

    // set if ad countdown will be shown or not.
    // default is true
    public IMAConfig setAdCountDown(boolean adCountDown) {
        this.adCountDown = adCountDown;
        return this;
    }

    public int getAdLoadTimeOut() {
        return adLoadTimeOut;
    }

    public IMAConfig setAdLoadTimeOut(int adLoadTimeOut) {
        this.adLoadTimeOut = adLoadTimeOut;
        return this;
    }

    public IMAConfig setMaxRedirects(int maxRedirects) {
        this.maxRedirects = maxRedirects;
        return this;
    }

    public IMAConfig enableDebugMode(boolean enableDebugMode) {
        this.enableDebugMode = enableDebugMode;
        return this;
    }

    public boolean isDebugMode() {
        return enableDebugMode;
    }

    public String getPlayerType() {
        return playerType;
    }

    public IMAConfig setPlayerType(String playerType) {
        this.playerType = playerType;
        return this;
    }

    public String getPlayerVersion() {
        return playerVersion;
    }

    public IMAConfig setPlayerVersion(String playerVersion) {
        this.playerVersion = playerVersion;
        return this;
    }

    public IMAConfig setControlsOverlayList(List<View> controlsOverlayList) {
        this.controlsOverlayList = controlsOverlayList;
        return this;
    }

    public IMAConfig addControlsOverlay(View controlsOverlay) {
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

    //    public Map<Double, String> getTagsTimes() {
//        return tagsTimes;
//    }
//
//    public void setTagsTimes(Map<Double, String> tagsTimes) {
//        this.tagsTimes = tagsTimes;
//    }

//    public View getCompanionView() {
//        return companionView;
//    }
//
//    public void setCompanionView(View companionView) {
//        this.companionView = companionView;
//    }
//

    public JsonObject toJSONObject() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty(AD_TAG_LANGUAGE_KEY , language);
        jsonObject.addProperty(AD_TAG_TYPE_KEY , adTagType.name());
        jsonObject.addProperty(AD_TAG_URL_KEY , adTagURL);
        jsonObject.addProperty(ENABLE_BG_PLAYBACK_KEY , enableBackgroundPlayback);
        jsonObject.addProperty(AD_VIDEO_BITRATE_KEY , videoBitrate);
        jsonObject.addProperty(AD_ATTRIBUTION_UIELEMENT_KEY , adAttribution);
        jsonObject.addProperty(AD_COUNTDOWN_UIELEMENT_KEY , adCountDown);
        jsonObject.addProperty(AD_LOAD_TIMEOUT_KEY , adLoadTimeOut);
        jsonObject.addProperty(AD_ENABLE_DEBUG_MODE_KEY , enableDebugMode);
        jsonObject.addProperty(AD_MAX_REDIRECTS_KEY , maxRedirects);
        jsonObject.addProperty(AD_PLAYER_TYPE_KEY , playerType);
        jsonObject.addProperty(AD_PLAYER_VERSION_KEY , playerVersion);


        Gson gson = new Gson();
        JsonArray jArray = new JsonArray();
        if (videoMimeTypes != null) {
            for (String mimeType : videoMimeTypes) {
                JsonPrimitive element = new JsonPrimitive(mimeType);
                jArray.add(element);
            }
        }
        jsonObject.add(AD_VIDEO_MIME_TYPES_KEY, jArray);

//        String tagsTimesJsonString = gson.toJson(tagsTimes);
//        if (tagsTimesJsonString != null && !tagsTimesJsonString.isEmpty()) {
//            JsonParser parser = new JsonParser();
//            JsonObject tagsTimesJsonObject = parser.parse(tagsTimesJsonString).getAsJsonObject();
//            jsonObject.add(AD_TAG_TIMES, tagsTimesJsonObject);
//        } else {
//            jsonObject.add(AD_TAG_TIMES, new JsonObject());
//        }

        return jsonObject;
    }
}
