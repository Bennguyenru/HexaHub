package com.dynamo.bob.test.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.awt.FontFormatException;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.junit.Test;

import com.dynamo.bob.font.BMFont;
import com.dynamo.bob.font.BMFont.BMFontFormatException;
import com.dynamo.bob.font.BMFont.ChannelData;
import com.dynamo.bob.font.BMFont.Char;
import com.dynamo.bob.font.Fontc;
import com.dynamo.bob.font.Fontc.FontResourceResolver;
import com.dynamo.render.proto.Font.FontDesc;
import com.dynamo.render.proto.Font.FontMap;
import com.dynamo.render.proto.Font.FontMap.Glyph;

public class FontTest {

    private static final double EPSILON = 0.000001;

    private String copyResourceToDir(String tmpDir, String resName) throws IOException {
        String outputPath = Paths.get(tmpDir, resName).toString();

        InputStream inputStream = getClass().getResourceAsStream(resName);
        File outputFile = new File(outputPath);
        outputFile.deleteOnExit();
        OutputStream outputStream = new FileOutputStream(outputFile);
        IOUtils.copy(inputStream, outputStream);
        inputStream.close();
        outputStream.close();

        return outputPath;
    }

    private static void assertBMFontFormatException( BMFont bmFont, InputStream in ) throws Exception {

        boolean success = true;
        try {
            bmFont.parse(in);
        } catch (BMFontFormatException e) {
            success = false;
        }

        assertFalse(success);
    }

    private static void assertEntryParse(HashMap<String,String> expected, String entries) throws Exception {
        HashMap<String,String> res = BMFont.splitEntries( entries );

        for (HashMap.Entry<String,String> expectedEntry : expected.entrySet()) {
            String key = expectedEntry.getKey();
            String expectedValue = expectedEntry.getValue();
            assertTrue(res.containsKey(key));

            String actualValue = res.get(key);
            assertEquals( expectedValue, actualValue );
        }

        for (HashMap.Entry<String,String> resEntry : res.entrySet()) {
            String key = resEntry.getKey();
            String resValue = resEntry.getValue();
            assertTrue(expected.containsKey(key));

            String actualValue = expected.get(key);
            assertEquals( resValue, actualValue );
        }
    }

    @Test
    public void testBMFontEntryParse() throws Exception {

        HashMap<String,String> entries = new HashMap<String,String>();
        entries.clear();
        entries.put("a", "32");
        entries.put("bcd", "0");
        entries.put("efg", "wut");
        entries.put("string", "farbror melker");
        entries.put("array", "0,0,0,0");
        assertEntryParse(entries, "a=32 bcd=0 efg=wut string=\"farbror melker\" array=0,0,0,0");

        entries.clear();
        entries.put("string", "båtsman i saltkråkan");
        assertEntryParse(entries, "string=\"båtsman i saltkråkan\"");

        entries.clear();
        entries.put("string", "   lofty     strings ");
        assertEntryParse(entries, "    string=\"   lofty     strings \"       ");

        entries.clear();
        entries.put("a", "b");
        entries.put("c", "d");
        entries.put("apa", "bepa cepa   ");
        assertEntryParse(entries, "  a=b c=d apa=\"bepa cepa   \"");

        entries.clear();
        entries.put("bcd", "0");
        assertEntryParse(entries, "a= bcd=0 asd aasd");

        assertBMFontFormatException(new BMFont(), new ByteArrayInputStream("info a=\"asdasdas".getBytes(StandardCharsets.UTF_8)));

    }

    @Test
    public void testBMFontInvalidFormat() throws Exception {
        BMFont bmfont = new BMFont();
        InputStream input = getClass().getResourceAsStream("invalid1.fnt");
        assertBMFontFormatException(bmfont, input);

        input = getClass().getResourceAsStream("invalid2.fnt");
        assertBMFontFormatException(bmfont, input);

        input = getClass().getResourceAsStream("invalid3.fnt");
        assertBMFontFormatException(bmfont, input);
    }

    @Test
    public void testBMFont() throws Exception {

        InputStream input = getClass().getResourceAsStream("bmfont.fnt");
        BMFont bmfont = new BMFont();
        bmfont.parse(input);

        // verify font info
        assertEquals(32, bmfont.size, EPSILON);
        assertEquals(false, bmfont.bold);
        assertEquals(false, bmfont.italics);
        assertEquals("", bmfont.charset);
        assertEquals(false, bmfont.unicode);
        assertEquals(100, bmfont.stretchH, EPSILON);
        assertEquals(false, bmfont.smooth);
        assertEquals(1, bmfont.aa);
        assertEquals(4, bmfont.padding.length);
        assertEquals(0, bmfont.padding[0], EPSILON);
        assertEquals(0, bmfont.padding[1], EPSILON);
        assertEquals(0, bmfont.padding[2], EPSILON);
        assertEquals(0, bmfont.padding[3], EPSILON);
        assertEquals(2, bmfont.spacing.length);
        assertEquals(1, bmfont.spacing[0], EPSILON);
        assertEquals(1, bmfont.spacing[1], EPSILON);


        // verify font common
        assertEquals(80, bmfont.lineHeight);
        assertEquals(26, bmfont.base);
        assertEquals(512, bmfont.scaleW);
        assertEquals(1024, bmfont.scaleH);
        assertEquals(1, bmfont.pages);
        assertEquals(false, bmfont.packed);
        assertEquals(ChannelData.OUTLINE, bmfont.alphaChnl);
        assertEquals(ChannelData.GLYPH, bmfont.redChnl);
        assertEquals(ChannelData.GLYPH, bmfont.greenChnl);
        assertEquals(ChannelData.GLYPH, bmfont.blueChnl);

        // verify chars
        assertEquals(96, bmfont.chars);
        assertEquals(96, bmfont.charArray.size());

        for (int i = 0; i < bmfont.chars; i++)
        {
            Char c = bmfont.charArray.get(i);
            assertTrue( (c.id <= 126 && c.id >= 32) || c.id == 9 );
        }

    }

