package com.kaltura.playkit.plugins.fbinstream;


import com.facebook.ads.internal.protocol.AdPlacementType;
import com.kaltura.playkit.plugins.ads.AdPositionType;

public class FBInStreamAdBreak {

    private String adPlacementId;
    private AdPlacementType adPlacementType;
    private long adTime;
    private AdPositionType adPositionType;
    private boolean adPlayed;

    public FBInStreamAdBreak(String adPlacementId, AdPlacementType adPlacementType, long adTime, AdPositionType adPositionType) {
        this.adPlacementId = adPlacementId;
        this.adPlacementType = adPlacementType; //AdPlacementType.INSTREAM
        this.adTime = adTime;
        this.adPositionType = adPositionType;
    }

    public String getAdPlacementId() {
        return adPlacementId;
    }

    public void setAdPlacementId(String adPlacementId) {
        this.adPlacementId = adPlacementId;
    }

    public AdPlacementType getAdPlacementType() {
        return adPlacementType;
    }

    public void setAdPlacementType(AdPlacementType adPlacementType) {
        this.adPlacementType = adPlacementType;
    }

    public long getAdTime() {
        return adTime;
    }

    public void setAdTime(long adTime) {
        this.adTime = adTime;
    }

    public AdPositionType getAdPositionType() {
        return adPositionType;
    }

    public void setAdPositionType(AdPositionType adPositionType) {
        this.adPositionType = adPositionType;
    }

    public boolean isAdPlayed() {
        return adPlayed;
    }

    public void setAdPlayed(boolean adPlayed) {
        this.adPlayed = adPlayed;
    }



    //AdBreak adBreak1 = new AdBreak("156903085045437_239184776817267", 0, AdBreak.AdBreakType.PREROLL, AdResource.AdProvider.FACEBOOK, AdBreak.AdFormat.FB);
}
