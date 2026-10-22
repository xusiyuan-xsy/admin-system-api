package com.ruoyi.system.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;
import com.ruoyi.common.core.domain.entity.SysPayingUser;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DatingPhotoReferences;
import com.ruoyi.common.utils.poi.ExcelUtil;

/** Embeds stored private photos without re-encoding, never public or external image links. */
@Service
public class DatingArchiveExportService
{
    private static final int MAX_ROWS = 10_000;
    private static final long MAX_IMAGE_BYTES = 64L * 1024 * 1024;
    private static final long MAX_TOTAL_IMAGE_BYTES = 512L * 1024 * 1024;
    private static final int MAX_USERS_PER_PART = 1000;
    private final DatingArchiveAccess access;
    private final DatingPhotoStorage storage;
    private final Semaphore exportSlot = new Semaphore(1);

    public DatingArchiveExportService(DatingArchiveAccess access, DatingPhotoStorage storage)
    {
        this.access = access;
        this.storage = storage;
    }

    public record Estimate(int userCount, int photoCount, int unavailablePhotoCount, long legacyPhotoCount,
        long imageBytes, int partCount, String fileType, long maxPartImageBytes,
        long maxTotalImageBytes, int maxUsersPerPart) { }

    private record ExportPlan(List<List<SysPayingUser>> parts, Estimate estimate) { }

    public Estimate estimate(List<SysPayingUser> users)
    {
        return plan(users).estimate();
    }

    private ExportPlan plan(List<SysPayingUser> users)
    {
        long rows = users.stream().mapToLong(user -> Math.max(1, photos(user).size())
            + (user.getLegacyPhotoCount() > 0 ? 1 : 0)).sum();
        if (rows > MAX_ROWS) throw new ServiceException("照片导出记录超过10000条，请缩小筛选范围后分批导出");
        List<List<SysPayingUser>> parts = new ArrayList<>();
        List<SysPayingUser> part = new ArrayList<>();
        long partBytes = 0, imageBytes = 0, legacyCount = 0;
        int photoCount = 0, unavailableCount = 0;
        for (SysPayingUser user : users)
        {
            access.check(user.getId(), false);
            long userBytes = 0;
            legacyCount += user.getLegacyPhotoCount();
            for (String reference : photos(user))
            {
                if (!DatingPhotoReferences.belongsTo(reference, user.getId()))
                {
                    unavailableCount++;
                    continue;
                }
                try
                {
                    userBytes += storage.size(user.getId(), reference);
                    photoCount++;
                }
                catch (ServiceException e) { unavailableCount++; }
            }
            if (userBytes > MAX_IMAGE_BYTES)
                throw new ServiceException("单个用户照片超过64MB，请核对照片后重试");
            imageBytes += userBytes;
            if (imageBytes > MAX_TOTAL_IMAGE_BYTES)
                throw new ServiceException("本次照片总量超过512MB，请缩小筛选范围后分批导出");
            if (!part.isEmpty() && (partBytes + userBytes > MAX_IMAGE_BYTES || part.size() >= MAX_USERS_PER_PART))
            {
                parts.add(List.copyOf(part));
                part.clear();
                partBytes = 0;
            }
            part.add(user);
            partBytes += userBytes;
        }
        if (!part.isEmpty() || parts.isEmpty()) parts.add(List.copyOf(part));
        return new ExportPlan(parts, new Estimate(users.size(), photoCount, unavailableCount, legacyCount,
            imageBytes, parts.size(), parts.size() > 1 ? "zip" : "xlsx",
            MAX_IMAGE_BYTES, MAX_TOTAL_IMAGE_BYTES, MAX_USERS_PER_PART));
    }

