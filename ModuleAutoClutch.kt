package net.ccbluex.jmcomicfix.features.module.modules.player.autoClutch

import com.mojang.blaze3d.systems.RenderSystem
import com.viaversion.viaversion.api.minecraft.Vector3f
import net.ccbluex.jmcomicfix.features.module.modules.world.stuck.ModuleAutoStuck
import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.event.events.*
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.misc.ModuleEasyPearl.getTargetRotation
import net.ccbluex.liquidbounce.features.module.modules.movement.ModuleAirJump
import net.ccbluex.liquidbounce.features.module.modules.movement.ModuleFreeze
import net.ccbluex.liquidbounce.features.module.modules.movement.fly.ModuleFly
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.ModuleScaffold
import net.ccbluex.liquidbounce.render.*
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsConfigurable
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.block.getState
import net.ccbluex.liquidbounce.utils.client.notification
import net.ccbluex.liquidbounce.utils.client.sendPacketSilently
import net.ccbluex.liquidbounce.utils.combat.CombatManager
import net.ccbluex.liquidbounce.utils.entity.SimulatedPlayer
import net.ccbluex.liquidbounce.utils.entity.SimulatedPlayerCache
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.useHotbarSlotOrOffhand
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.math.*
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.render.refreshRate
import net.minecraft.block.BlockRenderType
import net.minecraft.block.Blocks
import net.minecraft.client.gl.ShaderProgramKeys
import net.minecraft.client.render.BufferRenderer
import net.minecraft.client.render.Camera
import net.minecraft.client.render.VertexFormat
import net.minecraft.client.render.VertexFormats
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.Entity
import net.minecraft.entity.projectile.ProjectileUtil
import net.minecraft.entity.projectile.thrown.EnderPearlEntity
import net.minecraft.item.Items
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket
import net.minecraft.state.property.Properties
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.Util
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.*
import net.minecraft.util.shape.VoxelShapes
import net.minecraft.world.RaycastContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*
import kotlin.random.Random

@Suppress("TooManyFunctions", "unused")
object ModuleAutoClutch : ClientModule("AutoClutch", Category.PLAYER) {

    enum class State { IDLE, FINDING_PEARL, CALCULATING, ROTATING, THROWING, PAUSED }
    enum class Algorithm(override val choiceName: String) : NamedChoice {
        SimulatedAnnealing("SimulatedAnnealing")
    }
    private val algorithm by enumChoice("Algorithm", Algorithm.SimulatedAnnealing)
    private val adjacentSafeBlocks by int("AdjacentSafeBlocks", 0, 0..3)
    private val pitchRange by floatRange("PitchLimit", -90f..0f, -90f..45f)
    private val voidEvasionFrequency by int("VoidEvasionFrequency", 14, 5..refreshRate)
    private val voidThreshold by int("VoidLevel", 0, -256..0)
    private val maxIterations by int("MaxIterations", 500, 50..10000)
    private val stagnationLimit by int("StagnationLimit", 2333, 1000..10000)
    private val iterationSpeed by float("IterationsSpeed", 5f, 1f..50f)
    private val initialTemperature by float("InitialTemp", 20f, 5f..50f)
    private val minTemperature by float("MinTemperature", 0.01f, 0.01f..0.1f)
    private val coolingFactor by float("CoolingFactor", 0.97f, 0.95f..0.99f)
    private val aimPrecision by float("AimPrecision", 0.1f, 0.1f..1f)
    private var avgCalcTime by float("AverageCalcTime", 0.1f, 0.01f..0.15f)
    private var maxCacheSize by int("MaxCacheSize", 1337, 500..1500)
    private val simulateTime by int("SimulationTime", 30, 30..50, "ticks")
    private var cooldownTicks by int("Cooldown", 0, 0..20, "ticks")
    private val allowClutchWithStuck by boolean("AllowClutchWithStuck", true)
    private val checkHeadSpace by boolean("EnsureHeadSpace", true)
    private val onlyDuringCombat by boolean("OnlyDuringCombat", false)

    private val defaultUnsafeBlocks = setOf(
        Blocks.WATER,
        Blocks.LAVA,
        Blocks.COBWEB,
        Blocks.MAGMA_BLOCK,
        Blocks.CACTUS,
        Blocks.FIRE,
        Blocks.SOUL_FIRE,
        Blocks.SWEET_BERRY_BUSH,
        Blocks.WITHER_ROSE,
        Blocks.POWDER_SNOW,
        Blocks.POINTED_DRIPSTONE
    )
    private val unsafeBlocks by blocks("UnsafeBlocks", defaultUnsafeBlocks.toMutableSet())
    private val rotationConfig = tree(RotationsConfigurable(this))

