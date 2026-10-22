package com.ruoyi.web.controller.dating;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.DatingPhotoReferences;
import com.ruoyi.system.service.DatingArchiveAccess;
import com.ruoyi.system.service.DatingPhotoStorage;
import com.ruoyi.system.service.DatingMatchmakerConfigService;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/dating/archives/{archiveId}/photos")
public class DatingPhotoController
{
    private final DatingArchiveAccess access;
    private final DatingPhotoStorage storage;
    private final DatingMatchmakerConfigService config;

    public DatingPhotoController(DatingArchiveAccess access, DatingPhotoStorage storage, DatingMatchmakerConfigService config)
    {
        this.access = access;
        this.storage = storage;
        this.config = config;
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermi('system:payingUsers:edit')")
    @Log(title = "档案照片上传", businessType = BusinessType.INSERT,
        isSaveRequestData = false, isSaveResponseData = false)
    public AjaxResult upload(@PathVariable Long archiveId, @RequestParam("file") MultipartFile file)
    {
        access.check(archiveId, false);
        var active = config.active();
        String reference = storage.save(archiveId, file, active == null ? "" : active.getWatermarkText());
        AjaxResult result = AjaxResult.success();
        result.put("url", reference);
        result.put("fileName", reference);
        return result;
    }

    @GetMapping("/{fileName}")
    @PreAuthorize("@ss.hasAnyPermi('system:payingUsers:query,system:payingUsers:list,system:payingRequirement:search')")
    @Log(title = "档案照片读取", businessType = BusinessType.OTHER,
        isSaveRequestData = false, isSaveResponseData = false)
    public ResponseEntity<byte[]> read(@PathVariable Long archiveId, @PathVariable String fileName)
    {
        access.check(archiveId, false);
        byte[] bytes = storage.read(archiveId, DatingPhotoReferences.prefix(archiveId) + fileName);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Disposition", "inline; filename=\"photo." + (fileName.endsWith(".png") ? "png" : "jpg") + "\"")
            .contentType(fileName.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG)
            .body(bytes);
    }
}
