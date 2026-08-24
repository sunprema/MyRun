package com.sient.myrun.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
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

    private val ambientObserver by lazy {
        AmbientLifecycleObserver(this, object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                burnInProtection.value = ambientDetails.burnInProtectionRequired
                isAmbient.value = true
            }

            override fun onExitAmbient() {
                isAmbient.value = false
            }

            override fun onUpdateAmbient() {}
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        setContent {
            MyRunApp(isAmbient.value, burnInProtection.value)
        }
    }

    override fun onDestroy() {
        lifecycle.removeObserver(ambientObserver)
        super.onDestroy()
    }
}

@Composable
fun MyRunApp(isAmbient: Boolean, burnInProtection: Boolean) {
    val vm: TimerViewModel = viewModel()

    MyRunTheme {
        if (isAmbient) {
            AmbientTimerScreen(vm, burnInProtection)
        } else {
            AppScaffold {
                val navController = rememberSwipeDismissableNavController()
                SwipeDismissableNavHost(navController = navController, startDestination = "timer") {
                    composable("timer") {
                        TimerScreen(vm, onOpenSettings = { navController.navigate("settings") })
                    }
                    composable("settings") {
                        SettingsScreen(vm)
                    }
                }
            }
        }
    }
}

@Composable
fun AmbientTimerScreen(vm: TimerViewModel, burnInProtection: Boolean) {
    // Low-power face: pure black, dim gray text, no buttons. With burn-in
    // protection the block of text drifts a little each minute.
    val offset = if (burnInProtection) {
        ((vm.totalSeconds / 60) % 3 - 1) * 6
    } else {
        0
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(top = offset.dp.coerceAtLeast(0.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = vm.currentPhase.name,
            color = Color(0xFF9A93B0),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = formatTime(vm.timeLeft),
            fontSize = 46.sp,
            color = Color(0xFFBFB8D4)
        )
        Text(
            text = "total ${formatTime(vm.totalSeconds)}",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF9A93B0)
        )
        if (!vm.isRunning) {
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
fun TimerScreen(vm: TimerViewModel, onOpenSettings: () -> Unit) {
    ScreenScaffold {
        val phaseColor = when (vm.currentPhase) {
            Phase.RUN -> MaterialTheme.colorScheme.primary
            Phase.WALK -> MaterialTheme.colorScheme.tertiary
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(top = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = vm.currentPhase.name,
                color = phaseColor,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = formatTime(vm.timeLeft),
                fontSize = 46.sp,
                fontWeight = FontWeight.Bold,
                color = phaseColor
            )
            Text(
                text = "run ${formatTime(vm.runSeconds)} · walk ${formatTime(vm.walkSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "total ${formatTime(vm.totalSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { vm.toggle() },
                    colors = if (vm.isRunning) {
                        ButtonDefaults.filledTonalButtonColors()
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Text(if (vm.isRunning) "Pause" else "Start")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.reset() }) {
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
fun SettingsScreen(vm: TimerViewModel) {
    ScreenScaffold {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            IntervalStepper(
                label = "Run",
                seconds = vm.runSeconds,
                onAdjust = { vm.adjustRunSeconds(it) }
            )
            Spacer(Modifier.height(12.dp))
            IntervalStepper(
                label = "Walk",
                seconds = vm.walkSeconds,
                onAdjust = { vm.adjustWalkSeconds(it) }
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
