package io.rakam.api;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.location.Location;
import android.os.Build;
import android.text.TextUtils;
import android.util.Pair;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.rakam.api.Constants.EVENT_BATCH_ENDPOINT;
import static io.rakam.api.Constants.MAX_STRING_LENGTH;

/**
 * <h1>RakamClient</h1>
 * This is the SDK instance class that contains all of the SDK functionality.<br><br>
 * <b>Note:</b> call the methods on the default shared instance in the Rakam class,
 * for example: {@code Rakam.getInstance().logEvent();}<br><br>
 * Many of the SDK functions return the SDK instance back, allowing you to chain multiple method
 * calls together, for example: {@code Rakam.getInstance().initialize(this, "APIKEY").enableForegroundTracking(getApplication())}
 */
public class RakamClient {
    public static final MediaType JSON
            = MediaType.parse("application/json; charset=utf-8");
    /**
     * The class identifier tag used in logging. TAG = {@code "RakamClient";}
     */
    public static final String TAG = "RakamClient";

    /**
     * The event type for start session events.
     */
    public static final String START_SESSION_EVENT = "_session_start";
    /**
     * The event type for end session events.
     */
    public static final String END_SESSION_EVENT = "_session_end";

    /**
     * The pref/database key for the device ID value.
     */
    public static final String DEVICE_ID_KEY = "device_id";
    /**
     * The pref/database key for the user ID value.
     */
    public static final String USER_ID_KEY = "user_id";
    /**
     * The pref/database key for the super properties.
     */
    public static final String SUPER_PROPERTIES_KEY = "super_properties";
    /**
     * The pref/database key for the opt out flag.
     */
    public static final String OPT_OUT_KEY = "opt_out";
    /**
     * The pref/database key for the last event time.
     */
    public static final String LAST_EVENT_TIME_KEY = "last_event_time";
    /**
     * The pref/database key for the last event ID value.
     */
    public static final String LAST_EVENT_ID_KEY = "last_event_id";
    /**
     * The pref/database key for the last identify ID value.
     */
    public static final String LAST_IDENTIFY_ID_KEY = "last_identify_id";
    /**
     * The pref/database key for the previous session ID value.
     */
    public static final String PREVIOUS_SESSION_ID_KEY = "previous_session_id";

    private static final RakamLog logger = RakamLog.getLogger();

    /**
     * The Android App Context.
     */
    protected Context context;
    /**
     * The shared OkHTTPClient instance.
     */
    protected OkHttpClient httpClient;
    /**
     * The shared Rakam database helper instance.
     */
    protected DatabaseHelper dbHelper;
    /**
     * The Rakam App API key.
     */
    protected String apiKey;
    /**
     * The name for this instance of RakamClient.
     */
    protected String instanceName;
    /**
     * The user's ID value.
     */
    protected String userId;
    /**
     * The user's Device ID value.
     */
    protected String deviceId;
    private boolean newDeviceIdPerInstall = false;
    private boolean useAdvertisingIdForDeviceId = false;
    protected boolean initialized = false;
    private boolean optOut = false;
    private boolean offline = false;
    TrackingOptions trackingOptions = new TrackingOptions();
    JSONObject apiPropertiesTrackingOptions;
    /**
     * The device's Platform value.
     */
    protected String platform;

    /**
     * Event metadata
     */
    long sessionId = -1;
    long lastEventId = -1;
    long lastIdentifyId = -1;
    long lastEventTime = -1;
    long previousSessionId = -1;

    private DeviceInfo deviceInfo;

    /**
     * The current session ID value.
     */
    private int eventUploadThreshold = Constants.EVENT_UPLOAD_THRESHOLD;
    private int eventUploadMaxBatchSize = Constants.EVENT_UPLOAD_MAX_BATCH_SIZE;
    private int eventMaxCount = Constants.EVENT_MAX_COUNT;
    private long eventUploadPeriodMillis = Constants.EVENT_UPLOAD_PERIOD_MILLIS;
    private long minTimeBetweenSessionsMillis = Constants.MIN_TIME_BETWEEN_SESSIONS_MILLIS;
    private long sessionTimeoutMillis = Constants.SESSION_TIMEOUT_MILLIS;
    private boolean backoffUpload = false;
    private int backoffUploadBatchSize = eventUploadMaxBatchSize;
    private boolean usingForegroundTracking = false;
    private boolean trackingSessionEvents = false;
    private boolean inForeground = false;
    private JSONObject superProperties;
    private boolean flushEventsOnClose = true;

    private AtomicBoolean updateScheduled = new AtomicBoolean(false);
    /**
     * Whether or not the SDK is in the process of uploading events.
     */
    AtomicBoolean uploadingCurrently = new AtomicBoolean(false);

    /**
     * The last SDK error - used for testing.
     */
    Throwable lastError;
    /**
     * The Rakam API url that will store the events.
     */
    private String apiUrl;
    /**
     * The background event logging worker thread instance.
     */
    WorkerThread logThread = new WorkerThread("logThread");
    /**
     * The background event uploading worker thread instance.
     */
    WorkerThread httpThread = new WorkerThread("httpThread");

    /**
     * Instantiates a new default instance RakamClient and starts worker threads.
     */
    public RakamClient() {
        this(null);
    }

