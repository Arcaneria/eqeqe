package template.rip.api.anchor;

import net.minecraft.util.math.MathHelper;
import template.rip.Template;

/**
 * Tracks the per-tick yaw/pitch delta from the previous tick for use in the
 * Grim DuplicateRotPlace bypass: the check gates on {@code deltaX > 2}, so as
 * long as we only fire interactions on a tick where last tick's delta was
 * small, we never trip the check regardless of what
 * {@code lastPlacedDeltaX} held.
 *
 * <p>{@link #update()} must be called at HEAD of every tick (before mouse
 * input applies this tick's rotation). It records both the visible player
 * rotation and the server-side silent rotation (the client rotation held by
 * {@link Template#rotationManager()} that gets spoofed into movement packets)
 * so the caller can pick whichever rotation is actually being sent this tick.
 */
public final class RotationConvergenceTracker {

    private float prevPlayerYaw;
    private float prevPlayerPitch;
    private float prevServerYaw;
    private float prevServerPitch;

    private float lastDeltaPlayerYaw;
    private float lastDeltaPlayerPitch;
    private float lastDeltaServerYaw;
    private float lastDeltaServerPitch;

    private float lastInteractionPitchDelta;
    private boolean primed;
    private boolean hasInteractionPitchDelta;

    public void reset() {
        primed = false;
        hasInteractionPitchDelta = false;
    }

    public void update() {
        if (Template.mc.player == null) {
            primed = false;
            return;
        }

        float curPlayerYaw = Template.mc.player.getYaw();
        float curPlayerPitch = Template.mc.player.getPitch();
        float curServerYaw = serverYaw();
        float curServerPitch = serverPitch();

        if (primed) {
            lastDeltaPlayerYaw = Math.abs(MathHelper.wrapDegrees(curPlayerYaw - prevPlayerYaw));
            lastDeltaPlayerPitch = Math.abs(curPlayerPitch - prevPlayerPitch);
            lastDeltaServerYaw = Math.abs(MathHelper.wrapDegrees(curServerYaw - prevServerYaw));
            lastDeltaServerPitch = Math.abs(curServerPitch - prevServerPitch);
        } else {
            // First sample of the session: no delta yet, force "not settled".
            lastDeltaPlayerYaw = lastDeltaPlayerPitch = Float.MAX_VALUE;
            lastDeltaServerYaw = lastDeltaServerPitch = Float.MAX_VALUE;
        }

        prevPlayerYaw = curPlayerYaw;
        prevPlayerPitch = curPlayerPitch;
        prevServerYaw = curServerYaw;
        prevServerPitch = curServerPitch;
        primed = true;
    }

    /** True if last tick's per-tick yaw AND pitch delta were both under {@code threshold} degrees. */
    public boolean isSettled(float threshold, boolean silent) {
        if (!primed) {
            return false;
        }
        float deltaYaw = silent ? lastDeltaServerYaw : lastDeltaPlayerYaw;
        float deltaPitch = silent ? lastDeltaServerPitch : lastDeltaPlayerPitch;
        return deltaYaw < threshold && deltaPitch < threshold;
    }

    /**
     * Grim's DuplicateRotPlace check keys off pitch delta only. A stable low
     * pitch delta is always fine, but while falling the required pitch can keep
     * changing; allow that as long as it is not duplicating the previous
     * interaction's pitch delta.
     */
    public boolean isDuplicateRotPlaceSafe(float threshold, boolean silent) {
        if (!primed) {
            return false;
        }
        float deltaPitch = currentPitchDelta(silent);
        if (deltaPitch < threshold) {
            return true;
        }
        return !hasInteractionPitchDelta || Math.abs(deltaPitch - lastInteractionPitchDelta) >= 0.001f;
    }

    /** Records the pitch delta that accompanied a sent interaction. */
    public void markInteraction(boolean silent) {
        if (!primed) {
            return;
        }
        lastInteractionPitchDelta = currentPitchDelta(silent);
        hasInteractionPitchDelta = true;
    }

    private float currentPitchDelta(boolean silent) {
        return silent ? lastDeltaServerPitch : lastDeltaPlayerPitch;
    }

    /**
     * The rotation the server currently observes. When the rotation manager is
     * holding a spoofed rotation (silent aim), that is what movement packets
     * carry; otherwise the player's live rotation.
     */
    public static float serverYaw() {
        if (Template.rotationManager() != null && Template.rotationManager().isEnabled()) {
            return Template.rotationManager().getClientRotation().fyaw();
        }
        return Template.mc.player != null ? Template.mc.player.getYaw() : 0.0f;
    }

    public static float serverPitch() {
        if (Template.rotationManager() != null && Template.rotationManager().isEnabled()) {
            return Template.rotationManager().getClientRotation().fpitch();
        }
        return Template.mc.player != null ? Template.mc.player.getPitch() : 0.0f;
    }
}
