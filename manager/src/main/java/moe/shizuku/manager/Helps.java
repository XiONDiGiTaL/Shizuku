package moe.shizuku.manager;

import moe.shizuku.manager.utils.MultiLocaleEntity;
import moe.shizuku.manager.xion.XionLinks;

/**
 * In-app help targets.
 * <p>
 * Every entry resolves to this fork's own repository: the guides that used to live on the
 * upstream project's website do not describe this build, and this app should not send anyone to
 * a third-party project it does not belong to.
 */
public class Helps {

    public static final MultiLocaleEntity ADB = new MultiLocaleEntity();
    public static final MultiLocaleEntity ADB_ANDROID11 = new MultiLocaleEntity();
    public static final MultiLocaleEntity APPS = new MultiLocaleEntity();
    public static final MultiLocaleEntity HOME = new MultiLocaleEntity();
    public static final MultiLocaleEntity DOWNLOAD = new MultiLocaleEntity();
    public static final MultiLocaleEntity ADB_PERMISSION = new MultiLocaleEntity();

    static {
        ADB.put("en", XionLinks.REPO);
        ADB.put("zh-CN", XionLinks.REPO);
        ADB.put("zh-TW", XionLinks.REPO);

        ADB_ANDROID11.put("en", XionLinks.REPO);
        ADB_ANDROID11.put("zh-CN", XionLinks.REPO);
        ADB_ANDROID11.put("zh-TW", XionLinks.REPO);

        APPS.put("en", XionLinks.REPO);
        APPS.put("zh-CN", XionLinks.REPO);
        APPS.put("zh-TW", XionLinks.REPO);

        HOME.put("en", XionLinks.REPO);

        DOWNLOAD.put("en", XionLinks.RELEASES);
        DOWNLOAD.put("zh-CN", XionLinks.RELEASES);
        DOWNLOAD.put("zh-TW", XionLinks.RELEASES);

        ADB_PERMISSION.put("en", XionLinks.REPO);
        ADB_PERMISSION.put("zh-CN", XionLinks.REPO);
        ADB_PERMISSION.put("zh-TW", XionLinks.REPO);
    }
}