    var state = State.IDLE
    private var predictedThrowPosition: Vec3d? = null
    private val positionCache = ConcurrentHashMap<BlockPos, Double>()
    private var bestEnergy = Double.MAX_VALUE
    private var currentSolution = Rotation(0f, 0f)
    private var currentEnergy = Double.MAX_VALUE
    private var temperature = initialTemperature
    private var iterations = 0
    private var noImprovementCount = 0
    private var lastPearlThrowTime = 0L
    private var safetyCheckCounter = 0
    private var triggerPosition: Vec3d? = null
    private var pearlSlot: HotbarItemSlot? = null
    private var lastPlayerPosition: Vec3d? = null
    private var bestSolution: Rotation? = null
    private var isLikelyFallingIntoVoid = false
    private var safetyCheckActive = false
    private var manualPearlThrown = false
    private var lastTrajectoryUpdate: Long = 0
    private var isPearlInFlight = false
    private var pearlThrowTick: Long = 0L
    private var cachedTrajectory: List<Pair<Vector3f, Color4b>>? = null
    private var lastPlayerState: Triple<Vec3d, Vec3d, DirectionalInput>? = null
    private val backgroundDone = AtomicBoolean(false)

    private fun shouldCalculateTrajectory(): Boolean {
        return !(onlyDuringCombat && !CombatManager.isInCombat)&&
            !(ModuleAutoStuck.shouldEnableStuck && !allowClutchWithStuck) &&
            !ModuleScaffold.running &&
            !ModuleFreeze.running &&
            !ModuleAirJump.running &&
            !ModuleFly.running
    }
    private val tickHandler = handler<GameTickEvent> {
        if (!shouldCalculateTrajectory()) {
            clearTrajectoryAndCache()
            return@handler
        }

        when (state) {
            State.CALCULATING -> if (backgroundDone.get()) {
                backgroundDone.set(false)
                state = State.ROTATING
            }
            else -> handleStateMachine()
        }
        checkPlayerMovement()
        checkVoidFall()
    }
    private val interactedItemHandler = handler<PlayerInteractedItemEvent> { event ->
        if (event.actionResult != ActionResult.PASS &&
            event.player == mc.player &&
            event.hand == Hand.MAIN_HAND &&
            player.getStackInHand(event.hand).item == Items.ENDER_PEARL &&
            state != State.PAUSED
        ) {
            manualPearlThrown = true
            state = State.PAUSED
            scheduleSafetyCheck()
        }
    }
    private val packetEventHandler = handler<PacketEvent> { event ->
        if (!isPearlInFlight) return@handler
        if (event.packet is PlayerPositionLookS2CPacket) {

            isPearlInFlight = false
            pearlThrowTick = 0L
            notification(
                "AutoClutch",
                "Pearl landed, resuming calculations.",
                NotificationEvent.Severity.INFO
            )
            if (!isPlayerSafe()) {

                triggerPosition = player.pos
                state = State.FINDING_PEARL
            } else {
                state = State.IDLE
            }
        }
    }

    private fun handleStateMachine() {
        if (isPearlInFlight) {
            state = State.PAUSED
            return
        }
        if (state == State.PAUSED) {
            safetyCheckCounter--
            if (safetyCheckCounter <= 0) {
                safetyCheckActive = false
                manualPearlThrown = false
                state = if (isPlayerSafe()) State.IDLE else {
                    triggerPosition = player.pos
                    State.FINDING_PEARL
                }
            }
            return
        }
        if (state == State.IDLE) {
            pruneCache()
        }
        if (safetyCheckActive) {
            safetyCheckCounter--
            if (safetyCheckCounter <= 0) {
                safetyCheckActive = false
                state = if (isPlayerSafe()) State.IDLE else {
                    triggerPosition = player.pos
                    State.FINDING_PEARL
                }
            }
            return
        }


        if (isPearlInFlight  && pearlThrowTick > 100) {
            isPearlInFlight = false
            pearlThrowTick = 0L
            notification(
                "AutoClutch",
                "Pearl timed out, resuming calculations.",
                NotificationEvent.Severity.ERROR
            )
        }


        if (isPearlInFlight && state == State.FINDING_PEARL) {
            return
        }

        if (ModuleScaffold.enabled && ModuleScaffold.blockCount == 0) {
            ModuleScaffold.enabled = false
        }

        when (state) {
            State.IDLE -> checkActivationConditions()
            State.FINDING_PEARL -> findPearl()
            State.CALCULATING -> calculateSolution()
            State.ROTATING -> rotateToSolution()
            State.THROWING -> throwPearl()
            State.PAUSED -> {}
        }
    }

    private fun pruneCache() {
        while (positionCache.size > maxCacheSize) {
            positionCache.entries.iterator().next().let { positionCache.remove(it.key) }
        }
    }

