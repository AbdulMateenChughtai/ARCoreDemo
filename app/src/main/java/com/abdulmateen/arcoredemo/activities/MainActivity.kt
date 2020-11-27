package com.abdulmateen.arcoredemo.activities

import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.abdulmateen.arcoredemo.R
import com.abdulmateen.arcoredemo.common.helpers.CameraPermissionHelper
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import kotlinx.android.synthetic.main.activity_main.*


class MainActivity : AppCompatActivity() {

    private var arSession: Session? = null

    // Set to true ensures requestInstall() triggers installation if necessary.
    private var userRequestedInstall = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("findMe", "123")
        setContentView(R.layout.activity_main)

        Log.d("findMe", "Good")
        maybeEnableArButton()
    }

    override fun onResume() {
        super.onResume()

        // ARCore requires camera permission to operate.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Log.d("findMe", "hey")
            CameraPermissionHelper.requestCameraPermission(this);
            return
        }


        try {
            if (arSession == null) {
                when (ArCoreApk.getInstance().requestInstall(this, userRequestedInstall)) {
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        Log.d("findMe", "INSTALLED")
                        arSession = Session(this)
                    }
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        userRequestedInstall = false
                    }
                }

            }
        } catch (e: UnavailableUserDeclinedInstallationException) {
            // Display an appropriate message to the user and return gracefully.
            Toast.makeText(this, "TODO: handle exception " + e, Toast.LENGTH_LONG)
                .show();
            return;
        } catch (e: Exception) {  // Current catch statements.
            return;  // mSession is still null.
        }
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(
                this,
                "Camera permission is needed to run this application",
                Toast.LENGTH_LONG
            )
                .show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }
    }

    fun setClickListeners() {
        btn_ar.setOnClickListener {
            arSession?.let {
                Log.d("findMe", "checkIfDeviceSupportsDepthApi")
                checkIfDeviceSupportsDepthApi(it)
            }
        }
    }


    fun maybeEnableArButton() {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        if (availability.isTransient) {
            // Re-query at 5Hz while compatibility is checked in the background.
            Handler().postDelayed(Runnable { maybeEnableArButton() }, 200)
        }
        if (availability.isSupported) {
            btn_ar.setVisibility(View.VISIBLE)
            btn_ar.setEnabled(true)
            setClickListeners()

            // indicator on the button.
        } else { // Unsupported or unknown.
            btn_ar.setVisibility(View.INVISIBLE)
            btn_ar.setEnabled(false)
        }
    }



    ///////////////////////////////////////////////////////////////////////////////////////////////
    /**
     * Methods related to Depth API
     */

    fun checkIfDeviceSupportsDepthApi(session: Session) {
        val config = session.config

        // Check whether the user's device supports the Depth API.
        val isDepthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        if (isDepthSupported) {
            config.depthMode = Config.DepthMode.AUTOMATIC
        }
        session.configure(config)
        session.resume()
        surfaceView.onResume()
        Log.d("findMe", "session.configure(config)")
        //retrieveDepthMaps(session.update())
    }

    fun retrieveDepthMaps(frame: Frame) {
        // Retrieve the depth map for the current frame, if available.
        try {
            val depthImage = frame.acquireDepthImage()
            // Use the depth map here.
        } catch (e: NotYetAvailableException) {
            // This means that depth data is not available yet.
            // Depth data will not be available if there are no tracked
            // feature points. This can happen when there is no motion, or when the
            // camera loses its ability to track objects in the surrounding
            // environment.
        }
    }
}