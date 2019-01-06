package com.kaltura.playkit.plugins.fbinstream;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.facebook.ads.Ad;
import com.facebook.ads.AdError;
import com.facebook.ads.AdSettings;
import com.facebook.ads.AdSize;
import com.facebook.ads.InstreamVideoAdListener;
import com.facebook.ads.InstreamVideoAdView;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kaltura.playkit.BuildConfig;
import com.kaltura.playkit.MessageBus;
import com.kaltura.playkit.PKError;
import com.kaltura.playkit.PKEvent;
import com.kaltura.playkit.PKLog;
import com.kaltura.playkit.PKMediaConfig;
import com.kaltura.playkit.PKPlugin;
import com.kaltura.playkit.Player;
import com.kaltura.playkit.PlayerEngineWrapper;
import com.kaltura.playkit.PlayerEvent;
import com.kaltura.playkit.ads.FBAdsPlayerEngineWrapper;
import com.kaltura.playkit.ads.PKAdErrorType;
import com.kaltura.playkit.ads.PKAdInfo;
import com.kaltura.playkit.ads.PKAdProviderListener;
import com.kaltura.playkit.player.PlayerEngine;
import com.kaltura.playkit.plugins.ads.AdCuePoints;
import com.kaltura.playkit.plugins.ads.AdEvent;
import com.kaltura.playkit.plugins.ads.AdInfo;
import com.kaltura.playkit.plugins.ads.AdsProvider;
import com.kaltura.playkit.utils.Consts;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

public class FBInstreamPlugin extends PKPlugin implements AdsProvider {
    private static final PKLog log = PKLog.get("FBInstreamPlugin");
    private static final int KB_MULTIPLIER = 1024;

    private Player player;
    private Context context;
    private MessageBus messageBus;
    private AdInfo adInfo;
    private FBInstreamConfig adConfig;
    private PKMediaConfig mediaConfig;
    private Long playbackStartPosition;
    private PlayerEngineWrapper adsPlayerEngineWrapper;
    private LinearLayout adContainer;
    private InstreamVideoAdView adView;
    private Map<Long, FBInStreamAdBreak> fbInStreamAdBreaksMap;
    private boolean isPlayerPrepared;
    private boolean isAdDisplayed;
    private boolean isAdError;
    private boolean isAdRequested;
    private boolean isAllAdsCompleted;
    private PKAdProviderListener pkAdProviderListener;

