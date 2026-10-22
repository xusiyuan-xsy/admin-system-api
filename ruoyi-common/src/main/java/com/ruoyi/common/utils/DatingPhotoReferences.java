package com.ruoyi.common.utils;

/** Archive-bound references, never arbitrary URLs or filesystem paths. */
public final class DatingPhotoReferences
{
    private DatingPhotoReferences() { }

    public static String prefix(Long archiveId)
    {
        return "/dating/archives/" + archiveId + "/photos/";
    }

    public static boolean belongsTo(String reference, Long archiveId)
    {
        return archiveId != null && archiveId > 0 && reference != null
            && reference.startsWith(prefix(archiveId))
            && reference.substring(prefix(archiveId).length()).matches("[a-f0-9]{32}\\.(png|jpg)");
    }
}
