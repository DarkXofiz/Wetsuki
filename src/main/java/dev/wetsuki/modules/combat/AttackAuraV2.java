package dev.wetsuki.modules.combat;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.AmbientEntity;
import net.minecraft.entity.mob.FlyingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.entity.passive.AllayEntity;
import net.minecraft.entity.passive.GolemEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.Entity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import dev.wetsuki.events.Event;
import dev.wetsuki.events.impl.EventUpdate;
import dev.wetsuki.events.impl.input.EventKeyBoard;
import dev.wetsuki.events.impl.move.EventMotion;
import dev.wetsuki.manager.Manager;
import dev.wetsuki.mixin.iface.ClientPlayerEntityAccessor;
import dev.wetsuki.modules.Function;
import dev.wetsuki.modules.FunctionAnnotation;
import dev.wetsuki.modules.Type;
import dev.wetsuki.modules.render.littlePet.GhostWolfEntity;
import dev.wetsuki.modules.setting.BooleanSetting;
import dev.wetsuki.modules.setting.ModeSetting;
import dev.wetsuki.modules.setting.MultiSetting;
import dev.wetsuki.modules.setting.SliderSetting;
import dev.wetsuki.util.math.MathUtil;
import dev.wetsuki.util.move.MoveUtil;
import dev.wetsuki.util.player.AuraUtil;
import dev.wetsuki.util.player.GCDUtil;
import dev.wetsuki.util.player.ServerUtil;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * AttackAura V2 - Aethereal "Aura" modulunun Wetsuki'ye uyarlanmis hali.
 * Orijinal AttackAura'ya dokunmaz, ayri bir modul olarak calisir.
 */
@SuppressWarnings("All")
@FunctionAnnotation(name = "AttackAuraV2", keywords = {"KillAura", "Aura", "FunTime"}, desc = "Automatically attacks targets around you (V2)", type = Type.Combat)
public class AttackAuraV2 extends Function {

    private final ModeSetting rotationType = new ModeSetting("Rotation type", "FunTime", "FunTime", "FunTime FOV", "Legit");
    private final MultiSetting targetSettings = new MultiSetting(
            "Targets",
            Arrays.asList("Naked", "Players"),
            new String[]{"Naked", "Monsters", "Animals", "Friends", "Players"}
    );
    private final SliderSetting attackDistance = new SliderSetting("Attack distance", 3.0, 0.1, 6.0, 0.1);
    private final SliderSetting extraReach = new SliderSetting("Extra distance", 0.5, 0.1, 3.0, 0.1);
    private final BooleanSetting onlyCrits = new BooleanSetting("Only crits", true);
    private final BooleanSetting adaptiveHits = new BooleanSetting("Adaptive hits", true, () -> onlyCrits.get());
    private final MultiSetting dontHitWhen = new MultiSetting(
            "Don't hit when",
            Arrays.asList("Using item", "Open container", "Enemy behind wall"),
            new String[]{"Using item", "Open container", "Enemy behind wall"}
    );
    private final BooleanSetting shieldBreaking = new BooleanSetting("Shield breaking", true);
    private final BooleanSetting smartSprint = new BooleanSetting("Smart sprint", false);
    private final ModeSetting targetPriority = new ModeSetting("Target priority", "Crosshair", "Crosshair", "Distance", "Health");
    private final ModeSetting movementCorrection = new ModeSetting("Movement correction", "Focus", "Focus", "Free");

    public LivingEntity target = null;
    public int attackCooldown = 0;
    /** ElytraTargetV2 ile cakismamak icin: yuksek oncelikli nisan alinan tick. */
    public int elytraAimTick = -1000;

    private final float[] pitchHistory = new float[30];
    private boolean randomDirection = false;

    private float sprintBlock = -1.0f;
    private float hitCount = 0.0f;
    private float hitGate = 0.0f;
    private float hitTimer = -1.0f;
    private float rotLock = 2.0f;

    private int swapInvSlot = -1;
    private int swapHotbar = -1;
    private int savedSelected = -1;

    public AttackAuraV2() {
        addSettings(
                attackDistance, extraReach, rotationType, movementCorrection, targetPriority,
                targetSettings, dontHitWhen, onlyCrits, adaptiveHits, shieldBreaking, smartSprint
        );
    }