    public void export(HttpServletResponse response, List<SysPayingUser> users) throws IOException
    {
        if (!exportSlot.tryAcquire()) throw new ServiceException("已有用户数据正在导出，请稍后重试");
        Path temporary = null;
        try
        {
            ExportPlan plan = plan(users);
            boolean zipped = plan.parts().size() > 1;
            ExportBudget budget = new ExportBudget();
            // Finish all parts before sending headers so late failures cannot become partial downloads.
            temporary = Files.createTempFile("dating-user-export-", ".tmp");
            if (zipped)
            {
                try (var output = new ZipOutputStream(Files.newOutputStream(temporary)))
                {
                    for (int i = 0; i < plan.parts().size(); i++)
                    {
                        output.putNextEntry(new ZipEntry(String.format(Locale.ROOT, "paying-users-%03d.xlsx", i + 1)));
                        writeWorkbook(output, plan.parts().get(i), budget);
                        output.closeEntry();
                    }
                }
            }
            else
            {
                try (var output = Files.newOutputStream(temporary))
                {
                    writeWorkbook(output, plan.parts().get(0), budget);
                }
            }
            response.setContentType(zipped ? "application/zip"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=\"paying-users." + (zipped ? "zip" : "xlsx") + "\"");
            response.setHeader("Cache-Control", "no-store");
            response.setContentLengthLong(Files.size(temporary));
            Files.copy(temporary, response.getOutputStream());
        }
        finally
        {
            try { if (temporary != null) Files.deleteIfExists(temporary); }
            finally { exportSlot.release(); }
        }
    }

    private static final class ExportBudget
    {
        private long imageBytes;

        void add(long bytes)
        {
            imageBytes += bytes;
            if (imageBytes > MAX_TOTAL_IMAGE_BYTES)
                throw new ServiceException("本次照片总量超过512MB，请缩小筛选范围后分批导出");
        }
    }

    private void writeWorkbook(OutputStream output, List<SysPayingUser> users, ExportBudget budget) throws IOException
    {
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        workbook.setCompressTempFiles(true);
        try (workbook)
        {
            int photoColumns = Math.max(1, users.stream().mapToInt(user -> photos(user).size()).max().orElse(0));
            ExcelUtil<SysPayingUser> data = new UserDataExcel(workbook, photoColumns, budget);
            data.initWithWorkbook(workbook, users, "用户数据", "");
            data.writeSheet();
            workbook.getSheetAt(0).createFreezePane(3, 1);
            workbook.write(output);
        }
        finally
        {
            workbook.dispose();
        }
    }

    private static List<String> photos(SysPayingUser user)
    {
        return user.getPhotos() == null ? List.of() : user.getPhotos();
    }

    /** Add photos while each data row is live, before SXSSF flushes older rows. */
    private final class UserDataExcel extends ExcelUtil<SysPayingUser>
    {
        private final SXSSFWorkbook workbook;
        private final int photoColumns;
        private final CellStyle body;
        private final ExportBudget budget;
        private int photoStart = -1;
        private int lastDataColumn;
        private long embeddedBytes;

        private UserDataExcel(SXSSFWorkbook workbook, int photoColumns, ExportBudget budget)
        {
            super(SysPayingUser.class);
            this.workbook = workbook;
            this.photoColumns = photoColumns;
            this.budget = budget;
            body = workbook.createCellStyle();
            body.setVerticalAlignment(VerticalAlignment.CENTER);
            body.setWrapText(true);
        }

        private int dataColumn(int column)
        {
            return photoStart >= 0 && column >= photoStart ? column + photoColumns + 1 : column;
        }

        @Override
        public Cell createHeadCell(Excel attr, Row row, int column)
        {
            lastDataColumn = column;
            Cell cell = super.createHeadCell(attr, row, dataColumn(column));
            if ("姓名".equals(attr.name()))
            {
                photoStart = column + 1;
                for (int i = 0; i <= photoColumns; i++)
                {
                    Cell heading = row.createCell(photoStart + i);
                    heading.setCellValue(i == photoColumns ? "照片状态" : "照片" + (i + 1));
                    heading.setCellStyle(cell.getCellStyle());
                    row.getSheet().setColumnWidth(photoStart + i, (i == photoColumns ? 42 : 48) * 256);
                }
            }
            return cell;
        }

        @Override
        public Cell addCell(Excel attr, Row row, SysPayingUser user, Field field, int column)
        {
            Cell cell = super.addCell(attr, row, user, field, dataColumn(column));
            if ("id".equals(field.getName()) && cell != null)
                cell.setCellValue(String.valueOf(user.getId()));
            // The base writer resets row height for every cell, so finish photos last.
            if (column == lastDataColumn) writePhotos(row, user);
            return cell;
        }

