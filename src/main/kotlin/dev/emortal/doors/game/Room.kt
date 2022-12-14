package dev.emortal.doors.game

import dev.emortal.doors.bigSchem
import dev.emortal.doors.block.ChestHandler
import dev.emortal.doors.block.SignHandler
import dev.emortal.doors.doorSchem
import dev.emortal.doors.game.ChestLoot.addRandomly
import dev.emortal.doors.game.DoorsGame.Companion.applyDoor
import dev.emortal.doors.pathfinding.asDirection
import dev.emortal.doors.pathfinding.offset
import dev.emortal.doors.pathfinding.rotate
import dev.emortal.doors.pathfinding.rotatePos
import dev.emortal.doors.relight
import dev.emortal.doors.schematic.RoomBounds.Companion.isOverlapping
import dev.emortal.doors.schematic.RoomSchematic
import dev.emortal.doors.schematics
import net.hollowcube.util.schem.Rotation
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.coordinate.Point
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.metadata.other.PaintingMeta
import net.minestom.server.instance.Instance
import net.minestom.server.instance.batch.AbsoluteBlockBatch
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.utils.Direction
import net.minestom.server.utils.time.TimeUnit
import org.jglrxavpok.hephaistos.nbt.NBTCompound
import org.jglrxavpok.hephaistos.parser.SNBTParser
import org.tinylog.kotlin.Logger
import world.cepi.kstom.adventure.noItalic
import world.cepi.kstom.util.asPos
import java.io.StringReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ThreadLocalRandom
import kotlin.collections.set


class Room(val game: DoorsGame, val instance: Instance, val position: Point, val rotation: Rotation) {

    companion object {
        val lightBlockTypes = setOf<Block>(
            Block.LANTERN,
            Block.REDSTONE_LAMP,
            Block.CANDLE,
            Block.LIGHT
        )
    }

    lateinit var schematic: RoomSchematic

    var keyRoom = false

    val lightBlocks = CopyOnWriteArrayList<Pair<Point, Block>>()
    val chests = CopyOnWriteArrayList<Pair<Point, ChestHandler>>()
    val entityIds = CopyOnWriteArraySet<Int>()

    var closets: Int = 0

    val number = game.roomNum.incrementAndGet()

    // 10% chance for dark room
    var darkRoom = number > 7 && ThreadLocalRandom.current().nextDouble() < 0.10

