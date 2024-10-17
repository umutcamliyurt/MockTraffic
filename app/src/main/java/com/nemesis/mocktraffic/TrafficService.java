package com.nemesis.mocktraffic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class TrafficService extends Service {

    // Notification Channel ID
    private static final String CHANNEL_ID = "TrafficServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    // Broadcast Action
    public static final String ACTION_UPDATE_STATS = "com.nemesis.mocktraffic.ACTION_UPDATE_STATS";

    // State variables
    private boolean isTrafficEnabled = true; // Service is started only when traffic is enabled
    private int requestCount = 0;
    private List<String> urlsToVisit = new ArrayList<>();
    private List<String> blacklistedUrls = new ArrayList<>();
    private int maxDepth = 5;
    private int minSleep = 2000; // in milliseconds
    private int maxSleep = 5000; // in milliseconds
    private int timeout = 60000; // 60 seconds timeout
    private Handler trafficHandler = new Handler();
    private Handler logCleanerHandler = new Handler();
    private OkHttpClient httpClient = new OkHttpClient();
    private Random random = new Random();

    private static final int LOG_CLEAN_INTERVAL = 30000; // Clean the log every 30 seconds

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("TrafficService", "Service created.");
        createNotificationChannel();
        loadConfigFromAssets(); // Load config when service is created
    }

    // Create the notification channel for Android O and above
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Traffic Service Channel";
            String description = "Channel for Traffic Generation Service";
            int importance = NotificationManager.IMPORTANCE_LOW; // Low importance to avoid sound
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            // Register the channel with the system
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                Log.d("TrafficService", "Notification channel created.");
            } else {
                Log.e("TrafficService", "NotificationManager is null.");
            }
        }
    }

    // Build the persistent notification
    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntent = PendingIntent.getActivity(this,
                    0, notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } else {
            pendingIntent = PendingIntent.getActivity(this,
                    0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Traffic Generation Active")
                .setContentText("Generating traffic in the background")
                .setSmallIcon(R.drawable.ic_launcher) // Ensure you have an icon named ic_traffic
                .setContentIntent(pendingIntent)
                .setOngoing(true) // Make the notification persistent
                .build();
    }

    // Start the service in the foreground with the notification
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("TrafficService", "onStartCommand called.");
        // Start as foreground service
        Notification notification = buildNotification();
        startForeground(NOTIFICATION_ID, notification);
        Log.d("TrafficService", "Foreground service started.");

        // Start traffic generation
        startTraffic();

        return START_STICKY; // Service will be restarted if terminated
    }

    // Stop the service
    @Override
    public void onDestroy() {
        super.onDestroy();
        stopTraffic(); // Stop traffic generation when service is destroyed
        Log.d("TrafficService", "Service destroyed.");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Binding not used
    }

    // Method to start traffic generation
    private void startTraffic() {
        if (urlsToVisit.isEmpty()) {
            Log.e("TrafficService", "No URLs to visit. Check config.json");
            stopSelf(); // Stop service if no URLs are available
            return;
        }

        trafficHandler.post(trafficRunnable); // Start the traffic generation loop
        scheduleLogCleaning(); // Start log cleaning
        Log.d("TrafficService", "Traffic generation started.");
    }

    // Method to stop traffic generation
    private void stopTraffic() {
        trafficHandler.removeCallbacks(trafficRunnable); // Stop traffic generation loop
        logCleanerHandler.removeCallbacksAndMessages(null); // Stop log cleaning
        Log.d("TrafficService", "Traffic generation stopped.");
    }

    // Runnable that handles traffic generation by crawling and making HTTP requests
    private Runnable trafficRunnable = new Runnable() {
        @Override
        public void run() {
            Log.d("TrafficService", "Traffic runnable started. URLs to visit: " + urlsToVisit.size());
            if (isTrafficEnabled && !urlsToVisit.isEmpty()) {
                String urlToVisit = urlsToVisit.get(random.nextInt(urlsToVisit.size()));
                Log.d("TrafficService", "Visiting URL: " + urlToVisit);
                makeHttpRequest(urlToVisit);

                // Schedule the next traffic request after a random delay
                int sleepTime = random.nextInt(maxSleep - minSleep + 1) + minSleep;
                trafficHandler.postDelayed(this, sleepTime);
            } else {
                Log.d("TrafficService", "Traffic generation stopped or no URLs to visit.");
                stopSelf(); // Stop the service if traffic is disabled or no URLs
            }
        }
    };

    // Method to make an HTTP request to a given URL
    private void makeHttpRequest(final String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Log.e("TrafficService", "Invalid URL scheme: " + url);
            return; // Skip this URL since it's not an HTTP/HTTPS URL
        }

        Request request = new Request.Builder()
                .url(url)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("TrafficService", "Failed to load URL: " + url, e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    requestCount++; // Increment request count on success
                    broadcastStats(); // Broadcast the updated stats
                    Log.d("TrafficService", "Visited URL: " + url + " | Status: " + response.code());

                    // Extract URLs from the response body and add to visit list
                    String body = response.body().string();
                    List<String> extractedUrls = extractUrlsFromBody(body, url);
                    synchronized (urlsToVisit) {
                        urlsToVisit.addAll(extractedUrls); // Add extracted URLs to the list
                    }
                } else {
                    Log.e("TrafficService", "Failed to visit URL: " + url + " | Status: " + response.code());
                }
            }
        });
    }


    // Broadcast the updated stats
    private void broadcastStats() {
        Intent intent = new Intent(ACTION_UPDATE_STATS);
        intent.putExtra("requestCount", requestCount);
        sendBroadcast(intent);
        Log.d("TrafficService", "Broadcasted stats: " + requestCount);
    }

    // Clean the log periodically (if logging to a file or similar)
    private void scheduleLogCleaning() {
        logCleanerHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Implement log cleaning logic if needed
                Log.d("TrafficService", "Log cleaned.");
                logCleanerHandler.postDelayed(this, LOG_CLEAN_INTERVAL); // Schedule next cleaning
            }
        }, LOG_CLEAN_INTERVAL);
    }

    // Load config.json file and populate urlsToVisit and blacklistedUrls
    private void loadConfigFromAssets() {
        String jsonString = null;
        try {
            AssetManager assetManager = getAssets();
            InputStream inputStream = assetManager.open("config.json");
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder stringBuilder = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line);
            }
            jsonString = stringBuilder.toString();
            reader.close();

            // Parse the JSON config file
            parseJsonConfig(jsonString);
            Log.d("TrafficService", "Configuration loaded successfully.");

        } catch (IOException e) {
            Log.e("TrafficService", "Error reading config.json", e);
            stopSelf(); // Stop service if config cannot be loaded
        }
    }

    // Parse the configuration JSON and populate urlsToVisit and blacklistedUrls
    private void parseJsonConfig(String jsonString) {
        try {
            JSONObject jsonObject = new JSONObject(jsonString);

            JSONArray rootUrls = jsonObject.getJSONArray("root_urls");
            JSONArray blacklistedUrlsJson = jsonObject.getJSONArray("blacklisted_urls");

            for (int i = 0; i < rootUrls.length(); i++) {
                urlsToVisit.add(rootUrls.getString(i)); // Add URLs from config to visit list
            }

            for (int i = 0; i < blacklistedUrlsJson.length(); i++) {
                blacklistedUrls.add(blacklistedUrlsJson.getString(i)); // Add blacklisted URLs
            }

            // Update additional configurations
            maxDepth = jsonObject.getInt("max_depth");
            minSleep = jsonObject.getInt("min_sleep");
            maxSleep = jsonObject.getInt("max_sleep");
            timeout = jsonObject.optInt("timeout", 60000); // Default to 60 seconds if not provided

            Log.d("TrafficService", "Parsed config.json: " + jsonObject.toString());

        } catch (JSONException e) {
            Log.e("TrafficService", "Error parsing config.json", e);
            stopSelf(); // Stop service if config cannot be parsed
        }
    }

    // Extract URLs from the HTML response using Jsoup
    private List<String> extractUrlsFromBody(String body, String rootUrl) {
        List<String> extractedUrls = new ArrayList<>();
        try {
            Document doc = Jsoup.parse(body, rootUrl);
            Elements links = doc.select("a[href]"); // Select all <a> tags with href attributes

            for (org.jsoup.nodes.Element link : links) {
                String absoluteUrl = link.absUrl("href"); // Get absolute URLs
                if (!absoluteUrl.isEmpty() && !urlsToVisit.contains(absoluteUrl) && !isBlacklisted(absoluteUrl)) {
                    extractedUrls.add(absoluteUrl); // Add valid URLs to the list
                }
            }
        } catch (Exception e) {
            Log.e("TrafficService", "Failed to extract URLs", e);
        }
        return extractedUrls;
    }

    // Check if a URL is blacklisted
    private boolean isBlacklisted(String url) {
        for (String blacklistedUrl : blacklistedUrls) {
            if (url.contains(blacklistedUrl)) {
                return true;
            }
        }
        return false;
    }
}
