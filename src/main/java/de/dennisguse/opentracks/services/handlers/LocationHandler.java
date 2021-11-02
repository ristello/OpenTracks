package de.dennisguse.opentracks.services.handlers;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import de.dennisguse.opentracks.R;
import de.dennisguse.opentracks.content.data.Distance;
import de.dennisguse.opentracks.content.data.TrackPoint;
import de.dennisguse.opentracks.util.LocationUtils;
import de.dennisguse.opentracks.settings.PreferencesUtils;

@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
public class LocationHandler implements LocationListener {

    public static final String TAG = LocationHandler.class.getSimpleName();

    // The duration that GpsStatus waits from minimal interval to consider GPS lost.
    public static final Duration SIGNAL_LOST_THRESHOLD = Duration.ofSeconds(10);


    private LocationManager locationManager;
    // TODO: resolve circular dependency!
    private final TrackPointCreator trackPointCreator;
    private GpsStatus gpsStatus;
    private Duration gpsInterval;
    private Distance thresholdHorizontalAccuracy;
    private TrackPoint lastTrackPoint;

    // Flag to prevent GpsStatus checks two or more locations at the same time.
    private final Lock gpsLocationChangedLock = new ReentrantLock();

    public LocationHandler(TrackPointCreator trackPointCreator) {
        this.trackPointCreator = trackPointCreator;
    }

    public void onStart(@NonNull Context context) {
        onSharedPreferenceChanged(null);
        gpsStatus = new GpsStatus();
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        registerLocationListener();
        gpsStatus.onGpsStatusChanged(GpsStatusValue.GPS_ENABLED);
    }

    private boolean isStarted() {
        return locationManager != null;
    }

    @SuppressWarnings({"MissingPermission"})
    //TODO upgrade to AGP7.0.0/API31 started complaining about removeUpdates.
    public void onStop() {
        lastTrackPoint = null;
        if (locationManager != null) {
            locationManager.removeUpdates(this);
            locationManager = null;
        }

        if (gpsStatus != null) {
            gpsStatus.onGpsStatusChanged(GpsStatusValue.GPS_NONE);
            if (gpsStatus.gpsStatusRunner != null) {
                gpsStatus.gpsStatusRunner.stop();
                gpsStatus.gpsStatusRunner = null;
            }

            gpsStatus = null;
        }
    }

    public void onSharedPreferenceChanged(String key) {
        boolean registerListener = false;

        if (PreferencesUtils.isKey(R.string.min_recording_interval_key, key)) {
            registerListener = true;

            gpsInterval = PreferencesUtils.getMinRecordingInterval();

            if (gpsStatus != null) {
                gpsStatus.onMinRecordingIntervalChanged(gpsInterval);
            }
        }
        if (PreferencesUtils.isKey(R.string.recording_gps_accuracy_key, key)) {
            thresholdHorizontalAccuracy = PreferencesUtils.getThresholdHorizontalAccuracy();
        }
        if (PreferencesUtils.isKey(R.string.recording_distance_interval_key, key)) {
            registerListener = true;

            if (gpsStatus != null) {
                Distance gpsMinDistance = PreferencesUtils.getRecordingDistanceInterval();
                gpsStatus.thresholdHorizontalAccuracy = gpsMinDistance;
            }
        }

        if (registerListener) {
            registerLocationListener();
        }
    }

