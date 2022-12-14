package dev.emortal.doors.game

import dev.emortal.doors.Main.Companion.doorsConfig
import dev.emortal.doors.damage.DoorsEntity
import dev.emortal.doors.damage.EyesDamage
import dev.emortal.doors.damage.HideDamage
import dev.emortal.doors.damage.RushDamage
import dev.emortal.doors.endingSchem
import dev.emortal.doors.pathfinding.*
import dev.emortal.doors.raycast.RaycastUtil
import dev.emortal.doors.schematics
import dev.emortal.doors.seekSchematics
import dev.emortal.doors.startingSchem
import dev.emortal.doors.util.MultilineHologramAEC
import dev.emortal.doors.util.lerp
import dev.emortal.immortal.game.Game
import dev.emortal.immortal.util.MinestomRunnable
import dev.emortal.immortal.util.cancel
import dev.emortal.immortal.util.expInterp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.hollowcube.util.schem.Rotation
import net.hollowcube.util.schem.Schematic
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.sound.SoundStop
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import net.minestom.server.attribute.Attribute
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.other.*
import net.minestom.server.event.EventNode
import net.minestom.server.event.entity.EntityDamageEvent
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.item.ItemDropEvent
import net.minestom.server.event.player.*
import net.minestom.server.event.trait.InstanceEvent
import net.minestom.server.instance.Instance
import net.minestom.server.instance.batch.AbsoluteBlockBatch
import net.minestom.server.instance.block.Block
import net.minestom.server.item.Enchantment
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.play.BlockActionPacket
import net.minestom.server.network.packet.server.play.EntitySoundEffectPacket
import net.minestom.server.network.packet.server.play.TeamsPacket
import net.minestom.server.network.packet.server.play.TeamsPacket.NameTagVisibility
import net.minestom.server.potion.Potion
import net.minestom.server.potion.PotionEffect
import net.minestom.server.sound.SoundEvent
import net.minestom.server.tag.Tag
import net.minestom.server.utils.Direction
import net.minestom.server.utils.NamespaceID
import net.minestom.server.utils.time.TimeUnit
import world.cepi.kstom.Manager
import world.cepi.kstom.adventure.noItalic
import world.cepi.kstom.event.listenOnly
import world.cepi.kstom.util.asPos
import world.cepi.kstom.util.asVec
import world.cepi.kstom.util.sendBreakBlockEffect
import world.cepi.particle.Particle
import world.cepi.particle.ParticleType
import world.cepi.particle.data.OffsetAndSpeed
import world.cepi.particle.showParticle
import java.time.Duration
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.floor

class DoorsGame : Game() {

    override val allowsSpectators = true
    override val countdownSeconds = 0
    override val maxPlayers = 4
    override val minPlayers = 1
    override val showScoreboard = false
    override val canJoinDuringGame = false
    override val showsJoinLeaveMessages = true

