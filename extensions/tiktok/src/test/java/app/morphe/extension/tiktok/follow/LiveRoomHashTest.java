/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.follow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.util.ReflectionHelpers;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A LIVE comment carries its room id in the form body, not the query (#110, 0.75.0 logged
 * room=not_in_query for every send). The report names the room only as a short hash.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LiveRoomHashTest {
    private boolean debug;
    private String salt;

    /** Named like TikTok's in-memory form body, which the probe replays into its own buffer. */
    public static final class FormUrlEncodedTypedOutput {
        private final String body;
        int writes;
        FormUrlEncodedTypedOutput(String body) { this.body = body; }
        public void writeTo(OutputStream out) throws IOException {
            writes++;
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static final class ChatRequest {
        private final String path;
        private final Object body;
        ChatRequest(String path, Object body) { this.path = path; this.body = body; }
        public String getPath() { return path; }
        public Object getBody() { return body; }
    }

    public static final class Answer {
        public final Object LIZIZ = new Object();
        public String LIZJ() { return "true"; }
        public int LIZ() { return 200; }
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        debug = BaseSettings.DEBUG.get();
        salt = Settings.DIAGNOSTIC_REPORT_SALT.get();
        Settings.DIAGNOSTIC_REPORT_SALT.save("test-install-key-that-is-not-exported");
        ReflectionHelpers.setStaticField(FollowDiagnostics.class, "salt", null);
        BaseSettings.DEBUG.save(true);
        FollowDiagnostics.resetForTests();
        ShadowLog.clear();
    }

    @After public void tearDown() {
        Settings.DIAGNOSTIC_REPORT_SALT.save(salt);
        ReflectionHelpers.setStaticField(FollowDiagnostics.class, "salt", null);
        BaseSettings.DEBUG.save(debug);
        FollowDiagnostics.resetForTests();
    }

    private static String field(String line, String name) {
        int start = line.indexOf(" " + name + "=");
        assertTrue(name + " missing: " + line, start >= 0);
        int from = start + name.length() + 2;
        int end = line.indexOf(' ', from);
        return line.substring(from, end < 0 ? line.length() : end);
    }

    private static List<String> lines(String prefix) {
        List<String> found = new ArrayList<>();
        for (ShadowLog.LogItem log : ShadowLog.getLogs()) {
            if (log.msg.startsWith(prefix)) found.add(log.msg);
        }
        return found;
    }

    private static String chat(String form) {
        ShadowLog.clear();
        FormUrlEncodedTypedOutput body = new FormUrlEncodedTypedOutput(form);
        FollowDiagnostics.logParsedResponse(
                new ChatRequest("/webcast/room/chat/?aid=1", body), new Answer());
        assertEquals("the body is only replayed into the probe's own buffer", 1, body.writes);
        List<String> found = lines("[Morphe TikTok FollowProbe] live comment response ");
        assertEquals(found.toString(), 1, found.size());
        return found.get(0);
    }

    @Test public void theSameRoomGivesTheSameHashAndAnotherRoomDoesNot() {
        String first = chat("room_id=7400000000000000001&content=private+words");
        String again = chat("content=other&room_id=7400000000000000001");
        String other = chat("room_id=7400000000000000002&content=private+words");

        assertEquals(field(first, "roomHash"), field(again, "roomHash"));
        assertNotEquals(field(first, "roomHash"), field(other, "roomHash"));
        assertEquals(8, field(first, "roomHash").length());
        assertFalse(first, first.contains("7400000000000000001"));
        assertFalse(first, first.contains("private"));
    }

    @Test public void aCommentIsComparedWithTheRoomEnteredLast() {
        FollowDiagnostics.logParsedResponse(
                new ChatRequest("/webcast/room/enter/", new FormUrlEncodedTypedOutput("room_id=7400000000000000001")),
                new Answer());
        String same = chat("room_id=7400000000000000001");
        String elsewhere = chat("room_id=7400000000000000002");

        assertTrue(same, field(same, "room").startsWith("last_entered"));
        assertEquals(field(same, "roomHash"), field(same, "enteredHash"));
        assertTrue(elsewhere, field(elsewhere, "room").startsWith("other_than_last_entered"));
        assertNotEquals(field(elsewhere, "roomHash"), field(elsewhere, "enteredHash"));
    }

    @Test public void aBodyWithoutARoomSaysSo() {
        String line = chat("content=hello");
        assertEquals(line, "not_found", field(line, "room"));
        assertEquals(line, "none", field(line, "roomHash"));
    }

    @Test public void theQueryStillWorksWhenTheRoomIsThere() {
        ShadowLog.clear();
        FollowDiagnostics.logParsedResponse(
                new ChatRequest("/webcast/room/chat/?room_id=7400000000000000001", null), new Answer());
        String line = lines("[Morphe TikTok FollowProbe] live comment response ").get(0);
        assertEquals(field(chat("room_id=7400000000000000001"), "roomHash"), field(line, "roomHash"));
    }
}
