package dev.wetsuki.modules.movement;

import dev.wetsuki.events.Event;
import dev.wetsuki.events.impl.EventUpdate;
import dev.wetsuki.events.impl.render.EventRender3D;
import dev.wetsuki.manager.Manager;
import dev.wetsuki.modules.Function;
import dev.wetsuki.modules.FunctionAnnotation;
import dev.wetsuki.modules.Type;
import dev.wetsuki.modules.setting.BooleanSetting;
import dev.wetsuki.modules.setting.SliderSetting;
import dev.wetsuki.util.color.ColorUtil;
import dev.wetsuki.util.render.RenderUtil;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

@SuppressWarnings("All")
@FunctionAnnotation(name = "ElytraTarget", desc = "Targets the nearest flying player and predicts the spot in front of him", type = Type.Move)
public class ElytraTarget extends Function {

    public final BooleanSetting drawBox = new BooleanSetting("Draw Box", true);
    public final SliderSetting distanceInFront = new SliderSetting("DistanceInFront", 2.5, 0.0, 20.0, 0.5);
    public final SliderSetting distance = new SliderSetting("Distance", 100.0, 10.0, 400.0, 5.0);
    public final BooleanSetting swordPreHit = new BooleanSetting("Sword Pre-Hit", true);
    public final SliderSetting preHitDelay = new SliderSetting("Pre-Hit Delay", 1000, 200, 5000, 50, () -> swordPreHit.get());

    // Box color (R, G, B, A) - same as the original module
    private static final int BOX_COLOR = ColorUtil.rgba(0, 0, 255, 120);

    public static PlayerEntity target = null;
    public static Vec3d predictedPos = null;

    private World lastWorld;
    private long lastHitTime = 0L;

    public ElytraTarget() {
        addSettings(drawBox, distanceInFront, distance, swordPreHit, preHitDelay);
    }

    @Override
    protected void onDisable() {
        target = null;
        predictedPos = null;
    }

    @Override
    public void onEvent(Event event) {
        if (event instanceof EventUpdate) {
            onTick();
        } else if (event instanceof EventRender3D) {
            onRender3D();
        }
    }

    private void onTick() {
        if (mc.player == null || mc.world == null) {
            target = null;
            predictedPos = null;
            return;
        }

        if (mc.world != lastWorld) {
            target = null;
            lastWorld = mc.world;
        }

        target = findTarget();
        if (target != null) {
            predictedPos = getFrontPosition(target, distanceInFront.get().doubleValue());
            useSwordHitIfNeeded();
        } else {
            predictedPos = null;
        }
    }

    private PlayerEntity findTarget() {
        PlayerEntity best = null;
        float closest = distance.get().floatValue();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            if (!player.isAlive() || player.isRemoved() || player.getHealth() <= 0.0f) continue;
            if (Manager.FRIEND_MANAGER.isFriend(player.getName().getString())) continue;

            boolean flying = player.isGliding()
                    || (player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA) && !player.isOnGround());
            if (!flying) continue;

            float d = mc.player.distanceTo(player);
            if (d < closest) {
                best = player;
                closest = d;
            }
        }
        return best;
    }

    private void onRender3D() {
        if (target == null || predictedPos == null || !drawBox.get()) return;

        Box targetBox = target.getBoundingBox();
        double w = (targetBox.maxX - targetBox.minX) / 2.0;
        double h = targetBox.maxY - targetBox.minY;

        Box box = new Box(
                predictedPos.x - w, predictedPos.y, predictedPos.z - w,
                predictedPos.x + w, predictedPos.y + h, predictedPos.z + w
        );

        RenderUtil.render3D.drawFilledBox(box, BOX_COLOR);
        RenderUtil.render3D.drawHoleOutline(box, BOX_COLOR, 2.0f);
    }

    private Vec3d getFrontPosition(PlayerEntity entity, double distanceInFront) {
        Vec3d targetPos = entity.getPos();
        Vec3d look = entity.getRotationVector();
        Vec3d horizontalLook = new Vec3d(look.x, 0.0, look.z).normalize();
        Vec3d front = targetPos.add(horizontalLook.multiply(distanceInFront));
        return new Vec3d(front.x, targetPos.y + 1.2, front.z);
    }

    private int findHotbarSword() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isIn(ItemTags.SWORDS)) return i;
        }
        return -1;
    }

    private void useSwordHitIfNeeded() {
        if (mc.player == null || mc.world == null || target == null || !swordPreHit.get()) return;
        if (!mc.player.isGliding()) return;
        if (System.currentTimeMillis() - lastHitTime < preHitDelay.get().longValue()) return;

        int swordSlot = findHotbarSword();
        if (swordSlot == -1) return;

        int oldSlot = mc.player.getInventory().selectedSlot;

        if (swordSlot != oldSlot) {
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(swordSlot));
        }
        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
        if (swordSlot != oldSlot) {
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(oldSlot));
        }

        lastHitTime = System.currentTimeMillis();
    }
}
