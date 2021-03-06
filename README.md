[![CircleCI](https://circleci.com/gh/willowtreeapps/conductor-mobile.svg?style=svg)](https://circleci.com/gh/willowtreeapps/conductor-mobile)
[![Maven Central](https://img.shields.io/maven-central/v/com.willowtreeapps/conductor-mobile.svg)](https://search.maven.org/search?q=a:conductor-mobile)

Conductor Mobile
================

Conductor Mobile is a port of the [Conductor](https://github.com/conductor-framework/conductor) Web Framework for iOS and Android, instead of wrapping around [Selenium](http://www.seleniumhq.org/) it wraps the [Appium Framework](http://appium.io/). Thanks to [@ddavison]

# Getting Started
```xml
<dependencies>
    <dependency>
        <groupId>com.willowtreeapps</groupId>
        <artifactId>conductor-mobile</artifactId>
        <version>0.21.2</version>
    </dependency>
</dependencies>
```
Create a Java Class, and extend it from `com.joss.conductor.mobile.Locomotive`

# Goals
Same as the original conductor, the primary goals of this project are to...
- Take advantage of method chaining, to create a fluent interface.
- Abstract the programmer from bloated scripts resulting from using too many css selectors, and too much code.
- Provide a quick and easy framework in Selenium 2 using Java, to get started writing scripts.
- Provide a free to use framework for any starting enterprise, or individual programmer.
- Automatic detection of connected iOS devices

# Configuration
Conductor can be configured using a [yaml](https://en.wikipedia.org/wiki/YAML) file. By default, Conductor looks for a "config.yaml" at the root of embedded resources.

The file has 3 sections: `current configuration`, `defaults`, and `schemes`.

## General Configuration
The general configuration only has two properties: `platformName` (which must be either IOS or ANDROID) and optionally `currentSchemes`, which will be discussed later

## Defaults
The defaults section contains both general defaults and defaults per platform. It looks like this:
```yaml
defaults:
  implicitWaitTime: 3
  appiumRequestTimeout: 10
  screenshotOnFail: false
  autoGrantPermissions: true

  ios:
    platformVersion: 13.0
    deviceName: iPhone 11 Pro

  android:
    appiumRequestTimeout: 15
    platformVersion: 10.0
```
Platforms can override defaults just be re-specifying them in the platform section

## Schemes

Sometimes it is useful to specify properties under specific circumstances, and this is what "schemes" are for.  Schemes override properties in the configuration *in the order they are specified in the `currentSchemes` section of the configuration file.*

You might, for example, have a scheme for running on a specific device or specific remote testing tool. It's a pain to have to re-specify these so, Conductor makes this easy with schemes. Some example schemes:

```yaml
ios_sauce_labs:
  hub: http://saucelabs-hub
  appFile: sauce-storage:app.zip
  automationName: XCUITest
  platformVersion: 13.0
  deviceName: iPhone 11 Pro

shorter_timeouts:
  appiumRequestTimeout: 1
  implicitWaitTime: 2
```
You can see a variety of example configuration files in the unit tests for conductor [here](src/test/resources/test_yaml)

# Supported Properties
## General (Common)
- `platformName` = {string: ANDROID or IOS} (note all capps)
- `deviceName` = {name of the device}
- `appPackageName` = {android app package name or iOS bundle id}
- `appFile` = {path to apk, ipa, or app}
- `platformVersion` = {string: version iOS or Android}
- `udid` = {string: iOS device's UDID or Android's device name from ADB, or auto to use the first connected device}
- `noReset` = {boolean: true or false}
- `fullReset` = {boolean: true or false}
- `appiumRequestTimeout` = {int: default equals 5 seconds per call}
- `implicitWaitTime` = {int: default equals 5}
- `screenshotsOnFail` = {boolean: true or false}
- `autoGrantPermissions` = {boolean: true or false}
- `automationName` = {string: i.e. uiautomator2 or xcuitest}


## General (less common, usually not required)
- `language` = {string: }
- `locale` = {string: }
- `orientation` = {string: portrait or landscape}
- `hub` = {string: url (local /cloud URL) - If local hub - value will be given in defaults section, if cloud specific then value given in scheme section'}

## Android specific
- `avd` = {string: the name of the avd to boot}
- `avdArgs` = {string: arguments to pass in to specify behaviors of the emulator i.e. "-no-window -no-boot-anim -no-snapshot -skin 480x800".  See [Android Developer Docs](https://developer.android.com/studio/run/emulator-commandline#common) for more arguments.}
- `appActivity` = {string: the name of the activity that starts the app}
- `appWaitActivity` = {string: the name of the activity to wait for}
- `intentCategory` = {string: i.e. android.intent.category.LEANBACK_LAUNCHER}

## iOS specific
- `xcodeSigningId` = {string: the signing id to use to load the app on a device, usually "iPhone Developer"}
- `xcodeOrgId` = {string: the org id to use to sign the app}


# Inline Actions
- ```click(By)```
- ```setText(By, text)```
- ```getText(By)```
- ```isPresent(By)```
- ```isPresentWait(By)```
- ```getAttribute(By, attribute)```
- ```swipe(SwipeElementDirection, By)```
- etc.

# Inline validations
This is one of the most important features that I want to _*accentuate*_.
- ```validateText```
- ```validateTextNot```
- ```validatePresent```
- ```validateNotPresent```
- ```validateTextPresent```
- ```validateTextNotPresent```

All of these methods are able to be called in-line, and fluently without ever having to break your tests.

# Platform Identifier Annotation

Initiate in BasePage:

    public BasePage(Locomotive driver) {
        this.driver = driver;
        PageFactory.initElements(new AppiumFieldDecorator(getDriver().getAppiumDriver()), this);
    }
    
Use in Pages:    
    
    import io.appium.java_client.pagefactory.AndroidFindBy;
    import io.appium.java_client.pagefactory.iOSXCUITFindBy;
    
        @iOSXCUITFindBy(id = "accounts_tab")
        @AndroidFindBy(id = "tab_accounts")
        private MobileElement ACCOUNT_TAB;

# Pull requests
To contribute, fork our project on GitHub, then submit a pull request to our master branch. (If you're a collaborator, just branch off of master)

# Release process
We follow [trunk-based development](https://trunkbaseddevelopment.com/).
 
 1. Create a branch off of `master` and set the version # and remove `-SNAPSHOT`. This can be done in the feature-branch you are working on.
 2. If approved merge branch into `master`. This will trigger the upload to Maven.
 3. Tag the merge commit in the master branch. `git tag 0.20.0 cdd9fad`
 4. Push Tag to Remote. `git push origin 0.20.0`
 5. Create Release in Github and add Release Notes, select the pushed up tag.
 6. Go to Sonatype, `Close` then `Release` the artifact.
 7. Releasing will move the components into the release repository of OSSRH where it will be synced to the Central Repositories

# Use with Sauce Labs
*Note: it is recommended you create a scheme for sauce labs testing when possible*

 1. get an API token for your sauce labs account
 2. upload the .app file as a zip to temporary [sauce storage](https://wiki.saucelabs.com/display/DOCS/Uploading+Mobile+Applications+to+Sauce+Storage+for+Testing)
 3. set the hub property to connect to saucelabs `https://<login-name>:<API-token>@ondemand.saucelabs.com:443/wd/hub`
 4. set the appFile property to `sauce-storage:<zip-filename>.zip`
 5. run the test with a scheme set
 
 ```
sauce-android-mock:
  hub: https://${SAUCE_USERNAME}:${SAUCE_ACCESS_KEY}@ondemand.saucelabs.com:443/wd/hub
  sauceUserName: ${SAUCE_USERNAME}
  sauceAccessKey: ${SAUCE_ACCESS_KEY}
  appFile: sauce-storage:${APP_FILENAME}
```

 # Dependency Updates
 
 We're using [Gradle Versions Plugin](https://github.com/ben-manes/gradle-versions-plugin) to help find out-of-date dependencies. 
  
 To use: `./gradlew dependencyUpdates` 

License
-------

    Copyright 2016 Jossay Jacobo

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
