package com.joss.conductor.mobile;

import com.google.common.base.Strings;
import com.joss.conductor.mobile.util.PageUtil;
import com.saucelabs.common.SauceOnDemandAuthentication;
import com.saucelabs.common.SauceOnDemandSessionIdProvider;
import com.saucelabs.testng.SauceOnDemandAuthenticationProvider;

import io.appium.java_client.AppiumDriver;
import io.appium.java_client.MobileElement;
import io.appium.java_client.TouchAction;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.AuthenticatesByFinger;
import io.appium.java_client.ios.IOSDriver;
import io.appium.java_client.ios.PerformsTouchID;
import io.appium.java_client.remote.AndroidMobileCapabilityType;
import io.appium.java_client.remote.MobileCapabilityType;
import io.appium.java_client.service.local.AppiumServiceBuilder;
import io.appium.java_client.service.local.flags.GeneralServerFlag;

import org.apache.commons.lang3.StringUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.junit.rules.TestRule;
import org.openqa.selenium.*;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.pmw.tinylog.Logger;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Listeners;

import java.lang.reflect.Method;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.appium.java_client.touch.WaitOptions.waitOptions;
import static io.appium.java_client.touch.offset.ElementOption.element;
import static io.appium.java_client.touch.offset.PointOption.point;
import static java.time.Duration.ofMillis;
import static org.openqa.selenium.support.ui.ExpectedConditions.*;

/**
 * Created on 8/10/16.
 */
@Listeners({TestListener.class, SauceLabsListener.class})
public class Locomotive extends Watchman implements Conductor<Locomotive>, SauceOnDemandSessionIdProvider, SauceOnDemandAuthenticationProvider {

    private static final float SWIPE_DISTANCE = 0.25f;
    private static final float SWIPE_DISTANCE_LONG = 0.50f;
    private static final float SWIPE_DISTANCE_SUPER_LONG = 1.0f;
    private static final int SWIPE_DURATION_MILLIS = 2000;

    /**
     * ThreadLocal variable which contains the  {@link AppiumDriver} instance which is used to perform interactions with.
     */
    private ThreadLocal<AppiumDriver> driver = new ThreadLocal<>();

    /**
     * ThreadLocal variable which contains the Sauce Job Id.
     */
    private ThreadLocal<String> sessionId = new ThreadLocal<>();

    /**
     * ThreadLocal variable which contains the Shutdown Hook instance for this Thread's Driver.
     * <p>
     * Registered just before Device creation, de-registered after 'quit' is called.
     */
    private ThreadLocal<Thread> shutdownHook = new ThreadLocal<>();

    public ConductorConfig configuration;
    private Map<String, String> vars = new HashMap<>();
    private String testMethodName;

    @Rule
    public TestRule watchman = this;

    @Rule
    public TestName testNameRule = new TestName();

    public Locomotive getLocomotive() {
        return this;
    }

    public Locomotive() {
    }

    public AppiumDriver getAppiumDriver() {
        return driver.get();
    }

    public Locomotive setAppiumDriver(AppiumDriver d) {
        registerShutdownHook();
        driver.set(d);
        return this;
    }

    /**
     * Creates the shutdownHook, or returns an existing copy
     */
    private Thread getShutdownHook() {
        if (shutdownHook.get() == null) {
            shutdownHook.set(new Thread(() -> {
                try {
                    if (getAppiumDriver() != null) {
                        getAppiumDriver().quit();
                    }
                } catch (org.openqa.selenium.NoSuchSessionException ignored) {
                } // Don't care if session already closed
            }));
        }
        return shutdownHook.get();
    }

    /**
     * Registers the shutdownHook with the runtime.
     * <p>
     * Ignoring exceptions on registration; They mean the VM is already shutting down and it's too late.
     */
    private void registerShutdownHook() {
        // Register a hook to always close this session.  Only works/needed once session is created.
        try {
            Runtime.getRuntime().addShutdownHook(getShutdownHook());
        } catch (IllegalStateException ignored) {
            // Thrown if a hook is added while shutting down; We don't care
        }
    }

