package com.example.hw4

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// Data class for gravity sensor readings.
data class GravityReading(val x: Float, val y: Float, val z: Float)

// Returns a Flow of GravityReading.
fun getGravityData(gravitySensor: Sensor?, sensorManager: SensorManager): Flow<GravityReading> =
    channelFlow {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event?.let { trySend(GravityReading(it.values[0], it.values[1], it.values[2])) }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, gravitySensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sensorManager.unregisterListener(listener) }
    }

// Data classes representing the game states.
data class MarbleState(
    val position: Offset,
    val velocity: Offset,
    val angle: Float = 0f
)
data class PokemonState(
    val position: Offset,
    val velocity: Offset,
    val size: Float
)
data class CircularObstacle(
    val center: Offset,
    val radius: Float
)
data class WaterObstacle(
    val center: Offset,
    val radius: Float
)

// The ViewModel
class MarbleViewModel(application: Application) : AndroidViewModel(application) {
    private val sensorManager =
        application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

    // Current gravity vector.
    private val _gravity = mutableStateOf(Offset.Zero)
    val gravity get() = _gravity

    // Pokéball state – position represents its center.
    var marbleState by mutableStateOf(
        MarbleState(position = Offset(500f, 1000f), velocity = Offset.Zero, angle = 0f)
    )
        private set

    // Container size (in pixels), provided by the UI.
    var containerSize by mutableStateOf(Size.Zero)

    // Physics parameters.
    private val scale = 666f
    private var lastUpdateTime = System.currentTimeMillis()

    // Pokémon target state.
    var pokemonState by mutableStateOf(
        PokemonState(position = Offset(750f, 750f), velocity = Offset.Zero, size = 75f)
    )
        private set

    // Game state.
    var hitCount by mutableStateOf(0)
    var isPokemonCaught by mutableStateOf(false)
    // Cooldown (seconds) to avoid multiple hits.
    private var collisionCooldown = 0f
    val collisionCooldownTime: Float get() = collisionCooldown

    // Tracks caught Pokémon resource IDs.
    var caughtPokemonSet by mutableStateOf(setOf<Int>())
        private set

    // List of possible Pokémon image resource IDs.
    private val possiblePokemonResIds = listOf(
        R.drawable.bulbasaur,
        R.drawable.charmander,
        R.drawable.pikachu,
        R.drawable.squirtle
    )
    var selectedPokemonResId by mutableStateOf(R.drawable.bulbasaur)
        private set

    // Define circular obstacles.
    val circularObstacles = listOf(
        CircularObstacle(center = Offset(0f, 0f), radius = 200f),
        CircularObstacle(center = Offset(-150f, 200f), radius = 200f),
        CircularObstacle(center = Offset(200f, -150f), radius = 200f),
        CircularObstacle(center = Offset(1050f, 0f), radius = 200f),
        CircularObstacle(center = Offset(750f, -100f), radius = 200f),
        CircularObstacle(center = Offset(-50f, 1750f), radius = 200f),
        CircularObstacle(center = Offset(200f, 1900f), radius = 200f),
        CircularObstacle(center = Offset(-150f, 1550f), radius = 200f),
        CircularObstacle(center = Offset(1050f, 1700f), radius = 200f),
        CircularObstacle(center = Offset(850f, 1850f), radius = 200f),

        CircularObstacle(center = Offset(740f, 305f), radius = 75f),
        CircularObstacle(center = Offset(160f, 400f), radius = 70f),
        CircularObstacle(center = Offset(920f, 975f), radius = 70f)
    )

    val waterObstacles = listOf(
        WaterObstacle(center = Offset(125f, 1575f), radius = 175f),
        WaterObstacle(center = Offset(175f, 1575f), radius = 175f),
        WaterObstacle(center = Offset(225f, 1575f), radius = 175f)
    )

    // Gravity sensor flow.
    private val gravityFlow = getGravityData(gravitySensor, sensorManager)

    // Hit registration flag used for haptic feedback.
    var hitRegistered by mutableStateOf(false)

