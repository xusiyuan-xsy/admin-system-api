package com.ruoyi.web.controller.common;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.ruoyi.common.core.domain.AjaxResult;

/**
 * 阿里云 OSS 图片上传
 */
@RestController
@RequestMapping("/oss")
public class OssController
{
    private static final Logger log = LoggerFactory.getLogger(OssController.class);

    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    @Value("${aliyun.oss.accessKeyId}")
    private String accessKeyId;

    @Value("${aliyun.oss.accessKeySecret}")
    private String accessKeySecret;

    @Value("${aliyun.oss.bucketName}")
    private String bucketName;

    /**
     * 单图上传到 OSS
     */
    @PostMapping("/upload")
    public AjaxResult upload(@RequestParam("file") MultipartFile file)
    {
        OSS ossClient = null;
        try
        {
            String originalFilename = file.getOriginalFilename();
            String suffix = (originalFilename != null && originalFilename.contains("."))
                    ? originalFilename.substring(originalFilename.lastIndexOf("."))
                    : "";
            String datePath = new SimpleDateFormat("yyyy/MM/dd").format(new Date());
            String objectKey = datePath + "/" + UUID.randomUUID().toString().replace("-", "") + suffix;

            InputStream inputStream = file.getInputStream();
            ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
            ossClient.putObject(bucketName, objectKey, inputStream);

            String url = "https://" + bucketName + "." + endpoint + "/" + objectKey;

            AjaxResult ajax = AjaxResult.success();
            ajax.put("url", url);
            ajax.put("fileName", objectKey);
            ajax.put("originalFilename", originalFilename);
            return ajax;
        }
        catch (Exception e)
        {
            log.error("OSS文件上传失败", e);
            return AjaxResult.error("文件上传失败：" + e.getMessage());
        }
        finally
        {
            if (ossClient != null)
            {
                ossClient.shutdown();
            }
        }
    }
}