    /**
     * Checks if location is valid and builds a track point that will be send through TrackPointCreator.
     *
     * @param location {@link Location} object.
     */
    @Override
    public void onLocationChanged(@NonNull Location location) {
        if (!isStarted()) {
            Log.w(TAG, "Location is ignored; not started.");
            return;
        }

        TrackPoint trackPoint = new TrackPoint(location, trackPointCreator.createNow());
        boolean isAccurate = trackPoint.fulfillsAccuracy(thresholdHorizontalAccuracy);
        boolean isValid = LocationUtils.isValidLocation(location);

        onGpsLocationChanged(trackPoint);

        if (!isValid) {
            Log.w(TAG, "Ignore newTrackPoint. location is invalid.");
            return;
        }

        if (!isAccurate) {
            Log.d(TAG, "Ignore newTrackPoint. Poor accuracy.");
            return;
        }

        lastTrackPoint = trackPoint;
        trackPointCreator.onNewTrackPoint(trackPoint, thresholdHorizontalAccuracy);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(@NonNull String provider) {
        if (gpsStatus != null) {
            gpsStatus.onGpsEnabled();
        }
    }

    @Override
    public void onProviderDisabled(@NonNull String provider) {
        if (gpsStatus != null) {
            gpsStatus.onGpsDisabled();
        }
    }

    private void registerLocationListener() {
        if (locationManager == null) {
            Log.e(TAG, "locationManager is null.");
            return;
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, gpsInterval.toMillis(), 0, this);
        } catch (SecurityException e) {
            Log.e(TAG, "Could not register location listener; permissions not granted.", e);
        }
    }

    TrackPoint getLastTrackPoint() {
        return lastTrackPoint;
    }

    /**
     * This method must be called from the client every time a new trackPoint is received.
     * Receive new trackPoint and calculate the new status if needed.
     * It look for GPS changes in lastLocation if it's not null. If it's null then look for in lastValidLocation if any.
     */
    public void onGpsLocationChanged(final TrackPoint trackPoint) {
        if (! gpsLocationChangedLock.tryLock()) {
            return;
        }
        try {
            gpsLocationChangedLock.lock();
            if (lastTrackPoint != null) {
                gpsStatus.checkStatusFromLastLocation();
            } else if (gpsStatus.lastValidTrackPoint != null) {
                gpsStatus.checkStatusFromLastValidLocation();
            }

            if (trackPoint != null) {
                gpsStatus.lastValidTrackPoint = trackPoint;
            }
            lastTrackPoint = trackPoint;
        } finally {
            gpsLocationChangedLock.unlock();
        }
    }


    /**
     * This class handle GPS status according to received locations` and some thresholds.
     */
    //TODO should handle sharedpreference changes
    class GpsStatus {

        private Distance thresholdHorizontalAccuracy;
        // Threshold for time without points.
        private Duration signalLostThreshold;

        private GpsStatusValue gpsStatusValue = GpsStatusValue.GPS_NONE;

        @Nullable
        private TrackPoint lastTrackPoint = null;

        @Nullable
        // The last valid (not null) location. Null value means that there have not been any location yet.
        private TrackPoint lastValidTrackPoint = null;


        private class GpsStatusRunner implements Runnable {
            private boolean stopped = false;

            @Override
            public void run() {
                if (gpsStatusValue != null && !stopped) {
                    onGpsLocationChanged(null);
                    gpsStatusHandler.postDelayed(gpsStatusRunner, signalLostThreshold.toMillis());
                }
            }

            public void stop() {
                stopped = true;
                sendStatus(GpsStatusValue.GPS_NONE);
            }
        }

        // better use ScheduledExecutorService#scheduleAtFixedRate()
        private final Handler gpsStatusHandler;
        private GpsStatusRunner gpsStatusRunner = null;

        // deps: only used once in LocationHandler
        public GpsStatus() {
            thresholdHorizontalAccuracy = PreferencesUtils.getRecordingDistanceInterval();

            Duration minRecordingInterval = PreferencesUtils.getMinRecordingInterval();
            signalLostThreshold = SIGNAL_LOST_THRESHOLD.plus(minRecordingInterval);

            // FIXME: creating an instance of Handler without providing the Looper is deprecated
            gpsStatusHandler = new Handler();
        }

        /**
         * Called from {@link GpsStatus} to inform that GPS status has changed from prevStatus to currentStatus.
         *
         * @param currentStatus current {@link GpsStatusValue}.
         */
        private void onGpsStatusChanged(GpsStatusValue currentStatus) {
            trackPointCreator.sendGpsStatus(currentStatus);
        }