    public LivingEntity getTarget() {
        return target;
    }

    /** Mixin'lerin rotasyon duzeltmesi uygulamasi gereken durum. */
    public boolean isActive() {
        return state && target != null;
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        rotLock = 2.0f;
        attackCooldown = 0;
        target = null;
        if (mc.player != null) {
            Arrays.fill(pitchHistory, mc.player.getPitch());
            Manager.ROTATION.set(mc.player.getYaw(), mc.player.getPitch());
        }
    }

    @Override
    protected void onDisable() {
        if (mc.player != null && mc.interactionManager != null) {
            restoreSelectedSlot(true);
            if (target != null) {
                Manager.ROTATION.smoothReturn(350);
            } else {
                Manager.ROTATION.set(mc.player.getYaw(), mc.player.getPitch());
            }
        }
        sprintBlock = -1.0f;
        hitCount = 0.0f;
        hitGate = 0.0f;
        hitTimer = -1.0f;
        rotLock = 1.0f;
        attackCooldown = 0;
        target = null;
        super.onDisable();
    }

    @Override
    public void onEvent(Event event) {
        if (mc.player == null || mc.world == null) return;

        if (event instanceof EventKeyBoard e) {
            onInput(e);
        } else if (event instanceof EventUpdate) {
            if (mc.player.isDead()) {
                target = null;
                return;
            }
            onTick();
        } else if (event instanceof EventMotion motion) {
            motion.setYaw(Manager.ROTATION.getYaw());
            motion.setPitch(Manager.ROTATION.getPitch());
        }
    }

    // ------------------------------------------------------------------ input

    private void onInput(EventKeyBoard e) {
        if (target != null && movementCorrection.get().equals("Free")) {
            MoveUtil.fixMovement(e, Manager.ROTATION.getYaw());
        }
        if (sprintBlock > 0.0f && target != null && inRange(target, attackDistance.get().doubleValue())) {
            e.setMovementForward(0.0f);
            e.setMovementStrafe(0.0f);
            sprintBlock -= 1.0f;
        }
    }

    // ------------------------------------------------------------------- tick

    private void onTick() {
        if (attackCooldown < 100) attackCooldown++;

        if (!isValidTarget(target)) {
            LivingEntity prev = target;
            boolean fresh = !isValidTarget(prev);
            target = (fresh && dontHitWhen.get("Enemy behind wall"))
                    ? findTargetWithParam(false).or(() -> findTargetWithParam(true)).orElse(null)
                    : findTargetWithParam(true).orElse(null);
            if (target != prev && target != null) {
                Arrays.fill(pitchHistory, mc.player.getPitch());
            }
        }

        restoreSelectedSlot(false);

        if (target != null) {
            performAttack();
            if (target == null) return;
            rotateToTarget();
            if (target == null) return;
            performAttack();
            return;
        }

        rotLock = 1.0f;
        Manager.ROTATION.set(mc.player.getYaw(), mc.player.getPitch());
    }

    // ----------------------------------------------------------------- attack

