package vn.edu.music.common.model;

import org.junit.jupiter.api.Test;
import vn.edu.music.common.protocol.AudioChunk;
import vn.edu.music.common.protocol.AudioChunkMetadata;
import vn.edu.music.common.protocol.MessageType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotTest {
    @Test
    void roomCollectionsAreIndependentAndReadOnly() {
        List<Participant> participants = new ArrayList<>(List.of(new Participant("c1", "Minh")));
        List<QueueItem> queue = new ArrayList<>(List.of(new QueueItem("q1", "t1", "c1")));
        RoomState snapshot = new RoomState("r1", "ABC123", "c1", participants, null,
                PlaybackState.PAUSED, 0, 0, 0, 0, queue);
        participants.clear();
        queue.clear();
        assertEquals(1, snapshot.participants().size());
        assertEquals(1, snapshot.queue().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.participants().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.queue().clear());
    }

    @Test
    void audioChunkDoesNotExposeMutablePcm() {
        byte[] pcm = {1, 2, 3, 4};
        AudioChunk chunk = new AudioChunk(new AudioChunkMetadata(MessageType.AUDIO_CHUNK, 1,
                "12345678-1234-1234-1234-123456789abc", "r1", "t1", 1, 0, 0, 1, true, 4), pcm);
        pcm[0] = 9;
        chunk.pcm()[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3, 4}, chunk.pcm());
    }
}
