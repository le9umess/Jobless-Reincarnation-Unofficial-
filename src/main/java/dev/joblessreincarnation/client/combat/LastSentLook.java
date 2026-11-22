package dev.joblessreincarnation.client.combat;

/**
 * The look vanilla last sent for the local player in a movement packet (LocalPlayer's own record, which it compares
 * against to decide whether a movement packet carries a rotation). Added to LocalPlayer by MovePacketSentMixin.
 */
public interface LastSentLook {
    float jobless$sentYaw();
    float jobless$sentPitch();
}
