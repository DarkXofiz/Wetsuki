package dev.wetsuki.modules.combat;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
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
import dev.wetsuki.modules.movement.ElytraTarget;
import dev.wetsuki.modules.render.littlePet.GhostWolfEntity;
import dev.wetsuki.modules.setting.BooleanSetting;
import dev.wetsuki.modules.setting.ModeSetting;
import dev.wetsuki.modules.setting.MultiSetting;
import dev.wetsuki.modules.setting.SliderSetting;
import dev.wetsuki.util.math.MathUtil;
import dev.wetsuki.util.math.RayTraceUtil;
import dev.wetsuki.util.move.MoveUtil;
import dev.wetsuki.util.player.AuraUtil;
import dev.wetsuki.util.player.GCDUtil;
import dev.wetsuki.util.player.InventoryUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@SuppressWarnings("All")
@FunctionAnnotation(name = "AttackAura", keywords = {"Smash", "Pound", "KillAura", "Aura"}, desc = "Automatically attacks targets near you", type = Type.Combat)
public class AttackAura extends Function {

    private final ModeSetting rotationType = new ModeSetting("Aim type", "FunTime", "FunTime", "FunTime FOV", "Legit");

    private final MultiSetting targetSettings = new MultiSetting(
            "Attack targets",
            Arrays.asList("Without armor", "Players"),
            new String[]{"Without armor", "Hostile mobs", "Animals", "Friends", "Players"}
    );

    private final SliderSetting attackDistance = new SliderSetting("Attack distance", 3.0f, 0.1f, 6.0f, 0.1f);
    private final SliderSetting extraReach = new SliderSetting("Extra distance", 0.5f, 0.1f, 3.0f, 0.1f);
    private final SliderSetting elytraDistance = new SliderSetting("Elytra radius", 40f, 0f, 80f, 1f);

    private final BooleanSetting onlyCrits = new BooleanSetting("Critical hits only", true);
    private final BooleanSetting adaptiveHits = new BooleanSetting("Adaptive hits", true, () -> onlyCrits.get());

    private final MultiSetting dontHitWhen = new MultiSetting(
            "Don't hit when",
            Arrays.asList("Using an item", "Container open", "Enemy behind a wall"),
            new String[]{"Using an item", "Container open", "Enemy behind a wall"}
    );

    private final BooleanSetting shieldBreaking = new BooleanSetting("Shield break", true);
    private final BooleanSetting smartSprint = new BooleanSetting("Smart sprint", false);
    private final ModeSetting targetPriority = new ModeSetting("Target priority", "Crosshair", "Crosshair", "Distance", "HP", "Armor");

    public final BooleanSetting correction = new BooleanSetting("Correction", true);
    public final ModeSetting correctionType = new ModeSetting(() -> correction.get(), "Correction type", "Focus", "Focus", "Free");

    public LivingEntity target = null;
    public int attackCooldown = 0;

    private final float[] pitchHistory = new float[30];
    private int sprintFreeze = 0;
    private int hitCounter = 0;
    private int attackWindow = 0;
    private int postAttack = -1;
    private int acquireTicks = 2;
    private boolean randomDirection = false;

    public AttackAura() {
        addSettings(
                attackDistance,
                extraReach,
                elytraDistance,
                rotationType,
                correction,
                correctionType,
                targetPriority,
                targetSettings,
                dontHitWhen,
                onlyCrits,
                adaptiveHits,
                shieldBreaking,
                smartSprint
        );
    }

    public double getAttackRadius() {
        return attackDistance.get().doubleValue();
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        acquireTicks = 2;
        sprintFreeze = 0;
        hitCounter = 0;
        attackWindow = 0;
        postAttack = -1;
        attackCooldown = 0;
        target = null;
        if (mc.player != null) {
            Manager.ROTATION.set(mc.player.getYaw(), mc.player.getPitch());
            Arrays.fill(pitchHistory, mc.player.getPitch());
        }
    }

    @Override
    protected void onDisable() {
        if (target != null && mc.player != null) {
            Manager.ROTATION.smoothReturn(350);
        }
        target = null;
        sprintFreeze = 0;
        attackWindow = 0;
        postAttack = -1;
        acquireTicks = 1;
        attackCooldown = 0;
        super.onDisable();
    }

