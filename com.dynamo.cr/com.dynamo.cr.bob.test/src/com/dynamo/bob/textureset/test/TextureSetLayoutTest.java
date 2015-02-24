package com.dynamo.bob.textureset.test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.junit.Test;

import com.dynamo.bob.textureset.TextureSetLayout;
import com.dynamo.bob.textureset.TextureSetLayout.Layout;
import com.dynamo.bob.textureset.TextureSetLayout.Rect;

public class TextureSetLayoutTest {

    void assertRect(Layout layout, int i, int x, int y, Object id) {
        Rect r = layout.getRectangles().get(i);
        assertThat(r.x, is(x));
        assertThat(r.y, is(y));
        assertThat(r.id, is(id));
    }

    private static Layout layout(int margin, List<Rect> rectangles) {
        return TextureSetLayout.layout(margin, rectangles, true, false);
    }

    @Test
    public void testEmpty() {
        List<TextureSetLayout.Rect> rectangles
            = Arrays.asList();

        Layout layout = layout(0, rectangles);
        assertThat(layout.getWidth(), is(1));
        assertThat(layout.getHeight(), is(1));
        assertThat(layout.getRectangles().size(), is(0));
    }

    @Test
    public void testBasic1() {
        List<TextureSetLayout.Rect> rectangles
            = Arrays.asList(rect(0, 16, 16),
                            rect(1, 16, 16),
                            rect(2, 16, 16),
                            rect(3, 16, 16));

        Layout layout = layout(0, rectangles);
        assertThat(layout.getWidth(), is(64));
        assertThat(layout.getHeight(), is(16));
        assertRect(layout, 0, 0, 0, 0);
        assertRect(layout, 1, 16, 0, 1);
        assertRect(layout, 2, 32, 0, 2);
        assertRect(layout, 3, 48, 0, 3);
    }

    @Test
    public void testBasic2() {
        List<TextureSetLayout.Rect> rectangles
            = Arrays.asList(rect(0, 16, 16),
                            rect(1, 8, 8),
                            rect(2, 8, 8),
                            rect(3, 8, 8),
                            rect(4, 8, 8),
                            rect(5, 8, 8),
                            rect(6, 8, 8));

        Layout layout = layout(0, rectangles);
        assertThat(layout.getWidth(), is(64));
        assertThat(layout.getHeight(), is(16));
        assertRect(layout, 0, 0, 0, 0);
        assertRect(layout, 1, 16, 0, 1);
        assertRect(layout, 2, 16, 8, 2);
        assertRect(layout, 3, 24, 0, 3);
        assertRect(layout, 4, 24, 8, 4);
        assertRect(layout, 5, 32, 0, 5);
        assertRect(layout, 6, 32, 8, 6);
    }

    @Test
    public void testBasic3() {
        List<TextureSetLayout.Rect> rectangles
            = Arrays.asList(rect(0, 512, 128));

        Layout layout = layout(0, rectangles);
        assertThat(layout.getWidth(), is(512));
        assertThat(layout.getHeight(), is(128));
        assertRect(layout, 0, 0, 0, 0);
    }

    @Test
    public void testBasic4() {
        List<TextureSetLayout.Rect> rectangles
            = Arrays.asList(rect(0, 32, 12),
                            rect(1, 16, 2),
                            rect(2, 16, 2));

        Layout layout = layout(0, rectangles);
        assertThat(layout.getWidth(), is(32));
        assertThat(layout.getHeight(), is(16));
        assertRect(layout, 0, 0, 0, 0);
        assertRect(layout, 1, 0, 12, 1);
        assertRect(layout, 2, 0, 14, 2);
    }

