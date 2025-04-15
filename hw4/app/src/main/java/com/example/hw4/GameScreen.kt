package com.example.hw4

import android.annotation.SuppressLint
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// Main screen composable
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun GameScreen(marbleViewModel: MarbleViewModel = viewModel()) {
    val haptic = LocalHapticFeedback.current

    // Launch a haptic effect whenever a hit is registered.
    LaunchedEffect(marbleViewModel.hitRegistered) {
        if (marbleViewModel.hitRegistered) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            // Reset the flag in the ViewModel.
            marbleViewModel.hitRegistered = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // Background image fills the screen
        Image(
            painter = painterResource(id = R.drawable.background),
            contentDescription = "Grass Background",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Game overlay with elements
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            val density = LocalDensity.current.density
            val containerSize = Size(maxWidth.value * density, maxHeight.value * density)
            LaunchedEffect(containerSize) {
                marbleViewModel.containerSize = containerSize
            }

            // Draw water obstacles (these slow the ball in your physics).
            marbleViewModel.waterObstacles.forEach { water ->
                WaterObstacleComposable(water = water)
            }

            // Draw invisible circular obstacles
            marbleViewModel.circularObstacles.forEach { obstacle ->
                CircularObstacleComposable(obstacle = obstacle)
            }

            // Draw Pokémon target if not yet caught
            if (!marbleViewModel.isPokemonCaught) {
                PokemonComposable(
                    position = marbleViewModel.pokemonState.position,
                    sizeDp = 75.dp,
                    pokemonResId = marbleViewModel.selectedPokemonResId,
                    invincible = marbleViewModel.collisionCooldownTime > 0f
                )
            }

            // Particle effect overlay.
            val ballSpeed = sqrt(
                marbleViewModel.marbleState.velocity.x.pow(2) +
                        marbleViewModel.marbleState.velocity.y.pow(2)
            )
            ParticlesOverlay(
                ballPosition = marbleViewModel.marbleState.position,
                ballSpeed = ballSpeed,
                modifier = Modifier.fillMaxSize()
            )

            // Draw the rolling Pokéball
            PokeballComposable(
                position = marbleViewModel.marbleState.position,
                ballDiameterDp = 66.dp,
                angle = marbleViewModel.marbleState.angle
            )

            // Display catch overlay when the Pokémon is caught
            if (marbleViewModel.isPokemonCaught) {
                // Determine if all four Pokémon have been caught.
                val allCaught = marbleViewModel.caughtPokemonSet.size == 4

                // Set message and button text based on caught status.
                val caughtMessage = if (allCaught) "You caught 'em all!" else {
                    val pokemonName = when (marbleViewModel.selectedPokemonResId) {
                        R.drawable.bulbasaur -> "Bulbasaur"
                        R.drawable.charmander -> "Charmander"
                        R.drawable.pikachu -> "Pikachu"
                        R.drawable.squirtle -> "Squirtle"
                        else -> "Unknown"
                    }
                    "You caught $pokemonName!"
                }
                val buttonText = if (allCaught) "Play again" else "Catch em' all"

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 32.dp)
                    ) {
                        Text(
                            text = caughtMessage,
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White
                        )
                        Image(
                            painter = painterResource(id = marbleViewModel.selectedPokemonResId),
                            contentDescription = "Caught Pokémon",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(150.dp)
                        )
                        Button(
                            onClick = { marbleViewModel.resetGame() },
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(2.dp, Color.Black),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(text = buttonText, color = Color.White)
                        }
                    }
                }
            }
        }

        // Catch tries indicator at bottom-left
        CatchTriesIndicator(
            hitCount = marbleViewModel.hitCount,
            modifier = Modifier.align(Alignment.BottomStart)
        )

        // Caught Pokémon indicator (2×2 grid) at bottom-right
        CaughtPokemonIndicator(
            caughtSet = marbleViewModel.caughtPokemonSet,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

@Composable
fun PokeballComposable(
    position: Offset,
    ballDiameterDp: androidx.compose.ui.unit.Dp,
    angle: Float
) {
    val ballDiameterPx = with(LocalDensity.current) { ballDiameterDp.toPx() }
    Image(
        painter = painterResource(id = R.drawable.pokeball),
        contentDescription = "Pokéball",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (position.x - ballDiameterPx / 2).roundToInt(),
                    y = (position.y - ballDiameterPx / 2).roundToInt()
                )
            }
            .size(ballDiameterDp)
            .graphicsLayer(rotationZ = angle)
    )
}

@Composable
fun PokemonComposable(
    position: Offset,
    sizeDp: androidx.compose.ui.unit.Dp,
    pokemonResId: Int,
    invincible: Boolean = false
) {
    val sizePx = with(LocalDensity.current) { sizeDp.toPx() }
    val animatedAlpha = if (invincible) {
        // Animate alpha from 0.5 to 1 and back, with each full cycle taking 3000ms.
        val infiniteTransition = rememberInfiniteTransition()
        infiniteTransition.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.75f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 500, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            )
        ).value
    } else {
        1f
    }

    Image(
        painter = painterResource(id = pokemonResId),
        contentDescription = "Pokémon Target",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (position.x - sizePx / 2).roundToInt(),
                    y = (position.y - sizePx / 2).roundToInt()
                )
            }
            .size(sizeDp)
            .alpha(animatedAlpha)
    )
}

