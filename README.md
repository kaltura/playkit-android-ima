[![CI Status](https://travis-ci.org/kaltura/playkit-android-ima.svg?branch=develop)](https://travis-ci.org/kaltura/playkit-android-ima)
[![Download](https://img.shields.io/maven-central/v/com.kaltura.playkit/imaplugin?label=Download)](https://search.maven.org/artifact/com.kaltura.playkit/imaplugin)
[![License](https://img.shields.io/badge/license-AGPLv3-black.svg)](https://github.com/kaltura/playkit-android/blob/master/LICENSE)
![Android](https://img.shields.io/badge/platform-android-green.svg)

# playkit-android-ima

IMA (Interactive Media Ads) provides an easy way of integrating the multimedia Ads in your Android app. Our plugin is developed on top of Google IMA SDK.

Benefit of using our plugin with our Player is that you don't need to handle Content and Ad playback separately. It is very easy to integrate with in a very few lines on code where App needs to pass Ad url in our `IMAConfig` and our plugin will handle Ad playback. Switching between Content and Ad playback will be taken care by plugin.
 
It gives more out of the box features like Advertising Layout, Client Side Ad playback, Server Side Ad insertion or Dynamic Ad Insertion (DAI) and easy to use API mechanism. 

For Client Side Ads, App should use IMAPlugin and for DAI, IMADAIPlugin should be used.

### Setup

For IMA and IMADAI, there is single dependency.

Add IMA plugin dependency to `build.gradle`. In android, we keep all plugins aligned with same version.

`implementation 'com.kaltura.playkit:imaplugin:x.x.x'`

**You can find the latest version here:**

[Latest Release](https://github.com/kaltura/playkit-android-ima/releases)

##### Create IMA Plugin Config:

```kotlin
val playerInitOptions = PlayerInitOptions(PARTNER_ID)
playerInitOptions.setAutoPlay(true)
...
...
// IMA Configuration
val pkPluginConfigs = PKPluginConfigs()
val adsConfig = IMAConfig().setAdTagUrl("AD_URL").setVideoMimeTypes("MIME_TYPES")
pkPluginConfigs.setPluginConfig(IMAPlugin.factory.name, adsConfig)

playerInitOptions.setPluginConfigs(pkPluginConfigs)

```

##### Create IMADAI Plugin Config:

```kotlin
val playerInitOptions = PlayerInitOptions(PARTNER_ID)
playerInitOptions.setAutoPlay(true)
...
...
// IMADAI Live Configuration

val pkPluginConfigs = PKPluginConfigs()
val assetTitle = "Title"
            val assetKey = "Asset_Key"
            val apiKey: String? = "KEY"
            val streamFormat = StreamRequest.StreamFormat.HLS OR DASH
            val licenseUrl: String? = "License"
            
            return IMADAIConfig.getLiveIMADAIConfig(assetTitle,
                    assetKey,
                    apiKey,
                    streamFormat, licenseUrl)
                    
// IMADAI VOD Configuration

val assetTitle = "BBB-widevine"
            val apiKey: String? = "KEY"
            val contentSourceId = "Source_Id"
            val videoId = "video_id"
            val streamFormat = StreamRequest.StreamFormat.DASH OR HLS
            val licenseUrl = "License"
            
            return IMADAIConfig.getVodIMADAIConfig(assetTitle,
                    contentSourceId,
                    videoId,
                    apiKey,
                    streamFormat,
                    licenseUrl)
                    
pkPluginConfigs.setPluginConfig(IMADAIPlugin.factory.name, assetTitle)

playerInitOptions.setPluginConfigs(pkPluginConfigs)

```

Once the plugin config is passed to the init options then `PlayerInitOptions` needs to be passed to the player creation.

Example for Kaltura OTT Player,

`player = KalturaOttPlayer.create(this@MainActivity, playerInitOptions)`

### Update Plugin Config for the next media

Application can call `updatePluginConfig` on `Player` object with the new `IMAConfig`.

`player?.updatePluginConfig(IMAPlugin.factory.name, "new adsConfig")`

In case, if App don't want to play the Ad for the next media then App can pass empty Ad URL in `IMAConfig`. By doing so, our plugin will drop the Ad player and will play the content.

### IMA and IMADAI Config features

> NOTE: Few features are not configurable for IMADAI config.

##### `setAdTagUrl(String adTagUrl)`

Pass the VMAP or VAST Ad tag URL for the Ad Playback.

##### `setAdTagResponse(String adTagResponse)`

Pass the VAST or VMAP Ad response instead of making a request via an ad tag URL.

##### `setLanguage(String language)` 

Allows you to specify the language to be used to localize ads and the Ad player UI controls. The supported codes can be found in the [locale codes](https://developers.google.com/interactive-media-ads/docs/sdks/android/client-side/localization?hl=en#locale-codes) and are closely related to the two-letter ISO 639-1 language codes. If invalid or unsupported, the language will default to "en" for English.

##### `setAdTagType(AdTagType adTagType)` 

Default is `AdTagType.VAST` and support for `AdTagType.VMAP` as well. `AdTagType.VPAID` Ads are not supported on Android. Check the Support guide [here](https://developers.google.com/interactive-media-ads/docs/sdks/android/client-side/compatibility).

##### `setEnableBackgroundPlayback(boolean enableBackgroundPlayback)` 

This feature is Noop (No operation). Default is `false`.

##### `setVideoBitrate(int videoBitrate)`

Maximum recommended bitrate. The value is in "kbit/s". IMA SDK will pick media with bitrate below the specified max, or the closest bitrate if there is no media with lower bitrate found. Default value is -1, means the bitrate will be selected by the IMA SDK.

##### `setAdAttribution(boolean adAttribution)` and `setAdCountDown(boolean adCountDown)`

Both are `true` by default. 

Sets the ad UI elements to be rendered by the IMA SDK. If both are false we remove the support int ad count down in ad.

Ad attribution `true` is required for a countdown timer to be displayed and set if Ad countdown will be shown or not.

All values in the list are instances of UiElement. Some elements may be required to be displayed, or unable to be displayed for a given ad (for instance, the ad UI may be customizable for DFP direct sold ads, but not for AdSense ads). In these cases, some modifications to the uiElements list may have no effect for specific ads.

##### `setAdLoadTimeOut(int adLoadTimeOut)`

Ad load timeout in seconds. Default is '8' seconds.

##### `enableDebugMode(boolean enableDebugMode)`

Default is `false`. This enables the App to see the underlying IMA SDK logs.

##### `setAlwaysStartWithPreroll(boolean alwaysStartWithPreroll)`

Default is `false`. If App has given `startPosition` to the content player which will start the player from the given position then inspite of having media starting from greater than 0th position where pre-roll is played; Pre-roll will be played.

##### `setEnableFocusSkipButton(boolean enableFocusSkipButton)`

Set whether to focus on the skip button when the skippable ad can be skipped on Android TV. Default is `true`. This is a no-op on non-Android TV devices.

##### `setEnableCustomTabs(boolean enableCustomTabs)`

Notifies the IMA whether to launch the click-through URL using Custom Tabs feature. Default is `false`. 
One simple example is 'Learn More' button on Ad. User can click on it. So if this feature is enables then URL will open in custom tab and performace
wise which is faster than Chrome or Custom WebView.
More information about Chrome Custom Tabs can be found [here](https://android-developers.googleblog.com/2015/09/chrome-custom-tabs-smooth-transition.html)

##### `setVideoMimeTypes(List<String> videoMimeTypes)`

Select the MIME TYPE that IMA will play instead of letting it select it by itself. Default selected MIME TYPE by plugin is "MP4". If null or empty list is set then it will be selected automatically. If MIME TYPE is sent it will try playing one of the given MIME TYPE in the list i.e "video/mp4", "video/webm", "video/3gpp", "application/x-mpegURL", "application/dash+xml".

This only refers to the mime types of videos to be selected for linear ads.

##### `setPlayerType(String playerType)`

Optional field. It sets the partner Player type. This setting should be used to specify the name of the player being integrated with the SDK. Player type greater than 20 characters will be truncated. The player type specified should be short and unique. This is an optional setting used to improve SDK usability by tracking player types.

##### `setPlayerVersion(String playerVersion)`

Optional field. It sets the partner Player version. This setting should be used to specify the name of the player version being integrated with the SDK. Player version greater than 20 characters will be truncated. The player version specified should be short and unique. This is an optional setting used to improve SDK usability by tracking player types.

##### `setContentDuration(float contentDuration)`

`DEFAULT_CONTENT_DURATION = -3`

Specifies the duration of the content in seconds to be shown.This optional parameter is used by AdX requests. It is recommended for AdX users.

Default value is `DEFAULT_CONTENT_DURATION`. By default postroll preloading is disabled. `DEFAULT_CONTENT_DURATION` is the fixed value given by IMA. 

If App will set any other value instead of `DEFAULT_CONTENT_DURATION` then
postroll will be preloaded.

##### `setSessionId(String sessionId)`

Session ID is a temporary random ID. It is used exclusively for frequency capping. A session ID must be a UUID, or an empty string if app doesn't want the SDK to use capping (Useful for changeMedia).

### Setup Companion Ads

To setup companion Ads, App can create a `ViewGroup` as below in the Activity's layout.

```xml
<LinearLayout
                android:layout_width="300dp"
                android:layout_height="150dp"
                android:layout_gravity="center_horizontal"
                android:gravity="center"
                android:textAlignment="center"
                android:id="@+id/companionAdSlot"
                android:background="#00000000">

                <TextView
                    android:id="@+id/companionPlaceholder"
                    android:text="companionPlaceholder"
                    android:textAlignment="center"
                    android:gravity="center_horizontal"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:textSize="16dp"/>
            </LinearLayout>
```

Then after getting it's layout Id. App can using the following API,

`setCompanionAdConfig(ViewGroup companionAdView, Integer companionAdWidth, Integer companionAdHeight)`

Height and Width of the companion Ad can be passed to the API along with the ViewGroup.

In order to clear the Companion Ad for the next media, App should not send anything with the new `IMAConfig` object.

### Setup FriendlyObstructions

Registers a view that overlays or obstructs this container as "friendly" for viewability measurement purposes. Please check [OM SDK](https://developers.google.com/interactive-media-ads/docs/sdks/android/client-side/omsdk?hl=en) what is and what is not allowed to be registered.

API to register FriendlyObstructions,

`addFriendlyObstruction(PKFriendlyObstruction friendlyObstruction)`

`PKFriendlyObstruction` object can be created as below,

`PKFriendlyObstruction(View friendlyView, FriendlyObstructionPurpose friendlyObstructionPurpose, String detailedReason)`

`FriendlyObstructionPurpose` is an Enum having values `VIDEO_CONTROLS`, `CLOSE_AD`, `NOT_VISIBLE` and `OTHER`.

By default the details reason is `DETAILED_REASON_UNAVAILABLE = "detailedReasonUnavailable"`

### Advanced Features

#### AdLayout 

With Ad Layout config app can create its own AdBreak timeline using your VAST tags. AdBreak can be set as Pre, Mid and Post rolls and each AdBreak can contain a single VAST tag or multiple tags, either as a AdPod, but also as a Waterfall.

This feature is only supported in [Kaltura-Player](https://github.com/kaltura/kaltura-player-android#kaltura-player-for-android).

>[See the migration guide to Kaltura-Player, if you are using Playkit](https://kaltura.github.io/playkit/guide/android/migration/KalturaPlayer.html)

For more details about AdLayout, please check [here](https://kaltura.github.io/playkit/guide/android/core/advertising-layout.html).

#### Ad autoplay feature

`setAdAutoPlayOnResume(boolean adAutoPlayOnResume)` 

This feature is hooked with `PlayerSettings`. `PlayerSettings` talks about more of the content playback rather than Ad playback.

But if App is using this API then if App goes to background and comes to foreground then Ad will autoplay. Default is `true`. In order to reverse the defined behavior, App can use this API. 

### [IMA Sample Ad Tags](https://developers.google.com/interactive-media-ads/docs/sdks/html5/client-side/tags)

### [Kaltura Ads Manager Ad Tags](https://kaltura.github.io/playkit-admanager-samples/)

### [IMA Video Suite inspector](https://developers.google.com/interactive-media-ads/docs/sdks/html5/client-side/vastinspector)


### Samples:

[Kaltura Player IMA Sample](https://github.com/kaltura/kaltura-player-android-samples/tree/develop/AdvancedSamples/IMASample)

[Kaltura Player IMADAI Sample](https://github.com/kaltura/kaltura-player-android-samples/tree/develop/AdvancedSamples/IMADAISample)