    private void restoreSelectedSlot(boolean force) {
        if (!force && shieldBreaking.get() && target != null && target.isBlocking()) {
            return;
        }
        if (savedSelected != -1) {
            mc.player.getInventory().selectedSlot = savedSelected;
            savedSelected = -1;
        }
        if (swapInvSlot != -1) {
            if (mc.player.currentScreenHandler == mc.player.playerScreenHandler) {
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, swapInvSlot, swapHotbar, SlotActionType.SWAP, mc.player);
            }
            swapInvSlot = -1;
            swapHotbar = -1;
        }
    }

    private int findAxeSlot(int from, int to) {
        for (int i = from; i < to; i++) {
            if (mc.player.getInventory().getStack(i).getItem() instanceof AxeItem) {
                return i;
            }
        }
        return -1;
    }

    private void performAttack() {
        if (target == null) return;
        boolean throughWalls = !dontHitWhen.get("Enemy behind wall");
        if (!rayHits(Manager.ROTATION.getYaw(), Manager.ROTATION.getPitch(), attackDistance.get().doubleValue(), target, throughWalls)) {
            return;
        }

        if (shieldBreaking.get() && target.isBlocking()) {
            if (mc.player.getMainHandStack().getItem() instanceof AxeItem) {
                if (mc.player.getAttackCooldownProgress(0.5f) >= 0.9f) {
                    mc.interactionManager.attackEntity(mc.player, target);
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
            } else {
                int hotbar = findAxeSlot(0, 9);
                if (hotbar != -1) {
                    if (mc.player.getInventory().selectedSlot != hotbar) {
                        if (savedSelected == -1) savedSelected = mc.player.getInventory().selectedSlot;
                        mc.player.getInventory().selectedSlot = hotbar;
                    }
                } else {
                    int inventory = findAxeSlot(9, 36);
                    if (inventory != -1 && swapInvSlot == -1
                            && mc.player.currentScreenHandler == mc.player.playerScreenHandler) {
                        swapInvSlot = inventory;
                        swapHotbar = mc.player.getInventory().selectedSlot;
                        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, inventory, swapHotbar, SlotActionType.SWAP, mc.player);
                    }
                }
            }
        }

        if (!canAttack()) return;

        ClientPlayerEntityAccessor acc = (ClientPlayerEntityAccessor) mc.player;
        if (acc.getLastSprinting()
                && !mc.player.isTouchingWater() && !mc.player.isInLava() && !mc.player.isSwimming()
                && !mc.player.isOnGround()) {
            if (!smartSprint.get()) {
                acc.setLastSprinting(false);
                mc.player.setSprinting(false);
                mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
                sprintBlock = 1.0f;
            } else {
                sprintBlock = 1.0f;
                return;
            }
        }

        hitGate = 0.0f;
        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
        attackCooldown = 0;
        hitTimer = MathUtil.random(8.0f, 10.0f);
        hitCount += 1.0f;
    }

    public boolean canAttack() {
        if (target == null) return false;

        if (dontHitWhen.get("Using item") && mc.player.isUsingItem()
                && mc.player.getItemUseTimeLeft() > 0 && attackCooldown >= 8) {
            attackCooldown = 8;
            return false;
        }
        if ((dontHitWhen.get("Open container") && mc.currentScreen instanceof HandledScreen)
                || !inRange(target, attackDistance.get().doubleValue())) {
            return false;
        }

        if (mc.player.fallDistance > 1.5f) {
            if (mc.player.getItemCooldownManager().isCoolingDown(mc.player.getMainHandStack()) || attackCooldown <= 3) {
                return false;
            }
        } else if (mc.player.getAttackCooldownProgress(0.5f) < 0.9f || attackCooldown < 10) {
            return false;
        }

        boolean critReady = critReady();
        boolean critsRequired = onlyCrits.get() && !critRestricted();
        return critReady
                || (adaptiveHits.get() && mc.player.isOnGround() && !mc.options.jumpKey.isPressed())
                || !critsRequired;
    }

    private boolean critReady() {
        return !mc.player.isOnGround() && mc.player.fallDistance > 0.0f && !critRestricted();
    }

    /** Kritik vurusun mumkun olmadigi durumlar (suda, tirmanirken vb.). */
    private boolean critRestricted() {
        return mc.player.hasStatusEffect(StatusEffects.BLINDNESS)
                || mc.player.hasStatusEffect(StatusEffects.LEVITATION)
                || mc.player.hasStatusEffect(StatusEffects.SLOW_FALLING)
                || mc.player.isInLava()
                || mc.player.inPowderSnow
                || mc.player.isClimbing()
                || mc.player.hasVehicle()
                || mc.player.getAbilities().flying
                || mc.player.isGliding()
                || mc.player.isTouchingWater()
                || MoveUtil.isInWeb();
    }

    // --------------------------------------------------------------- targeting

    private boolean elytraFlight() {
        ElytraTargetV2 ely = Manager.FUNCTION_MANAGER.elytraTargetV2;
        return ely != null && ely.state && mc.player.isGliding();
    }

    private boolean isEntityReachable(LivingEntity entity) {
        if (elytraFlight()) return true;
        double reach = attackDistance.get().doubleValue() + extraReach.get().doubleValue()
                + mc.player.getVelocity().length() * 3.0;
        return inRange(entity, reach);
    }

    private Optional<LivingEntity> findTargetWithParam(boolean allowBehindWalls) {
        if (mc.world == null || mc.player == null) return Optional.empty();

        Vec3d eye = mc.player.getEyePos();
        double reach = attackDistance.get().doubleValue() + extraReach.get().doubleValue();

        Comparator<LivingEntity> order;
        switch (targetPriority.get()) {
            case "Distance":
                order = Comparator.comparingDouble(AuraUtil::getDistance);
                break;
            case "Health":
                order = Comparator.comparingDouble(LivingEntity::getHealth);
                break;
            default:
                Vec3d look = Vec3d.fromPolar(mc.player.getPitch(), mc.player.getYaw());
                order = Comparator.comparingDouble(e -> Math.acos(MathHelper.clamp(
                        look.dotProduct(e.getBoundingBox().getCenter().subtract(eye).normalize()), -1.0, 1.0)));
                break;
        }

        Stream<LivingEntity> stream = Manager.SYNC_MANAGER.getEntities().stream()
                .filter(e -> e instanceof LivingEntity)
                .map(e -> (LivingEntity) e)
                .filter(this::isValidTarget);
        if (!allowBehindWalls) {
            stream = stream.filter(e -> canSee(eye, e, reach));
        }
        return stream.min(order);
    }

    private boolean isValidTarget(LivingEntity entity) {
        if (entity == null || entity == mc.player || !entity.isAlive() || !isEntityReachable(entity)) {
            return false;
        }
        if (Manager.FUNCTION_MANAGER.antiBot.check(entity)) return false;
        if (Manager.FUNCTION_MANAGER.littleSnickers.state && entity instanceof GhostWolfEntity) return false;

        if (entity instanceof PlayerEntity) {
            if (!targetSettings.get("Players")) return false;
            if (Manager.FRIEND_MANAGER.isFriend(entity.getName().getString())) {
                return targetSettings.get("Friends");
            }
            return hasArmor(entity) || targetSettings.get("Naked");
        }
        if (entity instanceof HostileEntity || entity instanceof SlimeEntity
                || entity instanceof FlyingEntity || entity instanceof EnderDragonEntity) {
            return targetSettings.get("Monsters");
        }
        if (entity instanceof PassiveEntity || entity instanceof GolemEntity
                || entity instanceof AllayEntity || entity instanceof AmbientEntity) {
            return targetSettings.get("Animals");
        }
        return false;
    }

    private boolean hasArmor(LivingEntity entity) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = entity.getEquippedStack(slot);
            if (!stack.isEmpty() && !stack.isOf(Items.ELYTRA)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- rotation

    private void rotateToTarget() {
        Vec3d eye = mc.player.getEyePos();
        LivingEntity t = target;
        double reach = attackDistance.get().doubleValue();
        boolean funTime = rotationType.get().startsWith("FunTime");
        boolean throughWalls = funTime || !dontHitWhen.get("Enemy behind wall");

        Vec3d aimVec = aimVector(eye, t, reach, throughWalls);
        boolean hasAim = aimVec != Vec3d.ZERO;
        float yawToTarget = !hasAim ? mc.player.getYaw()
                : (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(aimVec.z, aimVec.x)) - 90.0);
        float pitchToTarget = !hasAim ? mc.player.getPitch()
                : (float) (-Math.toDegrees(Math.atan2(aimVec.y, Math.hypot(aimVec.x, aimVec.z))));

        System.arraycopy(pitchHistory, 0, pitchHistory, 1, 29);
        pitchHistory[0] = pitchToTarget;

        float hp = ServerUtil.getHealth(t) * t.getMaxHealth();
        if (attackCooldown >= 2 && ((hp > 6.0f || hitCount > 43.0f) && hitCount >= 33.0f
                && ((attackCooldown == 4 || Math.random() > 0.5)
                && (!randomDirection || !rayHits(Manager.ROTATION.getYaw(), Manager.ROTATION.getPitch(), 3.0, t, false))))) {
            mc.player.swingHand(Hand.MAIN_HAND);
            if (Math.random() > 0.5) randomDirection = !randomDirection;
            hitCount = (int) MathUtil.random(-10.0f, 10.0f);
        }

        boolean skip = (dontHitWhen.get("Using item") && mc.player.isUsingItem()
                && mc.player.getItemUseTimeLeft() > 0 && attackCooldown >= 8)
                || (dontHitWhen.get("Open container") && mc.currentScreen instanceof HandledScreen);
        boolean attackImminent = !skip && attackCooldown >= 9 && inRange(t, attackDistance.get().doubleValue());

        if ((hitGate <= 0.0f && canAttack()) || attackImminent) {
            hitGate = 1.0f;
            if (!mc.player.isTouchingWater() && smartSprint.get() && !mc.player.isOnGround()) {
                sprintBlock = 1.0f;
            }
        }

        if (elytraFlight()) {
            if (canAttack() && inRange(t, 3.0)) {
                aim(yawToTarget, pitchToTarget, 180.0f);
                elytraAimTick = mc.player.age;
            }
        } else {
            switch (rotationType.get()) {
                case "FunTime":
                case "FunTime FOV":
                    applyFunTimeSmoothing(yawToTarget, pitchToTarget);
                    break;
                case "Legit":
                    applyLegitSmoothing(yawToTarget, pitchToTarget);
                    break;
                default:
                    break;
            }
        }

        hitGate -= 1.0f;
        hitTimer -= 1.0f;
        rotLock -= 1.0f;
    }

    private void applyFunTimeSmoothing(float yawToTarget, float pitchToTarget) {
        float time = mc.player.age + mc.getRenderTickCounter().getTickDelta(false);
        float smoothW = (float) ((Math.sin(time * 0.4) * 3.0) + (Math.sin(time * 0.95 + 1.4) * 2.0));
        float smoothH = (float) ((Math.cos(time * 0.5 + 0.7) * 0.5) + (Math.cos(time * 0.78 + 3.1) * 1.5));

        float curYaw = Manager.ROTATION.getYaw();
        float curPitch = Manager.ROTATION.getPitch();
        double dist = attackDistance.get().doubleValue();

        float finalPitch = lerpAngle(curPitch,
                pitchHistory[MathHelper.clamp(10 - attackCooldown, 0, 29)] + (smoothH * 1.5f),
                MathUtil.random(0.1f, 0.5f));
        float finalYaw = lerpAngle(curYaw, yawToTarget + smoothW, MathUtil.random(0.1f, 0.4f));

        if (hitGate >= 0.0f) {
            if (!rayHits(curYaw, curPitch, dist, target, true) && rotLock <= 0.0f) {
                finalYaw = yawToTarget;
            }
            if (!rayHits(yawToTarget, finalPitch, dist, target, true) && rotLock <= 0.0f) {
                finalPitch = pitchToTarget;
            }
            if (!rayHits(curYaw + smoothW, curPitch + smoothH, dist, target, true)
                    && rayHits(curYaw, curPitch, dist, target, true)) {
                smoothW = MathHelper.clamp(smoothW, -0.05f, 0.05f);
                smoothH = MathHelper.clamp(smoothH, -0.05f, 0.05f);
            }
        }
        if (attackCooldown <= 4 && hitCount % 2.0f == 0.0f) {
            finalYaw = curYaw;
        }

        float pitchBase = rotationType.get().equals("FunTime") ? finalPitch : mc.player.getPitch();
        aim(finalYaw + smoothW, pitchBase + smoothH, 220.0f);
    }

    private void applyLegitSmoothing(float yawToTarget, float pitchToTarget) {
        float t = mc.player.age + mc.getRenderTickCounter().getTickDelta(false);
        float sin = (float) (((Math.sin(t * 0.31f) * 0.5) + (Math.sin(t * 1.7f + 2.6f) * 0.2)) * 8.0) / 4.0f;
        float smoothW = sin;
        float smoothH = sin;

        float curYaw = Manager.ROTATION.getYaw();
        float curPitch = Manager.ROTATION.getPitch();
        double dist = attackDistance.get().doubleValue();

        float finalYaw = lerpAngle(curYaw, yawToTarget, MathUtil.random(0.2f, 0.35f));
        float finalPitch = lerpAngle(curPitch, pitchToTarget, MathUtil.random(0.15f, 0.25f));

        if (hitGate >= 0.0f) {
            finalPitch = lerpAngle(curPitch, pitchToTarget, 0.35f);
            smoothH /= 3.0f;
            smoothW /= 3.0f;
            if (!rayHits(curYaw, curPitch, dist, target, true)) {
                finalYaw = lerpAngle(curYaw, yawToTarget, MathUtil.random(0.7f, 1.0f));
            }
        }
        if (!rayHits(finalYaw + smoothW, finalPitch + smoothH, dist, target, true)
                && rayHits(yawToTarget, pitchToTarget, dist, target, true)) {
            smoothW = MathHelper.clamp(smoothW, -0.15f, 0.15f);
            smoothH = MathHelper.clamp(smoothH, -0.15f, 0.15f);
        }
        if (hitTimer >= 0.0f) {
            smoothW *= 8.0f;
            if (attackCooldown >= 1 && hitCount % 5.0f == 0.0f) {
                finalPitch = lerpAngle(curPitch, -pitchToTarget, 0.05f);
            }
        }
        aim(finalYaw + smoothW, finalPitch + smoothH, 180.0f);
    }

    /** Rotasyonu (GCD uygulayarak) Manager.ROTATION'a yazar. ElytraTargetV2 de kullanir. */
    public void aim(float yaw, float pitch, float maxStep) {
        float cy = Manager.ROTATION.getYaw();
        float cp = Manager.ROTATION.getPitch();
        float dy = MathHelper.clamp(MathHelper.wrapDegrees(yaw - cy), -maxStep, maxStep);
        float dp = MathHelper.clamp(pitch - cp, -maxStep, maxStep);
        float gcd = GCDUtil.getGCDValue();
        if (gcd > 0.0f) {
            dy -= dy % gcd;
            dp -= dp % gcd;
        }
        Manager.ROTATION.set(cy + dy, MathHelper.clamp(cp + dp, -90.0f, 90.0f));
    }

    private static float lerpAngle(float current, float target, float factor) {
        return current + MathHelper.wrapDegrees(target - current) * factor;
    }

    // ------------------------------------------------------------------ helpers

    private boolean inRange(LivingEntity e, double dist) {
        return AuraUtil.getDistance(e) <= dist;
    }

    private boolean canSeePoint(Vec3d from, Vec3d to) {
        return mc.world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, mc.player)).getType() == HitResult.Type.MISS;
    }

    private boolean canSee(Vec3d eye, LivingEntity e, double reach) {
        Vec3d closest = MathUtil.getClosestVec(eye, e.getBoundingBox());
        return eye.distanceTo(closest) <= reach && canSeePoint(eye, closest);
    }

    private boolean rayHits(float yaw, float pitch, double dist, LivingEntity t, boolean throughWalls) {
        if (t == null) return false;
        Vec3d eye = mc.player.getEyePos();
        Vec3d end = eye.add(Vec3d.fromPolar(pitch, yaw).multiply(dist));
        Box box = t.getBoundingBox();
        Optional<Vec3d> hit = box.contains(eye) ? Optional.of(eye) : box.raycast(eye, end);
        if (hit.isEmpty()) return false;
        return throughWalls || canSeePoint(eye, hit.get());
    }

    /** Gozden hedefe giden vektor; uygun nokta bulunamazsa Vec3d.ZERO. */
    private Vec3d aimVector(Vec3d eye, LivingEntity t, double reach, boolean throughWalls) {
        Box box = t.getBoundingBox();
        double h = box.getLengthY();
        Vec3d center = box.getCenter();
        float curPitch = Manager.ROTATION.getPitch();

        Vec3d best = null;
        float bestDiff = Float.MAX_VALUE;
        for (double f : new double[]{0.9, 0.7, 0.5, 0.3}) {
            Vec3d p = new Vec3d(center.x, box.minY + h * f, center.z);
            if (!throughWalls && !canSeePoint(eye, p)) continue;
            double dx = p.x - eye.x, dy = p.y - eye.y, dz = p.z - eye.z;
            float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
            float diff = Math.abs(pitch - curPitch);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = p;
            }
        }
        if (best == null) {
            if (!throughWalls) return Vec3d.ZERO;
            best = center;
        }
        return best.subtract(eye);
    }
}
