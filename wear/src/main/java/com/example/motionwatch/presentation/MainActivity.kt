/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter and
 * https://github.com/android/wear-os-samples/tree/main/ComposeAdvanced to find the most up to date
 * changes to the libraries and their usages.
 */

package com.example.motionwatch.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.example.motionwatch.R
import com.example.motionwatch.presentation.theme.MotionWatchTheme
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.material.Button
import androidx.compose.ui.unit.dp
import com.google.android.gms.wearable.Wearable

class MainActivity : ComponentActivity() {


    private var gx by mutableStateOf(0f)
    private var gy by mutableStateOf(0f)
    private var gz by mutableStateOf(0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)

        setTheme(android.R.style.Theme_DeviceDefault)

        val filter = IntentFilter("GYRO_UPDATE")


        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                gx = intent?.getFloatExtra("gx", 0f) ?: 0f
                gy = intent?.getFloatExtra("gy", 0f) ?: 0f
                gz = intent?.getFloatExtra("gz", 0f) ?: 0f
            }
        }, filter)

//        setContent {
//            WearApp("Android",
//                startLogging = { startLogging() },
//                stopLogging = { stopLogging() })
//
//        }
        setContent {
            WearApp(
                gx = gx,
                gy = gy,
                gz = gz,
                startLogging = { sport -> startLogging(sport) },
                stopLogging = { stopLogging() }
            )
        }
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                Log.d("WearTest", "Connected nodes: ${nodes.map { it.id }}")
            }

    }
    private fun startLogging(sport: String) {
        val intent = Intent(this, SensorService::class.java)
        intent.putExtra("sport", sport)   // ← NEW
        startForegroundService(intent)
    }


    private fun stopLogging() {
        val intent = Intent(this, SensorService::class.java)
        stopService(intent)
    }
}

@Composable
fun WearApp(
    gx: Float,
    gy: Float,
    gz: Float,
    startLogging: (String) -> Unit,   // ← updated
    stopLogging: () -> Unit
) {
    MotionWatchTheme {

        var isLogging by remember { mutableStateOf(false) }

        // >>> NEW: Sport category state
        var sportCategory by remember { mutableStateOf("RUNNING") }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
        ) {

            TimeText(modifier = Modifier.align(Alignment.TopCenter))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                // Status + Sensor Readout
                Greeting(
                    isLogging = isLogging,
                    gx = gx,
                    gy = gy,
                    gz = gz
                )

                Spacer(modifier = Modifier.height(15.dp))

                // >>> NEW: Sport Category Selector Button
                Button(
                    onClick = {
                        sportCategory = when (sportCategory) {
                            "RUNNING" -> "WALKING"
                            "WALKING" -> "CYCLING"
                            "CYCLING" -> "GYM"
                            "GYM" -> "HIKING"
                            else -> "RUNNING"
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()          // ← FULL WIDTH BUTTON
                        .padding(horizontal = 1.dp)
                        .height(24.dp),          // ← TALLER BUTTON FRAME
                ) {
                    Text(
                        text = sportCategory,
                        style = MaterialTheme.typography.title3
                    )
                }



                Spacer(modifier = Modifier.height(15.dp))

                // Start / Stop toggle
                Button(
                    onClick = {
                        if (!isLogging) {
                            startLogging(sportCategory)   // ← pass category
                            isLogging = true
                        } else {
                            stopLogging()
                            isLogging = false
                        }
                    }
                ) {
                    Text(if (!isLogging) "Start" else "Stop")
                }
            }
        }
    }
}



@Composable
fun Greeting(
    isLogging: Boolean,
    gx: Float,
    gy: Float,
    gz: Float
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // >>> FIX: Status now reacts to isLogging
        Text(
            text = if (isLogging) "Status: Logging…" else "Status: Not Logging",
            color = if (isLogging) MaterialTheme.colors.primary else MaterialTheme.colors.secondary,
            textAlign = TextAlign.Center
        )
        // <<< END FIX

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Gyro: %.2f, %.2f, %.2f".format(gx, gy, gz),
            color = MaterialTheme.colors.primary,
            textAlign = TextAlign.Center
        )
    }
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    WearApp(
        gx = 0f,
        gy = 0f,
        gz = 0f,
        startLogging = {},
        stopLogging = {}
    )
}