    fun applyRoom(schemList: Collection<RoomSchematic> = schematics.toMutableList()): CompletableFuture<Void>? {
        val randomSchem = schemList.shuffled().firstOrNull { schem ->
            val bounds = schem.bounds(position, rotation)
            if (bounds.outOfBounds()) {
                Logger.info("out of bounds")
                Logger.info(bounds.toString())
                return@firstOrNull false
            }

            val doors = schem.doorPositions.map { it.first.rotatePos(rotation).add(position) to it.second.rotate(rotation) }

            // check that the attempted schem
            // - will not overlap with any rooms
            // - have atleast one door available to spawn next room from
            game.rooms.none { otherRoom ->
                if (otherRoom.number + 1 == number) return@none false

                val otherRoomBounds = otherRoom.schematic.bounds(otherRoom.position, otherRoom.rotation)

                val isOverlapping = isOverlapping(bounds, otherRoomBounds)
                val allDoorsOverlapping = doors.all { door ->
                    val doorbound = bigSchem.bounds(door.first, door.second)

                    isOverlapping(doorbound, otherRoomBounds)
                }

                Logger.info("overlap ${isOverlapping}")
                Logger.info("doors overlap ${allDoorsOverlapping}")
                isOverlapping || allDoorsOverlapping
            }
        }



        if (randomSchem == null) {
            Logger.info("No more schematics")
            return null
        }

        val doorPositions = randomSchem.doorPositions.map { it.first.rotatePos(rotation).add(position) to it.second.rotate(rotation) }
        val randomDoor = doorPositions.shuffled().firstOrNull { door ->
            val doorDir = door.second
            if (doorDir == Rotation.CLOCKWISE_180) return@firstOrNull false

            // TODO - doesn't really work properly
            val doorbound = bigSchem.bounds(door.first, doorDir)
            if (doorbound.outOfBounds()) return@firstOrNull false

            game.rooms.none { otherRoom ->
                if (otherRoom.number + 1 == number) return@none false
                isOverlapping(doorbound, otherRoom.schematic.bounds(otherRoom.position, otherRoom.rotation))
            }
        }

        if (randomDoor == null) {
            Logger.info("Available doors was empty")

            val newSchemList = schemList.filter { it != randomSchem }.filter {
                val bounds = it.bounds(position, rotation)
                game.rooms.none { otherRoom ->
                    if (otherRoom.number + 1 == number) return@none false
                    isOverlapping(bounds, otherRoom.schematic.bounds(otherRoom.position, otherRoom.rotation))
                }
            }
            if (newSchemList.isEmpty()) {
                Logger.info("NO MORE ROOMS ARE AVAILABLE")
                return null
            }

            return applyRoom(newSchemList)
        }

        val paintingPositions = mutableSetOf<Pair<Point, Direction>>()

        val batch = AbsoluteBlockBatch()

        val doubleChests = mutableMapOf<Point, Pair<Point, Block>>()

        var closetDoorCount = 0.0

        randomSchem.schem.apply(rotation) { pos, block ->
            val blockPos = pos.add(position)

            // Initialize chests
            if (block.compare(Block.CHEST)) {

                val type = block.getProperty("type")
                if (type == "single") {
                    val handler = ChestHandler(game, blockPos)
                    chests.add(blockPos to handler)
                    batch.setBlock(blockPos, block.withHandler(handler))
                } else { // left or right
                    if (doubleChests.containsKey(blockPos)) {
                        val doubleChest = doubleChests[blockPos]!!
                        val handler = ChestHandler(game, blockPos)
                        batch.setBlock(blockPos, block.withHandler(handler))
                        batch.setBlock(doubleChest.first, doubleChest.second.withHandler(handler))
                        chests.add(blockPos to handler)
                        doubleChests.remove(blockPos)
                    } else {
                        val newPos = blockPos.add(
                            Direction.valueOf(block.getProperty("facing").uppercase()).rotate().rotate()
                                .rotate().offset()
                        )
                        doubleChests[newPos] = blockPos to block
                    }
                }
                return@apply
            }

            // Signs (painting, lever)
            if (block.compare(Block.WARPED_WALL_SIGN)) {
                val direction = Direction.valueOf(block.getProperty("facing").uppercase())

                paintingPositions.add(blockPos to direction)

                return@apply
            }

            if (block.compare(Block.SPRUCE_DOOR)) {
                closetDoorCount += 1.0/4.0 // (closets have 4 spruce door blocks)
            }

            if (lightBlockTypes.any { it.compare(block) }) {
                if (darkRoom) {
                    // Do not add to light blocks list, and do not place the block (return early)
                    return@apply
                } else {
                    lightBlocks.add(blockPos to block)
                }
            }

            if (!instance.getBlock(blockPos).compare(Block.AIR)) return@apply

            batch.setBlock(blockPos, block)
        }

        closets = (closetDoorCount / 4.0).toInt()

        if (!keyRoom) keyRoom = randomSchem.name.endsWith("key")

        if (keyRoom) {
            val keyItemStack = ItemStack.builder(Material.TRIPWIRE_HOOK)
                .displayName(Component.text("Room Key ${number + 1}", NamedTextColor.GOLD).noItalic())
                .meta {
                    it.canPlaceOn(Block.IRON_DOOR)
                }
                .build()

            chests.random().second.inventory.addRandomly(keyItemStack)
        }

        // loop through other doors and remove them
        doorPositions.forEachIndexed { i, door ->
            val doorDir = door.second
            if (i == doorPositions.indexOf(randomDoor)) return@forEachIndexed // ignore the picked door

//            applyDoor(game, batch, doorSchem, door.first, doorDir.asRotation(), instance)
        }

        applyDoor(batch, doorSchem, randomDoor.first, randomDoor.second)
        game.activeDoorPosition = randomDoor.first
        game.activeDoorRotation = randomDoor.second
        game.doorPositions.add(randomDoor.first)

        paintingPositions.forEach {
            val painting = Entity(EntityType.PAINTING)
            val meta = painting.entityMeta as PaintingMeta
            // Remove paintings that are above 3 blocks wide and 2 blocks high
            meta.motive = PaintingMeta.Motive.values().filter { it.width <= 3*16 && it.height <= 2*16 }.random()
            meta.direction = it.second

            painting.setNoGravity(true)

            when (it.second) {
                Direction.SOUTH, Direction.EAST -> {
                    println(it.first.add(1.5, 0.0, 1.5))
//                    painting.setInstance(instance, it.first.add(1.5, 0.0, 1.5))
                }
                Direction.NORTH, Direction.WEST -> {
                    println(it.first.add(1.5, 0.0, 1.5))
//                    painting.setInstance(instance, it.first.add(2.5, 0.0, 2.5))
                }
                else -> {}
            }

            entityIds.add(painting.entityId)

            batch.setBlock(it.first, Block.AIR)
        }

        val data: NBTCompound = SNBTParser(
            StringReader("{\"GlowingText\":1B,\"Color\":\"brown\",\"Text1\":\"{\\\"text\\\":\\\"\\\"}\"," +
                "\"Text2\":\"{\\\"text\\\":\\\"Room ${number + 1}\\\"}\",\"Text3\":\"{\\\"text\\\":\\\"\\\"}\",\"Text4\":\"{\\\"text\\\":\\\"\\\"}\"}")
        ).parse() as NBTCompound

        batch.setBlock(
            randomDoor.first.add(0.0, 2.0, 0.0).sub(randomDoor.second.offset()),
            Block.DARK_OAK_WALL_SIGN
                .withProperties(mapOf("facing" to randomDoor.second.rotate(Rotation.CLOCKWISE_180).asDirection().name.lowercase()))
                .withHandler(SignHandler)
                .withNbt(data)
        )

        schematic = randomSchem

        game.players.forEach {
            it.respawnPoint = position.asPos()
        }

        val future = CompletableFuture<Void>()
        batch.apply(instance) {
            instance.scheduler().buildTask {
                instance.relight(position.asPos())
            }.delay(3, TimeUnit.SERVER_TICK).schedule()

            future.complete(null)
        }!!
        return future
    }

}