    // ------------------------------------------------------------------ events

    @Override
    public void onEvent(Event event) {
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null || player.isDead()) {
            target = null;
            sprintFreeze = 0;
            return;
        }

        if (event instanceof EventKeyBoard e) {
            handleInput(e);
        } else if (event instanceof EventUpdate) {
            handleUpdate();
        } else if (event instanceof EventMotion motion) {
            motion.setYaw(Manager.ROTATION.getYaw());
            motion.setPitch(Manager.ROTATION.getPitch());
        }
    }

    private void handleInput(EventKeyBoard e) {
        if (target == null) return;

        if (correction.get() && correctionType.is("Free")) {
            MoveUtil.fixMovement(e, Manager.ROTATION.getYaw());
        }

        if (sprintFreeze > 0 && eyeDistance(target) <= attackDistance.get().doubleValue()) {
            e.setMovementForward(0.0f);
            e.setMovementStrafe(0.0f);
            sprintFreeze--;
        }
    }

    private void handleUpdate() {
        attackCooldown = Math.min(attackCooldown + 1, 1000);

        if (Manager.FUNCTION_MANAGER.autoPotion.isActivePotion) {
            return;
        }

        if (!isValidTarget(target)) {
            LivingEntity prev = target;
            target = dontHitWhen.get("Enemy behind a wall")
                    ? findTargetWithParam(false).or(() -> findTargetWithParam(true)).orElse(null)
                    : findTargetWithParam(true).orElse(null);
            if (target != null && target != prev) {
                Arrays.fill(pitchHistory, Manager.ROTATION.getPitch());
            }
        }

        if (target == null) {
            Manager.ROTATION.set(mc.player.getYaw(), mc.player.getPitch());
            acquireTicks = 1;
            return;
        }

        if (handleElytraRotation(target)) {
            performAttack(true);
            return;
        }

        performAttack(false);
        if (target == null) return;
        rotateToTarget();
        performAttack(false);
    }

    // ------------------------------------------------------------------ attack

    private void performAttack(boolean elytraMode) {
        LivingEntity t = target;
        if (t == null) return;

        if (!elytraMode && !aimHits(curYaw(), curPitch(), attackDistance.get().doubleValue(), t,
                !dontHitWhen.get("Enemy behind a wall"))) {
            return;
        }

        if (!canAttack()) return;

        boolean skip = false;

        if (((ClientPlayerEntityAccessor) mc.player).getLastSprinting()
                && !mc.player.isTouchingWater() && !mc.player.isInLava() && !mc.player.isSwimming()
                && !mc.player.isOnGround() && !skip) {
            if (!smartSprint.get()) {
                mc.player.setSprinting(false);
                mc.player.networkHandler.sendPacket(
                        new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
                sprintFreeze = 1;
            } else {
                sprintFreeze = 1;
                return;
            }
        }

        if (mc.interactionManager == null) return;

        boolean done = false;
        if (shieldBreaking.get() && t.isBlocking() && !(mc.player.getMainHandStack().getItem() instanceof AxeItem)) {
            done = shieldBreaker(t);
        }
        if (!done) {
            mc.interactionManager.attackEntity(mc.player, t);
            mc.player.swingHand(Hand.MAIN_HAND);
        }
        onAttacked();
    }

    private void onAttacked() {
        attackWindow = 0;
        attackCooldown = 0;
        postAttack = (int) MathUtil.random(8.0f, 10.0f);
        hitCounter++;

        ElytraTarget ely = Manager.FUNCTION_MANAGER.elytraTarget;
        if (ely != null && ely.mode.is("Advanced")) {
            ely.trueFireWork = true;
            if (ely.prefer.get() && target != null) ely.nextPhase(target);
        }
    }

    private boolean shieldBreaker(LivingEntity t) {
        int axeSlot = InventoryUtil.getAxe().slot();
        if (axeSlot == -1) return false;

        if (axeSlot >= 9) {
            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, axeSlot, mc.player.getInventory().selectedSlot, SlotActionType.SWAP, mc.player);
            mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(mc.player.currentScreenHandler.syncId));
            mc.interactionManager.attackEntity(mc.player, t);
            mc.player.swingHand(Hand.MAIN_HAND);
            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, axeSlot, mc.player.getInventory().selectedSlot, SlotActionType.SWAP, mc.player);
            mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(mc.player.currentScreenHandler.syncId));
        } else {
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(axeSlot));
            mc.interactionManager.attackEntity(mc.player, t);
            mc.player.swingHand(Hand.MAIN_HAND);
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(mc.player.getInventory().selectedSlot));
        }
        return true;
    }

    private boolean usingItemBlocks() {
        return dontHitWhen.get("Using an item")
                && mc.player.isUsingItem()
                && mc.player.getItemUseTimeLeft() > 0
                && attackCooldown >= 8;
    }

    private boolean containerBlocks() {
        return dontHitWhen.get("Container open") && mc.currentScreen instanceof HandledScreen<?>;
    }

    public boolean canAttack() {
        if (target == null) return false;

        if (usingItemBlocks()) {
            attackCooldown = 8;
            return false;
        }
        if (containerBlocks() || attackDistanceTo(target) > attackDistance.get().doubleValue()) {
            return false;
        }

        boolean holdingMace = mc.player.getMainHandStack().isOf(Items.MACE);
        boolean cooling = mc.player.getItemCooldownManager().isCoolingDown(mc.player.getMainHandStack());

        if (mc.player.fallDistance > 1.5f) {
            if (cooling || attackCooldown <= 3) return false;
        } else if (holdingMace) {
            if (cooling || mc.player.getAttackCooldownProgress(0.5f) < 0.9f) return false;
        } else if (mc.player.getAttackCooldownProgress(0.5f) < 0.9f || attackCooldown < 10) {
            return false;
        }

        if (!onlyCrits.get()) return true;
        if (isCritState()) return true;
        return adaptiveHits.get() && mc.player.isOnGround() && !mc.options.jumpKey.isPressed();
    }

    private boolean critImpossible() {
        return mc.player.hasStatusEffect(StatusEffects.BLINDNESS)
                || mc.player.hasStatusEffect(StatusEffects.LEVITATION)
                || mc.player.hasStatusEffect(StatusEffects.SLOW_FALLING)
                || mc.player.isInLava()
                || mc.player.inPowderSnow
                || mc.player.isClimbing()
                || mc.player.hasVehicle()
                || mc.player.getAbilities().flying
                || mc.player.isGliding()
                || (mc.player.isInFluid() && !mc.options.jumpKey.isPressed())
                || MoveUtil.isInWeb();
    }

    private boolean isCritState() {
        return critImpossible() || (!mc.player.isOnGround() && mc.player.fallDistance > 0.0f);
    }

    // ------------------------------------------------------------------ targets

    private boolean elytraActive() {
        ElytraTarget ely = Manager.FUNCTION_MANAGER.elytraTarget;
        return ely != null && ely.state && mc.player.isGliding();
    }

    private boolean handleElytraRotation(LivingEntity t) {
        if (!elytraActive()) return false;
        ElytraTarget ely = Manager.FUNCTION_MANAGER.elytraTarget;
        if (ely.mode.is("Advanced")) ely.overtakingElytra(t, false);
        else ely.targetDefault(t, false);
        return true;
    }

    private double attackDistanceTo(LivingEntity e) {
        if (elytraActive()) {
            double d = Manager.FUNCTION_MANAGER.elytraTarget.getCompDistance(e);
            if (d >= 0.0) return d;
        }
        return eyeDistance(e);
    }

    private double eyeDistance(Entity e) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d p = closestPoint(eye, e.getBoundingBox());
        return eye.distanceTo(p);
    }

    private Vec3d closestPoint(Vec3d from, Box b) {
        return new Vec3d(
                MathHelper.clamp(from.x, b.minX, b.maxX),
                MathHelper.clamp(from.y, b.minY, b.maxY),
                MathHelper.clamp(from.z, b.minZ, b.maxZ));
    }

    private boolean isEntityReachable(LivingEntity e) {
        if (elytraActive()) {
            return eyeDistance(e) <= elytraDistance.get().doubleValue();
        }
        double reach = attackDistance.get().doubleValue()
                + extraReach.get().doubleValue()
                + mc.player.getVelocity().length() * 3.0;
        return eyeDistance(e) <= reach;
    }

    private Optional<LivingEntity> findTargetWithParam(boolean allowBehindWalls) {
        if (mc.world == null || mc.player == null) return Optional.empty();

        Vec3d eye = mc.player.getEyePos();
        double reach = attackDistance.get().doubleValue() + extraReach.get().doubleValue();

        Comparator<LivingEntity> order;
        switch (targetPriority.get()) {
            case "Distance":
                order = Comparator.comparingDouble(e -> eyeDistance(e));
                break;
            case "HP":
                order = Comparator.comparingDouble(e -> (double) e.getHealth());
                break;
            case "Armor":
                order = Comparator.comparingDouble(e -> (double) AuraUtil.getArmor(e));
                break;
            default:
                final Vec3d look = Vec3d.fromPolar(Manager.ROTATION.getPitch(), Manager.ROTATION.getYaw());
                order = Comparator.comparingDouble(e -> Math.acos(MathHelper.clamp(
                        look.dotProduct(e.getBoundingBox().getCenter().subtract(eye).normalize()), -1.0, 1.0)));
                break;
        }

        List<LivingEntity> list = new ArrayList<>();
        for (Entity e : Manager.SYNC_MANAGER.getEntities()) {
            if (!(e instanceof LivingEntity le)) continue;
            if (le == mc.player || !le.isAlive()) continue;
            if (!isValidTarget(le)) continue;
            if (!allowBehindWalls && !blockClear(eye, closestPoint(eye, le.getBoundingBox()))) continue;
            list.add(le);
        }
        return list.stream().min(order);
    }

    private boolean isValidTarget(LivingEntity entity) {
        if (entity == null || entity == mc.player || entity.isDead() || !entity.isAlive()) return false;
        if (entity instanceof ArmorStandEntity) return false;
        if (Manager.FUNCTION_MANAGER.littleSnickers.state && entity instanceof GhostWolfEntity) return false;
        if (!isEntityReachable(entity)) return false;
        if (Manager.FUNCTION_MANAGER.antiBot.check(entity)) return false;

        if (entity instanceof PlayerEntity) {
            if (!targetSettings.get("Players")) return false;
            boolean friend = Manager.FRIEND_MANAGER.isFriend(entity.getName().getString());
            if (friend) return targetSettings.get("Friends");
            boolean naked = entity.getArmor() <= 0;
            return !naked || targetSettings.get("Without armor");
        }
        if (entity instanceof Monster) {
            return targetSettings.get("Hostile mobs");
        }
        if (entity instanceof MobEntity) {
            return targetSettings.get("Animals");
        }
        return false;
    }

    // ------------------------------------------------------------------ aim helpers

    private float curYaw() {
        return Manager.ROTATION.getYaw();
    }

    private float curPitch() {
        return Manager.ROTATION.getPitch();
    }

    private boolean blockClear(Vec3d from, Vec3d to) {
        if (mc.world == null || mc.player == null) return true;
        BlockHitResult hit = mc.world.raycast(new RaycastContext(from, to,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
        return hit == null || hit.getType() == HitResult.Type.MISS;
    }

    private boolean aimHits(float yaw, float pitch, double dist, LivingEntity t, boolean ignoreWalls) {
        if (t == null || mc.player == null) return false;
        if (RayTraceUtil.getMouseOver(t, yaw, pitch, dist) != t) return false;
        if (ignoreWalls) return true;

        BlockHitResult hit = RayTraceUtil.rayCast(dist, yaw, pitch, false);
        if (hit == null || hit.getType() == HitResult.Type.MISS) return true;
        return mc.player.getEyePos().distanceTo(hit.getPos()) >= eyeDistance(t);
    }

    private Vec3d aimPoint(Vec3d eye, LivingEntity t, double reach, boolean throughWalls) {
        Box b = t.getBoundingBox();
        double cx = (b.minX + b.maxX) / 2.0;
        double cz = (b.minZ + b.maxZ) / 2.0;
        double hx = (b.maxX - b.minX) / 2.0 * 0.6;
        double hz = (b.maxZ - b.minZ) / 2.0 * 0.6;
        double h = b.maxY - b.minY;

        double[] heights = {0.85, 0.7, 0.55, 0.4, 0.25};
        double[] ox = {0.0, -hx, hx};
        double[] oz = {0.0, -hz, hz};

        final float cy = curYaw();
        final float cp = curPitch();

        List<Vec3d> points = new ArrayList<>();
        for (double hh : heights) {
            for (double x : ox) {
                for (double z : oz) {
                    points.add(new Vec3d(cx + x, b.minY + h * hh, cz + z));
                }
            }
        }
        points.sort(Comparator.comparingDouble(p -> angleCost(eye, p, cy, cp)));

        for (Vec3d p : points) {
            if (eye.distanceTo(p) > reach) continue;
            if (!throughWalls && !blockClear(eye, p)) continue;
            return p.subtract(eye);
        }

        Vec3d closest = closestPoint(eye, b);
        if (throughWalls || blockClear(eye, closest)) {
            return closest.subtract(eye);
        }
        return Vec3d.ZERO;
    }

    private double angleCost(Vec3d eye, Vec3d p, float cy, float cp) {
        double dx = p.x - eye.x;
        double dy = p.y - eye.y;
        double dz = p.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        return Math.abs(MathHelper.wrapDegrees(yaw - cy)) + Math.abs(pitch - cp);
    }

    private float stepAngle(float current, float wanted, float factor) {
        return current + MathHelper.wrapDegrees(wanted - current) * factor;
    }

    private void aim(float yaw, float pitch, float maxStep) {
        float cy = curYaw();
        float cp = curPitch();
        float dy = MathHelper.clamp(MathHelper.wrapDegrees(yaw - cy), -maxStep, maxStep);
        float dp = MathHelper.clamp(pitch - cp, -maxStep, maxStep);

        float gcd = GCDUtil.getGCDValue();
        if (gcd > 0.0f) {
            dy -= dy % gcd;
            dp -= dp % gcd;
        }
        Manager.ROTATION.set(cy + dy, MathHelper.clamp(cp + dp, -90.0f, 90.0f));
    }

    // ------------------------------------------------------------------ rotation

    private void rotateToTarget() {
        LivingEntity t = target;
        if (t == null) return;

        Vec3d eye = mc.player.getEyePos();
        double reach = attackDistance.get().doubleValue();
        boolean funTime = rotationType.get().contains("FunTime");
        boolean throughWalls = funTime || !dontHitWhen.get("Enemy behind a wall");

        float cy = curYaw();
        float cp = curPitch();

        Vec3d tp = aimPoint(eye, t, reach, throughWalls);
        boolean hasPoint = tp.lengthSquared() > 1.0E-7;
        float yawToTarget = !hasPoint ? cy
                : (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(tp.z, tp.x)) - 90.0);
        float pitchToTarget = !hasPoint ? cp
                : (float) -Math.toDegrees(Math.atan2(tp.y, Math.hypot(tp.x, tp.z)));

        System.arraycopy(pitchHistory, 0, pitchHistory, 1, pitchHistory.length - 1);
        pitchHistory[0] = pitchToTarget;

        if (attackCooldown >= 2 && hitCounter > 43
                && (attackCooldown == 4 || Math.random() > 0.5)
                && (!randomDirection || !aimHits(cy, cp, 3.0, t, false))) {
            mc.player.swingHand(Hand.MAIN_HAND);
            if (Math.random() > 0.5) randomDirection = !randomDirection;
            hitCounter = (int) MathUtil.random(-10.0f, 10.0f);
        }

        boolean skip = usingItemBlocks() || containerBlocks();
        boolean attackSoon = !skip && attackCooldown >= 8
                && eyeDistance(t) <= attackDistance.get().doubleValue() + 0.5;
        if ((attackWindow <= 0 && canAttack()) || attackSoon) {
            attackWindow = 1;
            if (!mc.player.isTouchingWater() && smartSprint.get() && !mc.player.isOnGround()) {
                sprintFreeze = 1;
            }
        }

        if (funTime) {
            applyFunTimeSmoothing(yawToTarget, pitchToTarget);
        } else {
            applyLegitSmoothing(yawToTarget, pitchToTarget);
        }

        attackWindow--;
        postAttack--;
        acquireTicks--;
    }

    private void applyFunTimeSmoothing(float yawToTarget, float pitchToTarget) {
        float cy = curYaw();
        float cp = curPitch();
        double dist = attackDistance.get().doubleValue();

        float time = mc.player.age + mc.getRenderTickCounter().getTickDelta(false);
        float smoothW = (float) ((Math.sin(time * 0.4) * 3.0) + (Math.sin(time * 0.95 + 1.4) * 2.0));
        float smoothH = (float) ((Math.cos(time * 0.5 + 0.7) * 0.5) + (Math.cos(time * 0.78 + 3.1) * 1.5));

        int hist = MathHelper.clamp(10 - attackCooldown, 0, pitchHistory.length - 1);
        float finalPitch = stepAngle(cp, pitchHistory[hist] + smoothH * 1.5f, MathUtil.random(0.1f, 0.5f));
        float finalYaw = stepAngle(cy, yawToTarget + smoothW, MathUtil.random(0.1f, 0.4f));

        if (attackWindow >= 0) {
            if (!aimHits(cy, cp, dist, target, true) && acquireTicks <= 0) {
                finalYaw = yawToTarget;
            }
            if (!aimHits(yawToTarget, finalPitch, dist, target, true) && acquireTicks <= 0) {
                finalPitch = pitchToTarget;
            }
            if (!aimHits(cy + smoothW, cp + smoothH, dist, target, true)
                    && aimHits(cy, cp, dist, target, true)) {
                smoothW = MathHelper.clamp(smoothW, -0.05f, 0.05f);
                smoothH = MathHelper.clamp(smoothH, -0.05f, 0.05f);
            }
        }

        if (attackCooldown <= 4 && hitCounter % 2 == 0) {
            finalYaw = cy;
        }

        boolean fullAim = rotationType.get().equals("FunTime");
        aim(finalYaw + smoothW, (fullAim ? finalPitch : cp) + smoothH, 220.0f);
    }

    private void applyLegitSmoothing(float yawToTarget, float pitchToTarget) {
        float cy = curYaw();
        float cp = curPitch();
        double dist = attackDistance.get().doubleValue();

        float t = mc.player.age + mc.getRenderTickCounter().getTickDelta(false);
        float sway = (float) (((Math.sin(t * 0.31f) * 0.5) + (Math.sin(t * 1.7f + 2.6f) * 0.2)) * 8.0) / 4.0f;
        float smoothW = sway;
        float smoothH = sway;

        float finalYaw = stepAngle(cy, yawToTarget, MathUtil.random(0.2f, 0.35f));
        float finalPitch = stepAngle(cp, pitchToTarget, MathUtil.random(0.15f, 0.25f));

        if (attackWindow >= 0) {
            finalPitch = stepAngle(cp, pitchToTarget, 0.35f);
            smoothH /= 3.0f;
            smoothW /= 3.0f;
            if (!aimHits(cy, cp, dist, target, true)) {
                finalYaw = stepAngle(cy, yawToTarget, MathUtil.random(0.7f, 1.0f));
            }
        }

        if (!aimHits(finalYaw + smoothW, finalPitch + smoothH, dist, target, true)
                && aimHits(yawToTarget, pitchToTarget, dist, target, true)) {
            smoothW = MathHelper.clamp(smoothW, -0.15f, 0.15f);
            smoothH = MathHelper.clamp(smoothH, -0.15f, 0.15f);
        }

        if (postAttack >= 0) {
            smoothW *= 8.0f;
            if (attackCooldown >= 1 && hitCounter % 5 == 0) {
                finalPitch = stepAngle(cp, -pitchToTarget, 0.05f);
            }
        }

        aim(finalYaw + smoothW, finalPitch + smoothH, 180.0f);
    }
}
