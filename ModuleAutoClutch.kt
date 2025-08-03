package net.ccbluex.liquidbounce.features.module.modules.player.autoclutch

import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PlayerInteractedItemEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.movement.ModuleAirJump
import net.ccbluex.liquidbounce.features.module.modules.movement.ModuleFreeze
import net.ccbluex.liquidbounce.features.module.modules.movement.elytrafly.ModuleElytraFly
import net.ccbluex.liquidbounce.features.module.modules.movement.fly.ModuleFly
import net.ccbluex.liquidbounce.features.module.modules.movement.longjump.ModuleLongJump
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.ModuleScaffold
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsConfigurable
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.combat.CombatManager
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.useHotbarSlotOrOffhand
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.block.Blocks
import net.minecraft.entity.Entity
import net.minecraft.entity.projectile.ProjectileUtil
import net.minecraft.entity.projectile.thrown.EnderPearlEntity
import net.minecraft.item.Items
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.util.shape.VoxelShapes
import net.minecraft.world.RaycastContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

@Suppress("TooManyFunctions","unused")
object ModuleAutoClutch : ClientModule("AutoClutch", Category.PLAYER) {

    enum class State { IDLE, FINDING_PEARL, CALCULATING, ROTATING, THROWING, PAUSED }
    enum class Algorithm(override val choiceName: String) : NamedChoice {
        SimulatedAnnealing("SimulatedAnnealing")
    }
    var state = State.IDLE
    private const val SAFE_TICKS_THRESHOLD = 10
    private const val PEARL_LANDING_TIME = 30

    private val mode by enumChoice("Algorithm", Algorithm.SimulatedAnnealing)
    private val maxIterations by int("MaxIterations", 5000, 50..10000)
    private val iterationsRate by float("IterationsRate", 5f, 1f..5f)
    private val noImprovementThreshold by int("NoImprovementThreshold", 1337, 1000..10000)
    private val initialTemperature by float("InitialTemp", 15f, 5f..30f)
    private val minTemperature by float("MinTemp", 0.01f, 0.01f..0.1f)
    private val coolingRate by float("CoolingRate", 0.97f, 0.95f..0.99f)
    private val aimThreshold by float("AimThreshold", 0.5f, 0.1f..1f)
    private val aimPitch by floatRange("PitchLimit", -90f..0f, -90f..45f)
    private var averageCalcTime by float("AverageCalcTime", 0.1f, 0.01f..0.15f)
    private val cooldownTicks by int("Cooldown", 0, 0..20, "ticks")
    private val safetyCheckTicks by int("SafetyCheck", 10, 5..20, "ticks")
    private val voidThreshold by int("VoidLevel", 0, -256..0)
    private val onlyDuringCombat by boolean("OnlyDuringCombat", false)
    private val rotationConfig = tree(RotationsConfigurable(this))

    private var predictedThrowPosition: Vec3d? = null
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

    init {
        enableLock()
    }

