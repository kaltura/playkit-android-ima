package com.kaltura.playkit.plugins.ima;

import android.view.View;

import com.google.ads.interactivemedia.v3.api.FriendlyObstruction;
import com.google.ads.interactivemedia.v3.api.FriendlyObstructionPurpose;

public class PKFriendlyObstruction implements FriendlyObstruction {

    private View friendlyView;
    private FriendlyObstructionPurpose friendlyObstructionPurpose;
    private String detailedReason;

    public PKFriendlyObstruction(View friendlyView, FriendlyObstructionPurpose friendlyObstructionPurpose) {

        this.friendlyView = friendlyView;
        this.friendlyObstructionPurpose = friendlyObstructionPurpose != null ? friendlyObstructionPurpose : FriendlyObstructionPurpose.OTHER;
        this.detailedReason = "";
    }

    public PKFriendlyObstruction(View friendlyView, FriendlyObstructionPurpose friendlyObstructionPurpose, String detailedReason) {
        this.friendlyView = friendlyView;
        this.friendlyObstructionPurpose = friendlyObstructionPurpose != null ? friendlyObstructionPurpose : FriendlyObstructionPurpose.OTHER;
        this.detailedReason = detailedReason != null ? detailedReason : "";
    }

    @Override
    public View getView() {
        return friendlyView;
    }

    @Override
    public FriendlyObstructionPurpose getPurpose() {
        return friendlyObstructionPurpose;
    }

    @Override
    public String getDetailedReason() {
        return detailedReason;
    }
}
