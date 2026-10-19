/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.font;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import android.util.Base64;
import android.webkit.WebView;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * Issue #7: Activity center and Watch history are TikTok web pages, so the font switches reach
 * them through the page itself, and only on TikTok's own pages.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class WebFontTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String WATCH_HISTORY = "https://inapp.tiktokv.com/tiktok/watch_history/index.html";
    private static final byte[] TRUETYPE = {0, 1, 0, 0, 0, 12, 0, (byte) 0x80};

    private Context context;
    private WebView webView;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        webView = new WebView(context);
        HookStatus.clear();
        ImportedFont.remove(context);
        Settings.SYSTEM_FONT.save(false);
        Settings.IMPORTED_FONT.save(false);
    }

    @After public void tearDown() {
        ImportedFont.remove(context);
        Settings.SYSTEM_FONT.resetToDefault();
        Settings.IMPORTED_FONT.resetToDefault();
        HookStatus.clear();
    }

    private String evaluated() {
        return Shadows.shadowOf(webView).getLastEvaluatedJavascript();
    }

    private void importFont(byte[] bytes, String name) throws IOException {
        ImportedFont.receive(context, new ByteArrayInputStream(bytes), name).install();
        ImportedFont.forget();
    }

    @Test public void withBothSwitchesOffThePageKeepsTikToksFont() {
        WebFont.onPageFinished(webView, WATCH_HISTORY);
        assertNull("a script ran with both switches off", evaluated());
        assertTrue(HookStatus.report().toString().contains("web font"));
    }

    @Test public void pagesOutsideTikTokAreLeftAlone() {
        Settings.SYSTEM_FONT.save(true);
        WebFont.onPageFinished(webView, "https://example.com/watch_history");
        WebFont.onPageFinished(webView, "http://inapp.tiktokv.com/plain-http");
        WebFont.onPageFinished(webView, "https://tiktok.com.example.net/");
        WebFont.onPageFinished(webView, null);
        assertNull("a script ran on a page TikTok doesn't own", evaluated());
    }

    @Test public void useSystemFontPutsTheDeviceFontOnTikTokPages() {
        Settings.SYSTEM_FONT.save(true);
        WebFont.onPageFinished(webView, WATCH_HISTORY);
        String script = evaluated();
        assertTrue("no script ran on Watch history", script != null);
        assertTrue(script, script.contains(WebFont.STYLE_ID));
        assertTrue(script, script.contains("font-family:sans-serif !important"));
        assertFalse(script, script.contains("@font-face"));
    }

    @Test public void anImportedFontGoesInAsADataUrl() throws IOException {
        importFont(TRUETYPE, "Inter.ttf");
        Settings.IMPORTED_FONT.save(true);
        String css = WebFont.styleSheet(context);
        String data = "data:font/ttf;base64," + Base64.encodeToString(TRUETYPE, Base64.NO_WRAP);
        assertTrue(css, css.contains("src:url(" + data + ")"));
        assertTrue(css, css.contains("font-family:\"" + WebFont.IMPORTED_FAMILY + "\",sans-serif !important"));

        WebFont.onPageFinished(webView, WATCH_HISTORY);
        assertTrue(evaluated(), evaluated().contains("@font-face"));
    }

    @Test public void theImportedFontWinsOverUseSystemFontLikeInTheApp() throws IOException {
        importFont(TRUETYPE, "Inter.ttf");
        Settings.SYSTEM_FONT.save(true);
        Settings.IMPORTED_FONT.save(true);
        assertTrue(WebFont.styleSheet(context).contains("@font-face"));

        Settings.IMPORTED_FONT.save(false);
        assertFalse("the imported font stayed with its switch off",
                WebFont.styleSheet(context).contains("@font-face"));
    }

    @Test public void aFontTooLargeToCarryInThePageGetsTheDeviceFont() throws IOException {
        byte[] large = Arrays.copyOf(TRUETYPE, (int) WebFont.MAX_INLINE_BYTES + 16);
        importFont(large, "Huge.ttf");
        Settings.IMPORTED_FONT.save(true);
        String css = WebFont.styleSheet(context);
        assertFalse("a font over the limit went into the page", css.contains("@font-face"));
        assertTrue(css, css.contains("font-family:sans-serif !important"));
    }

    @Test public void anOpenTypeFontIsNamedAsOne() throws IOException {
        importFont(new byte[] {'O', 'T', 'T', 'O', 0, 9, 0, 0x40}, "Display.otf");
        Settings.IMPORTED_FONT.save(true);
        assertTrue(WebFont.styleSheet(context).contains("data:font/otf;base64,"));
    }

    @Test public void theScriptKeepsOneStyleElementAndQuotesTheSheet() {
        String script = WebFont.script("a{content:\"'</style>\"}");
        assertEquals("the script looks the element up before adding one", 1,
                script.split("getElementById\\('" + WebFont.STYLE_ID + "'\\)", -1).length - 1);
        assertTrue(script, script.contains("s.textContent=\"a{content:\\\"'<\\/style>\\\"}\""));
    }

    /** The hybrid kit's chain of clients hands one page event to several hooked callbacks. */
    @Test public void onePageEventDownTheClientChainStylesThePageOnce() {
        Settings.SYSTEM_FONT.save(true);
        int[] scripts = {0};
        WebView counted = new WebView(context) {
            @Override public void evaluateJavascript(String script, android.webkit.ValueCallback<String> callback) {
                scripts[0]++;
            }
        };
        WebFont.onPageFinished(counted, WATCH_HISTORY);
        WebFont.onPageFinished(counted, WATCH_HISTORY);
        WebFont.onPageFinished(counted, WATCH_HISTORY);
        assertEquals("the chain's repeats styled the page again", 1, scripts[0]);

        WebFont.onPageFinished(counted, "https://inapp.tiktokv.com/tiktok/comment_history/index.html");
        assertEquals("a different page right after wasn't styled", 2, scripts[0]);

        shadowOf(Looper.getMainLooper()).idleFor(WebFont.REPEAT_WINDOW_MS + 1, TimeUnit.MILLISECONDS);
        WebFont.onPageFinished(counted, "https://inapp.tiktokv.com/tiktok/comment_history/index.html");
        assertEquals("a reload after the window wasn't styled", 3, scripts[0]);
    }

    @Test public void aMissingWebViewIsIgnored() {
        Settings.SYSTEM_FONT.save(true);
        WebFont.onPageFinished(null, WATCH_HISTORY);
    }
}