    private val tickHandler = handler<GameTickEvent> {
        if (onlyDuringCombat && !CombatManager.isInCombat) return@handler
        handleStateMachine()
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

    private fun handleStateMachine() {
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

    private fun checkActivationConditions() {
        if (System.currentTimeMillis() - lastPearlThrowTime < 1000L) return

        if (ModuleScaffold.running || ModuleLongJump.running || ModuleFly.running ||
            ModuleElytraFly.running || ModuleAirJump.running || ModuleFreeze.running) return

        if (player.isSneaking) {
            resetAllVariables()
            return
        }

        if (isLikelyFallingIntoVoid || (!isBlockUnder() && !canReachSafeBlock())) {
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

    private fun checkVoidFall() {
        isLikelyFallingIntoVoid = isPredictingFall() && !canReachSafeBlock()
    }

    private fun isPlayerSafe(): Boolean {
        if (player.isSneaking && isBlockUnder(1.0)) return true
        if (!isInVoid(player.pos)) return true

        for (tick in 0 until safetyCheckTicks) {
            val futurePos = player.pos.add(0.0, -tick.toDouble(), 0.0)
            if (!isInVoid(futurePos)) return true
        }
        return false
    }

    private fun isPredictingFall(): Boolean {
        return simulatePlayerTrajectory(SAFE_TICKS_THRESHOLD) { pos, _, _ ->
            pos.y <= voidThreshold.toDouble()
        }
    }

    private fun canReachSafeBlock(): Boolean {
        return simulatePlayerTrajectory(20) { _, playerBox, blockPos ->
            val blockState = world.getBlockState(blockPos)
            val collisions = world.getBlockCollisions(player, playerBox)
            collisions.iterator().hasNext() &&
                !blockState.isAir &&
                blockState.block != Blocks.WATER &&
                blockState.block != Blocks.LAVA
        }
    }

    private fun isInVoid(pos: Vec3d): Boolean {
        val boundingBox = player.boundingBox
            .offset(pos.subtract(player.pos))
            .withMinY(voidThreshold.toDouble())
        val collisions = world.getBlockCollisions(player, boundingBox)
        return collisions.none() || collisions.all { shape -> shape == VoxelShapes.empty() }
    }

    private fun isBlockUnder(height: Double = 30.0): Boolean {
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
        val startTime = System.currentTimeMillis()
        val pearlSlot = Slots.OffhandWithHotbar.findSlot(Items.ENDER_PEARL)?.hotbarSlotForServer
        if (pearlSlot == null) {
            state = State.IDLE
            return
        }
        predictedThrowPosition = predictFuturePosition(averageCalcTime.toDouble())
        resetAnnealing()
        state = State.CALCULATING
        val endTime = System.currentTimeMillis()
        averageCalcTime = (averageCalcTime * 0.9 + (endTime - startTime) / 1000.0 * 0.1).toFloat()
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

    private fun simulatePearlTrajectory(rotation: Rotation): Vec3d {
        val predictedPos = player.pos
        val yawRad = Math.toRadians(rotation.yaw.toDouble())
        val pitchRad = Math.toRadians(rotation.pitch.toDouble())
        val velocity = 1.5
        var motion = Vec3d(
            -sin(yawRad) * cos(pitchRad) * velocity + player.velocity.x,
            -sin(pitchRad) * velocity + player.velocity.y,
            cos(yawRad) * cos(pitchRad) * velocity + player.velocity.z
        )
        val pearlEntity = EnderPearlEntity(mc.world!!, player, player.getStackInHand(Hand.MAIN_HAND))
        var pos = Vec3d(predictedPos.x, predictedPos.y + player.standingEyeHeight, predictedPos.z)

        repeat(40) {
            val nextPos = pos.add(motion)
            val hitResult: HitResult = mc.world!!.raycast(
                RaycastContext(
                    pos, nextPos,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    pearlEntity
                )
            )
            if (hitResult.type == HitResult.Type.BLOCK) {
                return (hitResult as BlockHitResult).pos
            }
            val box = Box(
                pos.x - 0.1, pos.y - 0.1, pos.z - 0.1,
                pos.x + 0.1, pos.y + 0.1, pos.z + 0.1
            ).stretch(motion)
            val entityHit = ProjectileUtil.getEntityCollision(
                mc.world!!, pearlEntity, pos, nextPos, box
            ) { entity -> entity.isAttackable && entity != player }
            if (entityHit != null && entityHit.entity != player) {
                return Vec3d(pos.x, voidThreshold.toDouble() - 10.0, pos.z)
            }
            pos = nextPos
            motion = motion.multiply(0.99).subtract(0.0, 0.03, 0.0)
        }
        return pos
    }

    private fun resetAnnealing() {
        var bestInitialSolution = currentSolution
        var bestInitialEnergy = Double.MAX_VALUE
        repeat(5) {
            val initialSolution = Rotation(
                getRandomInRange(-180f, 180f).toFloat(),
                getRandomInRange(-90f, 90f).toFloat()
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
        val scaledTemperature = temperature * 100
        repeat((maxIterations * iterationsRate).toInt()) {
            if (iterations >= maxIterations || temperature < minTemperature) {
                state = State.ROTATING
                return@repeat
            }
            val newSolution = Rotation(
                (currentSolution.yaw + getRandomInRange(-temperature * 18f, temperature * 18f)),
                (currentSolution.pitch + getRandomInRange(-temperature * 9f, temperature * 9f)).coerceIn(aimPitch)
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
                    if (noImprovementCount > noImprovementThreshold) {
                        state = State.ROTATING
                        return@repeat
                    }
                }
            }
            temperature = (scaledTemperature * coolingRate) / 100
            iterations++
        }
    }

    private fun assessRotation(rotation: Rotation): Double {
        val pearlPos = simulatePearlTrajectory(rotation)
        return assessPosition(pearlPos)
    }

    private fun assessPosition(pos: Vec3d): Double {
        val groundPos = BlockPos(pos.x.toInt(), (pos.y - 0.5).toInt(), pos.z.toInt())
        val groundState = world.getBlockState(groundPos)
        val hasGround = !groundState.isAir && groundState.block != Blocks.WATER && groundState.block != Blocks.LAVA
        var allPositionsSafe = true
        val offsetChecks = listOf(
            Vec3d(0.0, 0.0, 0.0),
            Vec3d(0.5, 0.0, 0.0),
            Vec3d(-0.5, 0.0, 0.0),
            Vec3d(0.0, 0.0, 0.5),
            Vec3d(0.0, 0.0, -0.5)
        )

        for (offset in offsetChecks) {
            val testPos = pos.add(offset)
            val playerBox = Box(
                testPos.x - 0.3, testPos.y, testPos.z - 0.3,
                testPos.x + 0.3, testPos.y + player.height, testPos.z + 0.3
            )
            val blockCollisions = world.getBlockCollisions(player, playerBox).iterator()
            val hasSpace = !blockCollisions.hasNext()
            val entityCollisions = world.getEntitiesByClass(
                Entity::class.java, playerBox
            ) { entity -> entity != player && entity.isCollidable }.isNotEmpty()
            if (!hasSpace || entityCollisions) {
                allPositionsSafe = false
                break
            }
            val testGroundPos = BlockPos(testPos.x.toInt(), (testPos.y - 0.5).toInt(), testPos.z.toInt())
            val testGroundState = world.getBlockState(testGroundPos)
            if (testGroundState.isAir || testGroundState.block == Blocks.WATER || testGroundState.block == Blocks.LAVA) {
                allPositionsSafe = false
                break
            }
        }

        var safeBlockCount = 0
        for (dx in -1..1) {
            for (dz in -1..1) {
                val nearbyPos = BlockPos(groundPos.x + dx, groundPos.y, groundPos.z + dz)
                val nearbyState = world.getBlockState(nearbyPos)
                if (!nearbyState.isAir && nearbyState.block != Blocks.WATER && nearbyState.block != Blocks.LAVA) {
                    safeBlockCount++
                }
            }
        }

        val xFraction = pos.x - pos.x.toInt()
        val zFraction = pos.z - pos.z.toInt()
        val edgePenalty = if (abs(xFraction - 0.5) < 0.3 || abs(zFraction - 0.5) < 0.3) 2000.0 else 0.0
        val horizontalDistance = distanceSq2D(pos, triggerPosition ?: player.pos)

        return when {
            !hasGround -> 10000.0
            !allPositionsSafe -> 8000.0 + horizontalDistance
            safeBlockCount < 6 -> 6000.0 + horizontalDistance - safeBlockCount * 100.0
            else -> horizontalDistance + edgePenalty - safeBlockCount * 200.0
        }
    }

    private fun rotateToSolution() {
        if (player.isSneaking || isPlayerSafe()) {
            state = State.IDLE
            resetAllVariables()
            return
        }

        predictedThrowPosition = player.pos
        resetAnnealing()
        calculateSolution()

        bestSolution?.let {
            RotationManager.setRotationTarget(
                rotationConfig.toRotationTarget(it),
                priority = Priority.IMPORTANT_FOR_USAGE_1,
                provider = this
            )
            if (RotationManager.serverRotation.angleTo(it) <= aimThreshold) {
                state = State.THROWING
            }
        } ?: run {
            state = State.IDLE
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
        Slots.Hotbar.findSlot(Items.ENDER_PEARL)?.let {
            useHotbarSlotOrOffhand(it, 0, bestSolution?.yaw ?: 0f, bestSolution?.pitch ?: 0f)
            lastPearlThrowTime = currentTime
            scheduleSafetyCheck()
        }
        state = State.IDLE
    }

    private fun scheduleSafetyCheck() {
        safetyCheckCounter = PEARL_LANDING_TIME
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
        manualPearlThrown = false
        safetyCheckCounter = 0
        safetyCheckActive = false
        predictedThrowPosition = null
    }

    private fun simulatePlayerTrajectory(ticks: Int, checkCondition: (Vec3d, Box, BlockPos) -> Boolean): Boolean {
        val player = mc.player ?: return false
        var motionX = player.velocity.x
        var motionY = player.velocity.y
        var motionZ = player.velocity.z
        var posX = player.pos.x
        var posY = player.pos.y
        var posZ = player.pos.z

        repeat(ticks) {
            motionY -= 0.08
            motionY *= 0.98
            motionX *= 0.91
            motionZ *= 0.91
            posX += motionX
            posY += motionY
            posZ += motionZ
            val playerBox = player.boundingBox.offset(posX - player.pos.x, posY - player.pos.y, posZ - player.pos.z)
            val blockPos = BlockPos(posX.toInt(), (posY - 0.5).toInt(), posZ.toInt())
            if (checkCondition(Vec3d(posX, posY, posZ), playerBox, blockPos)) {
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

    fun getRandomInRange(min: Float, max: Float): Int {
        return (Math.random() * (max - min).toDouble() + min.toDouble()).toInt()
    }

    override fun disable() {
        resetAllVariables()
        lastPlayerPosition = null
    }
}
