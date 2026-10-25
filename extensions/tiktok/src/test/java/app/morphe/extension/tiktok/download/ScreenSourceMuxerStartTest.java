package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import app.morphe.extension.tiktok.SettingsContextRule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowMediaCodec;
import org.robolectric.shadows.ShadowMediaMuxer;
import org.robolectric.shadows.ShadowSystemClock;

/** Exercise the real drains with independently delayed encoder format events. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, shadows = {ScreenSourceMuxerStartTest.Codec.class,
        ScreenSourceMuxerStartTest.Muxer.class})
public class ScreenSourceMuxerStartTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final TemporaryFolder files = new TemporaryFolder();
    private static final Map<String, Codec> codecs = new HashMap<>();
    private static final List<String> tracks = new ArrayList<>();
    private static final List<Sample> written = new ArrayList<>();
    private static int starts;
    private static ScreenSource.Encoder startupEncoder;
    private static long startupOrigin;
    private final List<ScreenSource.Encoder> encoders = new ArrayList<>();

    @Before public void reset() {
        codecs.clear();
        tracks.clear();
        written.clear();
        starts = 0;
        startupEncoder = null;
        startupOrigin = 0;
    }

    @Test public void runSetsTheCommonOriginBeforeConstructingTheFirstEncoder() throws Exception {
        for (boolean audio : new boolean[]{false, true}) observeStartupOrigin(audio);
    }

    @Test public void framesQueuedDuringSetupKeepDistinctTimesOnTheSameAudioClock() throws Exception {
        long origin = observeStartupOrigin(true);
        ScreenSource.Encoder encoder = encoder(true);
        set(encoder, "originUs", origin);
        Codec video = codecs.get(MediaFormat.MIMETYPE_VIDEO_AVC);
        Codec audio = codecs.get(MediaFormat.MIMETYPE_AUDIO_AAC);
        long[] times = new long[32];
        byte[] bytes = new byte[32];
        int[] flags = new int[32];
        for (int i = 0; i < times.length; i++) {
            times[i] = (i + 1L) * 33_333L;
            bytes[i] = (byte) (i + 1);
            flags[i] = i == 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            // All these frames arrived before a simulated 1.365-second setup finished.
            assertTrue(times[i] < 1_365_400L);
            video.output.add(new Sample(origin + times[i], flags[i], bytes[i]));
        }
        audio.output.add(new Sample(42_800, 0, (byte) 71));
        assertTrue(drain(encoder, true));
        assertTrue(drain(encoder, false));
        assertTrue(drain(encoder, true));
        assertTrack(MediaFormat.MIMETYPE_VIDEO_AVC, times, bytes, flags);
        assertTrack(MediaFormat.MIMETYPE_AUDIO_AAC, new long[]{42_800}, new byte[]{71}, new int[]{0});
        assertEquals(origin, ((Number) get(encoder, "originUs")).longValue());
    }

    /** Stop setup at its first codec allocation, before any native display or audio capture. */
    private long observeStartupOrigin(boolean audio) throws Exception {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2));
        var app = RuntimeEnvironment.getApplication();
        ScreenSource source = new ScreenSource(app, files.newFile(),
                app.getResources().getDisplayMetrics(), audio);
        ScreenSource.Encoder encoder = source.new Encoder(null);
        encoders.add(encoder);
        startupEncoder = encoder;
        long before = System.nanoTime() / 1000L;
        try {
            assertThrows(SetupObserved.class, () -> encoder.run(null));
            long after = System.nanoTime() / 1000L;
            assertTrue("the first codec was constructed before the clock origin was initialized",
                    startupOrigin > 0 && startupOrigin >= before && startupOrigin <= after);
            assertEquals(startupOrigin, ((Number) get(encoder, "originUs")).longValue());
            return startupOrigin;
        } finally {
            startupEncoder = null;
        }
    }

    private static final class SetupObserved extends RuntimeException { }

    @After public void releaseEncoders() {
        for (ScreenSource.Encoder encoder : encoders) encoder.release();
    }

    @Test public void lateAudioFormatKeepsTheFirstVideoKeyFrameAndEveryFollowingSample() throws Exception {
        ScreenSource.Encoder encoder = encoder(true);
        Codec video = codecs.get(MediaFormat.MIMETYPE_VIDEO_AVC);
        Codec audio = codecs.get(MediaFormat.MIMETYPE_AUDIO_AAC);
        video.output.add(new Sample(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, (byte) 90));
        set(encoder, "originUs", 1_000_000L);
        video.output.add(new Sample(1_007_000, MediaCodec.BUFFER_FLAG_KEY_FRAME, (byte) 11));
        video.output.add(new Sample(1_040_333, 0, (byte) 12));
        audio.output.add(new Sample(9_000, 0, (byte) 21));
        audio.formatReady = false;

        assertTrue(drain(encoder, true));
        assertWaiting(encoder, true, video, false);
        assertEquals(0, written.size());
        audio.formatReady = true;
        assertTrue(drain(encoder, false));
        assertTrue(drain(encoder, true));

        assertTrack(MediaFormat.MIMETYPE_VIDEO_AVC, new long[]{7_000, 40_333},
                new byte[]{11, 12}, new int[]{MediaCodec.BUFFER_FLAG_KEY_FRAME, 0});
        assertTrack(MediaFormat.MIMETYPE_AUDIO_AAC, new long[]{9_000}, new byte[]{21}, new int[]{0});
        assertEquals(3, video.releases);
        assertEquals(1, audio.releases);
        assertEquals(1, starts);
    }

    @Test public void lateVideoFormatKeepsTheFirstAacSample() throws Exception {
        ScreenSource.Encoder encoder = encoder(true);
        Codec video = codecs.get(MediaFormat.MIMETYPE_VIDEO_AVC);
        Codec audio = codecs.get(MediaFormat.MIMETYPE_AUDIO_AAC);
        video.formatReady = false;
        audio.output.add(new Sample(0, 0, (byte) 31));
        audio.output.add(new Sample(23_220, 0, (byte) 32));
        video.output.add(new Sample(5_000, MediaCodec.BUFFER_FLAG_KEY_FRAME, (byte) 41));

        assertTrue(drain(encoder, false));
        assertWaiting(encoder, false, audio, true);
        video.formatReady = true;
        assertTrue(drain(encoder, true));
        assertTrue(drain(encoder, false));

        assertTrack(MediaFormat.MIMETYPE_AUDIO_AAC, new long[]{0, 23_220},
                new byte[]{31, 32}, new int[]{0, 0});
        assertEquals(1, starts);
    }

    @Test public void stopWhileWaitingRetainsEosAndRepeatedCloseOrDrainDoesNotConsumeItTwice() throws Exception {
        ScreenSource.Encoder encoder = encoder(true);
        Codec video = codecs.get(MediaFormat.MIMETYPE_VIDEO_AVC);
        Codec audio = codecs.get(MediaFormat.MIMETYPE_AUDIO_AAC);
        audio.formatReady = false;
        video.output.add(new Sample(10_000,
                MediaCodec.BUFFER_FLAG_KEY_FRAME | MediaCodec.BUFFER_FLAG_END_OF_STREAM, (byte) 51));
        audio.output.add(new Sample(0, MediaCodec.BUFFER_FLAG_END_OF_STREAM));
        assertTrue(drain(encoder, true));
        encoder.close();
        encoder.close();
        assertTrue((Boolean) get(encoder, "stopRequested"));
        assertWaiting(encoder, true, video, false);

        audio.formatReady = true;
        assertTrue(drain(encoder, false));
        assertTrue(drain(encoder, true));
        assertTrue((Boolean) get(encoder, "videoDone"));
        assertTrue((Boolean) get(encoder, "audioDone"));
        int videoCalls = video.dequeues;
        int audioCalls = audio.dequeues;
        encoder.close();
        assertFalse(drain(encoder, true));
        assertFalse(drain(encoder, false));
        assertEquals(videoCalls, video.dequeues);
        assertEquals(audioCalls, audio.dequeues);
        assertEquals(1, video.releases);
        assertEquals(1, audio.releases);
        assertEquals(1, written.size());
        assertEquals(1, starts);
    }

    @Test public void videoOnlyStartsImmediatelyAndRetainsItsFirstKeyFrameThroughEos() throws Exception {
        ScreenSource.Encoder encoder = encoder(false);
        Codec video = codecs.get(MediaFormat.MIMETYPE_VIDEO_AVC);
        video.output.add(new Sample(0, MediaCodec.BUFFER_FLAG_KEY_FRAME, (byte) 61));
        video.output.add(new Sample(33_333, 0, (byte) 62));
        video.output.add(new Sample(0, MediaCodec.BUFFER_FLAG_END_OF_STREAM));
        assertTrue(drain(encoder, true));
        assertFalse(drain(encoder, true));
        assertTrack(MediaFormat.MIMETYPE_VIDEO_AVC, new long[]{0, 33_333},
                new byte[]{61, 62}, new int[]{MediaCodec.BUFFER_FLAG_KEY_FRAME, 0});
        assertEquals(1, tracks.size());
        assertEquals(1, starts);
        assertEquals(3, video.releases);
        assertTrue((Boolean) get(encoder, "videoDone"));
    }

    /** Repeated worker iterations must leave the known-format codec's finite output queue alone. */
    private static void assertWaiting(ScreenSource.Encoder encoder, boolean knownVideo,
            Codec known, boolean missingVideo) throws Exception {
        assertEquals("encoded samples were discarded before the other track format", 0, known.releases);
        int calls = known.dequeues;
        int queued = known.output.size();
        for (int i = 0; i < 32; i++) {
            assertFalse(drain(encoder, knownVideo));
            assertFalse(drain(encoder, missingVideo));
        }
        assertEquals(calls, known.dequeues);
        assertEquals(queued, known.output.size());
        assertEquals(0, known.releases);
        assertEquals(0, starts);
    }

    private ScreenSource.Encoder encoder(boolean audio) throws Exception {
        var app = RuntimeEnvironment.getApplication();
        ScreenSource source = new ScreenSource(app, files.newFile(),
                app.getResources().getDisplayMetrics(), audio);
        ScreenSource.Encoder encoder = source.new Encoder(null);
        encoders.add(encoder);
        set(encoder, "videoCodec", MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC));
        if (audio) set(encoder, "audioCodec", MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC));
        set(encoder, "audioDone", !audio);
        set(encoder, "muxer", new MediaMuxer(files.newFile().getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4));
        return encoder;
    }

    private static boolean drain(ScreenSource.Encoder encoder, boolean video) throws Exception {
        Method method = ScreenSource.Encoder.class.getDeclaredMethod(
                video ? "drainVideo" : "drainAudio", MediaCodec.BufferInfo.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(encoder, new MediaCodec.BufferInfo());
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void assertTrack(String mime, long[] times, byte[] payloads, int[] flags) {
        List<Sample> samples = new ArrayList<>();
        for (Sample sample : written) if (sample.track == tracks.indexOf(mime)) samples.add(sample);
        assertEquals(times.length, samples.size());
        for (int i = 0; i < times.length; i++) {
            assertEquals(times[i], samples.get(i).timeUs);
            assertArrayEquals(new byte[]{payloads[i]}, samples.get(i).bytes);
            assertEquals(flags[i], samples.get(i).flags);
        }
    }

    private static final class Sample {
        final long timeUs;
        final int flags;
        final byte[] bytes;
        int track;
        Sample(long timeUs, int flags, byte... bytes) {
            this.timeUs = timeUs;
            this.flags = flags;
            this.bytes = bytes;
        }
    }

    @Implements(MediaCodec.class)
    public static class Codec extends ShadowMediaCodec {
        final ArrayDeque<Sample> output = new ArrayDeque<>();
        boolean formatReady = true;
        boolean formatReported;
        String mime;
        Sample held;
        int dequeues, releases;

        @Override @Implementation protected void __constructor__(String name, boolean byType, boolean encoder) {
            if (startupEncoder != null) {
                try {
                    Field origin = ScreenSource.Encoder.class.getDeclaredField("originUs");
                    origin.setAccessible(true);
                    startupOrigin = origin.getLong(startupEncoder);
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
                throw new SetupObserved();
            }
            super.__constructor__(name, byType, encoder);
            mime = name;
            codecs.put(name, this);
        }

        @Implementation protected int dequeueOutputBuffer(MediaCodec.BufferInfo info, long timeoutUs) {
            dequeues++;
            assertEquals(0, timeoutUs);
            assertTrue("a dequeued output was not released", held == null);
            if (!formatReported) {
                if (!formatReady) return MediaCodec.INFO_TRY_AGAIN_LATER;
                formatReported = true;
                return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED;
            }
            held = output.poll();
            if (held == null) return MediaCodec.INFO_TRY_AGAIN_LATER;
            info.set(1, held.bytes.length, held.timeUs, held.flags);
            return 0;
        }

        @Implementation protected MediaFormat getOutputFormat() {
            return MediaFormat.MIMETYPE_VIDEO_AVC.equals(mime)
                    ? MediaFormat.createVideoFormat(mime, 1080, 1920)
                    : MediaFormat.createAudioFormat(mime, 44100, 2);
        }

        @Implementation protected ByteBuffer getOutputBuffer(int index) {
            ByteBuffer buffer = ByteBuffer.allocate(held.bytes.length + 2);
            buffer.position(1);
            buffer.put(held.bytes);
            return buffer;
        }

        @Implementation protected void releaseOutputBuffer(int index, boolean render) {
            assertFalse(render);
            releases++;
            held = null;
        }
    }

    @Implements(MediaMuxer.class)
    public static class Muxer extends ShadowMediaMuxer {
        @Implementation protected static int nativeAddTrack(long muxer, String[] keys, Object[] values) {
            assertEquals("a track was added after starting", 0, starts);
            for (int i = 0; i < keys.length; i++) {
                if (MediaFormat.KEY_MIME.equals(keys[i])) tracks.add((String) values[i]);
            }
            return tracks.size() - 1;
        }

        @Implementation protected static void nativeStart(long muxer) { starts++; }

        @Implementation protected static void nativeWriteSampleData(long muxer, int track, ByteBuffer data,
                int offset, int size, long timeUs, int flags) {
            assertEquals("sample was written before muxing started", 1, starts);
            byte[] bytes = new byte[size];
            ByteBuffer copy = data.duplicate();
            copy.position(offset);
            copy.get(bytes);
            Sample sample = new Sample(timeUs, flags, bytes);
            sample.track = track;
            written.add(sample);
        }
    }
}
