package com.sient.myrun.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.sient.myrun.presentation.theme.MyRunTheme

class MainActivity : ComponentActivity() {

    private val isAmbient = mutableStateOf(false)
    private val burnInProtection = mutableStateOf(false)
    // Wall-clock minute, refreshed by the system's ambient updates, so the
    // burn-in drift keeps moving even while the timer is paused.
    private val ambientMinute = mutableLongStateOf(0L)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val ambientObserver by lazy {
        AmbientLifecycleObserver(this, object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                burnInProtection.value = ambientDetails.burnInProtectionRequired
                ambientMinute.longValue = currentMinute()
                isAmbient.value = true
            }

            override fun onExitAmbient() {
                isAmbient.value = false
            }

            override fun onUpdateAmbient() {
                ambientMinute.longValue = currentMinute()
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TimerEngine.load(this)
        lifecycle.addObserver(ambientObserver)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MyRunApp(isAmbient.value, burnInProtection.value, ambientMinute.longValue)
        }
    }

    override fun onDestroy() {
        lifecycle.removeObserver(ambientObserver)
        super.onDestroy()
    }

    private fun currentMinute() = System.currentTimeMillis() / 60_000L
}

@Composable
fun MyRunApp(isAmbient: Boolean, burnInProtection: Boolean, ambientMinute: Long) {
    MyRunTheme {
        if (isAmbient) {
            AmbientTimerScreen(burnInProtection, ambientMinute)
        } else {
            AppScaffold {
                val navController = rememberSwipeDismissableNavController()
                SwipeDismissableNavHost(navController = navController, startDestination = "timer") {
                    composable("timer") {
                        TimerScreen(onOpenSettings = { navController.navigate("settings") })
                    }
                    composable("settings") {
                        SettingsScreen()
                    }
                }
            }
        }
    }
}

@Composable
fun AmbientTimerScreen(burnInProtection: Boolean, ambientMinute: Long) {
    // Low-power face: pure black, dim gray text, no buttons. With burn-in
    // protection the block of text drifts -6/0/+6 dp on a three-minute cycle,
    // keyed off the wall clock so it moves even when the timer is paused.
    val offset = if (burnInProtection) {
        ((ambientMinute % 3).toInt() - 1) * 6
    } else {
        0
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .offset(y = offset.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = TimerEngine.currentPhase.name,
            color = Color(0xFF9A93B0),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = formatTime(TimerEngine.timeLeft),
            fontSize = 46.sp,
            color = Color(0xFFBFB8D4)
        )
        Text(
            text = "total ${formatTime(TimerEngine.totalSeconds)}",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF9A93B0)
        )
        if (!TimerEngine.isRunning) {
            Text(
                text = "paused",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6E6885)
            )
        }
    }
}

private fun formatTime(totalSeconds: Int): String =
    "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)

@Composable
fun TimerScreen(onOpenSettings: () -> Unit) {
    ScreenScaffold {
        val context = LocalContext.current
        val phaseColor = when (TimerEngine.currentPhase) {
            Phase.RUN -> MaterialTheme.colorScheme.primary
            Phase.WALK -> MaterialTheme.colorScheme.tertiary
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(top = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = TimerEngine.currentPhase.name,
                color = phaseColor,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = formatTime(TimerEngine.timeLeft),
                fontSize = 46.sp,
                fontWeight = FontWeight.Bold,
                color = phaseColor
            )
            Text(
                text = "run ${formatTime(TimerEngine.runSeconds)} · walk ${formatTime(TimerEngine.walkSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "total ${formatTime(TimerEngine.totalSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { TimerEngine.toggle(context) },
                    colors = if (TimerEngine.isRunning) {
                        ButtonDefaults.filledTonalButtonColors()
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Text(if (TimerEngine.isRunning) "Pause" else "Start")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { TimerEngine.reset(context) }) {
                    Text("Reset")
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onOpenSettings) {
                Text("Intervals")
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    ScreenScaffold {
        val context = LocalContext.current
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            IntervalStepper(
                label = "Run",
                seconds = TimerEngine.runSeconds,
                onAdjust = { TimerEngine.adjustRunSeconds(context, it) }
            )
            Spacer(Modifier.height(12.dp))
            IntervalStepper(
                label = "Walk",
                seconds = TimerEngine.walkSeconds,
                onAdjust = { TimerEngine.adjustWalkSeconds(context, it) }
            )
        }
    }
}

@Composable
private fun IntervalStepper(label: String, seconds: Int, onAdjust: (Int) -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = { onAdjust(-30) },
            modifier = Modifier.size(40.dp),
            colors = ButtonDefaults.filledTonalButtonColors()
        ) {
            Text("−", fontSize = 20.sp)
        }
        Text(
            text = formatTime(seconds),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Button(
            onClick = { onAdjust(30) },
            modifier = Modifier.size(40.dp),
            colors = ButtonDefaults.filledTonalButtonColors()
        ) {
            Text("+", fontSize = 20.sp)
        }
    }
}
