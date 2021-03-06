package io.rakam.api;

import android.util.Log;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.rakam.api.RakamClient.JSON;

/**
 * Created by djih on 11/1/18.
 */

public class Diagnostics {
    private static final RakamLog logger = RakamLog.getLogger();
    public static final String TAG = "RakamDiagnostics";

    public static final String DIAGNOSTIC_EVENT_ENDPOINT = "https://diagnostics.rakam.io/event/batch";

    public static final int DIAGNOSTIC_EVENT_MAX_COUNT = 50; // limit memory footprint
    public static final int DIAGNOSTIC_EVENT_MIN_COUNT = 5;

    volatile boolean enabled;
    private volatile String apiKey;
    private volatile OkHttpClient httpClient;
    private volatile String deviceId;
    int diagnosticEventMaxCount;
    String url;
    WorkerThread diagnosticThread = new WorkerThread("diagnosticThread");
    List<String> unsentErrorStrings;
    Map<String, JSONObject> unsentErrors;

    protected static Diagnostics instance;

    static synchronized Diagnostics getLogger() {
        if (instance == null) {
            instance = new Diagnostics();
        }
        return instance;
    }

    private Diagnostics() {
        enabled = false;
        diagnosticEventMaxCount = DIAGNOSTIC_EVENT_MAX_COUNT;
        url = DIAGNOSTIC_EVENT_ENDPOINT;
        unsentErrorStrings = new ArrayList<String>(diagnosticEventMaxCount);
        unsentErrors = new HashMap<String, JSONObject>(diagnosticEventMaxCount);
        diagnosticThread.start();
    }

    Diagnostics enableLogging(OkHttpClient httpClient, String apiKey, String deviceId) {
        this.enabled = true;
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.deviceId = deviceId;
        return this;
    }

    Diagnostics disableLogging() {
        this.enabled = false;
        return this;
    }

    Diagnostics setDiagnosticEventMaxCount(final int diagnosticEventMaxCount) {
        final Diagnostics client = this;

        runOnBgThread(new Runnable() {
            @Override
            public void run() {
                // only allow overrides between 5 and 50
                client.diagnosticEventMaxCount = Math.max(diagnosticEventMaxCount, DIAGNOSTIC_EVENT_MIN_COUNT);
                client.diagnosticEventMaxCount = Math.min(client.diagnosticEventMaxCount, DIAGNOSTIC_EVENT_MAX_COUNT);

                // check if need to downsize
                if (client.diagnosticEventMaxCount < client.unsentErrorStrings.size()) {
                    for (int i = 0; i < unsentErrorStrings.size() - client.diagnosticEventMaxCount; i++) {
                        String errorString = unsentErrorStrings.remove(0);
                        unsentErrors.remove(errorString);
                    }
                }
            }
        });

        return this;
    }

    Diagnostics logError(final String error) {
        return logError(error, null);
    }

    Diagnostics logError(final String error, final Throwable exception) {
        if (!enabled || Utils.isEmptyString(error) || Utils.isEmptyString(deviceId)) {
            return this;
        }

        runOnBgThread(new Runnable() {
            @Override
            public void run() {
                // only add error if unique, otherwise increment count
                JSONObject event = unsentErrors.get(error);
                if (event == null) {
                    event = new JSONObject();
                    try {
                        event.put("error", RakamClient.truncate(error));
                        event.put("timestamp", System.currentTimeMillis());
                        event.put("device_id", deviceId);
                        event.put("count", 1);

                        if (exception != null) {
                            String stackTrace = Log.getStackTraceString(exception);
                            if (!Utils.isEmptyString(stackTrace)) {
                                event.put("stack_trace", RakamClient.truncate(stackTrace));
                            }
                        }

                        // unsent queues are full, make room by removing
                        if (unsentErrorStrings.size() >= diagnosticEventMaxCount) {
                            for (int i = 0; i < DIAGNOSTIC_EVENT_MIN_COUNT; i++) {
                                String errorString = unsentErrorStrings.remove(0);
                                unsentErrors.remove(errorString);
                            }
                        }
                        unsentErrors.put(error, event);
                        unsentErrorStrings.add(error);

                    } catch (JSONException e) {}

                } else {
                    int count = event.optInt("count", 0);
                    try {
                        event.put("count", count + 1);
                    } catch (JSONException e) {}
                }
            }
        });

        return this;
    }

    // call this manually to upload unsent events
    Diagnostics flushEvents() {
        if (!enabled || Utils.isEmptyString(apiKey) || httpClient == null || Utils.isEmptyString(deviceId)) {
            return this;
        }

        runOnBgThread(new Runnable() {
            @Override
            public void run() {
                if (unsentErrorStrings.isEmpty()) {
                    return;
                }
                List<JSONObject> orderedEvents = new ArrayList<JSONObject>(unsentErrorStrings.size());
                for (String error : unsentErrorStrings) {
                    JSONObject event;
                    try {
                        event = new JSONObject()
                                .put("properties", unsentErrors.get(error))
                                .put("collection", "android_sdk_error");
                    } catch (JSONException e) {
                        logger.e(TAG, "Unable to serialize events: "+ e.getMessage());
                        continue;
                    }
                    orderedEvents.add(event);
                }
                JSONArray eventJson = new JSONArray(orderedEvents);

                if (eventJson.length() > 0) {
                    makeEventUploadPostRequest(new JSONArray(orderedEvents));
                }
            }
        });

        return this;
    }

    protected void makeEventUploadPostRequest(JSONArray events) {

        final String body;
        try {
            JSONObject api = new JSONObject()
                    .put("api_key", apiKey)
                    .put("library", new JSONObject()
                            .put("name", Constants.LIBRARY)
                            .put("version", Constants.VERSION))
                    .put("upload_time", System.currentTimeMillis());

            body = new JSONObject().put("api", api).put("events", events).toString();
        } catch (JSONException e) {
            logger.e(TAG, String.format("Failed to convert revenue object to JSON: %s", e.toString()));
            return;
        }

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, body))
                .build();

        try {
            Response response = httpClient.newCall(request).execute();
            String stringResponse = response.body().string();
            if (stringResponse.equals("1")) {
                unsentErrors.clear();
                unsentErrorStrings.clear();
            }
        } catch (IOException e) {
        } catch (AssertionError e) {
        } catch (Exception e) {
        }
    }


    protected void runOnBgThread(Runnable r) {
        if (Thread.currentThread() != diagnosticThread) {
            diagnosticThread.post(r);
        } else {
            r.run();
        }
    }
}