    /**
     * Instantiates a new RakamClient and starts worker threads.
     */
    public RakamClient(String instance) {
        this.instanceName = Utils.normalizeInstanceName(instance);
        logThread.start();
        httpThread.start();

        logThread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                logger.e(TAG, "Unknown exception thrown from log thread.", e);
            }
        });
        httpThread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                logger.e(TAG, "Unknown exception thrown from HTTP thread.", e);
            }
        });
    }

    /**
     * Initialize the Rakam SDK with the Android application context and your Rakam
     * App API key. <b>Note:</b> initialization is required before you log events and modify
     * user properties.
     *
     * @param context the Android application context
     * @param apiUrl  your Rakam API Url
     * @param apiKey  your Rakam App API key
     * @return the RakamClient
     */
    public RakamClient initialize(Context context, URL apiUrl, String apiKey) {
        return initialize(context, apiUrl, apiKey, null);
    }

    /**
     * Initialize the Rakam SDK with the Android application context and your Rakam
     * App API key. <b>Note:</b> initialization is required before you log events and modify
     * user properties.
     *
     * @param context the Android application context
     * @param apiUrl  your Rakam API Url
     * @param apiKey  your Rakam App API key
     * @param userId  your Application User Id
     * @return the RakamClient
     */
    public synchronized RakamClient initialize(Context context, URL apiUrl, String apiKey, String userId) {
        return initialize(context, apiUrl, apiKey, userId, null, true);
    }

    /**
     * Initialize the Rakam SDK with the Android application context, your Rakam App API
     * key, and a user ID for the current user. <b>Note:</b> initialization is required before
     * you log events and modify user properties.
     *
     * @param context                 the Android application context
     * @param apiUrl                  your Rakam App API Url
     * @param apiKey                  your Rakam App API key
     * @param userId                  your Application User Id
     * @param platform                The platform name
     * @param enableDiagnosticLogging Enable error tracking to Rakam APIs
     * @return the RakamClient
     */
    public synchronized RakamClient initialize(final Context context, final URL apiUrl, final String apiKey, final String userId, final String platform, final boolean enableDiagnosticLogging) {
        if (context == null) {
            logger.e(TAG, "Argument context cannot be null in initialize()");
            return this;
        }

        setApiUrl(apiUrl);

        if (TextUtils.isEmpty(apiKey)) {
            logger.e(TAG, "Argument apiKey cannot be null or blank in initialize()");
            return this;
        }

        this.context = context.getApplicationContext();
        this.apiKey = apiKey;
        this.dbHelper = DatabaseHelper.getDatabaseHelper(this.context, this.instanceName);
        this.platform = Utils.isEmptyString(platform) ? Constants.PLATFORM : platform;

        final RakamClient client = this;
        runOnLogThread(new Runnable() {
            @Override
            public void run() {
                if (!initialized) {
                    // this try block is idempotent, so it's safe to retry initialize if failed
                    try {
                        if (instanceName.equals(Constants.DEFAULT_INSTANCE)) {
                            RakamClient.upgradePrefs(context);
                            RakamClient.upgradeSharedPrefsToDB(context);
                        }
                        httpClient = new OkHttpClient();
                        deviceInfo = new DeviceInfo(context);
                        deviceId = initializeDeviceId();
                        if (enableDiagnosticLogging) {
                            Diagnostics.getLogger().enableLogging(httpClient, apiKey, deviceId);
                        }
                        deviceInfo.prefetch();

                        if (userId != null) {
                            client.userId = userId;
                            dbHelper.insertOrReplaceKeyValue(USER_ID_KEY, userId);
                        } else {
                            client.userId = dbHelper.getValue(USER_ID_KEY);
                        }
                        final Long optOutLong = dbHelper.getLongValue(OPT_OUT_KEY);
                        optOut = optOutLong != null && optOutLong == 1;

                        // try to restore previous session id
                        previousSessionId = getLongvalue(PREVIOUS_SESSION_ID_KEY, -1);
                        if (previousSessionId >= 0) {
                            sessionId = previousSessionId;
                        }

                        // reload event meta data
                        lastEventId = getLongvalue(LAST_EVENT_ID_KEY, -1);
                        lastIdentifyId = getLongvalue(LAST_IDENTIFY_ID_KEY, -1);
                        lastEventTime = getLongvalue(LAST_EVENT_TIME_KEY, -1);

                        // install database reset listener to re-insert metadata in memory
                        dbHelper.setDatabaseResetListener(new DatabaseResetListener() {
                            @Override
                            public void onDatabaseReset(SQLiteDatabase db) {
                                dbHelper.insertOrReplaceKeyValueToTable(db, DatabaseHelper.STORE_TABLE_NAME, DEVICE_ID_KEY, client.deviceId);
                                dbHelper.insertOrReplaceKeyValueToTable(db, DatabaseHelper.STORE_TABLE_NAME, USER_ID_KEY, client.userId);
                                dbHelper.insertOrReplaceKeyValueToTable(db, DatabaseHelper.LONG_STORE_TABLE_NAME, OPT_OUT_KEY, client.optOut ? 1L : 0L);
                                dbHelper.insertOrReplaceKeyValueToTable(db, DatabaseHelper.LONG_STORE_TABLE_NAME, PREVIOUS_SESSION_ID_KEY, client.sessionId);
                                dbHelper.insertOrReplaceKeyValueToTable(db, DatabaseHelper.LONG_STORE_TABLE_NAME, LAST_EVENT_TIME_KEY, client.lastEventTime);
                            }
                        });

                        initialized = true;

                        String value = dbHelper.getValue(SUPER_PROPERTIES_KEY);
                        if (value != null) {
                            try {
                                superProperties = new JSONObject(value);
                            } catch (JSONException e) {
                                dbHelper.insertOrReplaceKeyValue(SUPER_PROPERTIES_KEY, null);
                            }
                        }

                    } catch (CursorWindowAllocationException e) {  // treat as uninitialized SDK
                        logger.e(TAG, String.format(
                                "Failed to initialize Rakam SDK due to: %s", e.getMessage()
                        ));
                        Diagnostics.getLogger().logError("Failed to initialize Rakam SDK", e);
                        client.apiKey = null;
                    }
                }
            }
        });

        return this;
    }

    /**
     * Sets super property keys for the user.
     * Super properties allow you to continuously attach a property to every event you track automatically.
     *
     * @param superProperties Super properties
     * @return the RakamClient
     */
    public RakamClient setSuperProperties(JSONObject superProperties) {
        this.superProperties = superProperties;
        dbHelper.insertOrReplaceKeyValue(SUPER_PROPERTIES_KEY, superProperties.toString());
        return this;
    }

    /**
     * Get super property keys for the user.
     *
     * @return the super properties
     */
    public JSONObject getSuperProperties() {
        return Utils.cloneJSONObject(superProperties);
    }

    /**
     * Enable foreground tracking for the SDK. This is <b>HIGHLY RECOMMENDED</b>, and will allow
     * for accurate session tracking.
     *
     * @param app the Android application
     * @return the RakamClient
     * @see <a href="https://github.com/buremba/rakam-android#tracking-sessions">
     * Tracking Sessions</a>
     */
    public RakamClient enableForegroundTracking(Application app) {
        if (usingForegroundTracking || !contextAndApiKeySet("enableForegroundTracking()")) {
            return this;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            app.registerActivityLifecycleCallbacks(new RakamCallbacks(this));
        }

        return this;
    }

    public RakamClient enableDiagnosticLogging() {
        if (!contextAndApiKeySet("enableDiagnosticLogging")) {
            return this;
        }
        Diagnostics.getLogger().enableLogging(httpClient, apiKey, deviceId);
        return this;
    }

    public RakamClient disableDiagnosticLogging() {
        Diagnostics.getLogger().disableLogging();
        return this;
    }

    public RakamClient setDiagnosticEventMaxCount(int eventMaxCount) {
        Diagnostics.getLogger().setDiagnosticEventMaxCount(eventMaxCount);
        return this;
    }

    /**
     * Whether to set a new device ID per install. If true, then the SDK will always generate a new
     * device ID on app install (as opposed to re-using an existing value like ADID).
     *
     * @param newDeviceIdPerInstall whether to set a new device ID on app install.
     * @return the RakamClient
     * @deprecated
     */
    public RakamClient enableNewDeviceIdPerInstall(boolean newDeviceIdPerInstall) {
        this.newDeviceIdPerInstall = newDeviceIdPerInstall;
        return this;
    }

    /**
     * Whether to use the Android advertising ID (ADID) as the user's device ID.
     *
     * @return the RakamClient
     */
    public RakamClient useAdvertisingIdForDeviceId() {
        this.useAdvertisingIdForDeviceId = true;
        return this;
    }

    /**
     * Enable location listening in the SDK. This will add the user's current lat/lon coordinates
     * to every event logged.
     *
     * @return the RakamClient
     */
    public RakamClient enableLocationListening() {
        runOnLogThread(new Runnable() {
            @Override
            public void run() {
                if (deviceInfo == null) {
                    throw new IllegalStateException(
                            "Must initialize before acting on location listening.");
                }
                deviceInfo.setLocationListening(true);
            }
        });
        return this;
    }

    /**
     * Disable location listening in the SDK. This will stop the sending of the user's current
     * lat/lon coordinates.
     *
     * @return the RakamClient
     */
    public RakamClient disableLocationListening() {
        runOnLogThread(new Runnable() {
            @Override
            public void run() {
                if (deviceInfo == null) {
                    throw new IllegalStateException(
                            "Must initialize before acting on location listening.");
                }
                deviceInfo.setLocationListening(false);
            }
        });
        return this;
    }

    /**
     * Sets event upload threshold. The SDK will attempt to batch upload unsent events
     * every eventUploadPeriodMillis milliseconds, or if the unsent event count exceeds the
     * event upload threshold.
     *
     * @param eventUploadThreshold the event upload threshold
     * @return the RakamClient
     */
    public RakamClient setEventUploadThreshold(int eventUploadThreshold) {
        this.eventUploadThreshold = eventUploadThreshold;
        return this;
    }

    /**
     * Sets event upload max batch size. This controls the maximum number of events sent with
     * each upload request.
     *
     * @param eventUploadMaxBatchSize the event upload max batch size
     * @return the RakamClient
     */
    public RakamClient setEventUploadMaxBatchSize(int eventUploadMaxBatchSize) {
        this.eventUploadMaxBatchSize = eventUploadMaxBatchSize;
        this.backoffUploadBatchSize = eventUploadMaxBatchSize;
        return this;
    }

    /**
     * Sets event max count. This is the maximum number of unsent events to keep on the device
     * (for example if the device does not have internet connectivity and cannot upload events).
     * If the number of unsent events exceeds the max count, then the SDK begins dropping events,
     * starting from the earliest logged.
     *
     * @param eventMaxCount the event max count
     * @return the RakamClient
     */
    public RakamClient setEventMaxCount(int eventMaxCount) {
        this.eventMaxCount = eventMaxCount;
        return this;
    }

    /**
     * Sets event upload period millis. The SDK will attempt to batch upload unsent events
     * every eventUploadPeriodMillis milliseconds, or if the unsent event count exceeds the
     * event upload threshold.
     *
     * @param eventUploadPeriodMillis the event upload period millis
     * @return the RakamClient
     */
    public RakamClient setEventUploadPeriodMillis(int eventUploadPeriodMillis) {
        this.eventUploadPeriodMillis = eventUploadPeriodMillis;
        return this;
    }

    /**
     * Sets min time between sessions millis.
     *
     * @param minTimeBetweenSessionsMillis the min time between sessions millis
     * @return the min time between sessions millis
     */
    public RakamClient setMinTimeBetweenSessionsMillis(long minTimeBetweenSessionsMillis) {
        this.minTimeBetweenSessionsMillis = minTimeBetweenSessionsMillis;
        return this;
    }

    /**
     * Sets session timeout millis. If foreground tracking has not been enabled with
     *
     * @param sessionTimeoutMillis the session timeout millis
     * @return the RakamClient
     * @{code enableForegroundTracking()}, then new sessions will be started after
     * sessionTimeoutMillis milliseconds have passed since the last event logged.
     */
    public RakamClient setSessionTimeoutMillis(long sessionTimeoutMillis) {
        this.sessionTimeoutMillis = sessionTimeoutMillis;
        return this;
    }

    public RakamClient setTrackingOptions(TrackingOptions trackingOptions) {
        this.trackingOptions = trackingOptions;
        this.apiPropertiesTrackingOptions = trackingOptions.getApiPropertiesTrackingOptions();
        return this;
    }

    /**
     * Sets opt out. If true then the SDK does not track any events for the user.
     *
     * @param optOut whether or not to opt the user out of tracking
     * @return the RakamClient
     */
    public RakamClient setOptOut(final boolean optOut) {
        if (!contextAndApiKeySet("setOptOut()")) {
            return this;
        }

        final RakamClient client = this;
        runOnLogThread(new Runnable() {
            @Override
            public void run() {
                if (Utils.isEmptyString(apiKey)) { // in case initialization failed
                    return;
                }
                client.optOut = optOut;
                dbHelper.insertOrReplaceKeyLongValue(OPT_OUT_KEY, optOut ? 1L : 0L);
            }
        });
        return this;
    }

    /**
     * Returns whether or not the user is opted out of tracking.
     *
     * @return the optOut flag value
     */
    public boolean isOptedOut() {
        return optOut;
    }

    /**
     * Enable/disable message logging by the SDK.
     *
     * @param enableLogging whether to enable message logging by the SDK.
     * @return the RakamClient
     */
    public RakamClient enableLogging(boolean enableLogging) {
        logger.setEnableLogging(enableLogging);
        return this;
    }

    /**
     * Sets the logging level. Logging messages will only appear if they are the same severity
     * level or higher than the set log level.
     *
     * @param logLevel the log level
     * @return the RakamClient
     */
    public RakamClient setLogLevel(int logLevel) {
        logger.setLogLevel(logLevel);
        return this;
    }

    /**
     * Sets offline. If offline is true, then the SDK will not upload events to Rakam servers;
     * however, it will still log events.
     *
     * @param offline whether or not the SDK should be offline
     * @return the RakamClient
     */
    public RakamClient setOffline(boolean offline) {
        this.offline = offline;

        // Try to update to the server once offline mode is disabled.
        if (!offline) {
            uploadEvents();
        }

        return this;
    }

    /**
     * Enable/disable flushing of unsent events on app close (enabled by default).
     *
     * @param flushEventsOnClose whether to flush unsent events on app close
     * @return the RakamClient
     */
    public RakamClient setFlushEventsOnClose(boolean flushEventsOnClose) {
        this.flushEventsOnClose = flushEventsOnClose;
        return this;
    }

    /**
     * Track session events rakam client. If enabled then the SDK will automatically send
     * start and end session events to mark the start and end of the user's sessions.
     *
     * @param trackingSessionEvents whether to enable tracking of session events
     * @return the RakamClient
     * @see <a href="https://github.com/buremba/rakam-android#tracking-sessions">
     * Tracking Sessions</a>
     */
    public RakamClient trackSessionEvents(boolean trackingSessionEvents) {
        this.trackingSessionEvents = trackingSessionEvents;
        return this;
    }

    /**
     * Set foreground tracking to true.
     */
    void useForegroundTracking() {
        usingForegroundTracking = true;
    }

    /**
     * Whether foreground tracking is enabled.
     *
     * @return whether foreground tracking is enabled
     */
    boolean isUsingForegroundTracking() {
        return usingForegroundTracking;
    }

    /**
     * Whether app is in the foreground.
     *
     * @return whether app is in the foreground
     */
    boolean isInForeground() {
        return inForeground;
    }

    /**
     * Log an event with the specified event type.
     * <b>Note:</b> this is asynchronous and happens on a background thread.
     *
     * @param eventType the event type
     */
    public void logEvent(String eventType) {
        logEvent(eventType, null);
    }

    /**
     * Log an event with the specified event type and event properties.
     * <b>Note:</b> this is asynchronous and happens on a background thread.
     *
     * @param eventType       the event type
     * @param eventProperties the event properties
     * @see <a href="https://github.com/buremba/rakam-android#setting-event-properties">
     * Setting Event Properties</a>
     */
    public void logEvent(String eventType, JSONObject eventProperties) {
        logEvent(eventType, eventProperties, false);
    }

    /**
     * Log an event with the specified event type and event properties.
     * <b>Note:</b> this is asynchronous and happens on a background thread.
     *
     * @param eventType       the event type
     * @param eventProperties the event properties
     * @param outOfSession    the out of session
     * @see <a href="https://github.com/buremba/rakam-android#setting-event-properties">
     * Setting Event Properties</a>
     */
    public void logEvent(String eventType, JSONObject eventProperties, boolean outOfSession) {
        if (validateLogEvent(eventType)) {
            logEvent(eventType, eventProperties, getCurrentTimeMillis(), outOfSession);
        }
    }

    /**
     * Log an event with the specified event type.
     * <b>Note:</b> this is version is synchronous and blocks the main thread until done.
     *
     * @param eventType the event type
     */
    public void logEventSync(String eventType) {
        logEventSync(eventType, null);
    }

    /**
     * Log an event with the specified event type and event properties.
     * <b>Note:</b> this is version is synchronous and blocks the main thread until done.
     *
     * @param eventType       the event type
     * @param eventProperties the event properties
     * @see <a href="https://github.com/buremba/rakam-android#setting-event-properties">
     * Setting Event Properties</a>
     */
    public void logEventSync(String eventType, JSONObject eventProperties) {
        logEventSync(eventType, eventProperties, false);
    }

    /**
     * Log an event with the specified event type, event properties, with optional out of session
     * flag. If out of session is true, then the sessionId will be -1 for the event, indicating
     * that it is not part of the current session. Note: this might be useful when logging events
     * for notifications received.
     * <b>Note:</b> this is version is synchronous and blocks the main thread until done.
     *
     * @param eventType       the event type
     * @param eventProperties the event properties
     * @param outOfSession    the out of session
     * @see <a href="https://github.com/buremba/rakam-android#setting-event-properties">
     * Setting Event Properties</a>
     * @see <a href="https://github.com/buremba/rakam-android#tracking-sessions">
     * Tracking Sessions</a>
     */
    public void logEventSync(String eventType, JSONObject eventProperties, boolean outOfSession) {
        if (validateLogEvent(eventType)) {
            logEvent(eventType, eventProperties, getCurrentTimeMillis(), outOfSession);
        }
    }

    /**
     * Validate the event type being logged. Also verifies that the context and API key
     * have been set already with an initialize call.
     *
     * @param eventType the event type
     * @return true if the event type is valid
     */
    protected boolean validateLogEvent(String eventType) {
        if (TextUtils.isEmpty(eventType)) {
            logger.e(TAG, "Argument eventType cannot be null or blank in logEvent()");
            return false;
        }

        return contextAndApiKeySet("logEvent()");
    }

    /**
     * Log event async. Internal method to handle the synchronous logging of events.
     *
     * @param eventType    the event type
     * @param properties   the request properties
     * @param timestamp    the timestamp
     * @param outOfSession the out of session
     */
    protected void logEventAsync(final String eventType, JSONObject properties,
                                 final long timestamp, final boolean outOfSession) {
        // Clone the incoming eventProperties object before sending over
        // to the log thread. Helps avoid ConcurrentModificationException
        // if the caller starts mutating the object they passed in.
        // Only does a shallow copy, so it's still possible, though unlikely,
        // to hit concurrent access if the caller mutates deep in the object.
        if (properties != null) {
            properties = Utils.cloneJSONObject(properties);
        }

        final JSONObject copyProperties = properties;
        runOnLogThread(new Runnable() {
            @Override
            public void run() {
                if (Utils.isEmptyString(apiKey)) {  // in case initialization failed
                    return;
                }

                logEvent(
                        eventType, copyProperties, timestamp, outOfSession
                );
            }
        });
    }

    /**
     * Log event. Internal method to handle the asynchronous logging of events on background
     * thread.
     *
     * @param eventType       the event type
     * @param eventProperties the event properties
     * @param timestamp       the timestamp
     * @param outOfSession    the out of session
     * @return the event ID if succeeded, else -1.
     */
    protected long logEvent(String eventType, JSONObject eventProperties, long timestamp, boolean outOfSession) {
        logger.d(TAG, "Logged event to Rakam: " + eventType);

        if (optOut) {
            return -1;
        }

        // skip session check if logging start_session or end_session events
        boolean loggingSessionEvent = trackingSessionEvents &&
                (eventType.equals(START_SESSION_EVENT) || eventType.equals(END_SESSION_EVENT));

        if (!loggingSessionEvent && !outOfSession) {
            // default case + corner case when async logEvent between onPause and onResume
            if (!inForeground) {
                startNewSessionIfNeeded(timestamp);
            } else {
                refreshSessionTime(timestamp);
            }
        }

        long result = -1;
        JSONObject properties = new JSONObject();
        try {
            properties.put("_id", UUID.randomUUID().toString());
            properties.put("_local_id", lastEventId);
            properties.put("_time", timestamp);
            properties.put("_user", replaceWithJSONNull(userId));
            properties.put("_device_id", replaceWithJSONNull(deviceId));
            properties.put("_session_id", outOfSession ? -1 : sessionId);

            if (trackingOptions.shouldTrackVersionName()) {
                properties.put("_version_name", replaceWithJSONNull(deviceInfo.getVersionName()));
            }

            if (trackingOptions.shouldTrackOsName()) {
                properties.put("_os_name", replaceWithJSONNull(deviceInfo.getOsName()));
            }

            if (trackingOptions.shouldTrackOsVersion()) {
                properties.put("_os_version", replaceWithJSONNull(deviceInfo.getOsVersion()));
            }

            if (trackingOptions.shouldTrackDeviceBrand()) {
                properties.put("_device_brand", replaceWithJSONNull(deviceInfo.getBrand()));
            }

            if (trackingOptions.shouldTrackDeviceManufacturer()) {
                properties.put("_device_manufacturer", replaceWithJSONNull(deviceInfo.getManufacturer()));
            }

            if (trackingOptions.shouldTrackDeviceModel()) {
                properties.put("_device_model", replaceWithJSONNull(deviceInfo.getModel()));
            }

            if (trackingOptions.shouldTrackCarrier()) {
                properties.put("_carrier", replaceWithJSONNull(deviceInfo.getCarrier()));
            }

            if (trackingOptions.shouldTrackCountry()) {
                properties.put("_country_code", replaceWithJSONNull(deviceInfo.getCountry()));
            }

            if (trackingOptions.shouldTrackLanguage()) {
                properties.put("_language", replaceWithJSONNull(deviceInfo.getLanguage()));
            }

            if (trackingOptions.shouldTrackPlatform()) {
                properties.put("_platform", platform);
            }
            properties.put("_library_name", Constants.LIBRARY);
            properties.put("_library_version", Constants.VERSION);
            properties.put("_ip", true);

            if (trackingOptions.shouldTrackLatLng()) {
                Location location = deviceInfo.getMostRecentLocation();
                if (location != null) {
                    properties.put("_latitude", location.getLatitude());
                    properties.put("_longitude", location.getLongitude());
                }
            }

            if (trackingOptions.shouldTrackAdid() && deviceInfo.getAdvertisingId() != null) {
                properties.put("_android_adid", deviceInfo.getAdvertisingId());
            }

            properties.put("_limit_ad_tracking", deviceInfo.isLimitAdTrackingEnabled());
            properties.put("_gps_enabled", deviceInfo.isGooglePlayServicesEnabled());

            if (eventProperties != null) {
                Iterator<String> keys = eventProperties.keys();
                while (keys.hasNext()) {
                    String next = keys.next();
                    properties.put(next, eventProperties.get(next));
                }
            }

            if (superProperties != null) {
                Iterator<String> keys = superProperties.keys();
                while (keys.hasNext()) {
                    String next = keys.next();
                    if (eventProperties != null && eventProperties.has(next)) {
                        continue;
                    }
                    properties.put(next, superProperties.get(next));
                }
            }

            JSONObject event = new JSONObject();
            event.put("properties", truncate(properties));
            event.put("collection", replaceWithJSONNull(eventType));
            result = saveEvent(eventType, event);
        } catch (JSONException e) {
            logger.e(TAG, String.format(
                    "JSON Serialization of event type %s failed, skipping: %s", eventType, e.toString()
            ));
            Diagnostics.getLogger().logError(
                    String.format("Failed to JSON serialize event type %s", eventType), e
            );
        }

        return result;
    }

    /**
     * Save event long. Internal method to save an event to the database.
     *
     * @param eventType the event type
     * @param event     the event
     * @return the event ID if succeeded, else -1
     */
    protected long saveEvent(String eventType, JSONObject event) {
        String eventString = event.toString();
        if (Utils.isEmptyString(eventString)) {
            logger.e(TAG, String.format(
                    "Detected empty event string for event type %s, skipping", eventType
            ));
            return -1;
        }

        if (eventType.equals(Constants.IDENTIFY_EVENT)) {
            lastIdentifyId = dbHelper.addIdentify(eventString);
            setLastIdentifyId(lastIdentifyId);
        } else {
            lastEventId = dbHelper.addEvent(eventString);
            setLastEventId(lastEventId);
        }

        int numEventsToRemove = Math.min(
                Math.max(1, eventMaxCount/10),
                Constants.EVENT_REMOVE_BATCH_SIZE
        );
        if (dbHelper.getEventCount() > eventMaxCount) {
            dbHelper.removeEvents(dbHelper.getNthEventId(numEventsToRemove));
        }
        if (dbHelper.getIdentifyCount() > eventMaxCount) {
            dbHelper.removeIdentifys(dbHelper.getNthIdentifyId(numEventsToRemove));
        }

        long totalEventCount = dbHelper.getTotalEventCount(); // counts may have changed, refetch
        if ((totalEventCount % eventUploadThreshold) == 0 &&
                totalEventCount >= eventUploadThreshold) {
            updateServer();
        } else {
            updateServerLater(eventUploadPeriodMillis);
        }

        return (eventType.equals(Constants.IDENTIFY_EVENT)
        ) ? lastIdentifyId : lastEventId;
    }

    // fetches key from dbHelper longValueStore
    // if key does not exist, return defaultValue instead
    private long getLongvalue(String key, long defaultValue) {
        Long value = dbHelper.getLongValue(key);
        return value == null ? defaultValue : value;
    }

    /**
     * Internal method to set the last event time.
     *
     * @param timestamp the timestamp
     */
    void setLastEventTime(long timestamp) {
        lastEventTime = timestamp;
        dbHelper.insertOrReplaceKeyLongValue(LAST_EVENT_TIME_KEY, timestamp);
    }

    /**
     * Internal method to set the last event id.
     *
     * @param eventId the event id
     */
    void setLastEventId(long eventId) {
        lastEventId = eventId;
        dbHelper.insertOrReplaceKeyLongValue(LAST_EVENT_ID_KEY, eventId);
    }

    /**
     * Internal method to set the last identify id.
     *
     * @param identifyId the identify id
     */
    void setLastIdentifyId(long identifyId) {
        lastIdentifyId = identifyId;
        dbHelper.insertOrReplaceKeyLongValue(LAST_IDENTIFY_ID_KEY, identifyId);
    }

    /**
     * Gets the current session id.
     *
     * @return The current sessionId value.
     */
    public long getSessionId() {
        return sessionId;
    }

    /**
     * Internal method to set the previous session id.
     *
     * @param timestamp the timestamp
     */
    void setPreviousSessionId(long timestamp) {
        previousSessionId = timestamp;
        dbHelper.insertOrReplaceKeyLongValue(PREVIOUS_SESSION_ID_KEY, timestamp);
    }

    /**
     * Public method to start a new session if needed.
     *
     * @param timestamp the timestamp
     * @return whether or not a new session was started
     */
    public boolean startNewSessionIfNeeded(long timestamp) {
        if (inSession()) {

            if (isWithinMinTimeBetweenSessions(timestamp)) {
                refreshSessionTime(timestamp);
                return false;
            }

            startNewSession(timestamp);
            return true;
        }

        // no current session - check for previous session
        if (isWithinMinTimeBetweenSessions(timestamp)) {
            if (previousSessionId == -1) {
                startNewSession(timestamp);
                return true;
            }

            // extend previous session
            setSessionId(previousSessionId);
            refreshSessionTime(timestamp);
            return false;
        }

        startNewSession(timestamp);
        return true;
    }

    private void startNewSession(long timestamp) {
        // end previous session
        if (trackingSessionEvents) {
            sendSessionEvent(END_SESSION_EVENT);
        }

        // start new session
        setSessionId(timestamp);
        refreshSessionTime(timestamp);
        if (trackingSessionEvents) {
            sendSessionEvent(START_SESSION_EVENT);
        }
    }

    private boolean inSession() {
        return sessionId >= 0;
    }

    private boolean isWithinMinTimeBetweenSessions(long timestamp) {
        long sessionLimit = usingForegroundTracking ?
                minTimeBetweenSessionsMillis : sessionTimeoutMillis;
        return (timestamp - lastEventTime) < sessionLimit;
    }

    private void setSessionId(long timestamp) {
        sessionId = timestamp;
        setPreviousSessionId(timestamp);
    }

    /**
     * Internal method to refresh the current session time.
     *
     * @param timestamp the timestamp
     */
    void refreshSessionTime(long timestamp) {
        if (!inSession()) {
            return;
        }

        setLastEventTime(timestamp);
    }

    private void sendSessionEvent(final String sessionEvent) {
        if (!contextAndApiKeySet(String.format("sendSessionEvent('%s')", sessionEvent))) {
            return;
        }

        if (!inSession()) {
            return;
        }

        logEvent(sessionEvent, null, lastEventTime, false);
    }

    /**
     * Internal method to handle on app exit foreground behavior.
     *
     * @param timestamp the timestamp
     */
    void onExitForeground(final long timestamp) {
        runOnLogThread(new Runnable() {
            @Override
            public void run() {
                if (Utils.isEmptyString(apiKey)) {
                    return;
                }
                refreshSessionTime(timestamp);
                inForeground = false;
                if (flushEventsOnClose) {
                    updateServer();
                }

                // re-persist metadata into database for good measure
                dbHelper.insertOrReplaceKeyValue(DEVICE_ID_KEY, deviceId);
                dbHelper.insertOrReplaceKeyValue(USER_ID_KEY, userId);
                dbHelper.insertOrReplaceKeyLongValue(OPT_OUT_KEY, optOut ? 1L : 0L);
                dbHelper.insertOrReplaceKeyLongValue(PREVIOUS_SESSION_ID_KEY, sessionId);
                dbHelper.insertOrReplaceKeyLongValue(LAST_EVENT_TIME_KEY, lastEventTime);
            }
        });
    }

    /**
     * Internal method to handle on app enter foreground behavior.
     *
     * @param timestamp the timestamp
     */
    void onEnterForeground(final long timestamp) {
        runOnLogThread(new Runnable() {
            @Override
            public void run() {
                if (Utils.isEmptyString(apiKey)) {
                    return;
                }
                startNewSessionIfNeeded(timestamp);
                inForeground = true;
            }
        });
    }


    /**
     * Log revenue. Create a {@link io.rakam.api.Revenue} object to hold your revenue data and
     * properties, and log it as a revenue event using {@code logRevenue}.
     *
     * @param revenue a {@link io.rakam.api.Revenue} object
     * @see io.rakam.api.Revenue
     * @see <a href="https://github.com/rakam/Rakam-Android#tracking-revenue">
     * Tracking Revenue</a>
     */
    public void logRevenue(Revenue revenue) {
        if (!contextAndApiKeySet("logRevenue()") || revenue == null || !revenue.isValidRevenue()) {
            return;
        }

        logEvent(Constants.REVENUE_EVENT, revenue.toJSONObject());
    }

    /**
     * Sets user properties. This is a convenience wrapper around the
     * {@link io.rakam.api.Identify} API to set multiple user properties with a single
     * command. <b>Note:</b> the replace parameter is deprecated and has no effect.
     *
     * @param userProperties the user properties
     * @param replace        the replace - has no effect
     * @see <a href="https://github.com/rakam/Rakam-Android#user-properties-and-user-property-operations">
     * User Properties</a>
     * @deprecated
     */
    public void setUserProperties(final JSONObject userProperties, final boolean replace) {
        setUserProperties(userProperties);
    }

    /**
     * Sets user properties. This is a convenience wrapper around the
     * {@link io.rakam.api.Identify} API to set multiple user properties with a single
     * command.
     *
     * @param userProperties the user properties
     * @see <a href="https://github.com/rakam/Rakam-Android#user-properties-and-user-property-operations">
     * User Properties</a>
     */
    public void setUserProperties(final JSONObject userProperties) {
        if (userProperties == null || userProperties.length() == 0 ||
                !contextAndApiKeySet("setUserProperties")) {
            return;
        }

        // sanitize and truncate properties before trying to convert to identify
        JSONObject sanitized = truncate(userProperties);
        if (sanitized.length() == 0) {
            return;
        }

        Identify identify = new Identify();
        Iterator<?> keys = sanitized.keys();
        while (keys.hasNext()) {
            String key = (String) keys.next();
            try {
                identify.setUserProperty(key, sanitized.get(key));
            } catch (JSONException e) {
                logger.e(TAG, e.toString());
                Diagnostics.getLogger().logError(
                        String.format("Failed to set user property %s", key), e
                );
            }
        }
        identify(identify);
    }

    /**
     * Clear user properties. This will clear all user properties at once. <b>Note: the
     * result is irreversible!</b>
     *
     * @see <a href="https://github.com/rakam/Rakam-Android#user-properties-and-user-property-operations">
     * User Properties</a>
     */
    public void clearUserProperties() {
        Identify identify = new Identify().clearAll();
        identify(identify);
    }

    /**
     * Clear super properties. This will clear all super properties at once. <b>Note: the
     * result is irreversible!</b>
     *
     * @see <a href="https://github.com/buremba/rakam-android#super-properties">
     * Super Properties</a>
     */
    public void clearSuperProperties() {
        dbHelper.insertOrReplaceKeyValue(SUPER_PROPERTIES_KEY, null);
        superProperties = null;
    }


    /**
     * Identify. Use this to send an {@link io.rakam.api.Identify} object containing
     * user property operations to Rakam server.
     *
     * @param identify an {@link io.rakam.api.Identify} object
     * @see io.rakam.api.Identify
     * @see <a href="https://github.com/rakam/Rakam-Android#user-properties-and-user-property-operations">
     * User Properties</a>
     */
    public void identify(Identify identify) {
        identify(identify, false);
    }

    /**
     * Identify. Use this to send an {@link io.rakam.api.Identify} object containing
     * user property operations to Rakam server. If outOfSession is true, then the identify
     * event is sent with a session id of -1, and does not trigger any session-handling logic.
     *
     * @param identify     an {@link io.rakam.api.Identify} object
     * @param outOfSession whther to log the identify event out of session
     * @see io.rakam.api.Identify
     * @see <a href="https://github.com/rakam/Rakam-Android#user-properties-and-user-property-operations">
     * User Properties</a>
     */
    public void identify(Identify identify, boolean outOfSession) {
        if (
                identify == null || identify.userPropertiesOperations.length() == 0 ||
                        !contextAndApiKeySet("identify()")
        ) return;
        logEventAsync(
                Constants.IDENTIFY_EVENT, identify.userPropertiesOperations, getCurrentTimeMillis(), outOfSession
        );
    }

    /**
     * Truncate values in a JSON object. Any string values longer than 1024 characters will be
     * truncated to 1024 characters.
     *
     * @param object the object
     * @return the truncated JSON object
     */
    public JSONObject truncate(JSONObject object) {
        if (object == null) {
            return new JSONObject();
        }

        if (object.length() > Constants.MAX_PROPERTY_KEYS) {
            logger.w(TAG, "Warning: too many properties (more than 1000), ignoring");
            return new JSONObject();
        }

        Iterator<?> keys = object.keys();
        while (keys.hasNext()) {
            String key = (String) keys.next();

            try {
                Object value = object.get(key);
                // do not truncate revenue receipt and receipt sig fields
                if (value.getClass().equals(String.class)) {
                    object.put(key, truncate((String) value));
                } else if (value.getClass().equals(JSONObject.class)) {
                    object.put(key, truncate((JSONObject) value));
                } else if (value.getClass().equals(JSONArray.class)) {
                    object.put(key, truncate((JSONArray) value));
                }
            } catch (JSONException e) {
                logger.e(TAG, e.toString());
            }
        }

        return object;
    }

    /**
     * Truncate values in a JSON array. Any string values longer than 1024 characters will be
     * truncated to 1024 characters.
     *
     * @param array the array
     * @return the truncated JSON array
     * @throws JSONException the json exception
     */
    public JSONArray truncate(JSONArray array) throws JSONException {
        if (array == null) {
            return new JSONArray();
        }

        for (int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            if (value.getClass().equals(String.class)) {
                array.put(i, truncate((String) value));
            } else if (value.getClass().equals(JSONObject.class)) {
                array.put(i, truncate((JSONObject) value));
            } else if (value.getClass().equals(JSONArray.class)) {
                array.put(i, truncate((JSONArray) value));
            }
        }
        return array;
    }

    /**
     * Truncate a string to 1024 characters.
     *
     * @param value the value
     * @return the truncated string
     */
    static String truncate(String value) {
        return value.length() <= MAX_STRING_LENGTH ? value : value.substring(0, MAX_STRING_LENGTH);
    }

    /**
     * Gets the user's id. Can be null.
     *
     * @return The developer specified identifier for tracking within the analytics system.
     */
    public Object getUserId() {
        return userId;
    }

    /**
     * Sets the user id (can be null).
     *
     * @param userId the user id
     * @return the RakamClient
     */
    public RakamClient setUserId(final String userId) {
        return setUserId(userId, false);
    }

    /**
     * Sets the user id (can be null).
     * If startNewSession is true, ends the session for the previous user and starts a new
     * session for the new user id.
     *
     * @param userId the user id
     * @return the RakamClient
     */
    public RakamClient setUserId(final String userId, final boolean startNewSession) {
        if (!contextAndApiKeySet("setUserId()")) {
            return this;
        }

        final RakamClient client = this;
        runOnLogThread(new Runnable() {
            @Override
            public void run() {
                if (Utils.isEmptyString(client.apiKey)) {  // in case initialization failed
                    return;
                }

                // end previous session
                if (startNewSession && trackingSessionEvents) {
                    sendSessionEvent(END_SESSION_EVENT);
                }

                client.userId = userId;
                dbHelper.insertOrReplaceKeyValue(USER_ID_KEY, userId);

                // start new session
                if (startNewSession) {
                    long timestamp = getCurrentTimeMillis();
                    setSessionId(timestamp);
                    refreshSessionTime(timestamp);
                    if (trackingSessionEvents) {
                        sendSessionEvent(START_SESSION_EVENT);
                    }
                }
            }
        });
        return this;
    }

    /**
     * Sets the user id (can be null).
     *
     * @param userId the user id
     * @return the RakamClient
     */
    public RakamClient setUserId(int userId) {
        return setUserId(String.valueOf(userId));
    }

    /**
     * Sets a custom device id. <b>Note: only do this if you know what you are doing!</b>
     *
     * @param deviceId the device id
     * @return the RakamClient
     * @see <a href="https://github.com/buremba/rakam-android#custom-device-ids">
     * Custom Device Ids</a>
     */
    public RakamClient setDeviceId(final String deviceId) {
        Set<String> invalidDeviceIds = getInvalidDeviceIds();
        if (!contextAndApiKeySet("setDeviceId()") || Utils.isEmptyString(deviceId) ||
                invalidDeviceIds.contains(deviceId)) {
            return this;
        }

        final RakamClient client = this;
        runOnLogThread(new Runnable() {
            @Override
            public void run() {
                if (Utils.isEmptyString(client.apiKey)) {  // in case initialization failed
                    return;
                }
                client.deviceId = deviceId;
                saveDeviceId(deviceId);
            }
        });
        return this;
    }

    /**
     * Regenerates a new random deviceId for current user. Note: this is not recommended unless you
     * know what you are doing. This can be used in conjunction with setUserId(null) to anonymize
     * users after they log out. With a null userId and a completely new deviceId, the current user
     * would appear as a brand new user in dashboard.
     *
     * @see <a href="https://github.com/rakam/Rakam-Android#logging-out-and-anonymous-users">
     * Logging Out Users</a>
     */
    public RakamClient regenerateDeviceId() {
        if (!contextAndApiKeySet("regenerateDeviceId()")) {
            return this;
        }

        final RakamClient client = this;
        runOnLogThread(new Runnable() {
            @Override
            public void run() {
                if (Utils.isEmptyString(client.apiKey)) { // in case initialization failed
                    return;
                }
                String randomId = DeviceInfo.generateUUID() + "R";
                setDeviceId(randomId);
            }
        });
        return this;
    }

    /**
     * Force SDK to upload any unsent events.
     */
    public void uploadEvents() {
        if (!contextAndApiKeySet("uploadEvents()")) {
            return;
        }

        logThread.post(new Runnable() {
            @Override
            public void run() {
                if (Utils.isEmptyString(apiKey)) {  // in case initialization failed
                    return;
                }
                updateServer();
            }
        });
    }

    private void updateServerLater(long delayMillis) {
        if (updateScheduled.getAndSet(true)) {
            return;
        }

        logThread.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateScheduled.set(false);
                updateServer();
            }
        }, delayMillis);
    }

    /**
     * Internal method to upload unsent events.
     */
    protected void updateServer() {
        updateServer(false);
        Diagnostics.getLogger().flushEvents();
    }

    /**
     * Internal method to upload unsent events. Limit controls whether to use event upload max
     * batch size or backoff upload batch size. <b>Note: </b> always call this on logThread
     *
     * @param limit the limit
     */
    protected void updateServer(boolean limit) {
        if (optOut || offline) {
            return;
        }

        // if returning out of this block, always be sure to set uploadingCurrently to false!!
        if (!uploadingCurrently.getAndSet(true)) {
            long totalEventCount = dbHelper.getTotalEventCount();
            long batchSize = Math.min(
                    limit ? backoffUploadBatchSize : eventUploadMaxBatchSize,
                    totalEventCount
            );

            if (batchSize <= 0) {
                uploadingCurrently.set(false);
                return;
            }

            try {
                List<JSONObject> events = dbHelper.getEvents(lastEventId, batchSize);
                List<JSONObject> identifys = dbHelper.getIdentifys(lastIdentifyId, batchSize);

                final Pair<Pair<Long, Long>, JSONArray> merged = mergeEventsAndIdentifys(events, identifys, batchSize);
                final JSONArray mergedEvents = merged.second;
                if (mergedEvents.length() == 0) {
                    uploadingCurrently.set(false);
                    return;
                }
                final long maxEventId = merged.first.first;
                final long maxIdentifyId = merged.first.second;

                final String body;
                try {
                    body = new JSONObject().put("api", getApi()).put("events", merged.second).toString();
                } catch (JSONException e) {
                    uploadingCurrently.set(false);
                    logger.e(TAG, e.toString());
                    return;
                }

                httpThread.post(new Runnable() {
                    @Override
                    public void run() {
                        makeEventUploadPostRequest(httpClient, body, maxEventId, maxIdentifyId);
                    }
                });
            } catch (JSONException e) {
                uploadingCurrently.set(false);
                logger.e(TAG, e.toString());
                Diagnostics.getLogger().logError("Failed to update server", e);

                // handle CursorWindowAllocationException when fetching events, defer upload
            } catch (CursorWindowAllocationException e) {
                uploadingCurrently.set(false);
                logger.e(TAG, String.format(
                        "Caught Cursor window exception during event upload, deferring upload: %s",
                        e.getMessage()
                ));
                Diagnostics.getLogger().logError("Failed to update server", e);
            }
        }
    }

    /**
     * Internal method to merge unsent events and identifies into a single array by sequence number.
     *
     * @param events    the events
     * @param identifys the identifys
     * @param numEvents the num events
     * @return the merged array, max event id, and max identify id
     * @throws JSONException the json exception
     */
    protected Pair<Pair<Long, Long>, JSONArray> mergeEventsAndIdentifys(List<JSONObject> events,
                                                                        List<JSONObject> identifys, long numEvents) throws JSONException {
        JSONArray merged = new JSONArray();
        long maxEventId = -1;
        long maxIdentifyId = -1;

        while (merged.length() < numEvents) {
            boolean noEvents = events.isEmpty();
            boolean noIdentifys = identifys.isEmpty();

            // case 0: no events or identifys, nothing to grab
            // this case should never happen, as it means there are less identifys and events
            // than expected
            if (noEvents && noIdentifys) {
                logger.w(TAG, String.format(
                        "mergeEventsAndIdentifys: number of events and identifys " +
                                "less than expected by %d", numEvents - merged.length())
                );
                break;

                // case 1: no identifys, grab from events
            } else if (noIdentifys) {
                JSONObject event = events.remove(0);
                maxEventId = event.getLong("event_id");
                merged.put(event);

                // case 2: no events, grab from identifys
            } else if (noEvents) {
                JSONObject identify = identifys.remove(0);
                maxIdentifyId = identify.getLong("event_id");
                merged.put(identify);

                // case 3: need to compare sequence numbers
            } else {
                // events logged before v2.1.0 won't have a sequence number, put those first
                if (!events.get(0).has("event_id") ||
                        events.get(0).getLong("event_id") <
                                identifys.get(0).getLong("event_id")) {
                    JSONObject event = events.remove(0);
                    maxEventId = event.getLong("event_id");
                    merged.put(event);
                } else {
                    JSONObject identify = identifys.remove(0);
                    maxIdentifyId = identify.getLong("event_id");
                    merged.put(identify);
                }
            }
        }

        return new Pair<Pair<Long, Long>, JSONArray>(new Pair<Long, Long>(maxEventId, maxIdentifyId), merged);
    }

    private JSONObject getApi()
            throws JSONException {
        return new JSONObject()
                .put("api_key", apiKey)
                .put("library", new JSONObject()
                        .put("name", Constants.LIBRARY)
                        .put("version", Constants.VERSION))
                .put("upload_time", getCurrentTimeMillis());
    }

    /**
     * Internal method to generate the event upload post request.
     *
     * @param client        the client
     * @param body        request body
     * @param maxEventId    the max event id
     * @param maxIdentifyId the max identify id
     */
    protected void makeEventUploadPostRequest(OkHttpClient client, String body, final long maxEventId, final long maxIdentifyId) {
        Request request;
        try {
            request = new Request.Builder()
                    .url(apiUrl + EVENT_BATCH_ENDPOINT)
                    .post(RequestBody.create(JSON, body))
                    .build();
        } catch (IllegalArgumentException e) {
            logger.e(TAG, e.toString());
            uploadingCurrently.set(false);
            Diagnostics.getLogger().logError("Failed to build upload request", e);
            return;
        }

        boolean uploadSuccess = false;

        try {
            Response response = client.newCall(request).execute();
            String stringResponse = response.body().string();
            if (stringResponse.equals("1")) {
                uploadSuccess = true;
                logThread.post(new Runnable() {
                    @Override
                    public void run() {
                        if (maxEventId >= 0) dbHelper.removeEvents(maxEventId);
                        if (maxIdentifyId >= 0) dbHelper.removeIdentifys(maxIdentifyId);
                        uploadingCurrently.set(false);
                        if (dbHelper.getTotalEventCount() > eventUploadThreshold) {
                            logThread.post(new Runnable() {
                                @Override
                                public void run() {
                                    updateServer(backoffUpload);
                                }
                            });
                        } else {
                            backoffUpload = false;
                            backoffUploadBatchSize = eventUploadMaxBatchSize;
                        }
                    }
                });
            } else if (response.code() == 403) {
                logger.e(TAG, "Invalid API key, make sure your API key is correct in initialize()");
            } else if (stringResponse.equals("bad_checksum")) {
                logger.w(TAG,
                        "Bad checksum, post request was mangled in transit, will attempt to reupload later");
            } else if (stringResponse.equals("request_db_write_failed")) {
                logger.w(TAG,
                        "Couldn't write to request database on server, will attempt to reupload later");
            } else if (response.code() == 413 || response.code() == 400) {

                // If blocked by one massive event, drop it
                if (backoffUpload && backoffUploadBatchSize == 1) {
                    if (maxEventId >= 0) dbHelper.removeEvent(maxEventId);
                    if (maxIdentifyId >= 0) dbHelper.removeIdentify(maxIdentifyId);
                    // maybe we want to reset backoffUploadBatchSize after dropping massive event
                }

                // Server complained about length of request, backoff and try again
                backoffUpload = true;
                int numEvents = Math.min((int) dbHelper.getEventCount(), backoffUploadBatchSize);
                backoffUploadBatchSize = (int) Math.ceil(numEvents / 2.0);
                logger.w(TAG, String.format("Request too large or invalid: %s, will decrease size and attempt to reupload", response.code()));
                logThread.post(new Runnable() {
                    @Override
                    public void run() {
                        uploadingCurrently.set(false);
                        updateServer(true);
                    }
                });
            } else if (response.code() == 500) {
                logger.w(TAG,
                        "A server error occurred, will attempt to reupload later");
            } else {
                logger.w(TAG, "Upload failed, " + stringResponse + ", will attempt to reupload later");
            }
        } catch (java.net.ConnectException e) {
            // logger.w(TAG,
            // "No internet connection found, unable to upload events");
            lastError = e;
            Diagnostics.getLogger().logError("Failed to post upload request", e);
        } catch (java.net.UnknownHostException e) {
            // logger.w(TAG,
            // "No internet connection found, unable to upload events");
            lastError = e;
            Diagnostics.getLogger().logError("Failed to post upload request", e);
        } catch (IOException e) {
            logger.e(TAG, e.toString());
            lastError = e;
            Diagnostics.getLogger().logError("Failed to post upload request", e);
        } catch (AssertionError e) {
            // This can be caused by a NoSuchAlgorithmException thrown by DefaultHttpClient
            logger.e(TAG, "Exception:", e);
            lastError = e;
            Diagnostics.getLogger().logError("Failed to post upload request", e);
        } catch (Exception e) {
            // Just log any other exception so things don't crash on upload
            logger.e(TAG, "Exception:", e);
            lastError = e;
            Diagnostics.getLogger().logError("Failed to post upload request", e);
        }

        if (!uploadSuccess) {
            uploadingCurrently.set(false);
        }

    }

    /**
     * Get the current device id. Can be null if deviceId hasn't been initialized yet.
     *
     * @return A unique identifier for tracking within the analytics system.
     */
    public String getDeviceId() {
        return deviceId;
    }

    // don't need to keep this in memory, if only using it at most 1 or 2 times
    private Set<String> getInvalidDeviceIds() {
        Set<String> invalidDeviceIds = new HashSet<String>();
        invalidDeviceIds.add("");
        invalidDeviceIds.add("9774d56d682e549c");
        invalidDeviceIds.add("unknown");
        invalidDeviceIds.add("000000000000000"); // Common Serial Number
        invalidDeviceIds.add("00000000-0000-0000-0000-000000000000"); // Empty UUID
        invalidDeviceIds.add("Android");
        invalidDeviceIds.add("DEFACE");

        return invalidDeviceIds;
    }

    private String initializeDeviceId() {
        Set<String> invalidIds = getInvalidDeviceIds();

        // see if device id already stored in db
        String deviceId = dbHelper.getValue(DEVICE_ID_KEY);
        String sharedPrefDeviceId = Utils.getStringFromSharedPreferences(context, instanceName, DEVICE_ID_KEY);
        if (!(Utils.isEmptyString(deviceId) || invalidIds.contains(deviceId))) {
            // compare against device id stored in backup storage and update if necessary
            if (!deviceId.equals(sharedPrefDeviceId)) {
                saveDeviceId(deviceId);
            }

            return deviceId;
        }

        // backup #1: check if device id is stored in shared preferences
        if (!(Utils.isEmptyString(sharedPrefDeviceId) || invalidIds.contains(sharedPrefDeviceId))) {
            saveDeviceId(sharedPrefDeviceId);
            return sharedPrefDeviceId;
        }

        if (!newDeviceIdPerInstall && useAdvertisingIdForDeviceId && !deviceInfo.isLimitAdTrackingEnabled()) {
            // Android ID is deprecated by Google.
            // We are required to use Advertising ID, and respect the advertising ID preference

            String advertisingId = deviceInfo.getAdvertisingId();
            if (!(Utils.isEmptyString(advertisingId) || invalidIds.contains(advertisingId))) {
                saveDeviceId(advertisingId);
                return advertisingId;
            }
        }

        // If this still fails, generate random identifier that does not persist
        // across installations. Append R to distinguish as randomly generated
        String randomId = deviceInfo.generateUUID() + "R";
        saveDeviceId(randomId);
        return randomId;
    }

    private void saveDeviceId(String deviceId) {
        dbHelper.insertOrReplaceKeyValue(DEVICE_ID_KEY, deviceId);
        Utils.writeStringToSharedPreferences(context, instanceName, DEVICE_ID_KEY, deviceId);
    }

    private void runOnLogThread(Runnable r) {
        if (Thread.currentThread() != logThread) {
            logThread.post(r);
        } else {
            r.run();
        }
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(URL apiUrl) {
        if (apiUrl == null) {
            logger.e(TAG, "apiUrl can't be null");
            return;
        }

        String scheme = apiUrl.getProtocol();
        String serverName = apiUrl.getHost();
        int serverPort = apiUrl.getPort();

        String address = scheme + "://" + serverName;

        if (apiUrl.getPath() != null && !(apiUrl.getPath().equals("/") || apiUrl.getPath().isEmpty())) {
            throw new IllegalStateException(String.format("Please set root address of the API address." +
                    " A valid example is %s, %s is not valid.", address, apiUrl.toString()));
        }

        if (serverPort > -1) {
            address = address + ":" + serverPort;
        }

        this.apiUrl = address;
    }

    /**
     * Internal method to replace null event fields with JSON null object.
     *
     * @param obj the obj
     * @return the object
     */
    protected Object replaceWithJSONNull(Object obj) {
        return obj == null ? JSONObject.NULL : obj;
    }

    /**
     * Internal method to check whether application context and api key are set
     *
     * @param methodName the parent method name to print in error message
     * @return whether application context and api key are set
     */
    protected synchronized boolean contextAndApiKeySet(String methodName) {
        if (context == null) {
            logger.e(TAG, "context cannot be null, set context with initialize() before calling "
                    + methodName);
            return false;
        }
        if (TextUtils.isEmpty(apiKey)) {
            logger.e(TAG,
                    "apiKey cannot be null or empty, set apiKey with initialize() before calling "
                            + methodName);
            return false;
        }
        return true;
    }

    /**
     * Internal method to convert bytes to hex string
     *
     * @param bytes the bytes
     * @return the string
     */
    protected String bytesToHexString(byte[] bytes) {
        final char[] hexArray = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b',
                'c', 'd', 'e', 'f'};
        char[] hexChars = new char[bytes.length * 2];
        int v;
        for (int j = 0; j < bytes.length; j++) {
            v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    /**
     * Move all preference data from the legacy name to the new, static name if needed.
     * <p>
     * Constants.PACKAGE_NAME used to be set using "Constants.class.getPackage().getName()"
     * Some aggressive proguard optimizations broke the reflection and caused apps
     * to crash on startup.
     * <p>
     * Now that Constants.PACKAGE_NAME is changed, old data on devices needs to be
     * moved over to the new location so that device ids remain consistent.
     * <p>
     * This should only happen once -- the first time a user loads the app after updating.
     * This logic needs to remain in place for quite a long time. It was first introduced in
     * April 2015 in version 1.6.0.
     *
     * @param context the context
     * @return the boolean
     */
    static boolean upgradePrefs(Context context) {
        return upgradePrefs(context, null, null);
    }

    /**
     * Upgrade prefs boolean.
     *
     * @param context       the context
     * @param sourcePkgName the source pkg name
     * @param targetPkgName the target pkg name
     * @return the boolean
     */
    static boolean upgradePrefs(Context context, String sourcePkgName, String targetPkgName) {
        try {
            if (sourcePkgName == null) {
                // Try to load the package name using the old reflection strategy.
                sourcePkgName = Constants.PACKAGE_NAME;
                try {
                    sourcePkgName = Constants.class.getPackage().getName();
                } catch (Exception e) {
                }
            }

            if (targetPkgName == null) {
                targetPkgName = Constants.PACKAGE_NAME;
            }

            // No need to copy if the source and target are the same.
            if (targetPkgName.equals(sourcePkgName)) {
                return false;
            }

            // Copy over any preferences that may exist in a source preference store.
            String sourcePrefsName = sourcePkgName + "." + context.getPackageName();
            SharedPreferences source =
                    context.getSharedPreferences(sourcePrefsName, Context.MODE_PRIVATE);

            // Nothing left in the source store to copy
            if (source.getAll().size() == 0) {
                return false;
            }

            String prefsName = targetPkgName + "." + context.getPackageName();
            SharedPreferences targetPrefs =
                    context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            SharedPreferences.Editor target = targetPrefs.edit();

            // Copy over all existing data.
            if (source.contains(sourcePkgName + ".previousSessionId")) {
                target.putLong(Constants.PREFKEY_PREVIOUS_SESSION_ID,
                        source.getLong(sourcePkgName + ".previousSessionId", -1));
            }
            if (source.contains(sourcePkgName + ".deviceId")) {
                target.putString(Constants.PREFKEY_DEVICE_ID,
                        source.getString(sourcePkgName + ".deviceId", null));
            }
            if (source.contains(sourcePkgName + ".userId")) {
                target.putString(Constants.PREFKEY_USER_ID,
                        source.getString(sourcePkgName + ".userId", null));
            }
            if (source.contains(sourcePkgName + ".optOut")) {
                target.putBoolean(Constants.PREFKEY_OPT_OUT,
                        source.getBoolean(sourcePkgName + ".optOut", false));
            }

            // Commit the changes and clear the source store so we don't recopy.
            target.apply();
            source.edit().clear().apply();

            logger.i(TAG, "Upgraded shared preferences from " + sourcePrefsName + " to " + prefsName);
            return true;
        } catch (Exception e) {
            logger.e(TAG, "Error upgrading shared preferences", e);
            Diagnostics.getLogger().logError("Failed to upgrade shared prefs", e);
            return false;
        }
    }

    /**
     * Upgrade shared prefs to db boolean.
     *
     * @param context the context
     * @return the boolean
     */
    /*
     * Move all data from sharedPrefs to sqlite key value store to support multi-process apps.
     * sharedPrefs is known to not be process-safe.
     */
    static boolean upgradeSharedPrefsToDB(Context context) {
        return upgradeSharedPrefsToDB(context, null);
    }

    /**
     * Upgrade shared prefs to db boolean.
     *
     * @param context       the context
     * @param sourcePkgName the source pkg name
     * @return the boolean
     */
    static boolean upgradeSharedPrefsToDB(Context context, String sourcePkgName) {
        if (sourcePkgName == null) {
            sourcePkgName = Constants.PACKAGE_NAME;
        }

        // check if upgrade needed
        DatabaseHelper dbHelper = DatabaseHelper.getDatabaseHelper(context);
        String deviceId = dbHelper.getValue(DEVICE_ID_KEY);
        Long previousSessionId = dbHelper.getLongValue(PREVIOUS_SESSION_ID_KEY);
        Long lastEventTime = dbHelper.getLongValue(LAST_EVENT_TIME_KEY);
        if (!Utils.isEmptyString(deviceId) && previousSessionId != null && lastEventTime != null) {
            return true;
        }

        String prefsName = sourcePkgName + "." + context.getPackageName();
        SharedPreferences preferences =
                context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);

        migrateStringValue(
                preferences, Constants.PREFKEY_DEVICE_ID, null, dbHelper, DEVICE_ID_KEY
        );

        migrateLongValue(
                preferences, Constants.PREFKEY_LAST_EVENT_TIME, -1, dbHelper, LAST_EVENT_TIME_KEY
        );

        migrateLongValue(
                preferences, Constants.PREFKEY_LAST_EVENT_ID, -1, dbHelper, LAST_EVENT_ID_KEY
        );

        migrateLongValue(
                preferences, Constants.PREFKEY_LAST_IDENTIFY_ID, -1, dbHelper, LAST_IDENTIFY_ID_KEY
        );

        migrateLongValue(
                preferences, Constants.PREFKEY_PREVIOUS_SESSION_ID, -1,
                dbHelper, PREVIOUS_SESSION_ID_KEY
        );

        migrateStringValue(
                preferences, Constants.PREFKEY_USER_ID, null, dbHelper, USER_ID_KEY
        );

        migrateBooleanValue(
                preferences, Constants.PREFKEY_OPT_OUT, false, dbHelper, OPT_OUT_KEY
        );

        return true;
    }

    private static void migrateLongValue(SharedPreferences prefs, String prefKey, long defValue, DatabaseHelper dbHelper, String dbKey) {
        Long value = dbHelper.getLongValue(dbKey);
        if (value != null) { // if value already exists don't need to migrate
            return;
        }
        long oldValue = prefs.getLong(prefKey, defValue);
        dbHelper.insertOrReplaceKeyLongValue(dbKey, oldValue);
        prefs.edit().remove(prefKey).apply();
    }

    private static void migrateStringValue(SharedPreferences prefs, String prefKey, String defValue, DatabaseHelper dbHelper, String dbKey) {
        String value = dbHelper.getValue(dbKey);
        if (!Utils.isEmptyString(value)) {
            return;
        }
        String oldValue = prefs.getString(prefKey, defValue);
        if (!Utils.isEmptyString(oldValue)) {
            dbHelper.insertOrReplaceKeyValue(dbKey, oldValue);
            prefs.edit().remove(prefKey).apply();
        }
    }

    private static void migrateBooleanValue(SharedPreferences prefs, String prefKey, boolean defValue, DatabaseHelper dbHelper, String dbKey) {
        Long value = dbHelper.getLongValue(dbKey);
        if (value != null) {
            return;
        }
        boolean oldValue = prefs.getBoolean(prefKey, defValue);
        dbHelper.insertOrReplaceKeyLongValue(dbKey, oldValue ? 1L : 0L);
        prefs.edit().remove(prefKey).apply();
    }

    /**
     * Internal method to fetch the current time millis. Used for testing.
     *
     * @return the current time millis
     */
    protected long getCurrentTimeMillis() {
        return System.currentTimeMillis();
    }
}
