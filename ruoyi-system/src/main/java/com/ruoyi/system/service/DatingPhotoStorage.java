package com.ruoyi.system.service;

import java.awt.image.BufferedImage;
import java.awt.*;
import java.io.*;
import java.nio.file.*;
import java.util.Iterator;
import java.util.UUID;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DatingPhotoReferences;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** Private local storage. This directory must never be published by a web server. */
@Component
public class DatingPhotoStorage
{
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private final Path configuredRoot;

    public DatingPhotoStorage(@Value("${dating.attachments.root:${user.home}/.ruoyi-private/dating-photos}") String root)
    {
        configuredRoot = Path.of(root).toAbsolutePath().normalize();
    }

    private Path root() throws IOException
    {
        String profile = RuoYiConfig.getProfile();
        if (profile != null && !profile.isBlank())
        {
            Path publicRoot = Path.of(profile).toAbsolutePath().normalize();
            if (configuredRoot.startsWith(publicRoot))
                throw new IOException("Private storage overlaps public profile");
            if (Files.exists(publicRoot) && Files.exists(configuredRoot)
                && configuredRoot.toRealPath().startsWith(publicRoot.toRealPath()))
                throw new IOException("Private storage resolves inside public profile");
        }
        Files.createDirectories(configuredRoot);
        Path realRoot = configuredRoot.toRealPath();
        if (profile != null && !profile.isBlank())
        {
            Path publicRoot = Path.of(profile).toAbsolutePath().normalize();
            if (Files.exists(publicRoot) && realRoot.startsWith(publicRoot.toRealPath()))
                throw new IOException("Private storage resolves inside public profile");
        }
        return realRoot;
    }

    private Path path(Long archiveId, String reference) throws IOException
    {
        if (!DatingPhotoReferences.belongsTo(reference, archiveId))
            throw new ServiceException("照片不存在或无权访问");
        Path root = root();
        Path directory = root.resolve(archiveId.toString());
        if (Files.exists(directory) && (Files.isSymbolicLink(directory)
            || !directory.toRealPath().startsWith(root)))
            throw new IOException("Invalid archive directory");
        Path file = directory.resolve(reference.substring(reference.lastIndexOf('/') + 1));
        if (Files.isSymbolicLink(file)) throw new IOException("Invalid photo file");
        return file;
    }

    public String save(Long archiveId, MultipartFile file)
    {
        return save(archiveId, file, "");
    }