    private fun checkActivationConditions() {
        if (System.currentTimeMillis() - lastPearlThrowTime < 1000L) return

        if (player.isCreative || player.isSpectator || player.isGliding || player.isSneaking) {
            resetAllVariables()
            return
        }

        if (isPlayerSafe() || canReachSafeBlockFrom() || isBlockUnder(2.0)) {
            resetAllVariables()
            return
        }

        if (isLikelyFallingIntoVoid) {
            triggerPosition = player.pos
            state = State.FINDING_PEARL
        }
    }

    private fun checkPlayerMovement() {
        val currentPos = player.pos
        lastPlayerPosition?.let { lastPos ->
            if (currentPos.distanceTo(lastPos) > 1.0) {
                resetAllVariables()
            }
        }
        lastPlayerPosition = currentPos
    }

    private val worldRenderHandler = handler<WorldRenderEvent> { event ->
        if (!shouldCalculateTrajectory()) {
            clearTrajectoryAndCache()
            return@handler
        }

        if (player.mainHandStack.item == Items.ENDER_PEARL || player.offHandStack.item == Items.ENDER_PEARL) {
            val matrixStack = event.matrixStack
            // Use simulated pearl trajectory instead of crosshair position
            val rotation = bestSolution ?: Rotation(player.yaw, player.pitch)
            val landingPos = simulatePearlTrajectory(rotation) ?: return@handler
            val blockPos = landingPos.toBlockPos()
            val state = blockPos.getState() ?: return@handler
            val belowPos = blockPos.down()
            val belowState = world.getBlockState(belowPos)
            val isSafeLanding = !belowState.isAir && belowState.block !in unsafeBlocks

            renderEnvironmentForWorld(matrixStack) {
                withDisabledCull {
                    val color = if (isSafeLanding && getTargetRotation(landingPos) != null) {
                        Color4b(0x20, 0xC2, 0x06) // Green
                    } else {
                        Color4b(0xD7, 0x09, 0x09) // Red
                    }

                    val baseColor = color.with(a = 50)
                    val transparentColor = baseColor.with(a = 0)
                    val outlineColor = color.with(a = 200)

                    withPositionRelativeToCamera(landingPos.toBlockPos().toVec3d()) {
                        if (state.renderType != BlockRenderType.MODEL && state.isAir) {
                            withColor(baseColor) {
                                drawSideBox(FULL_BOX, Direction.DOWN)
                            }
                            withColor(outlineColor) {
                                drawSideBox(FULL_BOX, Direction.DOWN, onlyOutline = true)
                            }
                            drawGradientSides(0.7, baseColor, transparentColor, FULL_BOX)
                        } else {
                            withColor(baseColor) {
                                drawSolidBox(FULL_BOX)
                            }
                            withColor(outlineColor) {
                                drawOutlinedBox(FULL_BOX)
                            }
                        }
                    }
                }
            }
        }

        drawPlayerTrajectory(event.matrixStack)
    }

