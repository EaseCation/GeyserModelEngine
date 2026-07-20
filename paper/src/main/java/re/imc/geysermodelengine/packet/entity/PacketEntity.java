package re.imc.geysermodelengine.packet.entity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Getter
@Setter
public class PacketEntity {

    // H6：进程内单调 id 分配器，取代 ThreadLocalRandom.nextInt(3e8,4e8) 的随机 id。
    // 随机方案存在生日悖论互撞（n≈1000 时 ~0.5%），共享 id 会连带误销毁另一载体。
    // 必须锁定在 [3e8, 4e8) 区间：粒子桥([[geyser-bound-particle-defer-flush]])凭 javaId 落此区间识别 GME 载体。
    private static final AtomicInteger ID_ALLOCATOR = new AtomicInteger(300_000_000);

    private int id;
    private UUID uuid;
    private EntityType type;
    private Set<Player> viewers;
    private Location location;
    private float headYaw;
    private float headPitch;

    private final ProtocolRotationState modelEngineRotation = new ProtocolRotationState();

    private boolean removed = false;

    public PacketEntity(EntityType type, Set<Player> viewers, Location location) {
        this.id = ID_ALLOCATOR.getAndUpdate(v -> v >= 399_999_999 ? 300_000_000 : v + 1);
        this.uuid = UUID.randomUUID();
        this.type = type;
        this.viewers = viewers;
        this.location = location;
        this.modelEngineRotation.update(location.getYaw(), location.getPitch(), location.getYaw());
    }

    public @NotNull Location getLocation() {
        return location;
    }

    public boolean teleport(@NotNull Location location) {
        if (location.getWorld() == null) {
            // i dont know why some ModelEngine models have a null world
            return false;
        }
        boolean sent = this.location.getWorld() != location.getWorld() || this.location.distanceSquared(location) > 0.000001 || this.location.getYaw() != location.getYaw() || this.location.getPitch() != location.getPitch();
        this.location = location.clone();
        if (sent) sendLocationPacket(viewers);

        return true;
    }

    /**
     * Synchronizes a ModelEngine pose using the final rotations calculated by ModelEngine.
     * The pitch is carried by the Java entity rotation while the independent head yaw is carried
     * by ENTITY_HEAD_LOOK. Position sync resets Geyser's cached head yaw to the body yaw, so the
     * head-look packet must always follow a position packet.
     */
    public synchronized boolean syncModelEnginePose(
            @NotNull Location location,
            float bodyYaw,
            float headPitch,
            float headYaw
    ) {
        if (location.getWorld() == null) return false;

        ProtocolRotationState.Update rotationUpdate = modelEngineRotation.update(bodyYaw, headPitch, headYaw);
        ProtocolRotationState.Rotation rotation = rotationUpdate.rotation();

        Location nextLocation = location.clone();
        nextLocation.setYaw(rotation.bodyYaw());
        nextLocation.setPitch(rotation.headPitch());

        boolean positionChanged = this.location.getWorld() != nextLocation.getWorld()
                || this.location.distanceSquared(nextLocation) > 0.000001;

        this.location = nextLocation;
        this.headYaw = rotation.headYaw();
        this.headPitch = rotation.headPitch();

        if (positionChanged) {
            sendLocationPacket(viewers);
            sendHeadRotation(viewers);
        } else if (rotationUpdate.bodyRotationChanged()) {
            sendBodyRotation(viewers);
            sendHeadRotation(viewers);
        } else if (rotationUpdate.headYawChanged()) {
            sendHeadRotation(viewers);
        }

        return true;
    }

    public void remove() {
        removed = true;
        sendEntityDestroyPacket(viewers);
    }

    public boolean isDead() {
        return removed;
    }

    public boolean isValid() {
        return !removed;
    }

    public void sendSpawnPacket(Collection<Player> players) {
        WrapperPlayServerSpawnEntity spawnEntity = new WrapperPlayServerSpawnEntity(id, uuid, type, SpigotConversionUtil.fromBukkitLocation(location), location.getYaw(), 0, null);
        players.forEach(player -> PacketEvents.getAPI().getPlayerManager().sendPacket(player, spawnEntity));
    }

    /** Sends a ModelEngine actor with its body pitch/yaw and independent head yaw initialized. */
    public synchronized void sendModelEngineSpawnPacket(Collection<Player> players) {
        WrapperPlayServerSpawnEntity spawnEntity = new WrapperPlayServerSpawnEntity(id, uuid, type, SpigotConversionUtil.fromBukkitLocation(location), headYaw, 0, null);
        players.forEach(player -> PacketEvents.getAPI().getPlayerManager().sendPacket(player, spawnEntity));
        sendHeadRotation(players);
    }

    public void sendLocationPacket(Collection<Player> players) {
        PacketWrapper<?> packet;
        EntityPositionData data = new EntityPositionData(SpigotConversionUtil.fromBukkitLocation(location).getPosition(), Vector3d.zero(), location.getYaw(), location.getPitch());

        if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_21_2)) {
            packet = new WrapperPlayServerEntityPositionSync(id, data, false);
        } else {
            packet = new WrapperPlayServerEntityTeleport(id, data, RelativeFlag.NONE,false);
        }

        players.forEach(player -> PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet));
    }

    private void sendBodyRotation(Collection<Player> players) {
        WrapperPlayServerEntityRotation packet = new WrapperPlayServerEntityRotation(id, location.getYaw(), location.getPitch(), false);
        players.forEach(player -> PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet));
    }

    public void sendHeadRotation(Collection<Player> players) {
        WrapperPlayServerEntityHeadLook packet = new WrapperPlayServerEntityHeadLook(id, headYaw);
        players.forEach(player -> PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet));
    }

    public void sendEntityDestroyPacket(Collection<Player> players) {
        WrapperPlayServerDestroyEntities packet = new WrapperPlayServerDestroyEntities(id);
        players.forEach(player -> PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet));
    }

    public int getEntityId() {
        return id;
    }
}
