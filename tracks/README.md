# Server audio library

Add at least three tracks you own or are allowed to redistribute.

P0 target format: WAV containing signed PCM, 44,100 Hz, 16-bit stereo, little-endian.
Do not assume every WAV has a 44-byte header. `TrackRepository` will validate the actual format.

No real audio assets are bundled yet. WAV/MP3 files and generated PCM caches are ignored by Git.
Keep licenses/source information alongside any assets the group later decides to distribute.

The bootstrap Server does not scan or stream this directory yet.
