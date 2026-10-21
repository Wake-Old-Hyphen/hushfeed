/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.theme;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The hot words in search are Lynx text in #FF576F on dark and #E10543 on light (#163): each red
 * the patch named takes its color, and everything else stays as the page asked.
 */
public class LynxAccentTest {
    private static final int DARK_TEXT = 0xFFFF576F;
    private static final int LIGHT_TEXT = 0xFFE10543;

    private final Map<String, Integer> ids = new HashMap<>();
    private final Map<Integer, Integer> colors = new HashMap<>();
    private final List<String> asked = new ArrayList<>();

    @Before
    public void setUp() {
        ids.put("hushfeed_lynx_ff576f", 1);
        ids.put("hushfeed_lynx_e10543", 2);
        colors.put(1, 0xFF8AB4F8);
        colors.put(2, 0xFF0B57D0);
        LynxAccent.setPaletteForTests(new LynxAccent.Palette() {
            @Override
            public int id(String name) {
                asked.add(name);
                Integer id = ids.get(name);
                return id == null ? 0 : id;
            }

            @Override
            public int color(int id) {
                return colors.get(id);
            }
        });
    }

    @After
    public void tearDown() {
        LynxAccent.setPaletteForTests(null);
    }

    @Test
    public void bothTextRedsTakeTheColorsThePatchWrote() {
        assertEquals(0xFF8AB4F8, LynxAccent.color(DARK_TEXT));
        assertEquals(0xFF0B57D0, LynxAccent.color(LIGHT_TEXT));
    }

    @Test
    public void colorsWithoutANameStayAsThePageAskedAndAreLookedUpOnce() {
        assertEquals(0xFFFE2C55, LynxAccent.color(0xFFFE2C55));
        assertEquals(0xFF161823, LynxAccent.color(0xFF161823));
        assertEquals(0xFFFE2C55, LynxAccent.color(0xFFFE2C55));
        assertEquals(List.of("hushfeed_lynx_fe2c55", "hushfeed_lynx_161823"), asked);
    }

    @Test
    public void aSeeThroughRedStays() {
        assertEquals(0x99FF576F, LynxAccent.color(0x99FF576F));
        assertEquals(0x00FF576F, LynxAccent.color(0x00FF576F));
        assertEquals("never looked up", 0, asked.size());
    }

    @Test
    public void theColorIsReadEachTimeSoANewWallpaperShows() {
        assertEquals(0xFF8AB4F8, LynxAccent.color(DARK_TEXT));
        colors.put(1, 0xFFF2B8B5);
        assertEquals(0xFFF2B8B5, LynxAccent.color(DARK_TEXT));
        assertEquals("the name is looked up once", 1, asked.size());
    }

    @Test
    public void aFailingLookupLeavesTheColor() {
        LynxAccent.setPaletteForTests(new LynxAccent.Palette() {
            @Override
            public int id(String name) {
                throw new IllegalStateException("no resources");
            }

            @Override
            public int color(int id) {
                throw new IllegalStateException("no resources");
            }
        });
        assertEquals(DARK_TEXT, LynxAccent.color(DARK_TEXT));
    }
}