    @Test
    public void testBasicMargin1() {
        List<TextureSetLayout.Rect> rectangles
            = Arrays.asList(rect(0, 16, 16),
                            rect(1, 16, 16),
                            rect(2, 16, 16),
                            rect(3, 16, 16));

        Layout layout = layout(2, rectangles);
        assertThat(layout.getWidth(), is(128));
        assertThat(layout.getHeight(), is(32));
        assertRect(layout, 0, 0, 0, 0);
        assertRect(layout, 1, 16 + 2, 0, 1);
        assertRect(layout, 2, (16 + 2) * 2, 0, 2);
        assertRect(layout, 3, (16 + 2) * 3, 0, 3);
    }

    @Test
    public void testBasicMargin2() {
        List<TextureSetLayout.Rect> rectangles
            = Arrays.asList(rect(0, 15, 15),
                            rect(1, 15, 15),
                            rect(2, 15, 15),
                            rect(3, 15, 15));

        Layout layout = layout(2, rectangles);
        assertThat(layout.getWidth(), is(128));
        assertThat(layout.getHeight(), is(32));
        assertRect(layout, 0, 0, 0, 0);
        assertRect(layout, 1, 15 + 2, 0, 1);
        assertRect(layout, 2, (15 + 2) * 2, 0, 2);
        assertRect(layout, 3, (15 + 2) * 3, 0, 3);
    }

    @Test
    public void testThinStrip() {
        List<TextureSetLayout.Rect> rectangles = Arrays.asList(rect(0, 1, 16));

        Layout layout = layout(0, rectangles);
        assertThat(layout.getWidth(), is(1));
        assertThat(layout.getHeight(), is(16));
        assertRect(layout, 0, 0, 0, 0);
    }

    private Rect rect(Object id, int w, int h) {
        return new Rect(id, w, h);
    }


    private static int nextLength(int a, int b) {
        return a + b;
    }

    private List<Rect> createSampleRectangles(int scale) {
        final int startRange = 2;
        final int endRange = 10;

        List<Rect> rects = new ArrayList<Rect>();

        int previousY = 1;
        for (int y=startRange; y<endRange; ++y) {
            int yLength = nextLength(y, previousY) * scale;
            int previousX = 1;
            for (int x=startRange; x<endRange; ++x) {
                int xLength = nextLength(x, previousX) * scale;
                rects.add(rect(rects.size(), xLength, yLength));
                previousX = x;
            }
            previousY = y;
        }

        return rects;
    }

    @Test
    public void testAllIncluded() {
        List<Rect> rectangles = createSampleRectangles(1);
        Layout layout = layout(0, rectangles);

        HashSet<Integer> recordedIds = new HashSet<Integer>();

        for (Rect r : layout.getRectangles()) {
            Integer id = (Integer)r.id;
            assertFalse(recordedIds.contains(id));
            recordedIds.add(id);
        }

        assertEquals(recordedIds.size(), rectangles.size());
    }

    private static boolean isOverlapping(Rect a, Rect b) {
        if (a.x >= b.x + b.width) {
            return false;
        }
        if (a.y >= b.y + b.height) {
            return false;
        }
        if (a.x + a.width <= b.x) {
            return false;
        }
        if (a.y + a.height <= b.y) {
            return false;
        }
        return true;
    }

    @Test
    public void testNoOverlaps() {
        List<Rect> rectangles = createSampleRectangles(1);
        Layout layout = layout(0, rectangles);
        List<Rect> outputRectangles = layout.getRectangles();
        int numRectangles = outputRectangles.size();

        for (int i=0; i<numRectangles; ++i) {
            for (int j=i+1; j<numRectangles; ++j) {
                assertFalse(isOverlapping(outputRectangles.get(i), outputRectangles.get(j)));
            }
        }
    }

    @Test
    public void testLargeLayout() {
        List<Rect> rectangles = Arrays.asList(rect(0, 1000, 800), rect(1, 800, 1000), rect(2, 1000, 100), rect(3, 800, 100));
        Layout layout = layout(0, rectangles);

        assertEquals(layout.getWidth(), 2048);
        assertEquals(layout.getHeight(), 1024);
    }
}
