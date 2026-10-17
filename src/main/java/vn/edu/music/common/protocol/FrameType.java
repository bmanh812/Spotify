package vn.edu.music.common.protocol;

public enum FrameType {
    CONTROL_JSON(0x01),
    AUDIO_CHUNK(0x02);

    private final int wireValue;

    FrameType(int wireValue) {
        this.wireValue = wireValue;
    }

    public int wireValue() {
        return wireValue;
    }

    public static FrameType fromWire(int value) throws ProtocolException {
        for (FrameType type : values()) {
            if (type.wireValue == value) {
                return type;
            }
        }
        throw new ProtocolException("Unknown frame type: " + value);
    }
}
