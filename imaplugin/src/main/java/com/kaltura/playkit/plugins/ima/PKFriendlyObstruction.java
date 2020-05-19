package com.kaltura.playkit.plugins.ima;

import android.view.View;

import com.google.ads.interactivemedia.v3.api.FriendlyObstruction;
import com.google.ads.interactivemedia.v3.api.FriendlyObstructionPurpose;

public class PKFriendlyObstruction {

    private FriendlyObstruction friendlyObstruction;

    public PKFriendlyObstruction(View friendlyView, FriendlyObstructionPurpose friendlyObstructionPurpose) {
        friendlyObstruction = new FriendlyObstruction() {
            @Override
            public View getView() {
                return friendlyView;
            }

            @Override
            public FriendlyObstructionPurpose getPurpose() {
                return friendlyObstructionPurpose != null ? friendlyObstructionPurpose : FriendlyObstructionPurpose.OTHER;
            }

            @Override
            public String getDetailedReason() {
                return "";
            }
        };
    }

    public PKFriendlyObstruction(View friendlyView, FriendlyObstructionPurpose friendlyObstructionPurpose, String detailedReason) {
        friendlyObstruction = new FriendlyObstruction() {
            @Override
            public View getView() {
                return friendlyView;
            }

            @Override
            public FriendlyObstructionPurpose getPurpose() {
                return friendlyObstructionPurpose != null ? friendlyObstructionPurpose : FriendlyObstructionPurpose.OTHER;
            }

            @Override
            public String getDetailedReason() {
                return detailedReason != null ? detailedReason : "";
            }
        };
    }

    public FriendlyObstruction getFriendlyObstruction() {
        return friendlyObstruction;
    }
}