    init {
        selectRandomPokemon()

        // Collect gravity sensor events.
        viewModelScope.launch {
            gravityFlow.collect { reading ->
                _gravity.value = Offset(reading.x, reading.y)
            }
        }

        // Marble physics loop.
        viewModelScope.launch {
            while (true) {
                val currentTime = System.currentTimeMillis()
                val dt = (currentTime - lastUpdateTime) / 1000f
                lastUpdateTime = currentTime
                updateMarble(dt)
                delay(16L)
            }
        }

        // Pokémon movement and collision detection.
        viewModelScope.launch {
            var lastPokemonUpdateTime = System.currentTimeMillis()
            while (true) {
                val currentTime = System.currentTimeMillis()
                val dt = (currentTime - lastPokemonUpdateTime) / 1000f
                lastPokemonUpdateTime = currentTime

                if (!isPokemonCaught) updatePokemon(dt)
                if (collisionCooldown > 0f) collisionCooldown -= dt
                checkCollisionBallPokemon()
                delay(16L)
            }
        }
    }

    private fun updateMarble(dt: Float) {
        if (containerSize == Size.Zero) return
        val ballRadius = 25f
        val damping = 0.99f
        val restitution = 0.75f

        val g = _gravity.value
        val acceleration = Offset(-g.x * scale, g.y * scale)
        var newVelocity = marbleState.velocity + acceleration * dt
        newVelocity *= damping
        var newPosition = marbleState.position + newVelocity * dt

        // Constrain to screen boundaries.
        if (newPosition.x - ballRadius < 0f) {
            newPosition = newPosition.copy(x = ballRadius)
            newVelocity = newVelocity.copy(x = -newVelocity.x * restitution)
        } else if (newPosition.x + ballRadius > containerSize.width) {
            newPosition = newPosition.copy(x = containerSize.width - ballRadius)
            newVelocity = newVelocity.copy(x = -newVelocity.x * restitution)
        }
        if (newPosition.y - ballRadius < 0f) {
            newPosition = newPosition.copy(y = ballRadius)
            newVelocity = newVelocity.copy(y = -newVelocity.y * restitution)
        } else if (newPosition.y + ballRadius > containerSize.height) {
            newPosition = newPosition.copy(y = containerSize.height - ballRadius)
            newVelocity = newVelocity.copy(y = -newVelocity.y * restitution)
        }

        // Apply water slowdown effect.
        val waterDampingFactor = 0.75f  // Lower value causes slower movement through water.
        for (water in waterObstacles) {
            val diff = newPosition - water.center
            val distanceToWaterCenter = sqrt(diff.x * diff.x + diff.y * diff.y)
            if (distanceToWaterCenter < water.radius) {
                newVelocity *= waterDampingFactor
                break  // If the ball is in one water area, no need to check others.
            }
        }

        // Collide with circular obstacles.
        for (obstacle in circularObstacles) {
            val minDistance = ballRadius + obstacle.radius
            val diff = newPosition - obstacle.center
            val distance = sqrt(diff.x.pow(2) + diff.y.pow(2))
            if (distance < minDistance) {
                val penetration = minDistance - distance
                val normal = if (distance == 0f) Offset(1f, 0f) else Offset(diff.x / distance, diff.y / distance)
                newPosition += normal * penetration
                val dot = newVelocity.x * normal.x + newVelocity.y * normal.y
                newVelocity = (newVelocity - normal * (2 * dot)) * restitution
            }
        }

        // Update rolling angle.
        val displacement = sqrt(
            (newPosition.x - marbleState.position.x).pow(2) +
                    (newPosition.y - marbleState.position.y).pow(2)
        )
        val baseDeltaAngle = (displacement / ballRadius) * (180f / PI.toFloat())
        val signFactor = if (abs(newVelocity.x) > abs(newVelocity.y))
            if (newVelocity.x > 0f) 1f else -1f
        else
            if (newVelocity.y > 0f) 1f else -1f
        val rotationMultiplier = 0.3f
        val deltaAngle = baseDeltaAngle * rotationMultiplier * signFactor
        val newAngle = marbleState.angle + deltaAngle

        marbleState = MarbleState(position = newPosition, velocity = newVelocity, angle = newAngle)
    }

