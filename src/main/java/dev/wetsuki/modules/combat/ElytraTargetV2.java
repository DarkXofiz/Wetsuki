package dev.wetsuki.modules.combat;

import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import dev.wetsuki.events.Event;
import dev.wetsuki.events.impl.EventUpdate;
import dev.wetsuki.manager.Manager;
import dev.wetsuki.modules.Function;
import dev.wetsuki.modules.FunctionAnnotation;
import dev.wetsuki.modules.Type;
import dev.wetsuki.util.player.InventoryUtil;
import dev.wetsuki.util.player.TimerUtil;

/**
 * ElytraTarget V2 - Aethereal "Elytra Target" modulunun Wetsuki'ye uyarlanmis hali.
 * AttackAuraV2 acikken, elytra ile ucarken dusmana nisan alir ve
 * ikinci elden (offhand) havai fisek ile hizlanir.
 */
@SuppressWarnings("All")
@FunctionAnnotation(name = "ElytraTargetV2", desc = "Aims at the enemy while flying with elytra and boosts with a firework from the offhand (V2)", type = Type.Move)
public class ElytraTargetV2 extends Function {

    private final TimerUtil fireworkTimer = new TimerUtil();
    private final TimerUtil moveTimer = new TimerUtil();

    @Override
    public void onEvent(Event event) {
        if (!(event instanceof EventUpdate)) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        AttackAuraV2 aura = Manager.FUNCTION_MANAGER.attackAuraV2;
        if (aura == null || !aura.state || !mc.player.isGliding()) return;

        if (mc.player.getOffHandStack().getItem() != Items.FIREWORK_ROCKET) {
            if (moveTimer.hasTimeElapsed(250, true)) {
                InventoryUtil.moveToOffhand(Items.FIREWORK_ROCKET);
            }
        } else if ((fireworkTimer.hasTimeElapsed(150) && mc.player.getVelocity().length() < 1.5)
                || aura.attackCooldown == 1) {
            mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
            fireworkTimer.reset();
        }

        LivingEntity target = aura.getTarget();
        if (target == null) return;
        // Aura bu tick yakin vurus icin daha yuksek oncelikli nisan aldiysa ona dokunma.
        if (aura.elytraAimTick == mc.player.age) return;

        Vec3d eye = mc.player.getEyePos();
        Vec3d enemy = target.getBoundingBox().getCenter();
        double dx = enemy.x - eye.x;
        double dz = enemy.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double nx = horizontal == 0.0 ? 0.0 : dx / horizontal;
        double nz = horizontal == 0.0 ? 0.0 : dz / horizontal;
        double lift = Math.max(0.0, 3.0 - groundDistance());
        Vec3d aim = new Vec3d(enemy.x + nx * 4.0, enemy.y + lift, enemy.z + nz * 4.0);

        double ax = aim.x - eye.x;
        double ay = aim.y - eye.y;
        double az = aim.z - eye.z;
        float aimYaw = (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(az, ax)) - 90.0);
        float aimPitch = (float) (-Math.toDegrees(Math.atan2(ay, Math.hypot(ax, az))));

        float curYaw = Manager.ROTATION.getYaw();
        float curPitch = Manager.ROTATION.getPitch();
        int cd = aura.attackCooldown;

        float yaw = curYaw + MathHelper.wrapDegrees(aimYaw - curYaw);
        float pitchTarget = cd <= 3 ? 0.0f : aimPitch;
        float pitchFactor = cd <= 3 ? 1.0f : MathHelper.clamp(cd / 10.0f, 0.0f, 1.0f);
        float pitch = curPitch + MathHelper.wrapDegrees(pitchTarget - curPitch) * pitchFactor;

        aura.aim(yaw, pitch, 180.0f);
    }

    /** Oyuncunun altindaki zemine olan mesafe (en fazla 2 blok bakilir). */
    private double groundDistance() {
        Vec3d start = mc.player.getPos();
        Vec3d end = start.subtract(0.0, 2.0, 0.0);
        BlockHitResult hit = mc.world.raycast(new RaycastContext(start, end,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
        if (hit.getType() == HitResult.Type.MISS) {
            return 2.0;
        }
        return start.getY() - hit.getPos().y;
    }
}
