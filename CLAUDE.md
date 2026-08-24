## REQUIREMENT
I have a samsung watch, I want to develop a simple application for it. Basically, I just need a timer for tracking my morning run. It needs to alternate between two timers, for example, I want to have a 1 minute interval for running, and 3 minutes for walking. It has to buzz a minute showing 1 after 1 minute, and then buzz and display number 3 after 3 minutes and alternate. The idea is that I should be able to configure these two timers. How can I implement this.

## Core app Structure
```kotlin
// TimerViewModel.kt
class TimerViewModel : ViewModel() {
var runSeconds by mutableStateOf(60)
var walkSeconds by mutableStateOf(180)

    var isRunning by mutableStateOf(false)
    var currentPhase by mutableStateOf("RUN") // or "WALK"
    var timeLeft by mutableStateOf(runSeconds)

    private var job: Job? = null

    fun start(vibrator: Vibrator) {
        isRunning = true
        job = viewModelScope.launch {
            while (isRunning) {
                delay(1000)
                timeLeft--
                if (timeLeft <= 0) {
                    vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                    currentPhase = if (currentPhase == "RUN") "WALK" else "RUN"
                    timeLeft = if (currentPhase == "RUN") runSeconds else walkSeconds
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        job?.cancel()
    }
}
```

## Main Screen

```kotlin
// MainScreen composable
@Composable
fun TimerScreen(vm: TimerViewModel, vibrator: Vibrator) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = vm.currentPhase, fontSize = 20.sp)
        Text(text = "${vm.timeLeft / 60}:${(vm.timeLeft % 60).toString().padStart(2, '0')}",
             fontSize = 40.sp)

        Row {
            Button(onClick = {
                if (vm.isRunning) vm.stop() else vm.start(vibrator)
            }) {
                Text(if (vm.isRunning) "Pause" else "Start")
            }
        }
    }
}
```

## Getting the vibrator

```kotlin

val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator


```

Add to AndroidManifest.xml: <uses-permission android:name="android.permission.VIBRATE"/>

Config screen: just a second composable with two Stepper or number pickers for run/walk minutes, storing into vm.runSeconds/vm.walkSeconds (convert min→sec). Persist with SharedPreferences if you want it to survive app restarts.

Nice-to-have: use a Wearable.CapabilityClient/foreground Service with a wake lock if you want it to keep ticking with screen off — otherwise Compose recomposition pauses when the screen sleeps.