package dev.wetsuki.modules.misc;

import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import dev.wetsuki.events.Event;
import dev.wetsuki.events.impl.EventUpdate;
import dev.wetsuki.modules.Function;
import dev.wetsuki.modules.FunctionAnnotation;
import dev.wetsuki.modules.Type;

/**
 * Packet-timing modules (Vulcan / Grim / Matrix / Verus).
 * Each one is a regular Function: it shows up in the ClickGUI, can be bound to a key
 * and is saved in the config like every other module.
 */
@SuppressWarnings("All")
public final class Wetsuki {

    private Wetsuki() {}

    /** Base class: counts ticks while enabled and calls {@link #tick()} once per client tick. */
    public abstract static class BypassModule extends Function {
        protected int tickCounter = 0;

        @Override
        public void onEvent(Event event) {
            if (!(event instanceof EventUpdate)) return;
            if (mc.player == null || mc.world == null || mc.player.networkHandler == null) return;
            tickCounter++;
            tick();
        }

        protected abstract void tick();

        @Override
        protected void onEnable() {
            tickCounter = 0;
            super.onEnable();
        }

        @Override
        protected void onDisable() {
            tickCounter = 0;
            super.onDisable();
        }

        /** 1.21.2+ has no PRESS/RELEASE_SHIFT_KEY: sneak is sent through PlayerInputC2SPacket. */
        protected void sendSneak(boolean sneaking) {
            PlayerInput cur = mc.player.input.playerInput;
            mc.player.networkHandler.sendPacket(new PlayerInputC2SPacket(new PlayerInput(
                    cur.forward(),
                    cur.backward(),
                    cur.left(),
                    cur.right(),
                    cur.jump(),
                    sneaking,
                    cur.sprint()
            )));
        }
    }

    @FunctionAnnotation(name = "VulcanBypass", desc = "Toggles sprint and sneak packets", type = Type.Misc)
    public static class VulcanBypass extends BypassModule {
        @Override
        protected void tick() {
            if (tickCounter % 2 == 0) {
                mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player,
                        mc.player.isSprinting()
                                ? ClientCommandC2SPacket.Mode.STOP_SPRINTING
                                : ClientCommandC2SPacket.Mode.START_SPRINTING));
            }
            if (tickCounter % 10 == 0) {
                sendSneak(!mc.player.isSneaking());
            }
        }
    }

    @FunctionAnnotation(name = "GrimBypass", desc = "Sends extra micro position packets", type = Type.Misc)
    public static class GrimBypass extends BypassModule {
        @Override
        protected void tick() {
            if (tickCounter % 3 != 0) return;
            double x = mc.player.getX() + (Math.random() - 0.5) * 0.01;
            double y = mc.player.getY();
            double z = mc.player.getZ() + (Math.random() - 0.5) * 0.01;
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                    x, y, z, mc.player.isOnGround(), mc.player.horizontalCollision));
        }
    }

    @FunctionAnnotation(name = "MatrixBypass", desc = "Sends extra arm swing packets", type = Type.Misc)
    public static class MatrixBypass extends BypassModule {
        @Override
        protected void tick() {
            if (tickCounter % 5 == 0) {
                mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
            }
        }
    }

    @FunctionAnnotation(name = "VerusBypass", desc = "Sends sneak packets while moving", type = Type.Misc)
    public static class VerusBypass extends BypassModule {
        @Override
        protected void tick() {
            if (tickCounter % 4 == 0) sendSneak(true);
            else if (tickCounter % 4 == 2) sendSneak(false);
        }
    }

    @FunctionAnnotation(name = "PolarBypass", desc = "Jump reset, position packet and extra arm swings", type = Type.Misc, key = GLFW.GLFW_KEY_P)
    public static class PolarBypass extends BypassModule {
        private boolean jumpReset = false;

        @Override
        protected void onDisable() {
            jumpReset = false;
            super.onDisable();
        }

        @Override
        protected void tick() {
            // Jump reset: every 3 ticks, jump if on the ground (LivingEntity.jump() is protected, so set the jump velocity directly)
            if (tickCounter % 3 == 0 && mc.player.isOnGround()) {
                Vec3d v = mc.player.getVelocity();
                mc.player.setVelocity(v.x, 0.42, v.z);
                jumpReset = true;
            }

            // Position packet on the tick after the jump reset
            if (jumpReset && tickCounter % 3 == 1) {
                mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(), true, mc.player.horizontalCollision));
                jumpReset = false;
            }

            // Extra arm swing for aura
            if (tickCounter % 7 == 0) {
                mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
            }
        }
    }
}