    @Test
    public void testTTF() throws Exception {

        // create "font file"
        FontDesc fontDesc = FontDesc.newBuilder()
            .setFont("Tuffy.ttf")
            .setMaterial("font.material")
            .setSize(24)
            .setExtraCharacters("åäöÅÄÖ")
            .build();

        // temp output file
        File outfile = File.createTempFile("font-output", ".fontc");
        outfile.deleteOnExit();

        // compile font
        Fontc fontc = new Fontc();
        InputStream fontDescStream = getClass().getResourceAsStream(fontDesc.getFont());
        FileOutputStream fontOutputStream = new FileOutputStream(outfile);
        final String searchPath = FilenameUtils.getBaseName(fontDesc.getFont());
        FontMap.Builder fontmapBuilder = FontMap.newBuilder();

        fontc.compile(fontDescStream, fontDesc, fontmapBuilder, new FontResourceResolver() {
                @Override
                public InputStream getResource(String resourceName)
                        throws FileNotFoundException {
                    return new FileInputStream(Paths.get(searchPath, resourceName).toString());
                }
            });
        fontmapBuilder.build().writeTo(fontOutputStream);

        fontDescStream.close();
        fontOutputStream.close();

        // verify output
        BufferedInputStream fontcStream = new BufferedInputStream(new FileInputStream(outfile));
        FontMap fontMap = FontMap.newBuilder().mergeFrom(fontcStream).build();

        // glyph count
        int expectedCharCount = (127 - 32) + 7; // (127 - 32) default chars, 7 extra åäöÅÄÖ
        assertEquals(expectedCharCount, fontMap.getGlyphsCount());

        // unicode chars
        assertEquals(0xF8FF, fontMap.getGlyphs(fontMap.getGlyphsCount() - 1).getCharacter());
    }

    @Test
    public void testBinaryFNT() throws Exception {

        FontDesc fontDesc = FontDesc.newBuilder()
                .setFont("binary.fnt")
                .setMaterial("font.material")
                .setSize(24)
                .build();

        File outfile = File.createTempFile("font-output", ".fontc");
        outfile.deleteOnExit();

        // compile font
        boolean success = true;
        Fontc fontc = new Fontc();
        InputStream fontDescStream = getClass().getResourceAsStream(fontDesc.getFont());
        FileOutputStream fontOutputStream = new FileOutputStream(outfile);
        final String searchPath = FilenameUtils.getBaseName(fontDesc.getFont());
        FontMap.Builder fontmapBuilder = FontMap.newBuilder();
        try {
            fontc.compile(fontDescStream, fontDesc, fontmapBuilder, new FontResourceResolver() {
                @Override
                public InputStream getResource(String resourceName)
                        throws FileNotFoundException {
                    return new FileInputStream(Paths.get(searchPath, resourceName).toString());
                }
            });
            fontmapBuilder.build().writeTo(fontOutputStream);
        } catch (FontFormatException e) {
            success = false;
        }

        fontDescStream.close();
        fontOutputStream.close();


        // NOTE: We don't support binary BMFont files at the moment,
        //       make sure we threw a format exception above!
        assertFalse(success);
    }

    @Test
    public void testFNT() throws Exception {

        // copy fnt and texture file
        final Path tmpDir = Files.createTempDirectory("fnt-tmp");
        tmpDir.toFile().deleteOnExit();
        String tmpFnt = copyResourceToDir(tmpDir.toString(), "bmfont.fnt");
        copyResourceToDir(tmpDir.toString(), "bmfont.png");

        // create "font file"
        FontDesc fontDesc = FontDesc.newBuilder()
            .setFont(tmpFnt)
            .setMaterial("font.material")
            .setSize(24)
            .build();

        // temp output file
        File outfile = File.createTempFile("font-output", ".fontc");
        outfile.deleteOnExit();

        // compile font
        Fontc fontc = new Fontc();
        FileInputStream fontDescStream = new FileInputStream(fontDesc.getFont());
        FileOutputStream fontOutputStream = new FileOutputStream(outfile);
        FontMap.Builder fontmapBuilder = FontMap.newBuilder();
        BufferedImage image = fontc.compile(fontDescStream, fontDesc, fontmapBuilder, new FontResourceResolver() {

            @Override
            public InputStream getResource(String resourceName)
                    throws FileNotFoundException {
                return new FileInputStream(Paths.get(tmpDir.toString(), resourceName).toString());
            }
        });

        fontmapBuilder.build().writeTo(fontOutputStream);
        fontDescStream.close();
        fontOutputStream.close();

        // verify data
        assertTrue(image != null);
        assertTrue(image.getHeight() > 0);
        assertTrue(image.getWidth() > 0);

        // verify glyphs
        BufferedInputStream fontcStream = new BufferedInputStream(new FileInputStream(outfile));
        FontMap fontMap = FontMap.newBuilder().mergeFrom(fontcStream).build();

        assertEquals(96, fontMap.getGlyphsCount());
        for (int i = 0; i < fontMap.getGlyphsCount(); i++) {
            Glyph g = fontMap.getGlyphs(i);

            // verify that the glyphs are inside the image
            assertTrue( g.getX() + g.getLeftBearing() >= 0.0);
            assertTrue( g.getY() - g.getAscent() >= 0.0);

            assertTrue( g.getX() + g.getLeftBearing() + g.getWidth() <= image.getWidth());
            assertTrue( g.getY() + g.getDescent() <= image.getHeight());
        }
    }
}
