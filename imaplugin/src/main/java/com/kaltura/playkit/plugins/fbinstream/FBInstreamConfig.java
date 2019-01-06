package com.kaltura.playkit.plugins.fbinstream;

import java.util.ArrayList;
import java.util.List;

public class FBInstreamConfig {

    List<FBInStreamAdBreak> fbInStreamAdBreaks;

    public FBInstreamConfig(List<FBInStreamAdBreak> fbInStreamAdBreaks) {
        this.fbInStreamAdBreaks = fbInStreamAdBreaks;
    }

    public List<FBInStreamAdBreak> getAdBreakList() {
        return fbInStreamAdBreaks != null ? fbInStreamAdBreaks : new ArrayList<>();
    }

    public void setAdBreakList(List<FBInStreamAdBreak> fbInStreamAdBreaks) {
        this.fbInStreamAdBreaks = fbInStreamAdBreaks;
    }

    public FBInStreamAdBreak getAdBreakByTime(long adBreaktime) {
        for (FBInStreamAdBreak fbInStreamAdBreak : fbInStreamAdBreaks) {
            if (fbInStreamAdBreak.getAdBreakTime() == adBreaktime) {
                return fbInStreamAdBreak;
            }
        }
        return null;
    }
}
