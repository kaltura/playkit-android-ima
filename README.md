[![CI Status](https://travis-ci.org/kaltura/playkit-android-ima.svg?branch=develop)](https://travis-ci.org/kaltura/playkit-android-ima)
[![Download](https://img.shields.io/maven-central/v/com.kaltura.playkit/imaplugin?label=Download)](https://search.maven.org/artifact/com.kaltura.playkit/imaplugin)
[![License](https://img.shields.io/badge/license-AGPLv3-black.svg)](https://github.com/kaltura/playkit-android/blob/master/LICENSE)
![Android](https://img.shields.io/badge/platform-android-green.svg)

# playkit-android-ima

IMA (Interactive Media Ads) provides an easy way of integrating the multimedia Ads in your Android app. Our plugin is developed on top of Google IMA SDK.

Benefit of using our plugin with our Player is that you don't need to handle Content and Ad playback separately. It is very easy to integrate with in a very few lines on code where App needs to pass Ad url in our `IMAConfig` and our plugin will handle Ad playback. Switching between Content and Ad playback will be taken care by plugin.
 
It gives more out of the box features like Advertising Layout, Client Side Ad playback, Server Side Ad insertion or Dynamic Ad Interstion (DAI) and easy to use API mechanism. 

For Client Side Ads, App should use IMAPlugin and for DAI, IMADAIPlugin should be used.

#### Setup

For IMA and IMADAI, there is single dependency.

Add IMA plugin dependency to `build.gradle`. In android we keep all plugins aligned with same verison.

`implementation 'com.kaltura.playkit:imaplugin:x.x.x'`

**You can find latest version here:**

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

#### Update Plugin Config for the next media

Application can call `updatePluginConfig` on `Player` object with the new `IMAConfig`.

`player?.updatePluginConfig(IMAPlugin.factory.name, "new adsConfig")`

In case, if App don't want to play the Ad for the next media then App can pass empty Ad URL in `IMAConfig`. By doing so, our plugin will drop the Ad player and will play the content.

### Samples:

[Kaltura Player IMA Sample](https://github.com/kaltura/kaltura-player-android-samples/tree/develop/AdvancedSamples/IMASample)