    private fun updatePokemon(dt: Float) {
        if (containerSize == Size.Zero) return

        val currentPos = pokemonState.position
        var currentVel = pokemonState.velocity
        val pokemonRadius = pokemonState.size / 2f

        // Predict new position and enforce screen boundaries.
        var newPos = currentPos + currentVel * dt
        if (newPos.x - pokemonRadius < 0f) {
            currentVel = currentVel.copy(x = abs(currentVel.x))
        } else if (newPos.x + pokemonRadius > containerSize.width) {
            currentVel = currentVel.copy(x = -abs(currentVel.x))
        }
        if (newPos.y - pokemonRadius < 0f) {
            currentVel = currentVel.copy(y = abs(currentVel.y))
        } else if (newPos.y + pokemonRadius > containerSize.height) {
            currentVel = currentVel.copy(y = -abs(currentVel.y))
        }
        newPos = currentPos + currentVel * dt

        // Handle collisions with obstacles.
        val restitution = 0.75f
        for (obstacle in circularObstacles) {
            val diff = newPos - obstacle.center
            val distance = sqrt(diff.x.pow(2) + diff.y.pow(2))
            val minDistance = pokemonRadius + obstacle.radius
            if (distance < minDistance) {
                val penetration = minDistance - distance
                val normal = if (distance == 0f) Offset(1f, 0f) else Offset(diff.x / distance, diff.y / distance)
                newPos += normal * penetration
                val dot = currentVel.x * normal.x + currentVel.y * normal.y
                currentVel = (currentVel - normal * (2 * dot)) * restitution
            }
        }

        // Conditional behavior: if this Pokémon type hasn't been caught, make it faster and more erratic.
        val desiredSpeed = if (caughtPokemonSet.contains(selectedPokemonResId)) 350f else 400f
        val randomRotationDegrees = if (caughtPokemonSet.contains(selectedPokemonResId))
            (Random.nextFloat() - 0.5f) * 10f   // ±5° variation
        else
            (Random.nextFloat() - 0.5f) * 20f   // ±10° variation
        val randomRotationRadians = randomRotationDegrees * (PI.toFloat() / 180f)
        val cosTheta = cos(randomRotationRadians)
        val sinTheta = sin(randomRotationRadians)
        var newVel = Offset(
            x = currentVel.x * cosTheta - currentVel.y * sinTheta,
            y = currentVel.x * sinTheta + currentVel.y * cosTheta
        )

        // Normalize velocity to constant speed.
        val length = sqrt(newVel.x.pow(2) + newVel.y.pow(2))
        newVel = if (length != 0f)
            Offset(newVel.x / length * desiredSpeed, newVel.y / length * desiredSpeed)
        else
            Offset(desiredSpeed, 0f)
        newPos = currentPos + newVel * dt

        pokemonState = PokemonState(position = newPos, velocity = newVel, size = pokemonState.size)
    }

    // Check collisions between the ball and the Pokémon.
    private fun checkCollisionBallPokemon() {
        val ballRadius = 25f
        // Use a slightly smaller effective radius for collision detection.
        val pokemonRadius = pokemonState.size / 1.5f
        val dx = marbleState.position.x - pokemonState.position.x
        val dy = marbleState.position.y - pokemonState.position.y
        val distance = sqrt(dx * dx + dy * dy)

        if (distance < ballRadius + pokemonRadius && collisionCooldown <= 0f) {
            hitCount++
            collisionCooldown = 2.5f
            hitRegistered = true
            repositionPokemon()
            if (hitCount >= 3) {
                isPokemonCaught = true
                caughtPokemonSet = caughtPokemonSet + selectedPokemonResId
            }
        }
    }

    // Repositions the Pokémon in the central 60% of the screen.
    private fun repositionPokemon() {
        if (containerSize == Size.Zero) return
        val marginX = containerSize.width * 0.2f
        val marginY = containerSize.height * 0.2f
        val newX = marginX + (containerSize.width - 2 * marginX) * Random.nextFloat()
        val newY = marginY + (containerSize.height - 2 * marginY) * Random.nextFloat()

        val newVelX = (50f + 100f * Random.nextFloat()) * if (Random.nextBoolean()) 1f else -1f
        val newVelY = (50f + 100f * Random.nextFloat()) * if (Random.nextBoolean()) 1f else -1f
        pokemonState = PokemonState(
            position = Offset(newX, newY),
            velocity = Offset(newVelX, newVelY),
            size = pokemonState.size
        )
    }

    private fun selectRandomPokemon() {
        selectedPokemonResId = possiblePokemonResIds.random()
    }

    fun resetGame() {
        hitCount = 0
        isPokemonCaught = false
        marbleState = MarbleState(position = Offset(750f, 750f), velocity = Offset.Zero, angle = 0f)
        selectRandomPokemon()
        repositionPokemon()
        lastUpdateTime = System.currentTimeMillis()
        collisionCooldown = 0f
        if (caughtPokemonSet.size == 4) {
            caughtPokemonSet = emptySet()
        }
    }
}