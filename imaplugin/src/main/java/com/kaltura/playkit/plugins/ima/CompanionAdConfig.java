package com.kaltura.playkit.plugins.ima;

import android.view.ViewGroup;

public class CompanionAdConfig {
    private transient ViewGroup companionAdView;
    private Integer companionAdWidth;
    private Integer companionAdHeight;

    public CompanionAdConfig(ViewGroup companionAdView, Integer companionAdWidth, Integer companionAdHeight) {
        this.companionAdView = companionAdView;
        this.companionAdWidth = companionAdWidth;
        this.companionAdHeight = companionAdHeight;
    }

    public ViewGroup getCompanionAdView() {
        return companionAdView;
    }

    public Integer getCompanionAdWidth() {
        return companionAdWidth;
    }

    public Integer getCompanionAdHeight() {
        return companionAdHeight;
    }
}