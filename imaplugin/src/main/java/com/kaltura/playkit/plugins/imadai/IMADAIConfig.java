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

import com.google.ads.interactivemedia.v3.api.StreamRequest;
import com.kaltura.playkit.PlayKitManager;
import com.kaltura.playkit.plugins.ima.PKFriendlyObstruction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by gilad.nadav on 17/11/2016.
 */

public class IMADAIConfig {

    private static final int DEFAULT_AD_LOAD_TIMEOUT = 8;
    private static final int DEFAULT_CUE_POINTS_CHANGED_DELAY = 2000;
    private static final int DEFAULT_AD_LOAD_COUNT_DOWN_TICK = 250;

    private static final String AD_PLAYER_TYPE = "kaltura-vp-android";
    private static final String AD_PLAYER_VERSION = PlayKitManager.VERSION_STRING;

    private String assetTitle;
    private String assetKey;
    private String apiKey;
    private String contentSourceId;
    private String videoId;
    private StreamRequest.StreamFormat streamFormat;
    private String licenseUrl;
    private Map<String, String> adTagParams;
    private String streamActivityMonitorId;
    private String authToken;

    private String language;
    private boolean adAttribution;
    private boolean adCountDown;
    private boolean enableDebugMode;
    private boolean alwaysStartWithPreroll;
    private boolean enableFocusSkipButton;
    private int adLoadTimeOut; // in sec
    private int maxRedirects;
    private String playerType;
    private String playerVersion;
    private String sessionId;
    private transient List<PKFriendlyObstruction> friendlyObstructions;

    // Map adTagParameters = new HashMap();
    private boolean disablePersonalizedAds; // adTagParameters.put("npa", 1);
    private boolean enableAgeRestriction; // adTagParameters.put("tfua", 1);

    //should not be used needed for reflection in kaltura player
    public IMADAIConfig() {
        enableFocusSkipButton = true;
    }

    private IMADAIConfig(String assetTitle,
                         String assetKey, // null for VOD
                         String contentSourceId, // null for Live
                         String videoId, // null for Live
                         String apiKey, // seems to be always null in demos
                         StreamRequest.StreamFormat streamFormat, // DASH or HLS
                         String licenseUrl) {

        this.assetKey                         = assetKey;
        this.contentSourceId                  = contentSourceId;
        this.videoId                          = videoId;
        this.assetTitle                       = assetTitle;
        this.apiKey                           = apiKey;
        this.streamFormat                     = streamFormat;
        this.licenseUrl                       = licenseUrl;
        this.language                         = "en";
        this.adAttribution                    = true;
        this.adCountDown                      = true;
        this.adLoadTimeOut                    = DEFAULT_AD_LOAD_TIMEOUT;
        this.enableDebugMode                  = false;
        this.alwaysStartWithPreroll           = false;
        this.enableFocusSkipButton            = true;
        this.playerType                       = AD_PLAYER_TYPE;
        this.playerVersion                    = AD_PLAYER_VERSION;
        this.sessionId                        = null;
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

    public Map<String, String> getAdTagParams() {
        return adTagParams;
    }

    public String getStreamActivityMonitorId() {
        return streamActivityMonitorId;
    }

    public String getAuthToken() {
        return authToken;
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

    public IMADAIConfig setEnableFocusSkipButton(boolean enableFocusSkipButton) {
        this.enableFocusSkipButton = enableFocusSkipButton;
        return this;
    }

    public IMADAIConfig setPlayerType(String playerType) {
        this.playerType = playerType;
        return this;
    }

    public IMADAIConfig setPlayerVersion(String playerVersion) {
        this.playerVersion = playerVersion;
        return this;
    }

    /**
     * Session ID is a temporary random ID. It is used exclusively for frequency capping.
     * A session ID must be a UUID, or an empty string if app doesn't want the SDK to use capping (Useful for changeMedia).
     *
     * @param sessionId id for this session
     */
    public IMADAIConfig setSessionId(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    public IMADAIConfig setFriendlyObstructions(List<PKFriendlyObstruction> friendlyObstructions) {

        if (friendlyObstructions != null) {
            if (this.friendlyObstructions == null) {
                this.friendlyObstructions = new ArrayList<>();
            }

            for (PKFriendlyObstruction pkFriendlyObstruction : friendlyObstructions) {
                if (pkFriendlyObstruction != null && pkFriendlyObstruction.getView() != null) {
                    this.friendlyObstructions.add(pkFriendlyObstruction);
                }
            }
        }
        return this;
    }

    /**
     * Sets the overridable ad tag parameters on the stream request.
     * @see <a href="https://support.google.com/admanager/answer/7320899">Supply targeting parameters to your stream</a>
     * provides more information.
     * You can use the dai-ot and dai-ov parameters for stream variant preference
     * @see <a href="https://support.google.com/admanager/answer/7320899">See Override Stream Variant Parameters </a>
     * for more information.
     *
     * @param adTagParams optional ad tag params
     * @return IMADAIConfig
     */
    public IMADAIConfig setAdTagParams(Map<String, String> adTagParams) {
        this.adTagParams = adTagParams;
        return this;
    }

    /**
     * Sets the ID to be used to debug the stream with the stream activity monitor.
     * This is used to provide a convenient way to allow publishers to find a stream
     * log in the stream activity monitor tool.
     *
     * @param streamActivityMonitorId monitorId
     * @return IMADAIConfig
     */
    public IMADAIConfig setStreamActivityMonitorId(String streamActivityMonitorId) {
        this.streamActivityMonitorId = streamActivityMonitorId;
        return this;
    }

    /**
     * Sets the stream request authorization token. Used in place of the API key for stricter content authorization.
     * The publisher can control individual content streams authorizations based on this token.
     * @param authToken authToken
     * @return IMADAIConfig
     */
    public IMADAIConfig setAuthToken(String authToken) {
        this.authToken = authToken;
        return this;
    }

    public IMADAIConfig addFriendlyObstruction(PKFriendlyObstruction friendlyObstruction) {
        if (friendlyObstruction != null && friendlyObstruction.getView() != null) {
            if (this.friendlyObstructions == null) {
                this.friendlyObstructions = new ArrayList<>();
            }
            this.friendlyObstructions.add(friendlyObstruction);
        }
        return this;
    }

    public List<PKFriendlyObstruction> getFriendlyObstructions() {
        return friendlyObstructions;
    }

    public boolean isDebugMode() {
        return enableDebugMode;
    }

    public boolean isAlwaysStartWithPreroll() {
        return alwaysStartWithPreroll;
    }

    public boolean isEnableFocusSkipButton() {
        return enableFocusSkipButton;
    }

    public String getPlayerType() {
        return playerType;
    }

    public String getPlayerVersion() {
        return playerVersion;
    }

    public String getSessionId() {
        return sessionId;
    }

    public boolean isLiveDAI() {
        return !TextUtils.isEmpty(assetKey);
    }

    public boolean isVodDAI() {
        return !TextUtils.isEmpty(contentSourceId) || !TextUtils.isEmpty(videoId);
    }

    public boolean isEmpty() {
        return !this.isLiveDAI() && !this.isVodDAI();
    }
}