    companion object {
        const val rushChanceIncrease = 0.10
        const val eyesChanceIncrease = 0.003

        const val seekSchemNumber = 3
        const val librarySchemNumber = 49
        const val endingSchemNumber = 49

        const val rushRange = 10.0
        const val maxLoadedRooms = 8

        val team = Manager.team.createBuilder("everyone")
            .teamColor(NamedTextColor.WHITE)
            .collisionRule(TeamsPacket.CollisionRule.NEVER)
            .nameTagVisibility(NameTagVisibility.ALWAYS)
            .updateTeamPacket()
            .build()

        val donatorTeam = Manager.team.createBuilder("donators")
            .teamColor(NamedTextColor.LIGHT_PURPLE)
            .collisionRule(TeamsPacket.CollisionRule.NEVER)
            .nameTagVisibility(NameTagVisibility.ALWAYS)
            .prefix(Component.text("Donator ", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
            .updateTeamPacket()
            .build()

        val teamHiddenName = Manager.team.createBuilder("everyoneH")
            .teamColor(NamedTextColor.WHITE)
            .collisionRule(TeamsPacket.CollisionRule.NEVER)
            .nameTagVisibility(NameTagVisibility.NEVER)
            .updateTeamPacket()
            .build()

        val donatorTeamHiddenName = Manager.team.createBuilder("donatorsH")
            .teamColor(NamedTextColor.LIGHT_PURPLE)
            .collisionRule(TeamsPacket.CollisionRule.NEVER)
            .nameTagVisibility(NameTagVisibility.NEVER)
            .prefix(Component.text("Donator ", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
            .updateTeamPacket()
            .build()

        val hidingTag = Tag.Boolean("hiding")

        fun applyDoor(batch: AbsoluteBlockBatch, doorSchem: Schematic, doorPos: Point, rotation: Rotation) {
            doorSchem.apply(rotation) { pos, block ->
                if (block.compare(Block.DARK_OAK_DOOR)) {
                    batch.setBlock(pos.add(doorPos), Block.IRON_DOOR.withProperties(block.properties()))
                } else {
                    batch.setBlock(pos.add(doorPos), block)
                }
            }
        }

        val spawnPosition = Pos(0.5, 0.0, 0.5, 180f, 0f)
    }

    override fun getSpawnPosition(player: Player, spectator: Boolean): Pos = spawnPosition

    // Mutable for seek, door range increases to prevent ping issues
    var doorRange = 3.6

    val roomNum = AtomicInteger(-1)

    // entity chances
    // 0.0 - 1.0
    var rushChance = 0.0
    var eyesChance = 0.0
    var haltChance = 0.0
    var screechChance = 0.0
    var ambushChance = 0.0

    val doorPositions = CopyOnWriteArrayList<Point>()
    val rooms = CopyOnWriteArrayList<Room>()
    val playerChestMap = ConcurrentHashMap<UUID, Point>()

    var activeDoorRotation: Rotation = Rotation.NONE
    var activeDoorPosition: Point = Vec(8.0, 0.0, -19.0)

    private lateinit var rushPathfinding: RushPathfinding

    private val generatingRoom = AtomicBoolean(false)

    private val seekSequence = AtomicBoolean(false)

    // Stops generating new rooms at doors and instead simply opens them.
    // This is for the rooms 49, 50, 51; 99 and 100; and the seek chases
    private val stopGenerating = AtomicBoolean(false)
    private val stopGeneratingDoors = CopyOnWriteArraySet<Point>()

    private var doorOpenSecondsLeft = AtomicInteger(30)
    private var doorTimerHologram = MultilineHologramAEC(mutableListOf(Component.text(doorOpenSecondsLeft.get(), NamedTextColor.GOLD)))
    private var doorTask: MinestomRunnable? = null

    val readyFuture = CompletableFuture<Void>()


    override fun gameCreated() {
        val lobbyRoom = Room(this, instance!!, Pos(0.5, 0.0, 0.5, 180f, 0f), Rotation.NONE)

        lobbyRoom.applyRoom(listOf(startingSchem))?.thenRun {
            readyFuture.complete(null)
        }
        lobbyRoom.keyRoom = true

        val itemFrameEntity = Entity(EntityType.GLOW_ITEM_FRAME)
        val meta = itemFrameEntity.entityMeta as GlowItemFrameMeta
        itemFrameEntity.setNoGravity(true)
        itemFrameEntity.isInvisible = true
        itemFrameEntity.setCustomSynchronizationCooldown(Duration.ofDays(1))
        meta.orientation = ItemFrameMeta.Orientation.SOUTH
        meta.item = ItemStack.builder(Material.TRIPWIRE_HOOK)
            .displayName(Component.text("Room Key 1", NamedTextColor.GOLD).noItalic())
            .meta {
                it.canPlaceOn(Block.IRON_DOOR)
            }
            .build()
        itemFrameEntity.setInstance(instance!!, Pos(-2.0, 2.0, -18.0))

        lobbyRoom.entityIds.add(itemFrameEntity.entityId)

        rooms.add(lobbyRoom)
    }

    override fun gameStarted() {
        rushPathfinding = RushPathfinding(instance!!)

        doorTimerHologram.setInstance(Pos(0.5, 0.5, -0.8), instance!!)

        doorTask = object : MinestomRunnable(repeat = Duration.ofSeconds(1), group = runnableGroup, iterations = 31) {
            override fun run() {
                val doorSecondsLeft = doorOpenSecondsLeft.get()

                doorTimerHologram.setLine(0, Component.text(doorSecondsLeft, NamedTextColor.GOLD))

                if (doorSecondsLeft == 5) {
                    instance!!.setBlock(1, 1, -1, Block.POLISHED_BLACKSTONE_BUTTON.withProperties(mapOf("powered" to "true", "facing" to "south")))
                }

                if (doorSecondsLeft <= 0) {
                    instance!!.setBlock(0, 0, -2, Block.IRON_DOOR.withProperties(mapOf("facing" to "north", "half" to "lower", "open" to "true")))
                    instance!!.setBlock(0, 1, -2, Block.IRON_DOOR.withProperties(mapOf("facing" to "north", "half" to "upper", "open" to "true")))

                    instance!!.playSound(Sound.sound(SoundEvent.BLOCK_IRON_DOOR_OPEN, Sound.Source.MASTER, 1f, 1f), Pos(0.5, 0.5, -2.0))

                    players.forEach {
                        it.food = 0
                        it.foodSaturation = 0f
                        it.addEffect(Potion(PotionEffect.JUMP_BOOST, 200.toByte(), Short.MAX_VALUE.toInt()))
                    }

                    doorTimerHologram.remove()
                    doorTask?.cancel()
                    doorTask = null
                }

                doorOpenSecondsLeft.decrementAndGet()
            }
        }

    }

    override fun gameEnded() {

    }

    override fun playerJoin(player: Player) {
        if (doorsConfig.donators.contains(player.username)) {
            player.team = donatorTeamHiddenName
        } else {
            player.team = teamHiddenName
        }

//        val msg = MiniMessage.miniMessage().deserialize("Press <light_purple><key:key.advancements><reset> to view your achievements.")
//        player.sendMessage(msg)
//        Achievements.create(player)

        if (doorTask != null) player.playSound(Sound.sound(Key.key("music.elevatorjam"), Sound.Source.MASTER, 0.6f, 1f), Sound.Emitter.self())

        player.stopSound(SoundStop.named(Key.key("music.dawnofthedoors.ending")))

        player.food = 0
        player.foodSaturation = 0f
        player.addEffect(Potion(PotionEffect.JUMP_BOOST, 200.toByte(), Short.MAX_VALUE.toInt()))

//        player.addEffect(Potion(PotionEffect.NIGHT_VISION, 1.toByte(), Short.MAX_VALUE.toInt()))

        // Ambiance
        player.scheduler().buildTask {
            player.playSound(Sound.sound(SoundEvent.AMBIENT_BASALT_DELTAS_LOOP, Sound.Source.MASTER, 0.6f, 1f), Sound.Emitter.self())
        }.repeat(Duration.ofMillis(43150)).schedule()

        player.scheduler().buildTask {
            player.playSound(Sound.sound(Key.key("currency.knobs.increase"), Sound.Source.MASTER, 1f, 1f), Sound.Emitter.self())
            refreshCoinCounts(player, 100, 20)
        }.delay(Duration.ofMillis(1300)).schedule()

        player.scheduler().buildTask {
            val sounds = listOf(
                SoundEvent.AMBIENT_CAVE,
                SoundEvent.AMBIENT_NETHER_WASTES_MOOD,
                SoundEvent.AMBIENT_BASALT_DELTAS_MOOD,
                SoundEvent.AMBIENT_BASALT_DELTAS_ADDITIONS,
                SoundEvent.AMBIENT_WARPED_FOREST_ADDITIONS
            )
            player.playSound(Sound.sound(sounds.random(), Sound.Source.MASTER, 0.4f, 1f), Sound.Emitter.self())
        }.delay(Duration.ofMinutes(2)).repeat(Duration.ofMinutes(2)).schedule()
    }

    override fun playerLeave(player: Player) {
//        Achievements.remove(player)
        bossBarMap.remove(player.uuid)
    }

    override fun registerEvents(eventNode: EventNode<InstanceEvent>) {
        eventNode.listenOnly<PlayerMoveEvent> {

            // TODO: Rework
//            val closestPlayerToDoor = players.minBy { it.position.distanceSquared(activeDoorPosition) }
//
//            if (closestPlayerToDoor.uuid == player.uuid) return@listenOnly
//
//            val distanceToPlayer = closestPlayerToDoor.getDistanceSquared(player)
//
//            player.getAttribute(Attribute.MOVEMENT_SPEED).baseValue = 0.1f + if (distanceToPlayer > 25*25) 0.2f else 0f

            if (player.hasTag(hidingTag)) {
                isCancelled = true
            }
        }

        eventNode.listenOnly<PlayerChatEvent> {
//            if (message == "cheats") {
//                player.gameMode = GameMode.CREATIVE
//                player.food = 20
//            }

            if (message == "eyessss") {
                spawnEyes(instance)
                isCancelled = true
            }

            if (message == "spec") {
                player.gameMode = GameMode.SPECTATOR
            }

            if (message == "unspec") {
                player.gameMode = GameMode.ADVENTURE
            }

            if (message == "rushhhh") {
                spawnRush(instance)
                isCancelled = true
            }
        }

        eventNode.listenOnly<PlayerEntityInteractEvent> {
            if (target.entityType == EntityType.PAINTING) {
                val motive = (target.entityMeta as PaintingMeta).motive
                player.sendActionBar(Component.text("That painting is called \"${motive.name.lowercase()}\""))
                player.playSound(Sound.sound(SoundEvent.ENTITY_ITEM_PICKUP, Sound.Source.MASTER, 1f, 1f), Sound.Emitter.self())
            }

            if (target.entityType == EntityType.GLOW_ITEM_FRAME) {
                // Do not give key twice
                if (player.inventory.itemStacks.any { it.material() == Material.TRIPWIRE_HOOK }) return@listenOnly

                val item = (target.entityMeta as GlowItemFrameMeta).item

                player.inventory.addItemStack(item)
                player.playSound(Sound.sound(SoundEvent.ITEM_ARMOR_EQUIP_LEATHER, Sound.Source.MASTER, 1f, 1.5f), Sound.Emitter.self())
            }
        }

        eventNode.listenOnly<PlayerBlockPlaceEvent> {
            if (player.gameMode != GameMode.ADVENTURE) {
                isCancelled = true
                return@listenOnly
            }

            if (block.compare(Block.TRIPWIRE_HOOK)) {
                isCancelled = true

                val blockPlacedOn = instance.getBlock(blockPosition.sub(blockFace.toDirection().offset()))
                if (blockPlacedOn.compare(Block.IRON_DOOR)) {
                    player.setItemInHand(hand, ItemStack.AIR)
                    player.playSound(Sound.sound(SoundEvent.ENTITY_ITEM_BREAK, Sound.Source.MASTER, 1f, 2f), Sound.Emitter.self())
                    generateNextRoom(instance)

                    // Remove the key for all other players
                    players.forEach { playerInv ->
                        playerInv.inventory.itemStacks.forEachIndexed { i, it ->
                            if (it.material() == Material.TRIPWIRE_HOOK) {
                                playerInv.inventory.setItemStack(i, ItemStack.AIR)
                            }
                        }
                    }
                } else {
                    player.sendActionBar(Component.text("Use the key on the door!", NamedTextColor.RED))
                }


            }
        }

        eventNode.listenOnly<PlayerBlockInteractEvent> {
            if (player.gameMode != GameMode.ADVENTURE) return@listenOnly

            if (block.compare(Block.BELL)) {
                instance.playSound(Sound.sound(SoundEvent.BLOCK_BELL_USE, Sound.Source.MASTER, 1f, 2f), blockPosition)
                instance.sendGroupedPacket(BlockActionPacket(blockPosition, 1, 2, block.stateId().toInt()))
            }

            else if (block.compare(Block.POLISHED_BLACKSTONE_BUTTON) && block.getProperty("powered") == "false") {
                instance.stopSound(SoundStop.named(Key.key("music.elevatorjam")))
                instance.playSound(Sound.sound(Key.key("music.elevatorjam.ending"), Sound.Source.MASTER, 0.5f, 1f), Sound.Emitter.self())

                instance.setBlock(blockPosition, block.withProperty("powered", "true"))

                doorOpenSecondsLeft.set(5)
                doorTimerHologram.setLine(0, Component.text(5, NamedTextColor.GOLD))
            }

            else if (block.compare(Block.CHEST)) {
                ChestLoot.openChest(this@DoorsGame, player, block, blockPosition)
            }

            else if (block.compare(Block.SPRUCE_DOOR)) {
                this.isCancelled = true
                this.isBlockingItemUse = true

                val closet = Closet.getFromDoor(block, blockPosition)
                closet.handleInteraction(this@DoorsGame, player, blockPosition, block, instance)
            }

//            else if (block.compare(Block.END_STONE_BRICK_WALL)) {
//                val lastRoom = rooms.last()
//
//                val towardsScreenOff = lastRoom.rotation.rotate(Rotation.CLOCKWISE_90).offset()
//                val roomPos = blockPosition.add(0.5).add(lastRoom.rotation.offset().mul(10.0)).sub(towardsScreenOff.mul(4.0))
//                val numPastePos = blockPosition.add(lastRoom.rotation.offset().mul(12.0)).add(towardsScreenOff.mul(5.0)).add(0.0, 4.0, 0.0)
//                val lightCenterPos = blockPosition.add(lastRoom.rotation.offset().mul(8.0)).add(towardsScreenOff.mul(5.0)).add(0.0, 2.0, 0.0)
//
//                player.teleport(roomPos.asPos())
//
//                object : MinestomRunnable(delay = Duration.ofMillis(1500), repeat = Duration.ofMillis(1200), iterations = 10, group = runnableGroup) {
//                    val combination = (0..9).map { it to ThreadLocalRandom.current().nextBoolean() }
//
//                    override fun run() {
//                        val currentIter = currentIteration.get()
//                        val combinationPart = combination[currentIter]
//                        val batch = AbsoluteBlockBatch()
//                        val schem = numSchems[combinationPart.first]
//
//                        schem.apply(lastRoom.rotation.rotate(Rotation.CLOCKWISE_90)) { pos, block ->
//                            batch.setBlock(pos, block)
//                        }
//                        for (x in -1..1) {
//                            for (y in -1..1) {
//                                batch.setBlock(lightCenterPos.add(lastRoom.rotation.offset().mul(x.toDouble())).add(0.0, y.toDouble(), 0.0), Block.REDSTONE_LAMP.withProperty("lit", combinationPart.second.toString()))
//                            }
//                        }
//
//                        batch.apply(instance) {}
//                    }
//                }
//            }

            else if (block.compare(Block.LEVER)) {
                if (block.getProperty("powered") == "true") return@listenOnly

                if (roomNum.get() == endingSchemNumber) {
                    val leverDir = Direction.valueOf(block.getProperty("facing").uppercase())
                    val rightLeverDir = leverDir.rotate().opposite()

                    instance.setBlock(blockPosition, block.withProperty("powered", "true"))
                    instance.playSound(Sound.sound(SoundEvent.BLOCK_IRON_DOOR_OPEN, Sound.Source.MASTER, 1f, 0.6f))

                    val alivePlayers = players.filter { it.gameMode == GameMode.ADVENTURE }

                    val specEntity = Entity(EntityType.BOAT)
//                    val specMeta = specEntity.entityMeta as AreaEffectCloudMeta
//                    specMeta.radius = 0f
                    specEntity.isInvisible = true
                    specEntity.setNoGravity(true)
                    specEntity.setInstance(instance, player.position)

                    object : MinestomRunnable(delay = Duration.ofMillis(650), repeat = Duration.ofMillis(1150), iterations = 3, group = runnableGroup) {

                        override fun run() {
                            val currentIter = currentIteration.get()

                            if (currentIter == 0) {
                                // Unload previous rooms!

                                rooms.iterator().forEachRemaining {
                                    if (rooms.indexOf(it) == rooms.size - 1) return@forEachRemaining
                                    val roomToRemove = rooms.removeAt(0)
                                    doorPositions.remove(roomToRemove.position)
                                    roomToRemove.lightBlocks.clear()
                                    roomToRemove.chests.clear()
                                    roomToRemove.entityIds.forEach {
                                        Entity.getEntity(it)?.remove()
                                    }
                                    roomToRemove.entityIds.clear()
                                }
                                val nextRoom = rooms[0]

                                instance.setBlock(nextRoom.position, Block.IRON_DOOR.withProperties(mapOf("facing" to nextRoom.rotation.asDirection().name.lowercase())))
                                instance.setBlock(nextRoom.position.add(0.0, 1.0, 0.0), Block.IRON_DOOR.withProperties(mapOf("facing" to nextRoom.rotation.asDirection().name.lowercase(), "half" to "upper")))

                                specEntity.teleport(blockPosition.add(0.5).sub(leverDir.offset()).add(rightLeverDir.offset().mul(4.5)).add(leverDir.offset().mul(4.5)).asPos().withYaw(leverDir.opposite().yaw()).withPitch(1f))

                                alivePlayers.forEach {
                                    it.gameMode = GameMode.SPECTATOR
                                    it.spectate(specEntity)
                                }
                            }

                            val block = instance.getBlock(blockPosition.sub(leverDir.offset()).add(rightLeverDir.offset().mul(4.0 - currentIter)))

                            for (y in -1..2) {
                                val left = blockPosition.sub(leverDir.offset()).add(rightLeverDir.offset().mul(4.0 - currentIter)).withY(blockPosition.y() + y)
                                val right = blockPosition.sub(leverDir.offset()).add(rightLeverDir.offset().mul(5.0 + currentIter)).withY(blockPosition.y() + y)

                                instance.sendBreakBlockEffect(left, Block.DIORITE_WALL)
                                instance.sendBreakBlockEffect(right, Block.DIORITE_WALL)
                                instance.setBlock(left, Block.AIR)
                                instance.setBlock(right, Block.AIR)

                                instance.showParticle(
                                    Particle.particle(
                                        type = ParticleType.CLOUD,
                                        count = 3,
                                        data = OffsetAndSpeed(0.1f, 0.1f, 0.1f, 0.1f)
                                    ), left.add(0.5).asVec()
                                )
                                instance.showParticle(
                                    Particle.particle(
                                        type = ParticleType.CLOUD,
                                        count = 3,
                                        data = OffsetAndSpeed(0.1f, 0.1f, 0.1f, 0.1f)
                                    ), right.add(0.5).asVec()
                                )
                            }

                            instance.playSound(Sound.sound(SoundEvent.ENTITY_GENERIC_EXTINGUISH_FIRE, Sound.Source.MASTER, 1f, 0.7f), Sound.Emitter.self())
                        }

                        override fun cancelled() {
                            // TODO: figure cutscene

                            alivePlayers.forEach {
                                it.teleport(specEntity.position)
                                it.gameMode = GameMode.ADVENTURE
                                it.stopSpectating()
                            }
                            specEntity.remove()
                        }
                    }
                }
            }

            else if (block.compare(Block.OAK_DOOR)) {
                if (block.getProperty("open") == "true") {
                    isCancelled = true
                    isBlockingItemUse = true
                } else {
                    // Open door for other players
                    if (block.getProperty("half") == "lower") {
                        instance.setBlock(blockPosition.add(0.0, 1.0, 0.0), block.withProperties(mapOf("open" to "true", "half" to "upper")))
                    } else {
                        instance.setBlock(blockPosition.add(0.0, -1.0, 0.0), block.withProperties(mapOf("open" to "true", "half" to "lower")))
                    }

                    instance.setBlock(blockPosition, block.withProperty("open", "true"))
                }
            }

            else {
                isCancelled = true
//                isBlockingItemUse = true
            }
        }

        eventNode.listenOnly<InventoryCloseEvent> {
            val pos = playerChestMap[player.uuid] ?: return@listenOnly
            val block = instance.getBlock(pos)
            ChestLoot.freeChest(player, block, pos)

            playerChestMap.remove(player.uuid)
        }

        eventNode.listenOnly<EntityDamageEvent> {
            val player = entity as? Player ?: return@listenOnly
            if (player.gameMode != GameMode.ADVENTURE) return@listenOnly

            // If the player would die from this damage
            if (player.health - damage <= 0f) {
                isCancelled = true // save them :sunglassses:

                val doorsEntity = when (damageType) {
                    is EyesDamage -> DoorsEntity.EYES
                    is HideDamage -> DoorsEntity.HIDE
                    is RushDamage -> DoorsEntity.RUSH
                    else -> return@listenOnly
                }

                kill(player, doorsEntity) // jk
            }
        }

        eventNode.listenOnly<PlayerMoveEvent> {
            if (activeDoorPosition == Vec.ZERO) return@listenOnly
            if (player.gameMode != GameMode.ADVENTURE) return@listenOnly
            if (generatingRoom.get()) {
                return@listenOnly
            }

            if (stopGenerating.get()) {
                stopGeneratingDoors.forEach {
                    if (it.distanceSquared(player.position) < doorRange * doorRange) {
                        // Do not open automatically if on a key room
                        if (rooms.last().keyRoom) return@listenOnly

                        val block = instance.getBlock(it)
                        val facing = block.getProperty("facing")
                        stopGeneratingDoors.remove(it)
                        if (facing == null) {
                            println("Facing was null")
                            return@forEach
                        }
                        generateNextRoom(instance)

                        instance.playSound(Sound.sound(Key.key("custom.door.open"), Sound.Source.MASTER, 0.8f, 1f), it)

                        instance.setBlock(it, Block.IRON_DOOR.withProperties(mapOf("open" to "true", "facing" to facing, "half" to "lower")))
                        instance.setBlock(it.add(0.0, 1.0, 0.0), Block.IRON_DOOR.withProperties(mapOf("open" to "true", "facing" to facing, "half" to "upper")))
                    }
                }

                return@listenOnly
            }

            val distanceToDoor = player.position.distanceSquared(activeDoorPosition)

            if (distanceToDoor < doorRange * doorRange) {
                // Do not open automatically if on a key room
                if (rooms.last().keyRoom) return@listenOnly

                generateNextRoom(instance)
            }
        }

        eventNode.listenOnly<InventoryPreClickEvent> {
            if ((41..44).contains(slot)) {
                isCancelled = true
            }
        }
        eventNode.cancel<ItemDropEvent>()
    }

    private fun kill(player: Player, killer: DoorsEntity) {
        player.gameMode = GameMode.SPECTATOR
        player.isInvisible = true

        fun deathAnimation() {
            player.playSound(Sound.sound(Key.key("custom.death"), Sound.Source.MASTER, 0.6f, 1f), Sound.Emitter.self())
            player.sendActionBar(Component.text("\uE00A"))

            player.showTitle(
                Title.title(
                    Component.text("\uE019", TextColor.color(255, 200, 200)),
                    Component.empty(),
                    Title.Times.times(Duration.ofMillis(0), Duration.ofMillis(1500), Duration.ofMillis(500))
                )
            )

            val bgCharacters = listOf('\uE015', '\uE016', '\uE017', '\uE018')

            val messages = listOf(killer.messages.first().first) + killer.messages.first().second.split(". ").map { if (it.endsWith(".")) it else "${it}." }

            val ticksPerMessage = (20 * 6.5).toInt()

            object : MinestomRunnable(delay = Duration.ofSeconds(2), repeat = Duration.ofMillis(50), iterations = ticksPerMessage * messages.size, group = runnableGroup) {

                override fun run() {
                    val currentIter = currentIteration.get()

                    if (currentIter == 0) {
                        player.playSound(Sound.sound(Key.key("music.guidinglight"), Sound.Source.MASTER, 0.6f, 1f), Sound.Emitter.self())
                    }
                    if (currentIter % ticksPerMessage == 0) {
                        val currentMessage = messages[(currentIter.toDouble() / ticksPerMessage).toInt()]

                        player.showTitle(
                            Title.title(
                                Component.empty(),
                                Component.text(currentMessage, TextColor.color(183, 245, 245)),
                                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis((ticksPerMessage * 50L) - 600L), Duration.ofMillis(300))
                            )
                        )
                    }

                    player.sendActionBar(Component.text(bgCharacters[currentIter % bgCharacters.size]))
                }

                override fun cancelled() {
                    player.stopSound(SoundStop.named(Key.key("music.guidinglight")))
                    player.playSound(Sound.sound(Key.key("music.guidinglight.ending"), Sound.Source.MASTER, 0.6f, 1f), Sound.Emitter.self())

                    val playerToSpectate = players.filter { it != player }.randomOrNull()
                    if (playerToSpectate != null) player.spectate(playerToSpectate)
                }
            }
        }

        if (killer == DoorsEntity.RUSH) {
            val firstTitle = 10
            val secondTitle = ThreadLocalRandom.current().nextInt(30, 45)
            val jumpscare = ThreadLocalRandom.current().nextInt(80, 110)

            object : MinestomRunnable(repeat = Duration.ofMillis(50), group = runnableGroup, iterations = jumpscare + 24) {
                override fun run() {
                    val currentIter = currentIteration.get()

                    player.sendActionBar(Component.text("\uE00A"))

                    if (currentIter == firstTitle || currentIter == secondTitle) {
                        player.playSound(Sound.sound(Key.key("entity.rush.jumpscare"), Sound.Source.MASTER, 0.8f, if (currentIter == firstTitle) 1f else 1.2f))
                        player.showTitle(
                            Title.title(
                                Component.text(if (currentIter == firstTitle) "\uE01A" else "\uE01B"),
                                Component.empty(),
                                Title.Times.times(Duration.ZERO, Duration.ofSeconds(4), Duration.ZERO)
                            )
                        )
                    }

                    if (currentIter == jumpscare) {
                        player.stopSound(SoundStop.named(Key.key("entity.rush.jumpscare")))
                        player.playSound(Sound.sound(Key.key("entity.rush.death"), Sound.Source.MASTER, 0.8f, 1f))

                        player.showTitle(
                            Title.title(
                                Component.text("\uE01C"),
                                Component.empty(),
                                Title.Times.times(Duration.ZERO, Duration.ofMillis(1200), Duration.ZERO)
                            )
                        )
                    }
                }

                override fun cancelled() {
                    player.clearTitle()
                    deathAnimation()
                }
            }
        } else {
            deathAnimation()
        }

    }

    private fun generateNextRoom(instance: Instance, openDoor: Boolean = true): CompletableFuture<Void>? {
        if (stopGenerating.get()) {
            // End seek sequence
            if (seekSequence.get() && stopGeneratingDoors.isEmpty()) {
                val alivePlayers = players.filter { it.gameMode == GameMode.ADVENTURE }

                stopGeneratingDoors.clear()
                stopGenerating.set(false)
                seekSequence.set(false)

                doorRange = 3.6

                alivePlayers.forEach {
                    it.leggings = ItemStack.AIR
                    it.isAutoViewable = true
                    it.getAttribute(Attribute.MOVEMENT_SPEED).baseValue = 0.1f
                }

                generateNextRoom(instance)
            }

            return null
        }

        val oldActiveDoor = activeDoorPosition
        val newRoomEntryPos = activeDoorPosition.add(activeDoorRotation.offset())

        // Begin seek sequence
        if (roomNum.get() == seekSchemNumber && !seekSequence.get()) { // If the last room was the seek hallway, begin sequence when the player attempts to open the door
            // TODO: animation

            val alivePlayers = players.filter { it.gameMode == GameMode.ADVENTURE }

            val leggingsItem = ItemStack.builder(Material.LEATHER_LEGGINGS)
                .meta {
                    it.enchantment(Enchantment.SWIFT_SNEAK, 1)
                }
                .build()
            alivePlayers.forEach {
                it.leggings = leggingsItem
                it.isAutoViewable = false
                it.getAttribute(Attribute.MOVEMENT_SPEED).baseValue = 0.15f
            }
            doorRange = 5.0

            seekSequence.set(true)

            stopGeneratingDoors.clear()
            stopGeneratingDoors.add(activeDoorPosition)

            CoroutineScope(Dispatchers.IO).launch {
                repeat(5) {
                    generateNextRoom(instance, false)?.join()
                    stopGeneratingDoors.add(activeDoorPosition)
                }

                stopGenerating.set(true)
            }

            return null
        }

        generatingRoom.set(true)

        val newRoom = Room(this@DoorsGame, instance, newRoomEntryPos, activeDoorRotation)

        var customRoom = true
        val applyRoom = when (roomNum.get()) {
            endingSchemNumber -> {
                stopGenerating.set(true)
                newRoom.applyRoom(listOf(endingSchem))
            }

            // Seek
            seekSchemNumber -> newRoom.applyRoom(listOf(schematics.first { it.name == "largehallway" }))
            seekSchemNumber + 1 -> newRoom.applyRoom(seekSchematics.filter { it.name.startsWith("seekhallway") })
            seekSchemNumber + 2 -> newRoom.applyRoom(seekSchematics.filter { it.name.startsWith("seekcross") })
            seekSchemNumber + 3 -> newRoom.applyRoom(listOf(seekSchematics.first { it.name == "seekshorthallway"}))
            seekSchemNumber + 4 -> newRoom.applyRoom(seekSchematics.filter { it.name.startsWith("seekcross") })
            seekSchemNumber + 5 -> newRoom.applyRoom(seekSchematics.filter { it.name.startsWith("seekhallway") })


            else -> {
                customRoom = false
                newRoom.applyRoom()
            }
        }

        if (customRoom) {

            newRoom.darkRoom = false
        }

        if (newRoom.darkRoom) {
            // Play dark ambiance
            playSound(Sound.sound(Key.key("music.ambiance.dark"), Sound.Source.MASTER, 1f, 1f), Sound.Emitter.self())
        }

        if (applyRoom == null) {
            //player.sendMessage("No room")
            generatingRoom.set(false)
            roomNum.decrementAndGet()
            return null
        }

        applyRoom.thenRun {
            if (openDoor) {
                val bottomBlock = instance.getBlock(oldActiveDoor)
                instance.setBlock(oldActiveDoor, bottomBlock.withProperty("open", "true"))

                val topBlock = instance.getBlock(oldActiveDoor.add(0.0, 1.0, 0.0))
                instance.setBlock(oldActiveDoor.add(0.0, 1.0, 0.0), topBlock.withProperties(mapOf("open" to "true", "half" to "upper")))

                instance.playSound(Sound.sound(Key.key("custom.door.open"), Sound.Source.MASTER, 0.8f, 1f), oldActiveDoor)
            }

            generatingRoom.set(false)
        }

        rooms.add(newRoom)

        // Unload previous rooms
        if (rooms.size >= maxLoadedRooms) {
            val roomToRemove = rooms.removeAt(0)
            val nextRoom = rooms[0]

            doorPositions.remove(roomToRemove.position)

            roomToRemove.lightBlocks.clear()
            roomToRemove.chests.clear()

            instance.setBlock(nextRoom.position, Block.IRON_DOOR.withProperties(mapOf("facing" to nextRoom.rotation.asDirection().name.lowercase())))
            instance.setBlock(nextRoom.position.add(0.0, 1.0, 0.0), Block.IRON_DOOR.withProperties(mapOf("facing" to nextRoom.rotation.asDirection().name.lowercase(), "half" to "upper")))

            roomToRemove.entityIds.forEach {
                Entity.getEntity(it)?.remove()
            }
            roomToRemove.entityIds.clear()
        }

        // Only begin allowing entity spawns above or on room 5
        // and if the room is not dark
        // and if it is not a scripted room
        if (!customRoom) run chances@{ if (roomNum.get() >= 5 && !newRoom.darkRoom) {
            rushChance += rushChanceIncrease // 10% per room
            eyesChance += eyesChanceIncrease // 0.3% per room

            sendMessage(Component.text("rush chance: ${rushChance}, eyes chance ${eyesChance}"))

            // Roll
            val random = ThreadLocalRandom.current()

            if (random.nextDouble() <= rushChance) {
                // If room does not have atleast 2 closets
                if (newRoom.closets < 2) {
                    // guarantee rush for next available room
                    rushChance = 1.0
                } else {
                    spawnRush(instance)
                    // reset to slightly below 0.0 to stop consecutive encounters
                    rushChance = -rushChanceIncrease
                }

                return@chances
            }
            if (random.nextDouble() <= eyesChance) {
                spawnEyes(instance)
                eyesChance = 0.0

                return@chances
            }

            // 10% chance for a fake flicker
            if (random.nextDouble() < 0.10) {
                flickerLights(instance)

                return@chances
            }
        } }

        return applyRoom
    }

    fun flickerLights(instance: Instance) {
        val lights = rooms.last().lightBlocks.toMutableList()

        if (lights.isNotEmpty()) {
            object : MinestomRunnable(repeat = Duration.ofMillis(50), iterations = 40, group = runnableGroup) {
                var lightBreakIndex = 0
                var loopIndex = 0
                var nextFlicker = 0

                override fun run() {
                    val currentIter = currentIteration.get()

                    if (currentIter == nextFlicker) {
                        lightBreakIndex++

                        val index = lightBreakIndex % lights.size
                        val light = lights[index]
                        val prevLight = lights[if (index == 0) lights.size - 1 else index - 1]

                        instance.setBlock(light.first, Block.AIR)
                        instance.setBlock(prevLight.first, prevLight.second)

                        instance.playSound(Sound.sound(Key.key("custom.light.zap"), Sound.Source.MASTER, 2f, 1f), light.first)

                        nextFlicker = currentIter + ThreadLocalRandom.current().nextInt(1, 5)
                    }

                    loopIndex++
                }
            }
        }
    }

    fun spawnRush(instance: Instance) {
        val entity = Entity(EntityType.ARMOR_STAND)
        val meta = entity.entityMeta as ArmorStandMeta
        meta.setNotifyAboutChanges(false)
        //meta.radius = 0f
        meta.isSmall = true
        meta.isHasGlowingEffect = true
        meta.isHasNoBasePlate = true
        meta.isMarker = true
        meta.isInvisible = true
        meta.isCustomNameVisible = true
        meta.customName = Component.text("\uF80D\uE012\uF80D")
        meta.isHasNoGravity = true
        meta.setNotifyAboutChanges(true)

        flickerLights(instance)

        val paths = mutableListOf<List<Point>>()

        val doorPoses = doorPositions.takeLast(maxLoadedRooms - 2)

        doorPoses.forEachIndexed { i, it ->
            if (i > 0) {
                val startPos = doorPoses[i - 1]

                val path = rushPathfinding.pathfind(startPos, it)
                if (path == null) {
                    val dist = startPos.distance(it).toInt()
                    val points = mutableListOf<Point>()

                    repeat(dist) { ind ->
                        points.add(startPos.lerp(it, ind.toDouble() / dist.toDouble()))
                    }

                    paths.add(points)
                } else {
                    paths.add(path.reversed())
                }
            }
        }

//        entity.updateViewableRule { player ->
//            val playerPos = player.position.add(0.0, 1.6, 0.0)
//            val distanceToEye = playerPos.distance(entity.position)
//            val directionToEye = playerPos.sub(entity.position).asVec().normalize()
//            val raycast = RaycastUtil.raycastBlock(instance, entity.position, directionToEye, maxDistance = distanceToEye)
//
//            raycast == null
//        }

        entity.setInstance(instance, doorPoses.first()).thenRun {
            object : MinestomRunnable(delay = Duration.ofSeconds(2), repeat = Duration.ofMillis(50), group = runnableGroup) {
                var playedSound = false

                var doorIndex = 0.0

                var pathIndex = 0
                val roomsCopy = rooms.toMutableList()

                var lightRoom: Room? = null
                var lightBreakTask: MinestomRunnable? = null

                override fun run() {
                    if (!playedSound && entity.position.distanceSquared(doorPoses.last()) < 80 * 80) {
                        playedSound = true
                        val packet = EntitySoundEffectPacket(SoundEvent.ENTITY_ZOMBIE_DEATH.id(), Sound.Source.MASTER, entity.entityId, 4f, 1f, 0)
                        instance.sendGroupedPacket(packet)
                    }

                    if (doorIndex % 1 == 0.0) {
                        entity.teleport(paths[pathIndex][doorIndex.toInt()].asPos().add(0.5, 1.0, 0.5))

                    } else {
                        val floored = floor(doorIndex).toInt()
                        val prev = paths[pathIndex][floored]
//                    val next = paths[pathIndex][(floored + 1).coerceAtMost(paths.size - 1)]
//                    val pos = prev.lerp(next, doorIndex - floored).asPos()
//                    entity.teleport(pos.add(0.5, 1.0, 0.5))
                        entity.teleport(prev.asPos().add(0.5, 1.0, 0.5))
                    }


                    instance.showParticle(
                        Particle.particle(
                            type = ParticleType.LARGE_SMOKE,
                            count = 20,
                            data = OffsetAndSpeed(2.0f, 2.0f, 2.0f, 0.2f)
                        ),
                        entity.position.add(0.0, 1.0, 0.0).asVec()
                    )

                    // Get players that are inside of rush's range and are not hiding in a closet
                    instance.players.filter { !it.hasTag(hidingTag) && it.getDistanceSquared(entity) < rushRange * rushRange }.forEach {
                        it.damage(RushDamage(entity), 420f)
                    }

//                    entity.updateViewableRule()

                    doorIndex += 1

                    if (doorIndex >= paths[pathIndex].size) {
                        doorIndex -= paths[pathIndex].size
                        if (pathIndex + 1 < paths.size) {

                            var lightBreakIndex = 0
                            val room = roomsCopy[(pathIndex + 3).coerceIn(0, roomsCopy.size - 1)]
                            val lights = room.lightBlocks

                            if (lights.isNotEmpty()) {
                                lights.iterator().forEachRemaining {
                                    instance.setBlock(it.first, Block.AIR)
                                    if (it.second.compare(Block.LIGHT)) lights.remove(it)
                                }

                                lightBreakTask?.cancel()
                                lightRoom?.lightBlocks?.clear()
                                lightRoom = room
                                lightBreakTask = object : MinestomRunnable(repeat = Duration.ofMillis(100), group = runnableGroup) {
                                    override fun run() {
                                        if (lightBreakIndex >= lights.size) return

                                        val light = lights[lightBreakIndex]

                                        val rand = ThreadLocalRandom.current()
                                        //player.sendMessage("Broke light")
                                        instance.playSound(Sound.sound(SoundEvent.BLOCK_GLASS_BREAK, Sound.Source.MASTER, 2f, rand.nextFloat(0.8f, 1.5f)), entity.position)

                                        lightBreakIndex++
                                        if (lightBreakIndex >= lights.size) {
                                            cancel()
                                            room.lightBlocks.clear()
                                        }
                                    }
                                }
                            }
                        }

                        pathIndex++
                    }
                    if (pathIndex >= paths.size) {
                        entity.remove()
                        generateNextRoom(instance)
                        cancel()
                    }
                }
            }
        }
    }

    fun spawnEyes(instance: Instance) {
        val entity = Entity(EntityType.AREA_EFFECT_CLOUD)
        val meta = entity.entityMeta as AreaEffectCloudMeta
//                meta.setNotifyAboutChanges(false)
        meta.radius = 0f
//                meta.isSmall = true
//                meta.isHasGlowingEffect = true
//                meta.isHasNoBasePlate = true
//                meta.isMarker = true
//                meta.isInvisible = true
        meta.isCustomNameVisible = true
        meta.customName = Component.text("\uF80D\uE013\uF80D")
        meta.isHasNoGravity = true
        meta.setNotifyAboutChanges(true)

        val roomNumberOnSpawn = roomNum.get()

        val lastRoom = rooms.last()
        val lastRoomBounds = lastRoom.schematic.bounds(lastRoom.position, lastRoom.rotation)

        var startPos = lastRoomBounds.bottomRight.lerp(lastRoomBounds.topLeft, 0.5).asPosition()

        var firstBlockY = lastRoomBounds.bottomRight.y()
        var airBlockY = 0.0
        var eyesSpawnPosition: Pos? = null
        stuff@ while (eyesSpawnPosition == null) {
            if (!instance.getBlock(startPos.withY(firstBlockY)).isAir) {
                airBlockY = firstBlockY + 1.0
                while (!instance.getBlock(startPos.withY(airBlockY)).isAir) {
                    airBlockY++
                }
                eyesSpawnPosition = startPos.withY(airBlockY + 1.0)
            }

            firstBlockY++
        }

        entity.setInstance(instance, eyesSpawnPosition)

        instance.setBlock(eyesSpawnPosition, Block.LIGHT)

        instance.playSound(Sound.sound(Key.key("entity.eyes.initiate"), Sound.Source.MASTER, 1f, 1f), eyesSpawnPosition)

        entity.scheduler().buildTask {
            instance.playSound(Sound.sound(Key.key("entity.eyes.ambiance"), Sound.Source.MASTER, 1f, 1f), eyesSpawnPosition)
        }.repeat(110, TimeUnit.SERVER_TICK).schedule()

        entity.scheduler().buildTask {

            if (roomNumberOnSpawn != roomNum.get()) {
                instance.stopSound(SoundStop.named(Key.key("entity.eyes.ambiance")))
                instance.stopSound(SoundStop.named(Key.key("entity.eyes.initiate")))
                entity.remove()
            }

            val random = ThreadLocalRandom.current()
            val jitter = 0.03

            entity.teleport(eyesSpawnPosition.add(random.nextDouble(-jitter, jitter), random.nextDouble(-jitter, jitter), random.nextDouble(-jitter, jitter)))

            entity.updateViewableRule { player ->
                val playerPos = player.position.add(0.0, 1.6, 0.0)
                val distanceToEye = playerPos.distance(eyesSpawnPosition)
                val directionToEye = playerPos.sub(eyesSpawnPosition).asVec().normalize()
                val raycast = RaycastUtil.raycastBlock(instance, entity.position, directionToEye, maxDistance = distanceToEye)

                raycast == null
            }

            // Don't deal damage for 2.5 seconds
            if (entity.aliveTicks > 50) players.forEach {
                if (it.gameMode != GameMode.ADVENTURE) return@forEach
                if (!entity.viewers.contains(it)) return@forEach

                val dir = Pos.ZERO.withDirection(eyesSpawnPosition.add(0.0, 1.0, 0.0).sub(it.position).asVec().normalize())
                val yawDiff = dir.yaw - it.position.yaw
                val pitchDiff = dir.pitch - it.position.pitch

                if (yawDiff > -70 && yawDiff < 70 && pitchDiff > -55 && pitchDiff < 46) {
                    if (it.aliveTicks % 3L == 0L) {
                        it.playSound(Sound.sound(Key.key("entity.eyes.attack"), Sound.Source.MASTER, 1f, 1f))
                        it.damage(EyesDamage(entity), 2f)
                    }
                }
            }
        }.repeat(2, TimeUnit.SERVER_TICK).schedule()
    }

    val bossBarMap = ConcurrentHashMap<UUID, CoinBar>()
    fun refreshCoinCounts(player: Player, goldIncrease: Int, knobsIncrease: Int) {

        val coinBar = if (bossBarMap.containsKey(player.uuid)) {
            bossBarMap[player.uuid]!!
        } else {
            val bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS)
            val coinBar = CoinBar(0, 0, bar)
            bossBarMap[player.uuid] = coinBar
            player.showBossBar(bar)
            coinBar
        }

        object : MinestomRunnable(iterations = 25, repeat = Duration.ofMillis(50), group = runnableGroup) {
            var i = 1f

            override fun run() {
                val knobsAmt = coinBar.knobs + ceil(knobsIncrease - expInterp(0f, knobsIncrease.toFloat(), i)).toInt()
                val goldAmt = coinBar.gold + ceil(goldIncrease - expInterp(0f, goldIncrease.toFloat(), i)).toInt()

                coinBar.bossBar.name(
                    Component.text()
                        .append(Component.text("Knobs: $knobsAmt", TextColor.lerp(i, NamedTextColor.WHITE, NamedTextColor.GOLD)))
                        .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("Gold: $goldAmt", TextColor.lerp(i, NamedTextColor.WHITE, NamedTextColor.GOLD)))
                )

                i -= (1f / iterations.toFloat())
            }

            override fun cancelled() {
                coinBar.bossBar.name(
                    Component.text()
                        .append(Component.text("Knobs: ${coinBar.knobs + knobsIncrease}"))
                        .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("Gold: ${coinBar.gold + goldIncrease}"))
                )

                bossBarMap[player.uuid] = coinBar.copy(knobs = coinBar.knobs + knobsIncrease, gold = coinBar.gold + goldIncrease)
            }
        }
    }

    override fun victory(winningPlayers: Collection<Player>) {
    }

    override fun instanceCreate(): CompletableFuture<Instance> {
        val instanceFuture = CompletableFuture<Instance>()

//        val dim = Manager.dimensionType.getDimension(NamespaceID.from("nolight"))!!
        val dim = Manager.dimensionType.getDimension(NamespaceID.from("fullbrighttt"))!!
        val newInstance = Manager.instance.createInstanceContainer(dim)
        newInstance.time = 18000
        newInstance.timeRate = 0
        newInstance.timeUpdate = null

        newInstance.enableAutoChunkLoad(true)

        // 1 chunk required for player to spawn
        newInstance.loadChunk(0, 0).thenRun { instanceFuture.complete(newInstance) }

        val radius = 3
        for (x in -radius..radius) {
            for (z in -radius..radius) {
                newInstance.loadChunk(x, z).thenAccept { it.sendChunk() }
            }
        }

        return instanceFuture
    }


}