    public static final Factory factory = new Factory() {
        @Override
        public String getName() {
            return "FBInstream";
        }

        @Override
        public PKPlugin newInstance() {
            return new FBInstreamPlugin();
        }

        @Override
        public String getVersion() {
            return BuildConfig.VERSION_NAME;
        }

        @Override
        public void warmUp(Context context) {

        }
    };
    @Override
    protected void onLoad(Player player, Object config, MessageBus messageBus, Context context) {
        log.d("onLoad");
        this.player = player;
        this.context = context;
        if (player == null) {
            log.e("Error, player instance is null.");
            return;
        }
        this.messageBus = messageBus;
        this.messageBus.listen(new PKEvent.Listener() {
            @Override
            public void onEvent(PKEvent event) {
                //log.d("Received:PlayerEvent:" + event.eventType().name() );//+ " lastAdEventReceived = " + lastAdEventReceived);
                if (event.eventType() == PlayerEvent.Type.PLAYHEAD_UPDATED) {
                    PlayerEvent.PlayheadUpdated playheadUpdated = (PlayerEvent.PlayheadUpdated)event;
                    long position = (playheadUpdated.position/ 100) * 100;
                    log.e("XXXXX position = " +  position);
                    long duration = playheadUpdated.duration;
                    if (fbInStreamAdBreaksMap.containsKey(position) && !fbInStreamAdBreaksMap.get(position).isAdBreakPlayed()) {
                        isAdRequested = true;

                        requestInStreamAdFromFB(fbInStreamAdBreaksMap.get(position));
                        if (adView != null) {
                            if (getPlayerEngine() != null && getPlayerEngine().isPlaying()) {
                                getPlayerEngine().pause();
                            }
                        }
                    }
                }
                else if (event.eventType() == PlayerEvent.Type.ENDED) {
                    if (adConfig.fbInStreamAdBreaks.get(adConfig.fbInStreamAdBreaks.size() -1).getAdBreakTime() == -1) {
                        requestInStreamAdFromFB(adConfig.fbInStreamAdBreaks.get(adConfig.fbInStreamAdBreaks.size() -1));
                    }
                    //if (fbInStreamAdBreaksMap.containsKey(player.getDuration())) {
                    //    requestInStreamAdFromFB(fbInStreamAdBreaksMap.get(player.getDuration()));
                    //}

//                    if (!isContentPrepared) {
//                        log.d("Event: ENDED ignored content is not prepared");
//                        return;
//                    }
//                    lastPlaybackPlayerState = PlayerEvent.Type.ENDED;
//                    if (adInfo != null) {
//                        log.d("Event: ENDED adInfo.getAdIndexInPod() = " + adInfo.getAdIndexInPod() + " -  adInfo.getTotalAdsInPod() = " + adInfo.getTotalAdsInPod());
//                    }
//                    boolean isLastMidRollPlayed = (adInfo == null) || !adCuePoints.hasMidRoll() || (adCuePoints.getAdCuePoints().size() >= 2 && adCuePoints.hasPostRoll() && adInfo != null && adInfo.getAdPodTimeOffset() == adCuePoints.getAdCuePoints().get(adCuePoints.getAdCuePoints().size() - 2));
//                    boolean isLastMidRollInValid = (adCuePoints.getAdCuePoints().size() >= 2 && adCuePoints.hasPostRoll() && adInfo != null && adCuePoints.getAdCuePoints().get(adCuePoints.getAdCuePoints().size() - 2) > getPlayerEngine().getDuration());
//                    log.d("contentCompleted isLastMidRollPlayed = " + isLastMidRollPlayed + " isLastMidRollInValid = " + isLastMidRollInValid);
//
//                    if (!isAdDisplayed && (!adCuePoints.hasPostRoll() || isAllAdsCompleted || isLastMidRollPlayed || isLastMidRollInValid)) {                        log.d("contentCompleted on ended");
//                        contentCompleted();
//                    } else {
//                        log.d("contentCompleted delayed");
//                        isContentEndedBeforeMidroll = true;
//                    }
                } else if (event.eventType() == PlayerEvent.Type.PLAYING) {
//                    displayContent();
//                    if (mediaConfig != null && mediaConfig.getMediaEntry() != null) {
//                        //log.d("PlayerDuration = " + getPlayerEngine().getDuration());
//                        //log.d("PlayerDuration Metadata = " + mediaConfig.getMediaEntry().getDuration());
//
//                        mediaConfig.getMediaEntry().setDuration(getPlayerEngine().getDuration());
//                        lastAdEventReceived = null;
//                    }
                } else if (event.eventType() == PlayerEvent.Type.CAN_PLAY) {
                    isPlayerPrepared = true;
                }
            }
        }, PlayerEvent.Type.CAN_PLAY, PlayerEvent.Type.ENDED, PlayerEvent.Type.PLAYING, PlayerEvent.Type.PLAYHEAD_UPDATED);
        adConfig = parseConfig(config);

        if (adConfig == null) {
            log.e("Error, adConfig instance is null.");
            return;
        }
        buildAdBreaksMap();
        initAdContentFrame();
    }

    private void buildAdBreaksMap() {
        if (fbInStreamAdBreaksMap == null) {
            fbInStreamAdBreaksMap = new HashMap<>();
        } else {
            fbInStreamAdBreaksMap.clear();
        }
        if (adConfig != null && adConfig.getAdBreakList() != null) {
            for (FBInStreamAdBreak adBreak : adConfig.getAdBreakList()) {
                fbInStreamAdBreaksMap.put(adBreak.getAdBreakTime(), adBreak);
            }
        }
        if (!fbInStreamAdBreaksMap.containsKey(0)) {
            isAdRequested = true; // incase no preroll prepare....
        }
    }

