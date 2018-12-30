package com.kaltura.playkit.plugins.fbinstream;

import java.util.ArrayList;
import java.util.List;

public class FBInstreamConfig {

    List<FBInStreamAdBreak> adBreaks;

    public FBInstreamConfig(List<FBInStreamAdBreak> adBreaks) {
        this.adBreaks = adBreaks;
    }

    public List<FBInStreamAdBreak> getAdBreaks() {
        return adBreaks != null ? adBreaks : new ArrayList<>();
    }

    public void setAdBreaks(List<FBInStreamAdBreak> adBreaks) {
        this.adBreaks = adBreaks;
    }
}
