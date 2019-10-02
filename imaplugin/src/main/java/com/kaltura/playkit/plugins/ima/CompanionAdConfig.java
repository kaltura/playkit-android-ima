package com.kaltura.playkit.plugins.ima;

import android.view.ViewGroup;

public class CompanionAdConfig {
    private transient ViewGroup companionAdView;
    private int companionAdWidth = 728;
    private int companionAdHeight = 90;

    public CompanionAdConfig(ViewGroup companionAdView, int companionAdWidth, int companionAdHeight) {
        this.companionAdView = companionAdView;
        this.companionAdWidth = companionAdWidth;
        this.companionAdHeight = companionAdHeight;
    }

    public ViewGroup getCompanionAdView() {
        return companionAdView;
    }

    public int getCompanionAdWidth() {
        return companionAdWidth;
    }

    public int getCompanionAdHeight() {
        return companionAdHeight;
    }
}