    public String save(Long archiveId, MultipartFile file, String watermarkText)
    {
        if (archiveId == null || archiveId <= 0 || file == null || file.isEmpty()
            || file.getSize() > MAX_BYTES)
            throw new ServiceException("照片不能为空且不得超过5MB");
        try (InputStream input = file.getInputStream())
        {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new ServiceException("照片不得超过5MB");
            try (ImageInputStream imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes)))
            {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) throw new ServiceException("仅支持有效的PNG或JPEG照片");
                ImageReader reader = readers.next();
                try
                {
                    String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                    if (!format.equals("png") && !format.equals("jpeg"))
                        throw new ServiceException("仅支持有效的PNG或JPEG照片");
                    reader.setInput(imageInput, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || width > 6000 || height > 6000
                        || (long) width * height > 12_000_000)
                        throw new ServiceException("照片尺寸不得超过6000像素或1200万像素");
                    BufferedImage image = reader.read(0);
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    // Re-encoding discards original metadata and appended payloads.
                    if (!ImageIO.write(image, format, output) || output.size() > MAX_BYTES)
                        throw new ServiceException("照片处理后超过5MB或格式不受支持");
                    byte[] original = output.toByteArray();
                    boolean watermarked = watermarkText != null && !watermarkText.isBlank();
                    if (watermarked)
                    {
                        image = watermark(image, watermarkText);
                        output.reset();
                        if (format.equals("jpeg"))
                        {
                            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
                            try (var stream = ImageIO.createImageOutputStream(output))
                            {
                                writer.setOutput(stream);
                                var parameters = writer.getDefaultWriteParam();
                                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                                parameters.setCompressionQuality(0.95f);
                                writer.write(null, new IIOImage(image, null, null), parameters);
                            }
                            finally { writer.dispose(); }
                        }
                        else ImageIO.write(image, format, output);
                        if (output.size() > MAX_BYTES)
                            throw new ServiceException("添加水印后照片超过5MB，请选择体积更小的图片");
                    }
                    String reference = DatingPhotoReferences.prefix(archiveId)
                        + UUID.randomUUID().toString().replace("-", "") + (format.equals("png") ? ".png" : ".jpg");
                    Path target = path(archiveId, reference);
                    Files.createDirectories(target.getParent());
                    Path originalPath = null;
                    boolean originalCreated = false, targetCreated = false;
                    try
                    {
                        if (watermarked)
                        {
                            Path originals = target.getParent().resolve("originals");
                            Files.createDirectories(originals);
                            if (Files.isSymbolicLink(originals)
                                || !originals.toRealPath().startsWith(target.getParent().toRealPath()))
                                throw new IOException("Invalid originals directory");
                            originalPath = originals.resolve(target.getFileName());
                            Files.createFile(originalPath);
                            originalCreated = true;
                            Files.write(originalPath, original, StandardOpenOption.WRITE);
                        }
                        Files.createFile(target);
                        targetCreated = true;
                        Files.write(target, output.toByteArray(), StandardOpenOption.WRITE);
                    }
                    catch (IOException e)
                    {
                        if (originalCreated) Files.deleteIfExists(originalPath);
                        if (targetCreated) Files.deleteIfExists(target);
                        throw e;
                    }
                    return reference;
                }
                finally { reader.dispose(); }
            }
        }
        catch (IOException e) { throw new ServiceException("照片保存失败，请联系管理员"); }
    }

    private BufferedImage watermark(BufferedImage source, String text)
    {
        int width = source.getWidth(), height = source.getHeight();
        var image = new BufferedImage(width, height, source.getColorModel().hasAlpha()
            ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try
        {
            g.drawImage(source, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Font font = new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(12, Math.min(width, height) / 22));
            int measured = g.getFontMetrics(font).stringWidth(text);
            if (measured > width * 0.75)
                font = font.deriveFont(Math.max(1f, font.getSize2D() * width * 0.75f / measured));
            g.setFont(font);
            int textWidth = Math.max(1, g.getFontMetrics().stringWidth(text));
            int stepX = textWidth + Math.max(30, width / 8);
            int stepY = Math.max(30, g.getFontMetrics().getHeight() * 5);
            g.rotate(-Math.PI / 6, width / 2.0, height / 2.0);
            g.setComposite(AlphaComposite.SrcOver.derive(0.22f));
            int extent = (int) Math.ceil(Math.hypot(width, height));
            for (int y = -extent; y < extent * 2; y += stepY)
                for (int x = -extent; x < extent * 2; x += stepX)
                {
                    g.setColor(Color.BLACK);
                    g.drawString(text, x + 1, y + 1);
                    g.setColor(Color.WHITE);
                    g.drawString(text, x, y);
                }
            return image;
        }
        finally { g.dispose(); }
    }

    public void validate(Long archiveId, String reference)
    {
        try
        {
            if (!Files.isRegularFile(path(archiveId, reference), LinkOption.NOFOLLOW_LINKS))
                throw new ServiceException("照片不存在或无权访问");
        }
        catch (IOException e) { throw new ServiceException("照片不存在或无权访问"); }
    }

    public byte[] read(Long archiveId, String reference)
    {
        validate(archiveId, reference);
        try (InputStream input = Files.newInputStream(path(archiveId, reference)))
        {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new ServiceException("照片读取失败");
            return bytes;
        }
        catch (IOException e) { throw new ServiceException("照片不存在或无权访问"); }
    }

    public long size(Long archiveId, String reference)
    {
        validate(archiveId, reference);
        try
        {
            long size = Files.size(path(archiveId, reference));
            if (size <= 0 || size > MAX_BYTES) throw new ServiceException("照片读取失败");
            return size;
        }
        catch (IOException e) { throw new ServiceException("照片不存在或无权访问"); }
    }
}
