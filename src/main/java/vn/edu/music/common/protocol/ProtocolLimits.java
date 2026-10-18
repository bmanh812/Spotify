package vn.edu.music.common.protocol;

/** Contract v1 ceilings, not adaptive-buffer tuning parameters. */
public final class ProtocolLimits {
    public static final int VERSION = 1;
    public static final int MAX_FRAME_BYTES = 65536;
    public static final int MAX_METADATA_BYTES = 2048;
    public static final int MAX_PCM_BYTES = 61440;
    public static final int SAMPLE_RATE = 44100;
    public static final int CHANNELS = 2;
    public static final int SAMPLE_SIZE_BITS = 16;
    public static final int FRAME_SIZE_BYTES = 4;

    private ProtocolLimits() { }
}