    /**
     * De-registers the shutdownHook. This allows the GC to remove the thread and avoids double-quitting.
     * <p>
     * Silently swallows exceptions if the VM is already shutting down; it's too late.
     */
    private void deregisterShutdownHook() {
        if (shutdownHook.get() != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(getShutdownHook());
            } catch (IllegalStateException ignored) {
                // VM already shutting down; Irrelevant
            }
        }
    }

    public Locomotive setConfiguration(ConductorConfig configuration) {
        this.configuration = configuration;
        return this;
    }

    @Before
    public void init() {
        // For jUnit get the method name from a test rule.
        this.testMethodName = testNameRule.getMethodName();
        initialize();
    }

    @BeforeMethod(alwaysRun = true)
    public void init(Method method) {
        // For testNG get the method name from an injected dependency.
        this.testMethodName = method.getName();
        initialize();
    }

    @AfterMethod(alwaysRun = true)
    public void quit() {
        if (getAppiumDriver() != null) {
            try {
                getAppiumDriver().quit();
            } catch (WebDriverException exception) {
                Logger.error(exception, "WebDriverException occurred during quit method");
            }
        }
        deregisterShutdownHook();
    }

    private void initialize() {
        if (this.configuration == null) {
            this.configuration = new ConductorConfig();
        }

        startAppiumSession(1);

        // Set session ID after driver has been initialized
        String id = getAppiumDriver().getSessionId().toString();
        sessionId.set(id);
    }

    void startAppiumSession(int startCounter) {
        if ((getAppiumDriver() != null) && (getAppiumDriver().getSessionId() != null)) {
            // session is already active -> terminal condition
            return;
        }

        if (startCounter > configuration.getStartSessionRetries()) {
            // maximum amount of retries reached
            throw new WebDriverException(
                    "Could not start Appium Session with capabilities: " + getCapabilities(configuration).toString());
        }

        // start a new session
        try {
            URL hub = configuration.getHub();
            DesiredCapabilities capabilities = onCapabilitiesCreated(getCapabilities(configuration));

            AppiumServiceBuilder builder = new AppiumServiceBuilder()
                    .withArgument(GeneralServerFlag.LOG_LEVEL, "warn");

            switch (configuration.getPlatformName()) {
                case ANDROID:
                    setAppiumDriver(configuration.isLocal()
                            ? new AndroidDriver(builder, capabilities)
                            : new AndroidDriver(hub, capabilities));
                    break;
                case IOS:
                    setAppiumDriver(configuration.isLocal()
                            ? new IOSDriver(builder, capabilities)
                            : new IOSDriver(hub, capabilities));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown platform: " + configuration.getPlatformName());
            }
        } catch (WebDriverException exception) {
            Logger.error(exception, "Received an exception while trying to start Appium session");
        }

        // recursive call to retry if necessary
        startAppiumSession(startCounter + 1);
    }

    protected DesiredCapabilities onCapabilitiesCreated(DesiredCapabilities desiredCapabilities) {
        return desiredCapabilities;
    }

    private DesiredCapabilities getCapabilities(ConductorConfig configuration) {
        DesiredCapabilities capabilities;
        switch (configuration.getPlatformName()) {
            case ANDROID:
            case IOS:
                capabilities = buildCapabilities(configuration);
                break;
            default:
                throw new IllegalArgumentException("Unknown platform: " + configuration.getPlatformName());
        }

        // If deviceName is empty replace it with something
        if (capabilities.getCapability(MobileCapabilityType.DEVICE_NAME).toString().isEmpty()) {
            capabilities.setCapability(MobileCapabilityType.DEVICE_NAME, "Empty Device Name");
        }

        return capabilities;
    }

    public DesiredCapabilities buildCapabilities(ConductorConfig config) {
        DesiredCapabilities capabilities = new DesiredCapabilities();
        capabilities.setCapability(MobileCapabilityType.UDID, config.getUdid());
        capabilities.setCapability(MobileCapabilityType.DEVICE_NAME, config.getDeviceName());
        capabilities.setCapability(MobileCapabilityType.APP, config.getFullAppPath());
        capabilities.setCapability(MobileCapabilityType.ORIENTATION, config.getOrientation());
        capabilities.setCapability("autoGrantPermissions", config.isAutoGrantPermissions());
        capabilities.setCapability(MobileCapabilityType.FULL_RESET, config.isFullReset());
        capabilities.setCapability(MobileCapabilityType.NO_RESET, config.getNoReset());
        capabilities.setCapability(MobileCapabilityType.PLATFORM_VERSION, config.getPlatformVersion());
        capabilities.setCapability("xcodeSigningId", config.getXcodeSigningId());
        capabilities.setCapability("xcodeOrgId", config.getXcodeOrgId());
        capabilities.setCapability(AndroidMobileCapabilityType.AVD, config.getAvd());
        capabilities.setCapability(AndroidMobileCapabilityType.AVD_ARGS, config.getAvdArgs());
        capabilities.setCapability(AndroidMobileCapabilityType.APP_ACTIVITY, config.getAppActivity());
        capabilities.setCapability(AndroidMobileCapabilityType.APP_WAIT_ACTIVITY, config.getAppWaitActivity());
        capabilities.setCapability(AndroidMobileCapabilityType.INTENT_CATEGORY, config.getIntentCategory());
        capabilities.setCapability("sauceUserName", config.getSauceUserName());
        capabilities.setCapability("sauceAccessKey", config.getSauceAccessKey());
        capabilities.setCapability("waitForQuiescence", config.isWaitForQuiescence());
        capabilities.setCapability(MobileCapabilityType.NEW_COMMAND_TIMEOUT, config.getNewCommandTimeout());
        capabilities.setCapability("idleTimeout", config.getIdleTimeout());
        capabilities.setCapability("simpleIsVisibleCheck", config.isSimpleIsVisibleCheck());
        capabilities.setCapability(MobileCapabilityType.APPIUM_VERSION, config.getAppiumVersion());

        if (StringUtils.isNotEmpty(config.getAutomationName())) {
            capabilities.setCapability(MobileCapabilityType.AUTOMATION_NAME, config.getAutomationName());
        }

        // Set custom capabilities if there are any
        for (String key : config.getCustomCapabilities().keySet()) {
            capabilities.setCapability(key, config.getCustomCapabilities().get(key));
        }

        return capabilities;
    }

    public Locomotive click(String id) {
        return click(PageUtil.buildBy(configuration, id));
    }

    public Locomotive click(By by) {
        TouchAction touchAction = new TouchAction(getAppiumDriver());
        touchAction.tap(element(getAppiumDriver().findElement(by)));
        touchAction.perform();
        return this;
    }

    public Locomotive click(MobileElement mobileElement) {
        try {
            TouchAction touchAction = new TouchAction(getAppiumDriver());
            touchAction.tap(element(mobileElement));
            touchAction.perform();
            return this;
        } catch (NoSuchElementException noSuchElementException) {
            throw new NoSuchElementException("Error: Unable to find element: " + mobileElement.toString() + " in order to click the element", noSuchElementException);
        }
    }

    public Locomotive click(WebElement webElement) {
        try {
            TouchAction touchAction = new TouchAction(getAppiumDriver());
            touchAction.tap(element(webElement));
            touchAction.perform();
            return this;
        } catch (NoSuchElementException noSuchElementException) {
            throw new NoSuchElementException("Error: Unable to find element: " + webElement.toString() + " in order to click the element", noSuchElementException);
        }
    }

    public Locomotive setText(String id, String text) {
        return setText(PageUtil.buildBy(configuration, id), text);
    }

    public Locomotive setText(By by, String text) {
        return setText(getAppiumDriver().findElement(by), text);
    }

    public Locomotive setText(MobileElement mobileElement, String text) {
        try {
            mobileElement.setValue(text);
            return this;
        } catch (NoSuchElementException noSuchElementException) {
            throw new NoSuchElementException("Error: Unable to find element: " + mobileElement.toString() + " in order to set the text of the element", noSuchElementException);
        }
    }

    public Locomotive setText(WebElement webElement, String text) {
        try {
            webElement.sendKeys(text);
            return this;
        } catch (NoSuchElementException noSuchElementException) {
            throw new NoSuchElementException("Error: Unable to find element: " + webElement.toString() + " in order to set the text of the element", noSuchElementException);
        }
    }

    public boolean isPresent(String id) {
        return isPresent(PageUtil.buildBy(configuration, id));
    }

    public boolean isPresent(By by) {
        return getAppiumDriver().findElements(by).size() > 0;
    }

    public boolean isPresent(MobileElement mobileElement) {
        return mobileElement.isDisplayed();
    }

    public boolean isPresentWait(String id) {
        return isPresentWait(PageUtil.buildBy(configuration, id), 5);
    }

    public boolean isPresentWait(By by) {
        return isPresentWait(by, 5);
    }

    public boolean isPresentWait(MobileElement element) {
        return isPresentWait(element, 5);
    }

    public boolean isPresentWait(String id, long timeOutInSeconds) {
        return isPresentWait(PageUtil.buildBy(configuration, id), timeOutInSeconds);
    }

    public boolean isPresentWait(By by, long timeOutInSeconds) {
        try {
            waitForCondition(elementToBeClickable(by), timeOutInSeconds, 200);
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    public boolean isPresentWait(MobileElement mobileElement, long timeOutInSeconds) {
        try {
            waitForCondition(elementToBeClickable(mobileElement), timeOutInSeconds, 200);
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    public String getText(String id) {
        return getText(PageUtil.buildBy(configuration, id));
    }

    public String getText(By by) {
        return getText(getAppiumDriver().findElement(by));
    }

    public String getText(WebElement webElement) {
        try {
            return webElement.getText();
        } catch (NoSuchElementException noSuchElementException) {
            throw new NoSuchElementException("Error: Unable to find element: " + webElement.toString() + " in order to get the text of the element", noSuchElementException);
        }
    }

    public String getText(MobileElement mobileElement) {
        try {
            return mobileElement.getText();
        } catch (NoSuchElementException noSuchElementException) {
            throw new NoSuchElementException("Error: Unable to find element: " + mobileElement.toString() + " in order to get the text of the element", noSuchElementException);
        }
    }

    public String getAttribute(String id, String attribute) {
        return getAttribute(PageUtil.buildBy(configuration, id), attribute);
    }

    public String getAttribute(By by, String attribute) {
        return getAppiumDriver().findElement(by).getAttribute(attribute);
    }

    public String getAttribute(MobileElement mobileElement, String attribute) {
        return getAttribute((WebElement) mobileElement, attribute);
    }

    public String getAttribute(WebElement webElement, String attribute) {
        try {
            return webElement.getAttribute(attribute);
        } catch (NoSuchElementException noSuchElementException) {
            throw new NoSuchElementException("Error: Unable to find element: " + webElement.toString() + " in order to get the attribute of the element", noSuchElementException);
        }
    }

    //region Generic swipes
    public Locomotive swipeCenter(SwipeElementDirection direction) {
        return swipe(direction, /*element=*/null, SWIPE_DISTANCE, 0);
    }

    public Locomotive swipeCenter(SwipeElementDirection direction, int swipeDurationInMillis) {
        return swipe(direction, /*element=*/null, SWIPE_DISTANCE, swipeDurationInMillis);
    }

    public Locomotive swipeCenter(SwipeElementDirection direction, int swipeDurationInMillis, int numberOfSwipes) {
        return repetitiveCenterSwipe(direction, swipeDurationInMillis, numberOfSwipes, SWIPE_DISTANCE);
    }

    public Locomotive swipe(SwipeElementDirection direction, String id, float percentage) {
        return swipe(direction, PageUtil.buildBy(configuration, id), percentage);
    }

    public Locomotive swipe(SwipeElementDirection direction, By by, float percentage) {
        return swipe(direction, (MobileElement) getAppiumDriver().findElement(by), percentage);
    }

    public Locomotive swipe(SwipeElementDirection direction, MobileElement mobileElement, float percentage) {
        return swipe(direction, (WebElement) mobileElement, percentage);
    }

    public Locomotive swipe(SwipeElementDirection direction, WebElement webElement, float percentage) {
        return swipe(direction, webElement, percentage, 0);
    }

    public Locomotive swipe(SwipeElementDirection direction, String id) {
        return swipe(direction, PageUtil.buildBy(configuration, id));
    }

    public Locomotive swipe(SwipeElementDirection direction, By by) {
        return swipe(direction, (MobileElement) getAppiumDriver().findElement(by));
    }

    public Locomotive swipe(SwipeElementDirection direction, MobileElement mobileElement) {
        return swipe(direction, (WebElement) mobileElement);
    }

    public Locomotive swipe(SwipeElementDirection direction, WebElement webElement) {
        return swipe(direction, webElement, SWIPE_DISTANCE, 0);
    }

    public Locomotive swipe(SwipeElementDirection direction, String id, int swipeDurationInMillis) {
        return swipe(direction, PageUtil.buildBy(configuration, id), swipeDurationInMillis);
    }

    public Locomotive swipe(SwipeElementDirection direction, By by, int swipeDurationInMillis) {
        return swipe(direction, (MobileElement) getAppiumDriver().findElement(by), swipeDurationInMillis);
    }


    public Locomotive swipe(SwipeElementDirection direction, MobileElement mobileElement, int swipeDurationInMillis) {
        return swipe(direction, (WebElement) mobileElement, swipeDurationInMillis);
    }

    public Locomotive swipe(SwipeElementDirection direction, WebElement webElement, int swipeDurationInMillis) {
        return swipe(direction, webElement, SWIPE_DISTANCE, swipeDurationInMillis);
    }

    public Locomotive swipeCenterLong(SwipeElementDirection direction) {
        return swipe(direction, /*element=*/null, SWIPE_DISTANCE_LONG, 0);
    }

    public Locomotive swipeCenterLong(SwipeElementDirection direction, int swipeDurationInMillis) {
        return swipe(direction, /*element=*/null, SWIPE_DISTANCE_LONG, swipeDurationInMillis);
    }

    public Locomotive swipeCenterLong(SwipeElementDirection direction, short numberOfSwipes) {
        return swipeCenterLong(direction, 0, numberOfSwipes);
    }

    public Locomotive swipeCenterLong(SwipeElementDirection direction, int swipeDurationInMillis, int numberOfSwipes) {
        return repetitiveCenterSwipe(direction, swipeDurationInMillis, numberOfSwipes, SWIPE_DISTANCE_LONG);
    }

    public Locomotive swipeCenterSuperLong(SwipeElementDirection direction) {
        return swipe(direction, /*element=*/null, SWIPE_DISTANCE_SUPER_LONG, 0);
    }

    public Locomotive swipeCenterSuperLong(SwipeElementDirection direction, int swipeDurationInMillis) {
        return swipe(direction, /*element=*/null, SWIPE_DISTANCE_SUPER_LONG, swipeDurationInMillis);
    }

    public Locomotive swipeCenterSuperLong(SwipeElementDirection direction, int swipeDurationInMillis, int numberOfSwipes) {
        return repetitiveCenterSwipe(direction, swipeDurationInMillis, numberOfSwipes, SWIPE_DISTANCE_SUPER_LONG);
    }

    public Locomotive swipeCornerLong(ScreenCorner corner, SwipeElementDirection direction, int duration) {
        return performCornerSwipe(corner, direction, SWIPE_DISTANCE_LONG, duration);
    }

    public Locomotive swipeCornerSuperLong(ScreenCorner corner, SwipeElementDirection direction, int duration) {
        return performCornerSwipe(corner, direction, SWIPE_DISTANCE_SUPER_LONG, duration);
    }

    public Locomotive swipeLong(SwipeElementDirection direction, String id) {
        return swipe(direction, PageUtil.buildBy(configuration, id));
    }

    public Locomotive swipeLong(SwipeElementDirection direction, By by) {
        return swipe(direction, (MobileElement) getAppiumDriver().findElement(by));
    }

    public Locomotive swipeLong(SwipeElementDirection direction, MobileElement mobileElement) {
        return swipeLong(direction, (WebElement) mobileElement);
    }

    public Locomotive swipeLong(SwipeElementDirection direction, WebElement element) {
        return swipe(direction, element, SWIPE_DISTANCE_LONG, 0);
    }

    public Locomotive swipeLong(SwipeElementDirection direction, String id, int swipeDurationInMillis) {
        return swipe(direction, PageUtil.buildBy(configuration, id), swipeDurationInMillis);
    }

    public Locomotive swipeLong(SwipeElementDirection direction, By by, int swipeDurationInMillis) {
        return swipe(direction, (MobileElement) getAppiumDriver().findElement(by), swipeDurationInMillis);
    }

    public Locomotive swipeLong(SwipeElementDirection direction, MobileElement element, int swipeDurationInMillis) {
        return swipe(direction, (WebElement) element, swipeDurationInMillis);
    }

    public Locomotive swipeLong(SwipeElementDirection direction, WebElement webElement, int swipeDurationInMillis) {
        return swipe(direction, webElement, SWIPE_DISTANCE_LONG, swipeDurationInMillis);
    }

    public Locomotive swipe(SwipeElementDirection direction, MobileElement mobileElement, float percentage, int swipeDurationInMillis) {
        return swipe(direction, (WebElement) mobileElement, percentage, swipeDurationInMillis);
    }

    public Locomotive swipe(SwipeElementDirection direction, WebElement webElement, float percentage, int swipeDurationInMillis) {
        return performSwipe(direction, false, webElement, percentage, swipeDurationInMillis);
    }

    public Locomotive swipe(Point start, Point end, int swipeDurationInMillis) {
        return performSwipe(false, start, end, swipeDurationInMillis);
    }

    public Locomotive swipe(Point start, Point end) {
        return swipe(start, end, 0);
    }

    public Locomotive repetitiveCenterSwipe(SwipeElementDirection direction, int swipeDurationInMillis, int numberOfSwipes, float swipeDistance) {
        int i = 0;
        while (i < numberOfSwipes) {
            swipe(direction, /*element=*/null, swipeDistance, swipeDurationInMillis);
            i++;
        }
        return this;
    }

    //endregion Generic swipes

    //region Directional swipes

    /***
     * @deprecated since 0.20.0, use {@link #scrollDown()} instead
     */
    @Deprecated
    public void swipeDown() {
        swipeCenterLong(SwipeElementDirection.UP);
    }

    /***
     * @deprecated since 0.20.0, use {@link #scrollDown()} instead
     */
    @Deprecated
    public void swipeDown(int times) {
        for (int i = 0; i < times; i++) {
            swipeCenterLong(SwipeElementDirection.UP);
        }
    }

    public void scrollDown() {
        swipeCenterLong(SwipeElementDirection.UP);
    }

    public void scrollDown(int times) {
        for (int i = 0; i < times; i++) {
            swipeCenterLong(SwipeElementDirection.UP);
        }
    }

    /***
     * @deprecated since 0.20.0, use {@link #scrollUp()} instead
     */
    @Deprecated
    public void swipeUp() {
        swipeCenterLong(SwipeElementDirection.DOWN);
    }

    /***
     * @deprecated since 0.20.0, use {@link #scrollUp()} instead
     */
    @Deprecated
    public void swipeUp(int times) {
        for (int i = 0; i < times; i++) {
            swipeCenterLong(SwipeElementDirection.DOWN);
        }
    }

    public void scrollUp() {
        swipeCenterLong(SwipeElementDirection.DOWN);
    }

    public void scrollUp(int times) {
        for (int i = 0; i < times; i++) {
            swipeCenterLong(SwipeElementDirection.DOWN);
        }
    }

    /***
     * @deprecated since 0.20.0, use {@link #scrollRight()} instead
     */
    @Deprecated
    public void swipeRight() {
        swipeCenterLong(SwipeElementDirection.LEFT);
    }

    /***
     * @deprecated since 0.20.0, use {@link #scrollRight()} instead
     */
    @Deprecated
    public void swipeRight(int times) {
        for (int i = 0; i < times; i++) {
            swipeCenterLong(SwipeElementDirection.LEFT);
        }
    }

    public void scrollRight() {
        swipeCenterLong(SwipeElementDirection.LEFT);
    }

    public void scrollRight(int times) {
        for (int i = 0; i < times; i++) {
            swipeCenterLong(SwipeElementDirection.LEFT);
        }
    }

    /***
     * @deprecated since 0.20.0, use {@link #scrollLeft()} instead
     */
    @Deprecated
    public void swipeLeft() {
        swipeCenterLong(SwipeElementDirection.RIGHT);
    }

    /***
     * @deprecated since 0.20.0, use {@link #scrollLeft()} instead
     */
    @Deprecated
    public void swipeLeft(int times) {
        for (int i = 0; i < times; i++) {
            swipeCenterLong(SwipeElementDirection.RIGHT);
        }
    }

    public void scrollLeft() {
        swipeCenterLong(SwipeElementDirection.RIGHT);
    }

    public void scrollLeft(int times) {
        for (int i = 0; i < times; i++) {
            swipeCenterLong(SwipeElementDirection.RIGHT);
        }
    }

    /***
     * Used to trigger iOS' gestural navigation back
     * As of 8/29/2019, Android does not have this feature, but it may in the future
     * More information on Android here: https://developer.android.com/preview/features/gesturalnav
     */
    public Locomotive swipeSystemBack() {
        Dimension screen = getAppiumDriver().manage().window().getSize();
        Point start = new Point(screen.getWidth() - 2, getYCenter());
        Point end = new Point(2, getYCenter());

        return swipe(start, end);
    }
    //endregion Directional swipes

    //region longPress swipes
    public Locomotive longPressSwipeCenter(SwipeElementDirection direction) {
        return longPressSwipe(direction, /*element=*/null, SWIPE_DISTANCE, 0);
    }

    public Locomotive longPressSwipeCenter(SwipeElementDirection direction, int swipeDurationInMillis) {
        return longPressSwipe(direction, /*element=*/null, SWIPE_DISTANCE, swipeDurationInMillis);
    }

    public Locomotive longPressSwipeCenter(SwipeElementDirection direction, int swipeDurationInMillis, short numberOfSwipes) {
        short i = 0;
        while (i < numberOfSwipes) {
            longPressSwipeCenter(direction, swipeDurationInMillis);
            i++;
        }
        return this;
    }

    public Locomotive longPressSwipe(SwipeElementDirection direction, String id) {
        return longPressSwipe(direction, PageUtil.buildBy(configuration, id));
    }

    public Locomotive longPressSwipe(SwipeElementDirection direction, By by) {
        return longPressSwipe(direction, (MobileElement) getAppiumDriver().findElement(by));
    }

    public Locomotive longPressSwipe(SwipeElementDirection direction, MobileElement mobileElement) {
        return longPressSwipe(direction, (WebElement) mobileElement);
    }

    public Locomotive longPressSwipe(SwipeElementDirection direction, WebElement webElement) {
        return longPressSwipe(direction, webElement, SWIPE_DISTANCE, 0);
    }

    public Locomotive longPressSwipe(SwipeElementDirection direction, String id, int swipeDurationInMillis) {
        return longPressSwipe(direction, PageUtil.buildBy(configuration, id), swipeDurationInMillis);
    }

    public Locomotive longPressSwipe(SwipeElementDirection direction, By by, int swipeDurationInMillis) {
        return longPressSwipe(direction, (MobileElement) getAppiumDriver().findElement(by), swipeDurationInMillis);
    }

    public Locomotive longPressSwipe(SwipeElementDirection direction, MobileElement mobileElement, int swipeDurationInMillis) {
        return longPressSwipe(direction, (WebElement) mobileElement, swipeDurationInMillis);
    }

    public Locomotive longPressSwipe(SwipeElementDirection direction, WebElement webElement, int swipeDurationInMillis) {
        return longPressSwipe(direction, webElement, SWIPE_DISTANCE, swipeDurationInMillis);
    }

    public Locomotive longPressSwipeCenterLong(SwipeElementDirection direction) {
        return longPressSwipe(direction, /*element=*/null, SWIPE_DISTANCE_LONG, 0);
    }

    public Locomotive longPressSwipeCenterLong(SwipeElementDirection direction, int swipeDurationInMillis) {
        return longPressSwipe(direction, /*element=*/null, SWIPE_DISTANCE_LONG, swipeDurationInMillis);
    }

    public Locomotive longPressSwipeCenterLong(SwipeElementDirection direction, int swipeDurationInMillis, short numberOfSwipes) {
        short i = 0;
        while (i < numberOfSwipes) {
            longPressSwipeCenterLong(direction, swipeDurationInMillis);
            i++;
        }
        return this;
    }

    public Locomotive longPressSwipeCenterSuperLong(SwipeElementDirection direction) {
        return longPressSwipe(direction, /*element=*/null, SWIPE_DISTANCE_SUPER_LONG, 0);
    }

    public Locomotive longPressSwipeCenterSuperLong(SwipeElementDirection direction, int swipeDurationInMillis) {
        return longPressSwipe(direction, /*element=*/null, SWIPE_DISTANCE_SUPER_LONG, swipeDurationInMillis);
    }

    public Locomotive longPressSwipeCenterSuperLong(SwipeElementDirection direction, int swipeDurationInMillis, short numberOfSwipes) {
        short i = 0;
        while (i < numberOfSwipes) {
            longPressSwipeCenterSuperLong(direction, swipeDurationInMillis);
            i++;
        }
        return this;
    }

    public Locomotive longPressSwipeLong(SwipeElementDirection direction, String id) {
        return longPressSwipe(direction, PageUtil.buildBy(configuration, id));
    }

    public Locomotive longPressSwipeLong(SwipeElementDirection direction, By by) {
        return longPressSwipe(direction, (MobileElement) getAppiumDriver().findElement(by));
    }

    public Locomotive longPressSwipeLong(SwipeElementDirection direction, MobileElement mobileElement) {
        return longPressSwipe(direction, (WebElement) mobileElement);
    }

    /**
     * It is preferred to pass in a MobileElement, hence this is deprecated.
     */
    @Deprecated
    public Locomotive longPressSwipeLong(SwipeElementDirection direction, WebElement webElement) {
        return longPressSwipe(direction, webElement, SWIPE_DISTANCE_LONG, 0);
    }

    public Locomotive longPressSwipeLong(SwipeElementDirection direction, String id, int swipeDurationInMillis) {
        return longPressSwipe(direction, PageUtil.buildBy(configuration, id), swipeDurationInMillis);
    }

    public Locomotive longPressSwipeLong(SwipeElementDirection direction, By by, int swipeDurationInMillis) {
        return longPressSwipe(direction, (MobileElement) getAppiumDriver().findElement(by), swipeDurationInMillis);
    }

    public Locomotive longPressSwipeLong(SwipeElementDirection direction, MobileElement mobileElement, int swipeDurationInMillis) {
        return longPressSwipeLong(direction, (WebElement) mobileElement, swipeDurationInMillis);
    }

    /**
     * It is preferred to pass in a MobileElement, hence this is deprecated.
     */
    @Deprecated
    public Locomotive longPressSwipeLong(SwipeElementDirection direction, WebElement webElement, int swipeDurationInMillis) {
        return longPressSwipe(direction, webElement, SWIPE_DISTANCE_LONG, swipeDurationInMillis);
    }

    public Locomotive longPressSwipe(SwipeElementDirection direction, MobileElement mobileElement, float percentage, int swipeDurationInMillis) {
        return longPressSwipe(direction, (WebElement) mobileElement, percentage, swipeDurationInMillis);
    }

    /**
     * It is preferred to pass in a MobileElement, hence this is deprecated.
     */
    @Deprecated
    public Locomotive longPressSwipe(SwipeElementDirection direction, WebElement webElement, float percentage, int swipeDurationInMillis) {
        return performSwipe(direction, true, webElement, percentage, swipeDurationInMillis);
    }

    public Locomotive longPressSwipe(Point start, Point end, int swipeDurationInMillis) {
        return performSwipe(true, start, end, swipeDurationInMillis);
    }

    public Locomotive longPressSwipe(Point start, Point end) {
        return longPressSwipe(start, end, 0);
    }
    //endregion longPress swipes

    public Locomotive hideKeyboard() {
        try {
            getAppiumDriver().hideKeyboard();
        } catch (WebDriverException e) {
            Logger.error(e, "WARN:" + e.getMessage());
        }
        return this;
    }

    /***
     * Generic Perform Swipe Method
     * Allows for different TouchAction presses: press() and longPress()
     * Can accept different start and end Points, by Appium's MobileElement, By, or Points
     *
     * @param direction - nullable, but required if `to` is null
     * @param isLongPress - determines TouchAction as `press()` or `longPress()`
     * @param from - origin point of swipe, not nullable
     * @param to - destination point of swipe, nullable, but required if `direction` is null
     * @param percentage - modifies destination X and Y depending on `direction`
     * @param swipeDurationInMillis - modifies duration of swipe
     ***/

    private Locomotive performSwipe(SwipeElementDirection direction, boolean isLongPress, Point from, Point to, float percentage, int swipeDurationInMillis) {
        Dimension screen = getAppiumDriver().manage().window().getSize();

        if (direction != null) {
            switch (direction) {
                case UP:
                    int toYUp = (int) (from.getY() - (from.getY() * percentage));
                    toYUp = toYUp <= 0 ? 1 : toYUp; // toYUp cannot be less than 0
                    to = new Point(from.getX(), toYUp);
                    break;
                case RIGHT:
                    int toXRight = (int) (from.getX() + (screen.getWidth() * percentage));
                    toXRight = toXRight >= screen.getWidth() ? screen.getWidth() - 1 : toXRight; // toXRight cannot be longer than screen width
                    to = new Point(toXRight, from.getY());
                    break;
                case DOWN:
                    int toYDown = (int) (from.getY() + (screen.getHeight() * percentage));
                    toYDown = toYDown >= screen.getHeight() ? screen.getHeight() - 1 : toYDown; // toYDown cannot be longer than screen height
                    to = new Point(from.getX(), toYDown);
                    break;
                case LEFT:
                    int toXLeft = (int) (from.getX() - (from.getX() * percentage));
                    toXLeft = toXLeft <= 0 ? 1 : toXLeft; // toXLeft cannot be less than 0
                    to = new Point(toXLeft, from.getY());
                    break;
                default:
                    throw new IllegalArgumentException("Swipe Direction not specified: " + direction.name());
            }
        } else if (to == null) {
            throw new IllegalArgumentException("Swipe Direction and To Point are not specified.");
        }

        // Appium specifies that TouchAction.moveTo should be relative. iOS implements this correctly, but android
        // does not. As a result we have to check if we're on iOS and perform the relativization manually
        if (configuration.getPlatformName() == Platform.IOS) {
            to = new Point(to.getX() - from.getX(), to.getY() - from.getY());
        }

        int swipeDuration = (swipeDurationInMillis != 0) ? swipeDurationInMillis : SWIPE_DURATION_MILLIS;

        TouchAction swipe;
        if (!isLongPress) {
            swipe = new TouchAction(getAppiumDriver())
                    .press(point(from.getX(), from.getY()))
                    .waitAction(waitOptions(ofMillis(swipeDuration)))
                    .moveTo(point(to.getX(), to.getY()))
                    .release();
        } else {
            swipe = new TouchAction(getAppiumDriver())
                    .longPress(point(from.getX(), from.getY()))
                    .waitAction(waitOptions(ofMillis(swipeDuration)))
                    .moveTo(point(to.getX(), to.getY()))
                    .release();
        }

        swipe.perform();
        return this;
    }

    //Overload method for using custom Points
    private Locomotive performSwipe(boolean isLongPress, Point from, Point to, int swipeDurationInMillis) {
        return performSwipe(null, isLongPress, from, to, 0, swipeDurationInMillis);
    }

    //Overload method for using MobileElement, By, or String
    private Locomotive performSwipe(SwipeElementDirection direction, boolean isLongPress, MobileElement mobileElement, float percentage, int swipeDurationInMillis) {
        return performSwipe(direction, isLongPress, getCenter((WebElement) mobileElement), null, percentage, swipeDurationInMillis);
    }

    @Deprecated
    private Locomotive performSwipe(SwipeElementDirection direction, boolean isLongPress, WebElement webElement, float percentage, int swipeDurationInMillis) {
        return performSwipe(direction, isLongPress, getCenter(webElement), null, percentage, swipeDurationInMillis);
    }

    private Locomotive performCornerSwipe(ScreenCorner corner, SwipeElementDirection direction, float percentage, int duration) {
        Dimension screen = getAppiumDriver().manage().window().getSize();

        final int SCREEN_MARGIN = 10;

        Point from;
        if (corner != null) {
            switch (corner) {
                case TOP_LEFT:
                    from = new Point(SCREEN_MARGIN, SCREEN_MARGIN);
                    break;
                case TOP_RIGHT:
                    from = new Point(screen.getWidth() - SCREEN_MARGIN, SCREEN_MARGIN);
                    break;
                case BOTTOM_LEFT:
                    from = new Point(SCREEN_MARGIN, screen.getHeight() - SCREEN_MARGIN);
                    break;
                case BOTTOM_RIGHT:
                    from = new Point(screen.getWidth() - SCREEN_MARGIN, screen.getHeight() - SCREEN_MARGIN);
                    break;
                default:
                    throw new IllegalArgumentException("Corner not specified: " + corner.name());
            }
        } else {
            throw new IllegalArgumentException("Corner not specified");
        }

        Point to;
        if (direction != null) {
            switch (direction) {
                case UP:
                    int toYUp = (int) (from.getY() - (screen.getHeight() * percentage));
                    toYUp = toYUp <= 0 ? 1 : toYUp;
                    to = new Point(from.getX(), toYUp);
                    break;
                case RIGHT:
                    int toXRight = (int) (from.getX() + (screen.getWidth() * percentage));
                    toXRight = toXRight >= screen.getWidth() ? screen.getWidth() - 1 : toXRight; // toXRight cannot be longer than screen width;
                    to = new Point(toXRight, from.getY());
                    break;
                case DOWN:
                    int toYDown = (int) (from.getY() + (screen.getWidth() * percentage));
                    toYDown = toYDown >= screen.getHeight() ? screen.getHeight() - 1 : toYDown; // toYDown cannot be longer than screen height;
                    to = new Point(from.getX(), toYDown);
                    break;
                case LEFT:
                    int toXLeft = (int) (from.getX() - (screen.getWidth() * percentage));
                    toXLeft = toXLeft <= 0 ? 1 : toXLeft; // toXLeft cannot be less than 0
                    to = new Point(toXLeft, from.getY());
                    break;
                default:
                    throw new IllegalArgumentException("Swipe Direction not specified: " + direction.name());

            }
        } else {
            throw new IllegalArgumentException("Swipe Direction not specified");
        }

        // Appium specifies that TouchAction.moveTo should be relative. iOS implements this correctly, but android
        // does not. As a result we have to check if we're on iOS and perform the relativization manually
        if (configuration.getPlatformName() == Platform.IOS) {
            to = new Point(to.getX() - from.getX(), to.getY() - from.getY());
        }

        new TouchAction(getAppiumDriver())
                .press(point(from.getX(), from.getY()))
                .waitAction(waitOptions(ofMillis(duration)))
                .moveTo(point(to.getX(), to.getY()))
                .release()
                .perform();
        return this;
    }

    public MobileElement swipeTo(SwipeElementDirection direction, By by, int attempts) {
        MobileElement mobileElement;
        for (int i = 0; i < attempts; i++) {
            swipeCenterLong(direction);
            try {
                mobileElement = (MobileElement) getAppiumDriver().findElement(by);
                // element was found, check for visibility
                if (mobileElement.isDisplayed()) {
                    // element is in view, exit the loop
                    return mobileElement;
                }
                // element was not visible, continue scrolling
            } catch (WebDriverException exception) {
                // element could not be found, continue scrolling
            }
        }
        // element could not be found or was not visible, return null
        Logger.warn("Element " + by.toString() + " does not exist!");
        return null;
    }

    public MobileElement swipeTo(By by) {
        SwipeElementDirection s = SwipeElementDirection.UP;
        int attempts = 3;

        return swipeTo(s, by, attempts);
    }

    public MobileElement swipeTo(SwipeElementDirection direction, By by) {
        int attempts = 3;

        return swipeTo(direction, by, attempts);
    }

    public MobileElement swipeTo(SwipeElementDirection direction, String id, int attempts) {
        return swipeTo(direction, By.id(id), attempts);
    }

    /**
     * Get center point of element, if element is null return center of screen
     *
     * @param element The element to get the center point form
     * @return Point centered on the provided element or screen.
     */

    public Point getCenter(MobileElement element) {
        return getCenter((WebElement) element);
    }

    @Deprecated
    public Point getCenter(WebElement element) {
        return new Point(getXCenter(element), getYCenter(element));
    }

    public int getXCenter(MobileElement element) {
        return getXCenter((WebElement) element);
    }

    public int getXCenter() {
        return getXCenter(null);
    }

    @Deprecated
    public int getXCenter(WebElement element) {
        if (element == null) {
            return getAppiumDriver().manage().window().getSize().getWidth() / 2;
        } else {
            return element.getLocation().getX() + (element.getSize().getWidth() / 2);
        }
    }

    public int getYCenter(MobileElement element) {
        return getYCenter((WebElement) element);
    }

    public int getYCenter() {
        return getYCenter(null);
    }

    @Deprecated
    public int getYCenter(WebElement element) {
        if (element == null) {
            return getAppiumDriver().manage().window().getSize().getHeight() / 2;
        } else {
            return element.getLocation().getY() + (element.getSize().getHeight() / 2);
        }
    }

    public List<MobileElement> getElements(String id) {
        return getElements(PageUtil.buildBy(configuration, id));
    }

    public List<MobileElement> getElements(By by) {
        return getAppiumDriver().findElements(by);
    }

    /**
     * Validation Functions for Testing
     */
    public Locomotive validatePresent(String id) {
        return validatePresent(PageUtil.buildBy(configuration, id));
    }

    public Locomotive validatePresent(By by) {
        Assert.assertTrue("Element " + by.toString() + " does not exist!", isPresent(by));
        return this;
    }

    public Locomotive validateNotPresent(String id) {
        return validateNotPresent(PageUtil.buildBy(configuration, id));
    }

    public Locomotive validateNotPresent(By by) {
        Assert.assertFalse("Element " + by.toString() + " exists!", isPresent(by));
        return this;
    }

    public Locomotive validateText(String id, String text) {
        return validateText(PageUtil.buildBy(configuration, id), text);
    }

    public Locomotive validateTextIgnoreCase(String id, String text) {
        return validateTextIgnoreCase(PageUtil.buildBy(configuration, id), text);
    }

    public Locomotive validateTextIgnoreCase(By by, String text) {
        return validateTextIgnoreCase(getAppiumDriver().findElement(by), text);
    }

    public Locomotive validateTextIgnoreCase(MobileElement element, String text) {
        return validateTextIgnoreCase((WebElement) element, text);
    }

    @Deprecated
    public Locomotive validateTextIgnoreCase(WebElement element, String text) {
        String actual = getText(element);
        Assert.assertTrue(String.format("Text does not match! [expected: %s] [actual: %s]", text, actual),
                text.equalsIgnoreCase(actual));
        return this;
    }

    public Locomotive validateText(By by, String expected) {
        return validateText(getAppiumDriver().findElement(by), expected);
    }

    public Locomotive validateText(MobileElement mobileElement, String expected) {
        return validateText((WebElement) mobileElement, expected);
    }

    @Deprecated
    public Locomotive validateText(WebElement webElement, String expected) {
        String actual = getText(webElement);
        Assert.assertEquals(String.format("Text does not match! [expected: %s] [actual: %s]", expected, actual), expected, actual);
        return this;
    }

    public Locomotive validateTextNot(String id, String text) {
        return validateTextNot(PageUtil.buildBy(configuration, id), text);
    }

    public Locomotive validateTextNotIgnoreCase(String id, String text) {
        return validateTextNotIgnoreCase(PageUtil.buildBy(configuration, id), text);
    }

    public Locomotive validateTextNotIgnoreCase(By by, String text) {
        return validateTextNotIgnoreCase(getAppiumDriver().findElement(by), text);
    }

    public Locomotive validateTextNotIgnoreCase(MobileElement element, String text) {
        return validateTextNotIgnoreCase((WebElement) element, text);
    }

    @Deprecated
    public Locomotive validateTextNotIgnoreCase(WebElement element, String text) {
        String actual = getText(element);
        Assert.assertFalse(String.format("Text matches! [expected: %s] [actual: %s]", text, actual),
                text.equalsIgnoreCase(actual));
        return this;
    }

    public Locomotive validateTextNot(By by, String text) {
        return validateTextNot(getAppiumDriver().findElement(by), text);
    }

    public Locomotive validateTextNot(MobileElement element, String text) {
        return validateTextNot((WebElement) element, text);
    }

    @Deprecated
    public Locomotive validateTextNot(WebElement element, String unexpected) {
        String actual = getText(element);
        Assert.assertNotEquals(String.format("Text matches! [expected: %s] [actual: %s]", unexpected, actual), unexpected, actual);
        return this;
    }

    public Locomotive validateTextPresent(String text) {
        Assert.assertTrue(getAppiumDriver().getPageSource().contains(text));
        return this;
    }

    public Locomotive validateTextNotPresent(String text) {
        Assert.assertFalse(getAppiumDriver().getPageSource().contains(text));
        return this;
    }

    public Locomotive validateAttribute(String id, String attr, String expected) {
        return validateAttribute(PageUtil.buildBy(configuration, id), attr, expected);
    }

    public Locomotive validateAttribute(By by, String attr, String expected) {
        return validateAttribute(getAppiumDriver().findElement(by), attr, expected);
    }

    public Locomotive validateAttribute(MobileElement element, String attr, String expected) {
        return validateAttribute((WebElement) element, attr, expected);
    }

    /**
     * It is preferred to use a MobileElement, hence this is deprecated.
     */
    @Deprecated
    public Locomotive validateAttribute(WebElement element, String attr, String expected) {
        String actual = null;
        try {
            actual = element.getAttribute(attr);
            if (actual.equals(expected)) return this; // test passes.
        } catch (NoSuchElementException e) {
            Assert.fail("No such element [" + element.toString() + "] exists.");
        } catch (Exception x) {
            Assert.fail("Cannot validate an attribute if an element doesn't have it!");
        }

        Pattern p = Pattern.compile(expected);
        Matcher m = p.matcher(actual);

        Assert.assertTrue(
                String.format("Attribute doesn't match! [Selector: %s] [Attribute: %s] [Desired value: %s] [Actual value: %s]",
                        element.toString(),
                        attr,
                        expected,
                        actual
                ),
                m.find());

        return this;
    }

    public Locomotive validateTrue(boolean condition) {
        Assert.assertTrue(condition);
        return this;
    }

    public Locomotive validateFalse(boolean condition) {
        Assert.assertFalse(condition);
        return this;
    }

    public Locomotive store(String key, String value) {
        vars.put(key, value);
        return this;
    }

    public String get(String key) {
        return get(key, null);
    }

    public String get(String key, String defaultValue) {
        return Strings.isNullOrEmpty(vars.get(key))
                ? defaultValue
                : vars.get(key);
    }

    /**
     * Enroll biometrics. This command is ignored on Android.
     *
     * @return The implementing class for fluency
     */
    public Locomotive enrollBiometrics(int id) {
        switch (configuration.getPlatformName()) {
            case ANDROID:
                // Don't do anything for now
                break;

            case IOS:
                PerformsTouchID performsTouchID = (PerformsTouchID) driver;
                performsTouchID.toggleTouchIDEnrollment(true);
                break;

            case NONE:
                break;
        }

        return this;
    }

    /**
     * Perform a biometric scan, either forcing a match (iOS) or by supplying the id of an enrolled
     * fingerprint (Android)
     *
     * @param match Whether or not the finger should match. This parameter is ignored on Android
     * @param id    The id of the enrolled finger. This parameter is ignored on iOS
     * @return The implementing class for fluency
     */
    public Locomotive performBiometric(boolean match, int id) {

        switch (configuration.getPlatformName()) {
            case ANDROID:
                //Note: Biometrics are only supported on Android APIs 23 and above (6.0)
                AuthenticatesByFinger authenticatesByFinger = (AuthenticatesByFinger) getAppiumDriver();
                authenticatesByFinger.fingerPrint(id);
                break;

            case IOS:
                PerformsTouchID performsTouchID = (PerformsTouchID) getAppiumDriver();
                performsTouchID.performTouchID(match);
                break;

            case NONE:
                break;
        }

        return this;
    }

    /**
     * Wait for a specific condition (polling every 1s, for MAX_TIMEOUT seconds)
     *
     * @param condition the condition to wait for
     * @return The implementing class for fluency
     */
    public Locomotive waitForCondition(ExpectedCondition<?> condition) {
        return waitForCondition(condition, configuration.getAppiumRequestTimeout());
    }

    /**
     * Wait for a specific condition (polling every 1s)
     *
     * @param condition        the condition to wait for
     * @param timeOutInSeconds the timeout in seconds
     * @return The implementing class for fluency
     */
    public Locomotive waitForCondition(ExpectedCondition<?> condition, long timeOutInSeconds) {
        return waitForCondition(condition, timeOutInSeconds, 1000); // poll every second
    }

    public Locomotive waitForCondition(ExpectedCondition<?> condition, long timeOutInSeconds, long sleepInMillis) {
        WebDriverWait wait = new WebDriverWait(getAppiumDriver(), timeOutInSeconds, sleepInMillis);
        wait.until(condition);
        return this;
    }

    public Locomotive waitUntilNotPresent(String id) {
        return waitUntilNotPresent(PageUtil.buildBy(configuration, id));
    }

    public Locomotive waitUntilNotPresent(By by) {
        return waitForCondition(ExpectedConditions.invisibilityOfElementLocated(by));
    }

    public Locomotive waitUntilNotPresent(MobileElement element) {
        return waitForCondition(ExpectedConditions.invisibilityOf(element));
    }

    public String getTestMethodName() {
        return testMethodName;
    }

    /**
     * @return the Sauce Job id for the current thread
     */
    @Override
    public String getSessionId() {
        return sessionId.get();
    }

    /**
     * @return the {@link SauceOnDemandAuthentication} instance containing the Sauce username/access key
     */
    @Override
    public SauceOnDemandAuthentication getAuthentication() {
        return configuration.getSauceAuthentication(configuration.getSauceUserName(), configuration.getSauceAccessKey());
    }

    public boolean isPlatFormAndroid() {
        return configuration.getPlatformName().equals(Platform.ANDROID);
    }

    public boolean isPlatFormiOS() {
        return !isPlatFormAndroid();
    }

}