        private void writePhotos(Row row, SysPayingUser user)
        {
            if (photoStart < 0) throw new ServiceException("用户导出缺少姓名列，无法关联照片");
            // Recheck current assignment before reading any private files.
            access.check(user.getId(), false);
            Sheet sheet = row.getSheet();
            Drawing<?> drawing = sheet.createDrawingPatriarch();
            for (int i = 0; i <= photoColumns; i++) row.createCell(photoStart + i).setCellStyle(body);
            List<String> references = photos(user);
            List<String> statuses = new ArrayList<>();
            row.setHeightInPoints(32);
            if (references.isEmpty() && user.getLegacyPhotoCount() == 0)
                statuses.add("未上传照片");
            for (int index = 0; index < references.size(); index++)
            {
                Cell photoCell = row.getCell(photoStart + index);
                String label = "照片" + (index + 1);
                String reference = references.get(index);
                if (!DatingPhotoReferences.belongsTo(reference, user.getId()))
                {
                    photoCell.setCellValue("照片引用无效，未导出");
                    statuses.add(label + "：引用无效");
                    continue;
                }
                ExportPhoto photo;
                try
                {
                    photo = exportPhoto(storage.read(user.getId(), reference));
                }
                catch (ServiceException | IOException e)
                {
                    photoCell.setCellValue("照片缺失或无法读取，未导出");
                    statuses.add(label + "：缺失或无法读取");
                    continue;
                }
                embeddedBytes += photo.bytes().length;
                if (embeddedBytes > MAX_IMAGE_BYTES)
                    throw new ServiceException("照片大小已变化，请重新预估后导出");
                budget.add(photo.bytes().length);
                row.setHeightInPoints(192);
                ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
                anchor.setCol1(photoStart + index);
                anchor.setCol2(photoStart + index);
                anchor.setRow1(row.getRowNum());
                anchor.setRow2(row.getRowNum());
                int left = 8 + (320 - photo.displayWidth()) / 2;
                int top = 8 + (240 - photo.displayHeight()) / 2;
                anchor.setDx1(Units.pixelToEMU(left));
                anchor.setDx2(Units.pixelToEMU(left + photo.displayWidth()));
                anchor.setDy1(Units.pixelToEMU(top));
                anchor.setDy2(Units.pixelToEMU(top + photo.displayHeight()));
                anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
                drawing.createPicture(anchor, workbook.addPicture(photo.bytes(), photo.pictureType()));
                statuses.add(label + "：已嵌入照片");
            }
            if (user.getLegacyPhotoCount() > 0)
                statuses.add("另有" + user.getLegacyPhotoCount() + "张历史照片尚未迁移，未导出");
            row.getCell(photoStart + photoColumns).setCellValue(String.join("\n", statuses));
        }
    }

    private record ExportPhoto(byte[] bytes, int pictureType, int displayWidth, int displayHeight) { }

    private static ExportPhoto exportPhoto(byte[] bytes) throws IOException
    {
        if (bytes == null || bytes.length == 0 || bytes.length > DatingPhotoStorage.MAX_BYTES)
            throw new IOException("Invalid image size");
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes)))
        {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Invalid image");
            var reader = readers.next();
            try
            {
                String format = reader.getFormatName();
                if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg"))
                    throw new IOException("Unsupported image");
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > 6000 || height > 6000
                    || (long) width * height > 12_000_000) throw new IOException("Invalid dimensions");
                double scale = Math.min(1, Math.min(320.0 / width, 240.0 / height));
                // Validate the payload, but preserve stored bytes; only the Excel anchor is scaled.
                BufferedImage source = reader.read(0);
                if (source == null) throw new IOException("Invalid image");
                source.flush();
                int pictureType = format.equalsIgnoreCase("png")
                    ? Workbook.PICTURE_TYPE_PNG : Workbook.PICTURE_TYPE_JPEG;
                return new ExportPhoto(bytes, pictureType,
                    Math.max(1, (int) Math.round(width * scale)),
                    Math.max(1, (int) Math.round(height * scale)));
            }
            finally { reader.dispose(); }
        }
    }
}