    private void initAdContentFrame() {
        adContainer = new LinearLayout(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        params.gravity = Gravity.CENTER;
        adContainer.setLayoutParams(params);
        player.getView().addView(adContainer);
    }

    @Override
    protected void onUpdateMedia(PKMediaConfig mediaConfig) {
        log.d("Start onUpdateMedia");
        isPlayerPrepared = false;
        isAdError = false;
        isAdRequested = false;
        isAdDisplayed = false;
        isAdDisplayed = false;

        this.mediaConfig = mediaConfig;
        if (mediaConfig != null) {
            playbackStartPosition = (mediaConfig.getStartPosition() != null) ? mediaConfig.getStartPosition() : null;
            log.d("mediaConfig start pos = " + playbackStartPosition);
        }
        if(adConfig != null && adConfig.getAdBreakList() != null && adConfig.getAdBreakList().size() > 0) {
            if (adConfig.getAdBreakList().get(0).getAdBreakTime() == 0) {
                isAdRequested = true;
                requestInStreamAdFromFB(adConfig.getAdBreakList().get(0));
            } else {
                preparePlayer(true);
            }
        }
    }

    private void requestInStreamAdFromFB(FBInStreamAdBreak adBreak) {
        log.e("XXXXX requestInStreamAdFromFB time = " +  adBreak.isAdBreakPlayed());
        if (adBreak.isAdBreakPlayed()) {
            return;
        }
        FBInStreamAd currentAdInAdBreak = null;
        for (FBInStreamAd adInAdBreak : adBreak.getFbInStreamAdList()) {
            if (adInAdBreak.isAdPlayed()) {
                continue;
            }
            currentAdInAdBreak = adInAdBreak;
            break;

        }
        currentAdInAdBreak.setAdPlayed(true);

        //messageBus.post(new AdEvent.AdBufferStart(-1));
        // Instantiate an InstreamVideoAdView object.
        // NOTE: the placement ID will eventually identify this as your App, you can ignore it for
        // now, while you are testing and replace it later when you have signed up.
        // While you are using this temporary code you will only get test ads and if you release
        // your code like this to the Google Play your users will not receive ads (you will get a no fill error).
        if (adContainer == null) {
            initAdContentFrame();
        }
        AdSettings.addTestDevice("294d7470-4781-4795-9493-36602bf29231");//("7450a453-4ba6-464b-85b6-6f319c7f7326");
        //AdSettings.setVideoAutoplayOnMobile(true);
        AdSettings.setDebugBuild(true);

        adView = new InstreamVideoAdView(
                context, currentAdInAdBreak.getAdPlacementId(), ///*"156903085045437_239184776817267"*/
                getAdSize());
        // set ad listener to handle events
        adView.setAdListener(new InstreamVideoAdListener() {
            @Override
            public void onError(Ad ad, AdError adError) {
                // Instream video ad failed to load
                log.e("XXX Instream video ad failed to load: " + adError.getErrorMessage());
                log.e("XXX Start onError " + adError.getErrorCode() + ":" + adError.getErrorMessage());
                isAdError = true;
                isAdDisplayed = false;
                sendError(PKAdErrorType.INTERNAL_ERROR, adError.getErrorCode() + ":" + adError.getErrorMessage(), null);
                if (isPlayerPrepared) {
                    if (adContainer != null) {
                        adContainer.setVisibility(View.GONE);
                    }
                    getPlayerEngine().play();
                }
            }

            @Override
            public void onAdLoaded(Ad ad) {
                player.getView().hideVideoSurface();
                // Instream video ad is loaded and ready to be displayed
                log.d("Instream video ad is loaded and ready to be displayed!");
                //messageBus.post(new AdEvent.AdBufferEnd(-1));
                // Race condition, load() called again before last ad was displayed
                if (adView == null || !adView.isAdLoaded()) {
                    return;
                }

                // Inflate Ad into container and show it
                adContainer.removeAllViews();
                adContainer.addView(adView);
                adContainer.setVisibility(View.VISIBLE);
                getPlayerEngine().pause();


                adView.show();
                isAdDisplayed = true;
                if (!isPlayerPrepared) {
                    preparePlayer(false);
                }
            }

            @Override
            public void onAdVideoComplete(Ad ad) {
                // Instream Video View Complete - the video has been played to the end.
                // You can use this event to continue your video playing
                log.d("XXX Instream video completed!");

                if (adBreak.isAdBreakPlayed()) {
                    isAdDisplayed = false;
                    player.getView().showVideoSurface();
                    adContainer.setVisibility(View.GONE);
                    getPlayerEngine().play();
                } else {
                    requestInStreamAdFromFB(adBreak);
                }
            }

            @Override
            public void onAdClicked(Ad ad) {
                log.d("Instream video ad clicked!");
            }

            @Override
            public void onLoggingImpression(Ad ad) {
                // Instream Video ad impression - the event will fire when the
                // video starts playing
                log.d("Instream video ad impression logged!" + ad.toString());
            }
        });
        if (getPlayerEngine().isPlaying()) {
            getPlayerEngine().pause();
        }
        adView.loadAd();
        isAdDisplayed = true;
    }

    private void preparePlayer(boolean doPlay) {
        isPlayerPrepared = true;
        if (pkAdProviderListener != null) {
            pkAdProviderListener.onAdLoadingFinished();
        }
        if (doPlay) {
            getPlayerEngine().play();
        }
    }

    private AdSize getAdSize() {
        return new AdSize(pxToDP(adContainer.getMeasuredWidth()), pxToDP(adContainer.getMeasuredHeight()));
    }

    private int pxToDP(int px) {
        return (int) (px / context.getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onUpdateConfig(Object config) {
        log.d("Start onUpdateConfig");
        adConfig = parseConfig(config);
        buildAdBreaksMap();
    }

    @Override
    protected void onApplicationPaused() {
        log.d("Start onApplicationPaused");
    }

    @Override
    protected void onApplicationResumed() {
        log.d("Start onApplicationResumed");
    }

    @Override
    protected void onDestroy() {
        log.d("XXX Start onDestroy");
        if(adView!=null) {
            adView.destroy();
        }
        if (adContainer != null) {
            adContainer.setVisibility(View.GONE);
            adContainer.removeAllViews();
            adContainer = null;
        }
    }

    @Override
    public void start() {
        log.d("Start start");
        if (!isAdRequested && fbInStreamAdBreaksMap != null && fbInStreamAdBreaksMap.containsKey(0L)) {
            requestInStreamAdFromFB(fbInStreamAdBreaksMap.get(0L));

        } else {
            preparePlayer(true);
        }
    }

    @Override
    public void destroyAdsManager() {
        log.d("XXX Start destroyAdsManager");
        if(adView!=null) {
            adView.destroy();
        }
        if (adContainer != null) {
            adContainer.setVisibility(View.GONE);
            adContainer.removeAllViews();
            adContainer = null;
        }
    }

    @Override
    public void resume() {
        log.d("Start resume");
    }

    @Override
    public void pause() {
        log.d("Start pause");
    }

    @Override
    public void contentCompleted() {
        log.d("Start contentCompleted");
    }

    @Override
    public PKAdInfo getAdInfo() {
        return null;
    }

    @Override
    public AdCuePoints getCuePoints() {
        if (fbInStreamAdBreaksMap == null) {
            return null;
        }
        SortedSet<Long> fbInStreamAdBreaksMapKeys = new TreeSet<>(fbInStreamAdBreaksMap.keySet());
        List<Long> adBreaksList = new ArrayList<>();

        for (Long adBreakTime : fbInStreamAdBreaksMapKeys) {
            if (fbInStreamAdBreaksMapKeys.size() > 0 && getPlayerEngine() != null && getPlayerEngine().getDuration() > 0 && adBreakTime == getPlayerEngine().getDuration()) {
                adBreaksList.add(-1L);
            } else {
                adBreaksList.add(adBreakTime);
            }
        }
        return new AdCuePoints(adBreaksList);
    }

    @Override
    public boolean isAdDisplayed() {
        return isAdDisplayed;
    }

    @Override
    public boolean isAdPaused() {
        return false;
    }

    @Override
    public boolean isAdRequested() {
        return isAdRequested;
    }

    @Override
    public boolean isAllAdsCompleted() {
        return isAllAdsCompleted;
    }

    @Override
    public boolean isAdError() {
        return isAdError;
    }

    @Override
    public long getDuration() {
        return Consts.TIME_UNSET;
    }

    @Override
    public long getCurrentPosition() {
        return Consts.POSITION_UNSET;
    }

    @Override
    public void setAdRequested(boolean isAdRequested) {
        this.isAdRequested = isAdRequested;
    }

    @Override
    public void setAdProviderListener(PKAdProviderListener adProviderListener) {
        pkAdProviderListener = adProviderListener;
    }

    @Override
    public void removeAdProviderListener() {
        pkAdProviderListener = null;
    }

    @Override
    public void skipAd() {
        log.d("Start skipAd");
    }


//    //////////////////FB Listeners
//    @Override
//    public void onError(Ad ad, AdError adError) {
//        log.d("Start onError " + adError.getErrorCode() + ":" + adError.getErrorMessage());
//        sendError(PKAdErrorType.INTERNAL_ERROR, adError.getErrorCode() + ":" + adError.getErrorMessage(), null);
//        if (isPlayerPrepared) {
//            if (adContainer != null) {
//                adContainer.setVisibility(View.GONE);
//            }
//            player.play();
//        }
//    }
//
//    @Override
//    public void onAdLoaded(Ad ad) {
//        log.d("Start onAdLoaded");
//    }
//
//    @Override
//    public void onAdClicked(Ad ad) {
//        log.d("Start onAdClicked");
//    }
//
//    @Override
//    public void onLoggingImpression(Ad ad) {
//        log.d("Start onLoggingImpression");
//    }

    @Override
    protected PlayerEngineWrapper getPlayerEngineWrapper() {
        if (adsPlayerEngineWrapper == null) {
            adsPlayerEngineWrapper = new FBAdsPlayerEngineWrapper(context, this);
        }
        return adsPlayerEngineWrapper;
    }

    private PlayerEngine getPlayerEngine() {
        return adsPlayerEngineWrapper.getPlayerEngine();
    }

    private static FBInstreamConfig parseConfig(Object config) {
        if (config instanceof FBInstreamConfig) {
            return ((FBInstreamConfig) config);
        } else if (config instanceof JsonObject) {
            return new Gson().fromJson(((JsonObject) config), FBInstreamConfig.class);
        }
        return null;
    }

    private void sendError(Enum errorType, String message, Throwable exception) {
        log.e("Ad Error: " + errorType.name() + " with message " + message);
        AdEvent errorEvent = new AdEvent.Error(new PKError(errorType, message, exception));
        messageBus.post(errorEvent);
    }

}