        // analysis: this is only called once: in LocationHandler#onSharedPreferenceChanged()
        public void onMinRecordingIntervalChanged(Duration value) {
            signalLostThreshold = SIGNAL_LOST_THRESHOLD.plus(value);
        }


        /**
         * Checks if lastLocation has new GPS status looking up time and accuracy.
         * It depends of signalLostThreshold and signalBadThreshold.
         * If there is any change then it does the change.
         * Also, it'll run the runnable if signal is bad or stop it if the signal is lost.
         */
        private void checkStatusFromLastLocation() {
            if (Duration.between(lastTrackPoint.getTime(), Instant.now()).compareTo(signalLostThreshold) > 0 && gpsStatusValue != GpsStatusValue.GPS_SIGNAL_LOST) {
                // Too much time without receiving signal -> signal lost.
                gpsStatusValue = GpsStatusValue.GPS_SIGNAL_LOST;
                sendStatus(gpsStatusValue);
                stopStatusRunner();
            } else if (lastTrackPoint.fulfillsAccuracy(thresholdHorizontalAccuracy) && gpsStatusValue != GpsStatusValue.GPS_SIGNAL_BAD) {
                // Too little accuracy -> bad signal.
                gpsStatusValue = GpsStatusValue.GPS_SIGNAL_BAD;
                sendStatus(gpsStatusValue);
                startStatusRunner();
            } else if (lastTrackPoint.fulfillsAccuracy(thresholdHorizontalAccuracy) && gpsStatusValue != GpsStatusValue.GPS_SIGNAL_FIX) {
                // Gps okay.
                gpsStatusValue = GpsStatusValue.GPS_SIGNAL_FIX;
                sendStatus(gpsStatusValue);
                startStatusRunner();
            }
        }

        /**
         * Checks if lastValidLocation has a new GPS status looking up time.
         * It depends on signalLostThreshold.
         * If there is any change then it does the change.
         */
        private void checkStatusFromLastValidLocation() {
            Duration elapsed = Duration.between(lastValidTrackPoint.getTime(), Instant.now());
            if (signalLostThreshold.minus(elapsed).isNegative()) {
                // Too much time without locations -> lost signal? (wait signalLostThreshold from last valid location).
                gpsStatusValue = GpsStatusValue.GPS_SIGNAL_LOST;
                sendStatus(gpsStatusValue);
                stopStatusRunner();
                lastValidTrackPoint = null;
            }
        }

        // analysis: called only once: LocationHandler#onProviderEnabled()
        /**
         * This method must be called from the client every time the GPS sensor is enabled.
         * Anyway, it checks that GPS is enabled because the client assumes that if it's on then GPS is enabled but user can disable GPS by hand.
         */
        public void onGpsEnabled() {
            if (gpsStatusValue != GpsStatusValue.GPS_ENABLED) {
                if (locationManager != null && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    sendStatus(GpsStatusValue.GPS_ENABLED);
                    startStatusRunner();
                } else {
                    onGpsDisabled();
                }
            }
        }

        // analysis: called from #onGpsEnabled() and LocationHandler#onProviderDisabled()
        /**
         * This method must be called from service every time the GPS sensor is disabled.
         */
        public void onGpsDisabled() {
            if (gpsStatusValue != GpsStatusValue.GPS_DISABLED) {
                sendStatus(GpsStatusValue.GPS_DISABLED);
                lastTrackPoint = null;
                lastValidTrackPoint = null;
                stopStatusRunner();
            }
        }

        private void sendStatus(GpsStatusValue current) {
            LocationHandler.this.trackPointCreator.sendGpsStatus(current);
        }

        private void startStatusRunner() {
            if (gpsStatusRunner == null) {
                gpsStatusRunner = new GpsStatusRunner();
                gpsStatusRunner.run();
            }
        }

        private void stopStatusRunner() {
            if (gpsStatusRunner != null) {
                gpsStatusRunner.stop();
                gpsStatusRunner = null;
            }
        }

    }
}
