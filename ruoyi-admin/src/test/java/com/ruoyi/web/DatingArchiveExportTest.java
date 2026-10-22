package com.ruoyi.web;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import com.ruoyi.common.core.domain.entity.SysPayingUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DatingPhotoReferences;
import com.ruoyi.system.service.*;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatingArchiveExportTest
{
    @TempDir Path directory;
    DatingArchiveAccess access;
    DatingPhotoStorage storage;
    DatingArchiveExportService exporter;

    @BeforeEach void setup()
    {
        access = mock(DatingArchiveAccess.class);
        storage = spy(new DatingPhotoStorage(directory.resolve("photos").toString()));
        exporter = new DatingArchiveExportService(access, storage);
    }

    private static SysPayingUser user(long id)
    {
        var user = new SysPayingUser();
        user.setId(id);
        user.setName("EXPORT_TEST_" + id);
        user.setPhone("19900000000");
        user.setServiceStatus("SERVING");
        user.setPassword("SECRET_NOT_EXPORTED");
        return user;
    }

    private String photo(long id, int width, int height, String format, Color color) throws IOException
    {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.WHITE);
        graphics.drawString("EXPORT TEST ONLY", 10, 20);
        graphics.dispose();
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, format, bytes);
        return storage.save(id, new MockMultipartFile("file", bytes.toByteArray()));
    }

    private MockHttpServletResponse export(List<SysPayingUser> users) throws IOException
    {
        var response = new MockHttpServletResponse();
        exporter.export(response, users);
        assertTrue(response.getContentType().startsWith("application/vnd.openxmlformats"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        return response;
    }

    @Test void embedsMultiplePhotosOfflineWithAspectRatioAndUserIdentity() throws Exception
    {
        var first = user(1);
        first.setSex("1");
        first.setBirthYear(1996);
        first.setPhotos(List.of(photo(1, 800, 400, "png", Color.BLUE),
            photo(1, 200, 600, "jpeg", Color.RED)));
        var response = export(List.of(first, user(2)));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray())))
        {
            assertEquals(1, workbook.getNumberOfSheets());
            assertEquals("用户数据", workbook.getSheetName(0));
            assertEquals(2, workbook.getAllPictures().size());
            assertTrue(workbook.getExternalLinksTable().isEmpty());
            var sheet = workbook.getSheetAt(0);
            assertEquals(2, sheet.getLastRowNum());
            assertEquals("照片1", sheet.getRow(0).getCell(3).getStringCellValue());
            assertEquals("照片2", sheet.getRow(0).getCell(4).getStringCellValue());
            assertEquals("照片状态", sheet.getRow(0).getCell(5).getStringCellValue());
            assertEquals("性别", sheet.getRow(0).getCell(6).getStringCellValue());
            assertEquals("1", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("19900000000", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("EXPORT_TEST_1", sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("照片1：已嵌入照片\n照片2：已嵌入照片", sheet.getRow(1).getCell(5).getStringCellValue());
            assertEquals("未上传照片", sheet.getRow(2).getCell(5).getStringCellValue());
            assertEquals("女", sheet.getRow(1).getCell(6).getStringCellValue());
            assertEquals(1996, sheet.getRow(1).getCell(7).getNumericCellValue());
            assertEquals(192, sheet.getRow(1).getHeightInPoints());
            assertEquals(2, sheet.getDrawingPatriarch().getShapes().size());
            for (int i = 0; i < 2; i++)
            {
                var picture = (XSSFPicture) sheet.getDrawingPatriarch().getShapes().get(i);
                assertEquals(3 + i, picture.getClientAnchor().getCol1());
                assertEquals(1, picture.getClientAnchor().getRow1());
                assertEquals(1, picture.getClientAnchor().getRow2());
                assertEquals(3 + i, picture.getClientAnchor().getCol2());
                assertTrue(picture.getClientAnchor().getDx2() > picture.getClientAnchor().getDx1());
                assertTrue(picture.getClientAnchor().getDy2() > picture.getClientAnchor().getDy1());
                var image = ImageIO.read(new ByteArrayInputStream(picture.getPictureData().getData()));
                assertEquals(i == 0 ? 800 : 200, image.getWidth());
                assertEquals(i == 0 ? 400 : 600, image.getHeight());
                assertEquals(i == 0 ? Workbook.PICTURE_TYPE_PNG : Workbook.PICTURE_TYPE_JPEG,
                    picture.getPictureData().getPictureType());
                assertArrayEquals(storage.read(1L, first.getPhotos().get(i)),
                    picture.getPictureData().getData());
                assertEquals(Units.pixelToEMU(i == 0 ? 320 : 80),
                    picture.getClientAnchor().getDx2() - picture.getClientAnchor().getDx1());
                assertEquals(Units.pixelToEMU(i == 0 ? 160 : 240),
                    picture.getClientAnchor().getDy2() - picture.getClientAnchor().getDy1());
            }
            for (var row : workbook.getSheetAt(0))
                for (var cell : row)
                    if (cell.getCellType() == CellType.STRING)
                        assertFalse(cell.getStringCellValue().contains("SECRET_NOT_EXPORTED"));
        }
        verify(access, times(2)).check(1L, false);
        verify(access, times(2)).check(2L, false);
        String evidence = System.getProperty("dating.export.evidence");
        if (evidence != null)
            Files.write(Path.of(evidence), response.getContentAsByteArray(), StandardOpenOption.CREATE_NEW);
    }

    @Test void missingCorruptForeignAndLegacyPhotosAreExplicitAndNeverFetchedAsUrls() throws Exception
    {
        var user = user(1);
        String missing = DatingPhotoReferences.prefix(1L) + "a".repeat(32) + ".png";
        String corrupt = DatingPhotoReferences.prefix(1L) + "b".repeat(32) + ".jpg";
        String foreign = DatingPhotoReferences.prefix(2L) + "c".repeat(32) + ".png";
        doReturn(new byte[]{1, 2, 3}).when(storage).read(1L, corrupt);
        user.setPhotos(List.of(missing, corrupt, foreign, "https://example.test/photo.jpg", "../private"));
        user.setLegacyPhotoCount(2);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(export(List.of(user)).getContentAsByteArray())))
        {
            assertTrue(workbook.getAllPictures().isEmpty());
            var sheet = workbook.getSheetAt(0);
            assertEquals(1, sheet.getLastRowNum());
            for (int i = 3; i <= 4; i++)
                assertEquals("照片缺失或无法读取，未导出", sheet.getRow(1).getCell(i).getStringCellValue());
            for (int i = 5; i <= 7; i++)
                assertEquals("照片引用无效，未导出", sheet.getRow(1).getCell(i).getStringCellValue());
            assertTrue(sheet.getRow(1).getCell(8).getStringCellValue().contains("另有2张历史照片尚未迁移，未导出"));
        }
        verify(storage, never()).read(1L, foreign);
        verify(storage, never()).read(1L, "https://example.test/photo.jpg");
        verify(storage, never()).read(1L, "../private");
    }

    @Test void highResolutionTransparentPhotoRetainsStoredPixelsAndBytes() throws Exception
    {
        var image = new BufferedImage(2400, 1600, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1200, 800, 0x8044AA77);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        image.flush();
        var user = user(1);
        String reference = storage.save(1L, new MockMultipartFile("file", bytes.toByteArray()));
        byte[] stored = storage.read(1L, reference);
        user.setPhotos(List.of(reference));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(export(List.of(user)).getContentAsByteArray())))
        {
            var picture = workbook.getAllPictures().get(0);
            assertArrayEquals(stored, picture.getData());
            var exported = ImageIO.read(new ByteArrayInputStream(picture.getData()));
            assertEquals(2400, exported.getWidth());
            assertEquals(1600, exported.getHeight());
            assertEquals(0x8044AA77, exported.getRGB(1200, 800));
            assertEquals(0, exported.getRGB(0, 0));
            exported.flush();
        }
        assertArrayEquals(stored, storage.read(1L, reference));
    }

    @Test void unexpectedPhotoGrowthAbortsBeforeWritingResponse() throws Exception
    {
        String reference = photo(1, 20, 20, "png", Color.BLUE);
        byte[] stored = storage.read(1L, reference);
        byte[] large = Arrays.copyOf(stored, DatingPhotoStorage.MAX_BYTES);
        doReturn(large).when(storage).read(1L, reference);
        var user = user(1);
        user.setPhotos(Collections.nCopies(9, reference));
        var response = new MockHttpServletResponse();
        assertThrows(ServiceException.class, () -> exporter.export(response, List.of(user, user)));
        assertEquals(0, response.getContentAsByteArray().length);
    }

    @Test void estimateUsesSizesWithoutReadingPrivatePayloads() throws Exception
    {
        var first = user(1);
        String reference = photo(1, 800, 400, "png", Color.BLUE);
        long size = storage.size(1L, reference);
        first.setPhotos(List.of(reference, DatingPhotoReferences.prefix(1L) + "b".repeat(32) + ".jpg",
            DatingPhotoReferences.prefix(2L) + "c".repeat(32) + ".png", "https://example.test/private.png"));
        first.setLegacyPhotoCount(2);
        var estimate = exporter.estimate(List.of(first, user(2)));
        assertEquals(2, estimate.userCount());
        assertEquals(1, estimate.photoCount());
        assertEquals(3, estimate.unavailablePhotoCount());
        assertEquals(2, estimate.legacyPhotoCount());
        assertEquals(size, estimate.imageBytes());
        assertEquals(1, estimate.partCount());
        assertEquals("xlsx", estimate.fileType());
        verify(storage, never()).read(anyLong(), anyString());
        verify(storage, never()).size(2L, first.getPhotos().get(2));
        verify(storage, never()).size(1L, first.getPhotos().get(2));
        verify(storage, never()).size(1L, first.getPhotos().get(3));
    }

    @Test void exactImageBoundaryStaysInOnePartAndNextUserStartsAnother()
    {
        List<SysPayingUser> users = new ArrayList<>();
        for (int i = 1; i <= 4; i++)
        {
            var user = user(i);
            String reference = DatingPhotoReferences.prefix((long) i) + "a".repeat(32) + ".png";
            long size = (i <= 2 ? 5L : i == 3 ? 4L : 1L) * 1024 * 1024;
            doReturn(size).when(storage).size((long) i, reference);
            user.setPhotos(Collections.nCopies(i == 1 ? 9 : i == 2 ? 3 : 1, reference));
            users.add(user);
        }
        assertEquals(1, exporter.estimate(users.subList(0, 3)).partCount());
        assertEquals(64L * 1024 * 1024, exporter.estimate(users.subList(0, 3)).imageBytes());
        assertEquals(2, exporter.estimate(users).partCount());
        assertEquals("zip", exporter.estimate(users).fileType());
        verify(storage, never()).read(anyLong(), anyString());
    }

    @Test void oversizedPhotosSplitIntoValidWorkbooksWithoutSplittingAUser() throws Exception
    {
        List<SysPayingUser> users = new ArrayList<>();
        var image = new BufferedImage(1280, 1024, BufferedImage.TYPE_INT_RGB);
        var random = new Random(42);
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, random.nextInt(0x1000000));
        var upload = new ByteArrayOutputStream();
        ImageIO.write(image, "png", upload);
        image.flush();
        byte[] expected = null;
        for (int i = 1; i <= 2; i++)
        {
            String reference = storage.save((long) i, new MockMultipartFile("file", upload.toByteArray()));
            expected = storage.read((long) i, reference);
            var user = user(i);
            user.setPhotos(Collections.nCopies(9, reference));
            users.add(user);
        }
        assertEquals(2, exporter.estimate(users).partCount());
        assertTrue(exporter.estimate(users).imageBytes() > 64L * 1024 * 1024);
        var response = new MockHttpServletResponse();
        exporter.export(response, users);
        assertTrue(response.getContentType().startsWith("application/zip"));
        assertTrue(response.getHeader("Content-Disposition").endsWith(".zip\""));
        assertEquals(response.getContentAsByteArray().length, response.getContentLengthLong());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        try (var zip = new ZipInputStream(new ByteArrayInputStream(response.getContentAsByteArray())))
        {
            for (int i = 1; i <= 2; i++)
            {
                assertEquals(String.format("paying-users-%03d.xlsx", i), zip.getNextEntry().getName());
                try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(zip.readAllBytes())))
                {
                    assertEquals(1, workbook.getSheetAt(0).getLastRowNum());
                    assertEquals(String.valueOf(i), workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
                    assertEquals(9, workbook.getAllPictures().size());
                    for (var picture : workbook.getAllPictures()) assertArrayEquals(expected, picture.getData());
                }
            }
            assertNull(zip.getNextEntry());
        }
        String evidence = System.getProperty("dating.export.zipEvidence");
        if (evidence != null) Files.write(Path.of(evidence), response.getContentAsByteArray(), StandardOpenOption.CREATE_NEW);
    }

    @Test void rowLimitSplitsWithoutLosingOrDuplicatingUsers() throws Exception
    {
        List<SysPayingUser> users = new ArrayList<>();
        for (int i = 1; i <= 1001; i++) users.add(user(i));
        assertEquals(1, exporter.estimate(users.subList(0, 1000)).partCount());
        assertEquals(2, exporter.estimate(users).partCount());
        var response = new MockHttpServletResponse();
        exporter.export(response, users);
        List<String> ids = new ArrayList<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(response.getContentAsByteArray())))
        {
            int parts = 0;
            while (zip.getNextEntry() != null)
            {
                try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(zip.readAllBytes())))
                {
                    var sheet = workbook.getSheetAt(0);
                    assertEquals(parts++ == 0 ? 1000 : 1, sheet.getLastRowNum());
                    for (int i = 1; i <= sheet.getLastRowNum(); i++)
                        ids.add(sheet.getRow(i).getCell(0).getStringCellValue());
                }
            }
            assertEquals(2, parts);
        }
        assertEquals(users.stream().map(user -> user.getId().toString()).toList(), ids);
    }

    @Test void totalBudgetRejectsBeforeReadingPhotos() throws Exception
    {
        var user = user(1);
        String reference = DatingPhotoReferences.prefix(1L) + "a".repeat(32) + ".png";
        doReturn((long) DatingPhotoStorage.MAX_BYTES).when(storage).size(1L, reference);
        user.setPhotos(Collections.nCopies(9, reference));
        var response = new MockHttpServletResponse();
        assertThrows(ServiceException.class, () -> exporter.estimate(Collections.nCopies(12, user)));
        assertThrows(ServiceException.class, () -> exporter.export(response, Collections.nCopies(12, user)));
        assertEquals(0, response.getContentAsByteArray().length);
        verify(storage, never()).read(anyLong(), anyString());
        user.setPhotos(Collections.nCopies(13, reference));
        assertThrows(ServiceException.class, () -> exporter.estimate(List.of(user)));
    }

    private Set<Path> temporaryExports() throws IOException
    {
        try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir"))))
        {
            return paths.filter(path -> path.getFileName().toString().startsWith("dating-user-export-"))
                .collect(java.util.stream.Collectors.toSet());
        }
    }

    @Test void latePermissionFailureCleansTemporaryFilesAndReleasesExportSlot() throws Exception
    {
        var before = temporaryExports();
        List<SysPayingUser> users = new ArrayList<>();
        for (int i = 1; i <= 1001; i++) users.add(user(i));
        doNothing().doThrow(new ServiceException("denied")).when(access).check(1001L, false);
        var response = new MockHttpServletResponse();
        assertThrows(ServiceException.class, () -> exporter.export(response, users));
        assertEquals(0, response.getContentAsByteArray().length);
        assertNull(response.getHeader("Content-Disposition"));
        assertEquals(before, temporaryExports());
        reset(access);
        export(List.of(user(1)));
        assertEquals(before, temporaryExports());
    }

    @Test void concurrentExportIsRejectedWithoutBlockingAndSlotIsReusable() throws Exception
    {
        String reference = photo(1, 20, 20, "png", Color.BLUE);
        byte[] bytes = storage.read(1L, reference);
        var user = user(1);
        user.setPhotos(List.of(reference));
        var entered = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!proceed.await(10, TimeUnit.SECONDS)) throw new IOException("Test timed out");
            return bytes;
        }).when(storage).read(1L, reference);
        var executor = Executors.newSingleThreadExecutor();
        try
        {
            var running = executor.submit(() -> export(List.of(user)));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var response = new MockHttpServletResponse();
            assertThrows(ServiceException.class, () -> exporter.export(response, List.of(user)));
            assertEquals(0, response.getContentAsByteArray().length);
            proceed.countDown();
            assertNotNull(running.get(10, TimeUnit.SECONDS));
            export(List.of(user));
        }
        finally
        {
            proceed.countDown();
            executor.shutdownNow();
        }
    }

    @Test void allNinePhotosAreKeptInOrderWithoutOverlap() throws Exception
    {
        var user = user(1);
        var references = new ArrayList<String>();
        for (int i = 0; i < 9; i++) references.add(photo(1, 20 + i, 20, "png", Color.GREEN));
        user.setPhotos(references);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(export(List.of(user)).getContentAsByteArray())))
        {
            assertEquals(9, workbook.getAllPictures().size());
            var sheet = workbook.getSheetAt(0);
            assertEquals(1, sheet.getLastRowNum());
            for (int i = 0; i < 9; i++)
            {
                var picture = (XSSFPicture) sheet.getDrawingPatriarch().getShapes().get(i);
                assertEquals(1, picture.getClientAnchor().getRow1());
                assertEquals(3 + i, picture.getClientAnchor().getCol1());
                assertEquals(3 + i, picture.getClientAnchor().getCol2());
                var image = ImageIO.read(new ByteArrayInputStream(picture.getPictureData().getData()));
                assertEquals(20 + i, image.getWidth());
            }
        }
    }

    @Test void lostArchiveAccessAbortsBeforeWritingResponse() throws Exception
    {
        var user = user(2);
        user.setPhotos(List.of(DatingPhotoReferences.prefix(2L) + "a".repeat(32) + ".png"));
        doThrow(new ServiceException("denied")).when(access).check(2L, false);
        var response = new MockHttpServletResponse();
        assertThrows(ServiceException.class, () -> exporter.export(response, List.of(user)));
        assertEquals(0, response.getContentAsByteArray().length);
        verify(storage, never()).read(anyLong(), anyString());
    }

    @Test void emptyAndLegacyOnlyExportsAreReadable() throws Exception
    {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(export(List.of()).getContentAsByteArray())))
        {
            assertTrue(workbook.getAllPictures().isEmpty());
            assertEquals(1, workbook.getNumberOfSheets());
            assertEquals(0, workbook.getSheetAt(0).getLastRowNum());
            assertEquals("照片1", workbook.getSheetAt(0).getRow(0).getCell(3).getStringCellValue());
        }
        var user = user(1);
        user.setLegacyPhotoCount(1);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(export(List.of(user)).getContentAsByteArray())))
        {
            assertEquals(1, workbook.getSheetAt(0).getLastRowNum());
            assertEquals("另有1张历史照片尚未迁移，未导出", workbook.getSheetAt(0).getRow(1).getCell(4).getStringCellValue());
        }
    }

    @Test void streamingRowsAndLongUserIdsKeepTheirPhotoAssociation() throws Exception
    {
        List<SysPayingUser> users = new ArrayList<>();
        for (int i = 1; i <= 510; i++) users.add(user(i));
        users.get(0).setPhotos(List.of(photo(1, 20, 10, "png", Color.RED)));
        var last = user(9007199254740993L);
        last.setName("=TEST_FORMULA_IS_TEXT");
        last.setPhotos(List.of(photo(last.getId(), 10, 10, "png", Color.BLUE)));
        users.add(last);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(export(users).getContentAsByteArray())))
        {
            var sheet = workbook.getSheetAt(0);
            assertEquals(511, sheet.getLastRowNum());
            assertEquals("9007199254740993", sheet.getRow(511).getCell(0).getStringCellValue());
            assertEquals(CellType.STRING, sheet.getRow(511).getCell(2).getCellType());
            assertEquals(2, sheet.getDrawingPatriarch().getShapes().size());
            assertEquals(1, ((XSSFPicture) sheet.getDrawingPatriarch().getShapes().get(0)).getClientAnchor().getRow1());
            assertEquals(511, ((XSSFPicture) sheet.getDrawingPatriarch().getShapes().get(1)).getClientAnchor().getRow1());
            assertEquals(192, sheet.getRow(1).getHeightInPoints());
            assertEquals(192, sheet.getRow(511).getHeightInPoints());
        }
    }

    @Test void oversizedExportIsRejectedBeforeAnyPrivateFilesAreRead()
    {
        var response = new MockHttpServletResponse();
        assertThrows(ServiceException.class, () -> exporter.export(response, Collections.nCopies(10001, user(1))));
        assertEquals(0, response.getContentAsByteArray().length);
        verifyNoInteractions(access, storage);
    }
}