    private fun drawPlayerTrajectory(matrixStack: MatrixStack) {
        if (!shouldCalculateTrajectory()) {
            clearTrajectoryAndCache()
            return
        }

        val currentTime = System.currentTimeMillis()
        val camera = mc.entityRenderDispatcher.camera ?: return
        val playerState = Triple(player.pos, player.velocity, DirectionalInput(player.input))
        if (cachedTrajectory == null || currentTime - lastTrajectoryUpdate > 100 || lastPlayerState?.let {
                it.first.distanceTo(playerState.first) > 0.5 || it.second.distanceTo(playerState.second) > 0.1
            } != false) {
            lastTrajectoryUpdate = currentTime
            lastPlayerState = playerState

            val simulatedPlayer = SimulatedPlayer.fromClientPlayer(
                SimulatedPlayer.SimulatedPlayerInput.fromClientPlayer(playerState.third).apply {
                    this.sprinting = true
                    this.jumping = true
                }
            )
            val cache = SimulatedPlayerCache(simulatedPlayer)
            val trajectoryPoints = mutableListOf<Pair<Vec3d, Boolean>>()

            for (tick in 0 until simulateTime) {
                simulatedPlayer.tick()
                val snapshot = cache.getSnapshotAt(tick)
                val currentPos = snapshot.pos
                val isSafe = canReachSafeBlockFrom() && !isInVoid(currentPos)
                trajectoryPoints.add(currentPos to isSafe)
            }
            cachedTrajectory = generateTrajectorySegments(trajectoryPoints, camera)
        }

        renderEnvironmentForWorld(matrixStack) {
            withDisabledCull {
                val matrix = matrixStack.peek().positionMatrix
                val buffer = RenderSystem.renderThreadTesselator().begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR)
                RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR)

                cachedTrajectory?.let { segments ->
                    buffer.apply {
                        for ((vec, color) in segments) {
                            vertex(matrix, vec.x, vec.y, vec.z)
                                .color(color.r / 255f, color.g / 255f, color.b / 255f, color.a / 255f)
                        }
                        BufferRenderer.drawWithGlobalProgram(end())
                    }
                }
            }
        }
    }

    private fun clearTrajectoryAndCache() {
        cachedTrajectory = null
        lastPlayerState = null
        lastTrajectoryUpdate = 0
        positionCache.clear()
    }

    private fun generateTrajectorySegments(
        points: List<Pair<Vec3d, Boolean>>,
        camera: Camera
    ): List<Pair<Vector3f, Color4b>> {
        val result = mutableListOf<Pair<Vector3f, Color4b>>()
        val segmentsPerTick = 5

        if (points.size < 2) {
            return points.map { (pos, isSafe) ->
                Vector3f(
                    (pos.x - camera.pos.x).toFloat(),
                    (pos.y - camera.pos.y).toFloat() + 0.1f,
                    (pos.z - camera.pos.z).toFloat()
                ) to if (isSafe) Color4b(0x20, 0xC2, 0x06, 200) else Color4b(0xD7, 0x09, 0x09, 200)
            }
        }

        fun catmullRom(t: Float, p0: Vec3d, p1: Vec3d, p2: Vec3d, p3: Vec3d): Vec3d {
            val t2 = t * t
            val t3 = t2 * t
            val a = -0.5 * t3 + t2 - 0.5 * t
            val b = 1.5 * t3 - 2.5 * t2 + 1.0
            val c = -1.5 * t3 + 2.0 * t2 + 0.5 * t
            val d = 0.5 * t3 - 0.5 * t2
            return Vec3d(
                a * p0.x + b * p1.x + c * p2.x + d * p3.x,
                a * p0.y + b * p1.y + c * p2.y + d * p3.y,
                a * p0.z + b * p1.z + c * p2.z + d * p3.z
            )
        }

        val pointList = points.map { it.first }
        val isSafeList = points.map { it.second }

        for (i in 0 until pointList.size - 1) {
            val p1 = pointList[i]
            val p2 = pointList[i + 1]
            val p0 = if (i > 0) pointList[i - 1] else p1
            val p3 = if (i < pointList.size - 2) pointList[i + 2] else p2

            val isSafe1 = isSafeList[i]
            val isSafe2 = isSafeList[i + 1]

            for (j in 0..segmentsPerTick) {
                val t = j / segmentsPerTick.toFloat()
                val interpolatedPos = catmullRom(t, p0, p1, p2, p3)

                val color = if (isSafe1 == isSafe2) {
                    if (isSafe1) Color4b(0x20, 0xC2, 0x06, 200) else Color4b(0xD7, 0x09, 0x09, 200)
                } else {
                    val colorT = t
                    val r = ((if (isSafe1) 0x20 else 0xD7) + ((if (isSafe2) 0x20 else 0xD7) - (if (isSafe1) 0x20 else 0xD7)) * colorT).toInt()
                    val g = ((if (isSafe1) 0xC2 else 0x09) + ((if (isSafe2) 0xC2 else 0x09) - (if (isSafe1) 0xC2 else 0x09)) * colorT).toInt()
                    val b = ((if (isSafe1) 0x06 else 0x09) + ((if (isSafe2) 0x06 else 0x09) - (if (isSafe1) 0x06 else 0x09)) * colorT).toInt()
                    Color4b(r, g, b, 200)
                }

                val relativePos = Vector3f(
                    (interpolatedPos.x - camera.pos.x).toFloat(),
                    (interpolatedPos.y - camera.pos.y).toFloat() + 0.1f,
                    (interpolatedPos.z - camera.pos.z).toFloat()
                )

                result.add(relativePos to color)
            }
        }

        return result
    }

    private fun canReachSafeBlockFrom(): Boolean {
        val simulatedPlayer = SimulatedPlayer.fromClientPlayer(
            SimulatedPlayer.SimulatedPlayerInput.fromClientPlayer(DirectionalInput(player.input)).apply {
                this.sprinting = true
                this.jumping = true
            }
        )
        val cache = SimulatedPlayerCache(simulatedPlayer)

        for (tick in 0 until voidEvasionFrequency) {
            simulatedPlayer.tick()
            val snapshot = cache.getSnapshotAt(tick)
            val currentPos = snapshot.pos
            val blockPos = BlockPos(currentPos.x.toInt(), currentPos.y.toInt(), currentPos.z.toInt())
            val belowPos = blockPos.down()
            val belowState = world.getBlockState(belowPos)
            val isSafeLanding = !belowState.isAir && belowState.block !in unsafeBlocks

            val playerBox = player.boundingBox.offset(currentPos.subtract(player.pos))

            val safeBlockCount = countAdjacentSafeBlocks(blockPos)
            val isNearSafeBlock = isSafeLanding && safeBlockCount >= adjacentSafeBlocks
            if (isNearSafeBlock && !world.getBlockCollisions(player, playerBox).any()) {
                return true
            }
        }
        return false
    }

    private fun checkVoidFall() {
        isLikelyFallingIntoVoid = isPredictingFall() && !canReachSafeBlock() && !isBlockUnder(2.0)
    }

    private fun isPlayerSafe(): Boolean {
        if (player.isSneaking && isBlockUnder(1.0)) return true
        if (!isInVoid(player.pos) || canReachSafeBlock()) return true
        if (player.velocity.y > 0.2 && canReachSafeBlock()) return true
        return false
    }

    private fun isPredictingFall(): Boolean {
        if (player.isOnGround) return false
        val velY = player.velocity.y
        if (velY < -0.2 && !canReachSafeBlock()) return true
        if (player.fallDistance > 2f && velY <= 0.0 && !canReachSafeBlock()) return true

        return simulatePlayerTrajectory { pos, _, _ ->
            pos.y <= voidThreshold.toDouble() && !canReachSafeBlock()
        }
    }

    private fun countAdjacentSafeBlocks(center: BlockPos): Int {
        var count = 0
        for (dx in -1..1) for (dz in -1..1) {
            val nearby = BlockPos(center.x + dx, center.y - 1, center.z + dz)
            val state = world.getBlockState(nearby)
            if (!state.isAir && state.block !in unsafeBlocks) count++
        }
        return count
    }

    private fun canReachSafeBlock(): Boolean {
        return simulatePlayerTrajectory { pos, playerBox, blockPos ->
            val belowPos = blockPos.down()
            val belowState = world.getBlockState(belowPos)
            val isSafeLanding = !belowState.isAir && belowState.block !in unsafeBlocks

            val hasCollision = world.getBlockCollisions(player, playerBox).iterator().hasNext()
            val safeBlockCount = countAdjacentSafeBlocks(blockPos)

            hasCollision && isSafeLanding && safeBlockCount >= adjacentSafeBlocks
        }
    }


    private fun isInVoid(pos: Vec3d): Boolean {
        val boundingBox = player.boundingBox
            .offset(pos.subtract(player.pos))
            .withMinY(voidThreshold.toDouble())
        val collisions = world.getBlockCollisions(player, boundingBox)
        return collisions.none() || collisions.all { shape -> shape == VoxelShapes.empty() }
    }

    private fun isBlockUnder(height: Double = 5.0): Boolean {
        val world = mc.world ?: return false
        val player = mc.player ?: return false
        var offset = 0.0
        while (offset < height) {
            val motionX = player.velocity.x
            val motionZ = player.velocity.z
            val playerBox = player.boundingBox.offset(motionX * offset, -offset, motionZ * offset)
            if (world.getBlockCollisions(player, playerBox).iterator().hasNext()) {
                return true
            }
            offset += 0.5
        }
        return false
    }

    private fun findPearl() {
        if (allowClutchWithStuck){
            ModuleAutoStuck.shouldEnableStuck = true
        }
        val startTime = System.currentTimeMillis()
        val pearlSlot = Slots.OffhandWithHotbar.findSlot(Items.ENDER_PEARL)?.hotbarSlotForServer
        if (pearlSlot == null) {
            state = State.IDLE
            return
        }
        predictedThrowPosition = predictFuturePosition(avgCalcTime.toDouble())
        resetAnnealing()

        CompletableFuture.supplyAsync({
            calculateSolutionBackground()
            backgroundDone.set(true)
        }, Util.getMainWorkerExecutor()).exceptionally { e ->
            notification(
                "AutoClutch",
                "Calculation failed: ${e.message}",
                NotificationEvent.Severity.ERROR
            )
            state = State.IDLE
            null
        }
        state = State.CALCULATING
        val endTime = System.currentTimeMillis()
        avgCalcTime = (avgCalcTime * 0.9 + (endTime - startTime) / 1000.0 * 0.1).toFloat()
    }

    private fun calculateSolutionBackground() {
        resetAnnealing()
        while (iterations < maxIterations && temperature >= minTemperature) {
            val newSolution = Rotation(
                (currentSolution.yaw + getRandomInRange(-temperature * 18f, temperature * 18f)),
                (currentSolution.pitch + getRandomInRange(-temperature * 9f, temperature * 9f)).coerceIn(pitchRange)
            )
            val newEnergy = assessRotation(newSolution)
            val delta = newEnergy - currentEnergy
            if (delta < 0 || Random.nextDouble() < exp(-delta / temperature)) {
                currentSolution = newSolution
                currentEnergy = newEnergy
                if (currentEnergy < bestEnergy) {
                    bestSolution = currentSolution
                    bestEnergy = currentEnergy
                    noImprovementCount = 0
                } else {
                    noImprovementCount++
                    if (noImprovementCount > stagnationLimit && bestEnergy > 5000.0) {
                        state = State.IDLE
                        return
                    }
                }
            }
            temperature *= coolingFactor
            iterations++
        }
    }

    private fun predictFuturePosition(deltaTimeSeconds: Double): Vec3d {
        val deltaTicks = (deltaTimeSeconds * 20).toInt()
        var futurePos = player.pos
        var futureVelocity = player.velocity
        repeat(deltaTicks) {
            futureVelocity = futureVelocity.add(0.0, -0.08, 0.0).multiply(0.99, 0.98, 0.99)
            futurePos = futurePos.add(futureVelocity)
        }
        return futurePos
    }

    private fun simulatePearlTrajectory(rotation: Rotation): Vec3d? {
        val predictedPos = predictedThrowPosition ?: player.pos
        val yawRad = Math.toRadians(rotation.yaw.toDouble())
        val pitchRad = Math.toRadians(rotation.pitch.toDouble())
        val velocity = 1.5
        var motion = Vec3d(
            -sin(yawRad) * cos(pitchRad) * velocity,
            -sin(pitchRad) * velocity,
            cos(yawRad) * cos(pitchRad) * velocity
        )

        val pearlEntity = EnderPearlEntity(mc.world!!, player, player.getStackInHand(Hand.MAIN_HAND))
        var pos = Vec3d(predictedPos.x, predictedPos.y + player.standingEyeHeight, predictedPos.z)

        repeat(40) {
            val newPos = pos + motion
            val drag = 0.99
            motion = motion * drag
            motion = motion.add(0.0, -0.03, 0.0)

            // Block collision check
            val blockHitResult = mc.world!!.raycast(
                RaycastContext(
                    pos, newPos,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    pearlEntity
                )
            )

            val entityHitResult = ProjectileUtil.getEntityCollision(
                mc.world!!,
                pearlEntity,
                pos,
                newPos,
                Box(pos, newPos).expand(0.25)
            ) { entity ->
                entity.isAlive && !entity.isSpectator && entity.canHit() && entity != player && !pearlEntity.isConnectedThroughVehicle(
                    entity
                )
            }
            // Check block collisions with nearby full cubes
            val blockPos = newPos.toBlockPos()
            val directions = listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)
            var hit = false
            for (dir in directions) {
                val nearby = blockPos.offset(dir)
                val state = world.getBlockState(nearby)
                if (!state.isAir && state.isFullCube(world, nearby)) {
                    val collisionBox = state.getCollisionShape(world, nearby).boundingBox.offset(nearby)
                    if (collisionBox.contains(newPos)) {
                        hit = true
                        break
                    }
                }
            }

            if (blockHitResult != null && blockHitResult.type != HitResult.Type.MISS || hit) {
                return blockHitResult?.pos ?: newPos
            }

            if (entityHitResult != null && entityHitResult.type != HitResult.Type.MISS) {
                return entityHitResult.pos // Return entity hit position
            }

            pos = newPos
        }
        return pos
    }

    private fun resetAnnealing() {
        var bestInitialSolution = currentSolution
        var bestInitialEnergy = Double.MAX_VALUE
        repeat(5) {
            val initialSolution = Rotation(
                getRandomInRange(-180f, 180f),
                getRandomInRange(-90f, 90f)
            )
            val initialEnergy = assessRotation(initialSolution)
            if (initialEnergy < bestInitialEnergy) {
                bestInitialSolution = initialSolution
                bestInitialEnergy = initialEnergy
            }
        }
        currentSolution = bestInitialSolution
        currentEnergy = bestInitialEnergy
        bestSolution = currentSolution
        bestEnergy = currentEnergy
        temperature = initialTemperature
        iterations = 0
        noImprovementCount = 0
    }

    private fun calculateSolution() {
        if (allowClutchWithStuck){
            ModuleAutoStuck.shouldEnableStuck = true
        }
        val scaledTemperature = temperature * 100
        repeat((maxIterations * iterationSpeed).toInt()) {
            if (iterations >= maxIterations || temperature < minTemperature) {
                state = State.ROTATING
                return@repeat
            }
            val newSolution = Rotation(
                (currentSolution.yaw + getRandomInRange(-temperature * 18f, temperature * 18f)),
                (currentSolution.pitch + getRandomInRange(-temperature * 9f, temperature * 9f)).coerceIn(pitchRange)
            )
            val newEnergy = assessRotation(newSolution)
            val deltaEnergy = newEnergy - currentEnergy
            if (deltaEnergy < 0 || Random.Default.nextDouble() < exp(-deltaEnergy / temperature)) {
                currentSolution = newSolution
                currentEnergy = newEnergy
                if (currentEnergy < bestEnergy) {
                    bestSolution = currentSolution
                    bestEnergy = currentEnergy
                    noImprovementCount = 0
                } else {
                    noImprovementCount++
                    if (noImprovementCount > stagnationLimit) {
                        state = State.ROTATING
                        return@repeat
                    }
                }
            }
            temperature = (scaledTemperature * coolingFactor) / 100
            iterations++
        }
    }

    private fun assessRotation(rotation: Rotation): Double {
        if (!shouldCalculateTrajectory()) return Double.MAX_VALUE
        val pearlPos = simulatePearlTrajectory(rotation) ?: return Double.MAX_VALUE
        return assessPosition(pearlPos)
    }

    private fun assessPosition(pos: Vec3d): Double {
        if (!shouldCalculateTrajectory()) return Double.MAX_VALUE
        val blockPos = BlockPos(pos.x.toInt(), (pos.y - 0.5).toInt(), pos.z.toInt())
        val maxThrowDistance = 50.0
        val horizontalDistance = distanceSq2D(pos, triggerPosition ?: player.pos)
        if (horizontalDistance > maxThrowDistance * maxThrowDistance) {
            return Double.MAX_VALUE
        }
        return positionCache.computeIfAbsent(blockPos) { _ ->
            val belowPos = blockPos.down()
            val belowState = world.getBlockState(belowPos)
            val hasGround = !belowState.isAir && belowState.block !in unsafeBlocks

            var allPositionsSafe = true
            val offsetChecks = listOf(
                Vec3d(0.0, 0.0, 0.0),
                Vec3d(0.4, 0.0, 0.4),
                Vec3d(-0.4, 0.0, 0.4),
                Vec3d(0.4, 0.0, -0.4),
                Vec3d(-0.4, 0.0, -0.4)
            )
            for (offset in offsetChecks) {
                val testPos = pos.add(offset)
                val playerBox = Box(
                    testPos.x - 0.3, testPos.y, testPos.z - 0.3,
                    testPos.x + 0.3, testPos.y + player.height, testPos.z + 0.3
                )

                val hasSpace = !world.getBlockCollisions(player, playerBox).any()
                val entityCollisions = world.getEntitiesByClass(Entity::class.java, playerBox) { e -> e != player && e.isCollidable }.isNotEmpty()
                if (!hasSpace || entityCollisions) {
                    allPositionsSafe = false
                    break
                }

                val belowTestPos = BlockPos(testPos.x.toInt(), (testPos.y - 0.5).toInt(), testPos.z.toInt()).down()
                val belowTestState = world.getBlockState(belowTestPos)
                if (belowTestState.isAir || belowTestState.block in unsafeBlocks || (belowTestState.block == Blocks.CAMPFIRE && belowTestState.get(Properties.LIT))) {
                    allPositionsSafe = false
                    break
                }

                if (checkHeadSpace) {
                    val abovePos = belowTestPos.up(2)
                    val aboveState = world.getBlockState(abovePos)
                    if (!aboveState.isAir || world.getBlockCollisions(player, playerBox.offset(0.0, 2.0, 0.0)).any()) {
                        allPositionsSafe = false
                        break
                    }
                }
            }

            var safeBlockCount = 0
            val directions = listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)
            for (dir in directions) {
                val nearby = blockPos.offset(dir)
                val state = world.getBlockState(nearby)
                if (!state.isAir && state.block !in unsafeBlocks && state.isFullCube(world, nearby)) {
                    safeBlockCount++
                }
            }

            // Find the nearest safe block to penalize distance appropriately
            val nearestSafeDistance = findNearestSafeBlock(pos)?.let { distanceSq2D(pos, it) } ?: 10000.0

            val xFrac = pos.x - pos.x.toInt()
            val zFrac = pos.z - pos.z.toInt()
            val edgePenalty = if (abs(xFrac - 0.5) < 0.3 || abs(zFrac - 0.5) < 0.3) 500.0 else 0.0

            when {
                !hasGround -> 5000.0 + nearestSafeDistance // Reduced penalty, prioritize nearest safe block
                !allPositionsSafe -> 3000.0 + nearestSafeDistance // Reduced penalty
                safeBlockCount < adjacentSafeBlocks -> 1000.0 + nearestSafeDistance - safeBlockCount * 50.0
                else -> nearestSafeDistance + edgePenalty - safeBlockCount * 100.0
            }
        }
    }

    // Helper function to find the nearest safe block
    private fun findNearestSafeBlock(pos: Vec3d): Vec3d? {
        val searchRadius = 50
        val blockPos = pos.toBlockPos()
        for (y in -10..10) {
            for (x in -searchRadius..searchRadius) {
                for (z in -searchRadius..searchRadius) {
                    val checkPos = blockPos.add(x, y, z)
                    val state = world.getBlockState(checkPos)
                    if (!state.isAir && state.block !in unsafeBlocks && state.isFullCube(world, checkPos)) {
                        val abovePos = checkPos.up()
                        if (!checkHeadSpace || world.getBlockState(abovePos).isAir) {
                            return checkPos.toVec3d().add(0.5, 1.0, 0.5)
                        }
                    }
                }
            }
        }
        return null
    }

    private fun rotateToSolution() {
        if (allowClutchWithStuck) {
            ModuleAutoStuck.shouldEnableStuck = true
            mc.options.forwardKey.isPressed = false
        }

        if (bestSolution == null || bestEnergy >= 10000.0) {
            notification(
                "AutoClutch",
                "Trying to evade Void...",
                NotificationEvent.Severity.INFO
            )
            state = State.IDLE
            resetAllVariables()
            return
        }

        if (player.isSneaking || isPlayerSafe()) {
            state = State.IDLE
            resetAllVariables()
            return
        }


        if (!mc.options.pickItemKey.isPressed) {
            bestSolution?.let { sol ->

                if (allowClutchWithStuck) {
                    sendPacketSilently(
                        PlayerMoveC2SPacket.LookAndOnGround(
                            sol.yaw,
                            sol.pitch,
                            mc.player?.isOnGround ?: true,
                            mc.player?.horizontalCollision ?: false
                        )
                    )
                }else{
                    RotationManager.setRotationTarget(
                        rotationConfig.toRotationTarget(sol),
                        priority = Priority.IMPORTANT_FOR_USAGE_1,
                        provider = this
                    )
                }
                // Transition to THROWING when server rotation is confirmed
                if (RotationManager.serverRotation.angleTo(sol) <= aimPrecision) {
                    state = State.THROWING
                }
            } ?: run {
                state = State.IDLE
            }
        }
    }

    private fun throwPearl() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPearlThrowTime < cooldownTicks * 50L) {
            state = State.IDLE
            return
        }
        if (!isLikelyFallingIntoVoid && isPlayerSafe()) {
            state = State.IDLE
            return
        }
        if (isPearlInFlight) {
            return
        }
        Slots.Hotbar.findSlot(Items.ENDER_PEARL)?.let {
            useHotbarSlotOrOffhand(it, 0, bestSolution?.yaw ?: 0f, bestSolution?.pitch ?: 0f)
            lastPearlThrowTime = currentTime
            isPearlInFlight = true
            pearlThrowTick = player.age.toLong()
            scheduleSafetyCheck()
        }
        ModuleAutoStuck.shouldEnableStuck = false
        state = State.PAUSED
    }

    private fun scheduleSafetyCheck() {
        safetyCheckCounter = 30
        safetyCheckActive = true
    }

    private fun resetAllVariables() {
        state = if (manualPearlThrown) State.PAUSED else State.IDLE
        bestSolution = null
        bestEnergy = Double.MAX_VALUE
        currentSolution = Rotation(0f, 0f)
        currentEnergy = Double.MAX_VALUE
        temperature = initialTemperature
        iterations = 0
        triggerPosition = null
        pearlSlot = null
        isLikelyFallingIntoVoid = false
        safetyCheckCounter = 0
        safetyCheckActive = false
        predictedThrowPosition = null
        clearTrajectoryAndCache()
    }

    private fun simulatePlayerTrajectory(checkCondition: (Vec3d, Box, BlockPos) -> Boolean): Boolean {
        val simulatedPlayer = SimulatedPlayer.fromClientPlayer(
            SimulatedPlayer.SimulatedPlayerInput.fromClientPlayer(DirectionalInput(player.input)).apply {
                this.sprinting = true
                this.jumping = true
            }
        )
        val cache = SimulatedPlayerCache(simulatedPlayer)

        for (tick in 0 until simulateTime) {
            simulatedPlayer.tick()
            val snapshot = cache.getSnapshotAt(tick)
            val pos = snapshot.pos
            val playerBox = player.boundingBox.offset(pos.subtract(player.pos))
            val blockPos = BlockPos(pos.x.toInt(), (pos.y - 0.5).toInt(), pos.z.toInt())

            if (checkCondition(pos, playerBox, blockPos)) {
                return true
            }
        }
        return false
    }

    private fun distanceSq2D(a: Vec3d, b: Vec3d): Double {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return dx * dx + dz * dz
    }

    private fun getRandomInRange(min: Float, max: Float): Float {
        return (Random.nextFloat() * (max - min) + min)
    }

    override fun enable() {
        super.enable()
        resetAllVariables()
    }

    override fun disable() {
        resetAllVariables()
        lastPlayerPosition = null
        manualPearlThrown = false
        positionCache.clear()
    }

}
