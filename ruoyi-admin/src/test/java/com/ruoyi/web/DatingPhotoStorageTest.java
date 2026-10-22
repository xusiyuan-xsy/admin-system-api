package com.ruoyi.web;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import javax.imageio.ImageIO;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DatingPhotoReferences;
import com.ruoyi.system.service.DatingPhotoStorage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;

class DatingPhotoStorageTest
{
    @TempDir Path directory;
    DatingPhotoStorage storage;
    @BeforeEach void setup() { storage = new DatingPhotoStorage(directory.resolve("private").toString()); }

    static byte[] image() throws Exception
    {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return bytes.toByteArray();
    }
    @Test void reencodesImageAndDoesNotTrustFilenameOrMime() throws Exception
    {
        var bytes = new ByteArrayOutputStream();
        bytes.write(image());
        bytes.write("PRIVATE_METADATA<script>payload</script>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String ref = storage.save(1L, new MockMultipartFile("file", "../../attack.html", "text/html", bytes.toByteArray()));
        assertTrue(DatingPhotoReferences.belongsTo(ref, 1L));
        assertArrayEquals(image(), storage.read(1L, ref));
        assertFalse(ref.contains("attack"));
    }
    @Test void rejectsEmptyFakeSvgAndOversizedFiles()
    {
        for (byte[] bytes : new byte[][] {new byte[0], "<svg/>".getBytes(),
            "not a PNG".getBytes(), new byte[DatingPhotoStorage.MAX_BYTES + 1]})
            assertThrows(ServiceException.class, () -> storage.save(1L,
                new MockMultipartFile("file", "image.png", "image/png", bytes)));
    }
    @Test void rejectsOversizedDimensionsBeforeDecoding() throws Exception
    {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(6001, 1, BufferedImage.TYPE_INT_RGB), "png", bytes);
        assertThrows(ServiceException.class, () -> storage.save(1L,
            new MockMultipartFile("file", "wide.png", "image/png", bytes.toByteArray())));
    }
    @Test void rejectsTraversalForeignReferencesAndMissingFiles() throws Exception
    {
        String ref = storage.save(1L, new MockMultipartFile("file", image()));
        assertThrows(ServiceException.class, () -> storage.read(2L, ref));
        for (String invalid : new String[] {ref + "/../secret", "https://example.test/photo.png",
            "/profile/photo.png", DatingPhotoReferences.prefix(1L) + "a".repeat(32) + ".png"})
            assertThrows(ServiceException.class, () -> storage.validate(1L, invalid));
    }
    @Test void refusesStorageUnderPublicProfile() throws Exception
    {
        String previous = RuoYiConfig.getProfile();
        try
        {
            new RuoYiConfig().setProfile(directory.toString());
            assertThrows(ServiceException.class, () -> storage.save(1L, new MockMultipartFile("file", image())));
            assertFalse(Files.exists(directory.resolve("private")));
        }
        finally { new RuoYiConfig().setProfile(previous); }
    }

    @Test void watermarkKeepsResolutionAndPrivateOriginalAndCannotBeReadViaPhotoRoute() throws Exception
    {
        var source = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        var g = source.createGraphics();
        g.setColor(new java.awt.Color(120, 160, 100));
        g.fillRect(0, 0, 800, 600);
        g.dispose();
        var data = new ByteArrayOutputStream();
        ImageIO.write(source, "png", data);
        var upload = new MockMultipartFile("file", "photo.png", "image/png", data.toByteArray());
        String oldRef = storage.save(1L, upload);
        byte[] oldBytes = storage.read(1L, oldRef);
        String ref = storage.save(1L, upload, "红娘服务测试 仅限内部使用");
        byte[] bytes = storage.read(1L, ref);
        assertFalse(java.util.Arrays.equals(oldBytes, bytes));
        var result = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        assertEquals(800, result.getWidth());
        assertEquals(600, result.getHeight());
        long changed = 0;
        for (int y = 0; y < 600; y++)
            for (int x = 0; x < 800; x++) if (source.getRGB(x,y) != result.getRGB(x,y)) changed++;
        assertTrue(changed > 500);
        Path original = directory.resolve("private/1/originals").resolve(ref.substring(ref.lastIndexOf('/') + 1));
        assertArrayEquals(oldBytes, Files.readAllBytes(original));
        assertThrows(ServiceException.class, () -> storage.read(1L,
            DatingPhotoReferences.prefix(1L) + "originals/" + original.getFileName()));
        assertArrayEquals(oldBytes, storage.read(1L, oldRef), "Existing photos must never change");
        String evidence = System.getProperty("dating.watermark.evidence");
        if (evidence != null) Files.write(Path.of(evidence), bytes);
    }

    @Test void watermarkSupportsJpegAndPreservesTransparentPng() throws Exception
    {
        for (String format : new String[]{"png", "jpeg"})
        {
            var image = new BufferedImage(640, 480, format.equals("png")
                ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(image, format, bytes);
            var ref = storage.save(1L, new MockMultipartFile("file", bytes.toByteArray()), "Watermark");
            var decoded = ImageIO.read(new java.io.ByteArrayInputStream(storage.read(1L, ref)));
            assertEquals(640, decoded.getWidth());
            assertEquals(480, decoded.getHeight());
            if (format.equals("png"))
            {
                assertTrue(decoded.getColorModel().hasAlpha());
                boolean transparent = false;
                for (int x = 0; x < 640; x++) if ((decoded.getRGB(x, 0) >>> 24) == 0) transparent = true;
                assertTrue(transparent);
            }
        }
    }
}