@Composable
fun CircularObstacleComposable(obstacle: CircularObstacle) {
    val diameterDp = with(LocalDensity.current) { (obstacle.radius * 2).toDp() }
    val sizePx = with(LocalDensity.current) { diameterDp.toPx() }
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (obstacle.center.x - sizePx / 2).roundToInt(),
                    y = (obstacle.center.y - sizePx / 2).roundToInt()
                )
            }
            .size(diameterDp)
            .clip(CircleShape)
            //.background(Color.Red.copy(alpha = 1f))
    )
}

@Composable
fun WaterObstacleComposable(water: WaterObstacle) {
    val diameterDp = with(LocalDensity.current) { (water.radius * 2).toDp() }
    // Calculate the offset to center the circle at water.center.
    val waterCenterPx = with(LocalDensity.current) { water.center }
    val diameterPx = with(LocalDensity.current) { diameterDp.toPx() }
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (water.center.x - diameterPx / 2).roundToInt(),
                    y = (water.center.y - diameterPx / 2).roundToInt()
                )
            }
            .size(diameterDp)
            .clip(CircleShape)
            //.background(Color.Red.copy(alpha = 1f))
    )
}

@Composable
fun CatchTriesIndicator(hitCount: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(8.dp)) {
        for (i in 0 until 3) {
            val alphaValue = if (hitCount > i) 1f else 0.3f
            Image(
                painter = painterResource(id = R.drawable.pokeball),
                contentDescription = "Catch Indicator",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(45.dp)
                    .padding(horizontal = 4.dp)
                    .graphicsLayer(alpha = alphaValue)
            )
        }
    }
}

@Composable
fun CaughtPokemonIndicator(caughtSet: Set<Int>, modifier: Modifier = Modifier) {
    // Define the order of Pokémon.
    val allPokemon = listOf(
        R.drawable.bulbasaur,
        R.drawable.charmander,
        R.drawable.pikachu,
        R.drawable.squirtle
    )
    Column(
        modifier = modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 2 until 4) {
                val resId = allPokemon[i]
                val alphaValue = if (caughtSet.contains(resId)) 1f else 0.3f
                Image(
                    painter = painterResource(id = resId),
                    contentDescription = "Caught Pokémon Indicator",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(50.dp)
                        .graphicsLayer(alpha = alphaValue)
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 0 until 2) {
                val resId = allPokemon[i]
                val alphaValue = if (caughtSet.contains(resId)) 1f else 0.3f
                Image(
                    painter = painterResource(id = resId),
                    contentDescription = "Caught Pokémon Indicator",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(50.dp)
                        .graphicsLayer(alpha = alphaValue)
                )
            }
        }
    }
}



data class Particle(val position: Offset, val lifetime: Float, val initialLifetime: Float)

@Composable
fun ParticlesOverlay(
    ballPosition: Offset,
    ballSpeed: Float,
    modifier: Modifier = Modifier
) {
    var particles by remember { mutableStateOf(listOf<Particle>()) }

    // More particles spawn when the ball is moving faster.
    val spawnCount = maxOf(1, (ballSpeed / 150).toInt())

    // Spawn particles at a fixed interval.
    LaunchedEffect(ballPosition, ballSpeed) {
        while (true) {
            if (ballSpeed > 100) {
                // Spread particles widely and spawn them slightly below the ball.
                val newParticles = List(spawnCount) {
                    val angle = Random.nextFloat() * 2 * PI.toFloat()
                    // Increase spread: random radius up to 20f.
                    val radius = Random.nextFloat() * 50f
                    Particle(
                        // Spawn slightly lower (offset by 20f) so particles appear underneath the Pokéball.
                        position = ballPosition + Offset(radius * cos(angle), radius * sin(angle)),
                        lifetime = 1f,
                        initialLifetime = 1f
                    )
                }
                particles = particles + newParticles
            }
            delay(50L)
        }
    }

    // Update particle lifetimes, removing particles that have faded out.
    LaunchedEffect(Unit) {
        while (true) {
            particles = particles.mapNotNull { particle ->
                val newLifetime = particle.lifetime - 0.05f
                if (newLifetime > 0f) particle.copy(lifetime = newLifetime) else null
            }
            delay(50L)
        }
    }

    // Draw particles as dark green circles that fade out.
    Canvas(modifier = modifier.fillMaxSize()) {
        particles.forEach { particle ->
            // The particle alpha scales from 0.5 (spawned value) to 0 over its lifetime.
            val alpha = (particle.lifetime / particle.initialLifetime) * 0.5f
            drawCircle(
                color = Color(0xFF006400),
                center = particle.position,
                radius = 5f,
                alpha = alpha
            )
        }
    }
}