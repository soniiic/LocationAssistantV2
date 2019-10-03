// https://github.com/klaasnotfound/LocationAssistant
/*
 *    Copyright 2017 Klaas Klasing (klaas [at] klaasnotfound.com)
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package dev.athela.epichat

import android.Manifest
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*

/**
 * A helper class that monitors the available location info on behalf of a requesting activity or application.
 */
class LocationAssistant
/**
 * Constructs a LocationAssistant instance that will listen for valid location updates.
 *
 * @param context            the context of the application or activity that wants to receive location updates
 * @param listener           a listener that will receive location-related events
 * @param accuracy           the desired accuracy of the loation updates
 * @param updateInterval     the interval (in milliseconds) at which the activity can process updates
 * @param allowMockLocations whether or not mock locations are acceptable
 */
    (// Parameters
    protected var context: Context,
    private var listener: Listener?,
    accuracy: Accuracy,
    private val updateInterval: Long,
    private val allowMockLocations: Boolean
) : LocationListener {

    private val mFusedLocationClient: FusedLocationProviderClient

    private val locationCallback: LocationCallback

    private var locationAvailability: LocationAvailability? = null

    private val REQUEST_CHECK_SETTINGS = 0
    private val REQUEST_LOCATION_PERMISSION = 1
    private var activity: AppCompatActivity? = null
    private var priority: Int = 0
    private var verbose: Boolean = false
    private var quiet: Boolean = false

    // Internal state
    private var permissionGranted: Boolean = false
    private var locationRequested: Boolean = false
    private var locationStatusOk: Boolean = false
    private var changeSettings: Boolean = false
    private var updatesRequested: Boolean = false
    /**
     * Returns the best valid location currently available.
     * Usually, this will be the last valid location that was received.
     *
     * @return the best valid location
     */
    private var bestLocation: Location? = null
    private var locationRequest: LocationRequest? = null
    private var mockLocationsEnabled: Boolean = false
    private var numTimesPermissionDeclined: Int = 0

    // Mock location rejection
    private var lastMockLocation: Location? = null
    private var numGoodReadings: Int = 0

    private val onGoToLocationSettingsFromDialog = object : DialogInterface.OnClickListener {
        override fun onClick(dialog: DialogInterface, which: Int) {
            if (this@LocationAssistant.activity != null) {
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                this@LocationAssistant.activity!!.startActivity(intent)
            } else if (!this@LocationAssistant.quiet)
                Log.e(
                    this.javaClass.simpleName,
                    "Need to launch an intent, but no activity is registered! " +
                            "Specify a valid activity when constructing " + this.javaClass.simpleName +
                            " or register it explicitly with register()."
                )
        }
    }

    private val onGoToLocationSettingsFromView = object : View.OnClickListener {
        override fun onClick(v: View) {
            if (this@LocationAssistant.activity != null) {
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                this@LocationAssistant.activity!!.startActivity(intent)
            } else if (!this@LocationAssistant.quiet)
                Log.e(
                    this.javaClass.simpleName,
                    "Need to launch an intent, but no activity is registered! " +
                            "Specify a valid activity when constructing " + this.javaClass.simpleName +
                            " or register it explicitly with register()."
                )
        }
    }

    private val onGoToDevSettingsFromDialog = object : DialogInterface.OnClickListener {
        override fun onClick(dialog: DialogInterface, which: Int) {
            if (this@LocationAssistant.activity != null) {
                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                this@LocationAssistant.activity!!.startActivity(intent)
            } else if (!this@LocationAssistant.quiet)
                Log.e(
                    this.javaClass.simpleName,
                    "Need to launch an intent, but no activity is registered! " +
                            "Specify a valid activity when constructing " + this.javaClass.simpleName +
                            " or register it explicitly with register()."
                )
        }
    }

    private val onGoToDevSettingsFromView = object : View.OnClickListener {
        override fun onClick(v: View) {
            if (this@LocationAssistant.activity != null) {
                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                this@LocationAssistant.activity!!.startActivity(intent)
            } else if (!this@LocationAssistant.quiet)
                Log.e(
                    this.javaClass.simpleName,
                    "Need to launch an intent, but no activity is registered! " +
                            "Specify a valid activity when constructing " + this.javaClass.simpleName +
                            " or register it explicitly with register()."
                )
        }
    }

    private val onGoToAppSettingsFromDialog = object : DialogInterface.OnClickListener {
        override fun onClick(dialog: DialogInterface, which: Int) {
            if (this@LocationAssistant.activity != null) {
                val intent = Intent()
                intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                val uri =
                    Uri.fromParts("package", this@LocationAssistant.activity!!.packageName, null)
                intent.data = uri
                this@LocationAssistant.activity!!.startActivity(intent)
            } else if (!this@LocationAssistant.quiet)
                Log.e(
                    this.javaClass.simpleName,
                    "Need to launch an intent, but no activity is registered! " +
                            "Specify a valid activity when constructing " + this.javaClass.simpleName +
                            " or register it explicitly with register()."
                )
        }
    }

    private val onGoToAppSettingsFromView = object : View.OnClickListener {
        override fun onClick(v: View) {
            if (this@LocationAssistant.activity != null) {
                val intent = Intent()
                intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                val uri =
                    Uri.fromParts("package", this@LocationAssistant.activity!!.packageName, null)
                intent.data = uri
                this@LocationAssistant.activity!!.startActivity(intent)
            } else if (!this@LocationAssistant.quiet)
                Log.e(
                    this.javaClass.simpleName,
                    "Need to launch an intent, but no activity is registered! " +
                            "Specify a valid activity when constructing " + this.javaClass.simpleName +
                            " or register it explicitly with register()."
                )
        }
    }

    /**
     * Delivers relevant events required to obtain (valid) location info.
     */
    interface Listener {
        /**
         * Called when the user needs to grant the app location permission at run time.
         * This is only necessary on newer Android systems (API level >= 23).
         * If you want to show some explanation up front, do that, then call [.requestLocationPermission].
         * Alternatively, you can call [.requestAndPossiblyExplainLocationPermission], which will request the
         * location permission right away and invoke [.onExplainLocationPermission] only if the user declines.
         * Both methods will bring up the system permission dialog.
         */
        fun onNeedLocationPermission()

        /**
         * Called when the user has declined the location permission and might need a better explanation as to why
         * your app really depends on it.
         * You can show some sort of dialog or info window here and then - if the user is willing - ask again for
         * permission with [.requestLocationPermission].
         */
        fun onExplainLocationPermission()

        /**
         * Called when the user has declined the location permission at least twice or has declined once and checked
         * "Don't ask again" (which will cause the system to permanently decline it).
         * You can show some sort of message that explains that the user will need to go to the app settings
         * to enable the permission. You may use the preconfigured OnClickListeners to send the user to the app
         * settings page.
         *
         * @param fromView   OnClickListener to use with a view (e.g. a button), jumps to the app settings
         * @param fromDialog OnClickListener to use with a dialog, jumps to the app settings
         */
        fun onLocationPermissionPermanentlyDeclined(
            fromView: View.OnClickListener,
            fromDialog: DialogInterface.OnClickListener
        )

        /**
         * Called when a change of the location provider settings is necessary.
         * You can optionally show some informative dialog and then request the settings change with
         * [.changeLocationSettings].
         */
        fun onNeedLocationSettingsChange()

        /**
         * In certain cases where the user has switched off location providers, changing the location settings from
         * within the app may not work. The LocationAssistant will attempt to detect these cases and offer a redirect to
         * the system location settings, where the user may manually enable on location providers before returning to
         * the app.
         * You can prompt the user with an appropriate message (in a view or a dialog) and use one of the provided
         * OnClickListeners to jump to the settings.
         *
         * @param fromView   OnClickListener to use with a view (e.g. a button), jumps to the location settings
         * @param fromDialog OnClickListener to use with a dialog, jumps to the location settings
         */
        fun onFallBackToSystemSettings(
            fromView: View.OnClickListener,
            fromDialog: DialogInterface.OnClickListener
        )

        /**
         * Called when a new and valid location is available.
         * If you chose to reject mock locations, this method will only be called when a real location is available.
         *
         * @param location the current user location
         */
        fun onNewLocationAvailable(location: Location)

        /**
         * Called when the presence of mock locations was detected and [.allowMockLocations] is `false`.
         * You can use this callback to scold the user or do whatever. The user can usually disable mock locations by
         * either switching off a running mock location app (on newer Android systems) or by disabling mock location
         * apps altogether. The latter can be done in the phone's development settings. You may show an appropriate
         * message and then use one of the provided OnClickListeners to jump to those settings.
         *
         * @param fromView   OnClickListener to use with a view (e.g. a button), jumps to the development settings
         * @param fromDialog OnClickListener to use with a dialog, jumps to the development settings
         */
        fun onMockLocationsDetected(
            fromView: View.OnClickListener,
            fromDialog: DialogInterface.OnClickListener
        )

        fun onPermissionGranted()

        /**
         * Called when an error has occurred.
         *
         * @param type    the type of error that occurred
         * @param message a plain-text message with optional details
         */
        fun onError(type: ErrorType, message: String)
    }

    /**
     * Possible values for the desired location accuracy.
     */
    enum class Accuracy {
        /**
         * Highest possible accuracy, typically within 30m
         */
        HIGH,
        /**
         * Medium accuracy, typically within a city block / roughly 100m
         */
        MEDIUM,
        /**
         * City-level accuracy, typically within 10km
         */
        LOW,
        /**
         * Variable accuracy, purely dependent on updates requested by other apps
         */
        PASSIVE
    }

    enum class ErrorType {
        /**
         * An error with the user's location settings
         */
        SETTINGS,
        /**
         * An error with the retrieval of location info
         */
        RETRIEVAL
    }

    init {
        if (this.context is AppCompatActivity)
            this.activity = this.context as AppCompatActivity
        this.priority = when (accuracy) {
            Accuracy.HIGH -> LocationRequest.PRIORITY_HIGH_ACCURACY
            Accuracy.MEDIUM -> LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
            Accuracy.LOW -> LocationRequest.PRIORITY_LOW_POWER
            Accuracy.PASSIVE -> LocationRequest.PRIORITY_NO_POWER
        }

        this.mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this.context)
        this.locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult?) {
                super.onLocationResult(result)
                this@LocationAssistant.onLocationChanged(result!!.lastLocation)
            }

            override fun onLocationAvailability(locationAvailability: LocationAvailability?) {
                super.onLocationAvailability(locationAvailability)
                this@LocationAssistant.onLocationAvailabilityChanged(locationAvailability)
            }
        }
    }

    private fun onLocationAvailabilityChanged(locationAvailability: LocationAvailability?) {
        this.locationAvailability = locationAvailability
    }

    /**
     * Makes the LocationAssistant print info log messages.
     *
     * @param verbose whether or not the LocationAssistant should print verbose log messages.
     */
    fun setVerbose(verbose: Boolean) {
        this.verbose = verbose
    }

    /**
     * Mutes/unmutes all log output.
     * You may want to mute the LocationAssistant in production.
     *
     * @param quiet whether or not to disable all log output (including errors).
     */
    fun setQuiet(quiet: Boolean) {
        this.quiet = quiet
    }

    /**
     * Starts the LocationAssistant and makes it subscribe to valid location updates.
     * Call this method when your application or activity becomes awake.
     */
    fun start() {
        this.checkMockLocations()
        this.acquireLocation()
    }

    /**
     * Updates the active Activity for which the LocationAssistant manages location updates.
     * When you want the LocationAssistant to start and stop with your overall application, but service different
     * activities, call this method at the end of your  implementation.
     *
     * @param activity the activity that wants to receive location updates
     * @param listener a listener that will receive location-related events
     */
    fun register(activity: AppCompatActivity, listener: Listener) {
        this.activity = activity
        this.listener = listener
        this.checkInitialLocation()
        this.acquireLocation()
    }

    /**
     * Stops the LocationAssistant and makes it unsubscribe from any location updates.
     * Call this method right before your application or activity goes to sleep.
     */
    fun stop() {
        this.mFusedLocationClient.removeLocationUpdates(this.locationCallback)
        this.permissionGranted = false
        this.locationRequested = false
        this.locationStatusOk = false
        this.updatesRequested = false
    }

    /**
     * Clears the active Activity and its listener.
     * Until you register a new activity and listener, the LocationAssistant will silently produce error messages.
     * When you want the LocationAssistant to start and stop with your overall application, but service different
     * activities, call this method at the beginning of your  implementation.
     */
    fun unregister() {
        this.activity = null
        this.listener = null
    }

    /**
     * In rare cases (e.g. after losing connectivity) you may want to reset the LocationAssistant and have it start
     * from scratch. Use this method to do so.
     */
    fun reset() {
        this.permissionGranted = false
        this.locationRequested = false
        this.locationStatusOk = false
        this.updatesRequested = false
        this.acquireLocation()
    }

    /**
     * The first time you call this method, it brings up a system dialog asking the user to give location permission to
     * the app. On subsequent calls, if the user has previously declined permission, this method invokes
     * [Listener.onExplainLocationPermission].
     */
    fun requestAndPossiblyExplainLocationPermission() {
        if (this.permissionGranted) return
        if (this.activity == null) {
            if (!this.quiet)
                Log.e(
                    this.javaClass.simpleName,
                    "Need location permission, but no activity is registered! " +
                            "Specify a valid activity when constructing " + this.javaClass.simpleName +
                            " or register it explicitly with register()."
                )
            return
        }
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this.activity!!,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) && this.listener != null
        )
            this.listener!!.onExplainLocationPermission()
        else
            this.requestLocationPermission()
    }

    /**
     * Brings up a system dialog asking the user to give location permission to the app.
     */
    fun requestLocationPermission() {
        if (this.activity == null) {
            if (!this.quiet)
                Log.e(
                    this.javaClass.simpleName,
                    "Need location permission, but no activity is registered! " +
                            "Specify a valid activity when constructing " + this.javaClass.simpleName +
                            " or register it explicitly with register()."
                )
            return
        }
        ActivityCompat.requestPermissions(
            this.activity!!,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), this.REQUEST_LOCATION_PERMISSION
        )
    }

    /**
     * Call this method at the end of your [AppCompatActivity.onRequestPermissionsResult] implementation to notify the
     * LocationAssistant of an update in permissions.
     *
     * @param requestCode  the request code returned to the activity (simply pass it on)
     * @param grantResults the results array returned to the activity (simply pass it on)
     * @return `true` if the location permission was granted, `false` otherwise
     */
    fun onPermissionsUpdated(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != this.REQUEST_LOCATION_PERMISSION) return false
        if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            this.acquireLocation()
            return true
        } else {
            this.numTimesPermissionDeclined++
            if (!this.quiet)
                Log.i(this.javaClass.simpleName, "Location permission request denied.")
            if (this.numTimesPermissionDeclined >= 2 && this.listener != null)
                this.listener!!.onLocationPermissionPermanentlyDeclined(
                    this.onGoToAppSettingsFromView,
                    this.onGoToAppSettingsFromDialog
                )
            return false
        }
    }

    /**
     * Call this method at the end of your  implementation to notify the
     * LocationAssistant of a change in location provider settings.
     *
     * @param requestCode the request code returned to the activity (simply pass it on)
     * @param resultCode  the result code returned to the activity (simply pass it on)
     */
    fun onActivityResult(requestCode: Int, resultCode: Int) {
        if (requestCode != this.REQUEST_CHECK_SETTINGS) return
        if (resultCode == AppCompatActivity.RESULT_OK) {
            this.changeSettings = false
            this.locationStatusOk = true
        }
        this.acquireLocation()
    }

    /**
     * Brings up an in-app system dialog that requests a change in location provider settings.
     * The settings change may involve switching on GPS and/or network providers and depends on the accuracy and
     * update interval that was requested when constructing the LocationAssistant.
     * Call this method only from within [Listener.onNeedLocationSettingsChange].
     */
    fun changeLocationSettings() {
        if (this.locationStatusOk) return
        if (this.activity == null) {
            if (!this.quiet)
                Log.e(
                    this.javaClass.simpleName,
                    "Need to resolve location status issues, but no activity is " +
                            "registered! Specify a valid activity when constructing " + this.javaClass.simpleName +
                            " or register it explicitly with register()."
                )
            return
        }
        try {
            this.activity!!.startActivityForResult(
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                this.REQUEST_CHECK_SETTINGS
            )
        } catch (e: Exception) {
            if (!this.quiet)
                Log.e(
                    this.javaClass.simpleName,
                    "Error while attempting to resolve location status issues:\n$e"
                )
            if (this.listener != null)
                this.listener!!.onError(
                    ErrorType.SETTINGS,
                    "Could not resolve location settings issue:\n" + e.message
                )
            this.changeSettings = false
            this.acquireLocation()
        }

    }

    private fun acquireLocation() {
        if (!this.permissionGranted) this.checkLocationPermission()
        if (!this.permissionGranted) {
            if (this.numTimesPermissionDeclined >= 2) return
            if (this.listener != null)
                this.listener!!.onNeedLocationPermission()
            else if (!this.quiet)
                Log.e(
                    this.javaClass.simpleName,
                    "Need location permission, but no listener is registered! " +
                            "Specify a valid listener when constructing " + this.javaClass.simpleName +
                            " or register it explicitly with register()."
                )
            return
        }
        if (!this.locationRequested) {
            this.requestLocation()
            return
        }
        if (!this.locationStatusOk) {
            if (this.changeSettings) {
                if (this.listener != null)
                    this.listener!!.onNeedLocationSettingsChange()
                else if (!this.quiet)
                    Log.e(
                        this.javaClass.simpleName,
                        "Need location settings change, but no listener is " +
                                "registered! Specify a valid listener when constructing " + this.javaClass.simpleName +
                                " or register it explicitly with register()."
                    )
            } else
                this.checkProviders()
            return
        }
        if (!this.updatesRequested) {
            this.requestLocationUpdates()
            // Check back in a few
            Handler().postDelayed({ this.acquireLocation() }, 10000)
            return
        }

        this.checkLocationAvailability()
    }

    protected fun checkInitialLocation() {
        if (!this.permissionGranted || !this.locationRequested || !this.locationStatusOk) return
        try {
            val task = this.mFusedLocationClient.lastLocation
            task.addOnSuccessListener(this.activity!!) { location ->
                this.onLocationChanged(location)
            }
        } catch (e: SecurityException) {
            if (!this.quiet)
                Log.e(this.javaClass.simpleName, "Error while requesting last location:\n $e")
            if (this.listener != null)
                this.listener!!.onError(
                    ErrorType.RETRIEVAL,
                    "Could not retrieve initial location:\n" + e.message
                )
        }

    }

    private fun checkMockLocations() {
        // Starting with API level >= 18 we can (partially) rely on .isFromMockProvider()
        // (http://developer.android.com/reference/android/location/Location.html#isFromMockProvider%28%29)
        // For API level < 18 we have to check the Settings.Secure flag
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 18 && Settings.Secure.getString(this.context.contentResolver, Settings.Secure.ALLOW_MOCK_LOCATION) != "0") {
            this.mockLocationsEnabled = true
            if (this.listener != null)
                this.listener!!.onMockLocationsDetected(this.onGoToDevSettingsFromView, this.onGoToDevSettingsFromDialog)
        } else
            this.mockLocationsEnabled = false
    }

    private fun checkLocationPermission() {
        this.permissionGranted = Build.VERSION.SDK_INT < 23 || ContextCompat.checkSelfPermission(
            this.context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (this.permissionGranted) {
            this.listener!!.onPermissionGranted()
        }
    }

    private fun requestLocation() {
        if (!this.permissionGranted) return
        this.locationRequest = LocationRequest.create()
        this.locationRequest!!.priority = this.priority
        this.locationRequest!!.interval = this.updateInterval
        this.locationRequest!!.fastestInterval = this.updateInterval
        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(this.locationRequest!!)
        builder.setAlwaysShow(true)

        val locationSettingsRequest = builder.build()
        val settingsClient = LocationServices.getSettingsClient(this.context)
        val task = settingsClient.checkLocationSettings(locationSettingsRequest)
        task.addOnSuccessListener(this.activity!!) { locationSettingsResponse ->
            this.locationRequested = true
            this.locationStatusOk = locationSettingsResponse.locationSettingsStates.isLocationUsable
            this.checkInitialLocation()
            this.acquireLocation()
        }

        task.addOnFailureListener(this.activity!!) { e ->
            this.locationRequested = true
            if (e is ResolvableApiException) {

                if (e.statusCode == LocationSettingsStatusCodes.RESOLUTION_REQUIRED) {
                    this.locationStatusOk = false
                    this.changeSettings = true
                }
                if (e.statusCode == LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE) {
                    this.locationStatusOk = false
                }
            }
            this.acquireLocation()
        }
    }

    private fun checkLocationAvailability() {
        if (!this.permissionGranted) {
            this.checkProviders()
        }
        if (this.locationAvailability != null && this.locationAvailability!!.isLocationAvailable) {
            this.checkProviders()
        }
        try {
            val locationAvailability = this.mFusedLocationClient.locationAvailability
            locationAvailability.addOnSuccessListener(this.activity!!) { la ->
                if (la != null && la.isLocationAvailable) {
                    this.checkProviders()
                }
            }
        } catch (e: SecurityException) {
            if (!this.quiet)
                Log.e(this.javaClass.simpleName, "Error while checking location availability:\n $e")
            if (this.listener != null)
                this.listener!!.onError(
                    ErrorType.RETRIEVAL,
                    "Could not check location availability:\n" + e.message
                )
        }

    }

    private fun checkProviders() {
        // Do it the old fashioned way
        val locationManager =
            this.context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val network = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (gps || network) return
        if (this.listener != null)
            this.listener!!.onFallBackToSystemSettings(
                this.onGoToLocationSettingsFromView,
                this.onGoToLocationSettingsFromDialog
            )
        else if (!this.quiet)
            Log.e(
                this.javaClass.simpleName,
                "Location providers need to be enabled, but no listener is " +
                        "registered! Specify a valid listener when constructing " + this.javaClass.simpleName +
                        " or register it explicitly with register()."
            )
    }

    private fun requestLocationUpdates() {
        if (!this.permissionGranted || !this.locationRequested) return
        try {
            this.mFusedLocationClient.requestLocationUpdates(
                this.locationRequest,
                this.locationCallback,
                Looper.getMainLooper()
            )
            this.updatesRequested = true
        } catch (e: SecurityException) {
            if (!this.quiet)
                Log.e(this.javaClass.simpleName, "Error while requesting location updates:\n $e")
            if (this.listener != null)
                this.listener!!.onError(
                    ErrorType.RETRIEVAL,
                    "Could not request location updates:\n" + e.message
                )
        }

    }

    private fun isLocationPlausible(location: Location?): Boolean {
        if (location == null) return false

        val isMock =
            this.mockLocationsEnabled || Build.VERSION.SDK_INT >= 18 && location.isFromMockProvider
        if (isMock) {
            this.lastMockLocation = location
            this.numGoodReadings = 0
        } else
            this.numGoodReadings = Math.min(this.numGoodReadings + 1, 1000000) // Prevent overflow

        // We only clear that incident record after a significant show of good behavior
        if (this.numGoodReadings >= 20) this.lastMockLocation = null

        // If there's nothing to compare against, we have to trust it
        if (this.lastMockLocation == null) return true

        // And finally, if it's more than 1km away from the last known mock, we'll trust it
        val d = location.distanceTo(this.lastMockLocation).toDouble()
        return d > 1000
    }

    override fun onLocationChanged(location: Location?) {
        if (location == null) return
        val plausible = this.isLocationPlausible(location)
        if (this.verbose && !this.quiet)
            Log.i(
                this.javaClass.simpleName,
                location.toString() + if (plausible) " -> plausible" else " -> not plausible"
            )

        if (!this.allowMockLocations && !plausible) {
            if (this.listener != null)
                this.listener!!.onMockLocationsDetected(
                    this.onGoToDevSettingsFromView,
                    this.onGoToDevSettingsFromDialog
                )
            return
        }

        this.bestLocation = location
        if (this.listener != null)
            this.listener!!.onNewLocationAvailable(location)
        else if (!this.quiet)
            Log.w(
                this.javaClass.simpleName,
                "New location is available, but no listener is registered!\n" +
                        "Specify a valid listener when constructing " + this.javaClass.simpleName +
                        " or register it explicitly with register()."
            )
    }
}
