package me.weishu.kernelsu.ui.webui;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.webkit.WebResourceResponse;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.webkit.WebViewAssetLoader;

import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.io.SuFile;
import com.topjohnwu.superuser.io.SuFileInputStream;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Handler class to open files from file system by root access
 * For more information about android storage please refer to
 * <a href="https://developer.android.com/guide/topics/data/data-storage">Android Developers
 * Docs: Data and file storage overview</a>.
 * <p class="note">
 * To avoid leaking user or app data to the web, make sure to choose {@code directory}
 * carefully, and assume any file under this directory could be accessed by any web page subject
 * to same-origin rules.
 * <p>
 * A typical usage would be like:
 * <pre class="prettyprint">
 * File publicDir = new File(context.getFilesDir(), "public");
 * // Host "files/public/" in app's data directory under:
 * // http://appassets.androidplatform.net/public/...
 * WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
 *          .addPathHandler("/public/", new InternalStoragePathHandler(context, publicDir))
 *          .build();
 * </pre>
 *
 * 说明：本实现用 root 读取 webroot，因此仅限内核模块的本地页面使用；
 * 必须限制在允许的目录内，避免把系统敏感文件暴露给网页。
 */
public final class SuFilePathHandler implements WebViewAssetLoader.PathHandler {
    /**
     * Default value to be used as MIME type if guessing MIME type failed.
     */
    public static final String DEFAULT_MIME_TYPE = "text/plain";
    private static final String TAG = "SuFilePathHandler";
    /**
     * Forbidden subdirectories of {@link Context#getDataDir} that cannot be exposed by this
     * handler. They are forbidden as they often contain sensitive information.
     * <p class="note">
     * Note: Any future addition to this list will be considered breaking changes to the API.
     */
    // 这些目录包含其他应用/系统敏感数据，任何情况下都不允许通过 webroot 暴露。
    private static final String[] FORBIDDEN_DATA_DIRS =
            new String[]{"/data/data", "/data/system"};

    // 模块的 webroot 目录（规范化并带结尾斜杠）。
    @NonNull
    private final File mDirectory;

    // 用于以 root 读取 webroot 文件的 shell。
    private final Shell mShell;
    // 提供当前窗口 insets，用于注入 internal/insets.css。
    private final InsetsSupplier mInsetsSupplier;
    // 网页请求 insets 时回调，通知宿主启用 insets 模式。
    private final OnInsetsRequestedListener mOnInsetsRequestedListener;
    // 读取主题/设置偏好，用于 colors.css。
    private final Context mContext;

    /**
     * Creates PathHandler for app's internal storage.
     * The directory to be exposed must be inside either the application's internal data
     * directory {@link Context#getDataDir} or cache directory {@link Context#getCacheDir}.
     * External storage is not supported for security reasons, as other apps with
     * {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE} may be able to modify the
     * files.
     * <p>
     * Exposing the entire data or cache directory is not permitted, to avoid accidentally
     * exposing sensitive application files to the web. Certain existing subdirectories of
     * {@link Context#getDataDir} are also not permitted as they are often sensitive.
     * These files are ({@code "app_webview/"}, {@code "databases/"}, {@code "lib/"},
     * {@code "shared_prefs/"} and {@code "code_cache/"}).
     * <p>
     * The application should typically use a dedicated subdirectory for the files it intends to
     * expose and keep them separate from other files.
     *
     * @param context                   {@link Context} that is used to access app's internal storage.
     * @param directory                 the absolute path of the exposed app internal storage directory from
     *                                  which files can be loaded.
     * @param rootShell                 {@link Shell} instance with root access to read files.
     * @param insetsSupplier            {@link InsetsSupplier} to provide window insets for styling web content.
     * @param onInsetsRequestedListener {@link OnInsetsRequestedListener} to notify when insets are requested.
     * @throws IllegalArgumentException if the directory is not allowed.
     *
     * 说明：构造时会对目录做 canonical 化并校验禁止目录，防止通过 `..`
     * 或符号链接绕过限制。
     */
    public SuFilePathHandler(@NonNull Context context, @NonNull File directory, Shell rootShell, @NonNull InsetsSupplier insetsSupplier, OnInsetsRequestedListener onInsetsRequestedListener) {
        try {
            mContext = context;
            mInsetsSupplier = insetsSupplier;
            mOnInsetsRequestedListener = onInsetsRequestedListener;
            mDirectory = new File(getCanonicalDirPath(directory));
            if (!isAllowedInternalStorageDir(context)) {
                throw new IllegalArgumentException("The given directory \"" + directory
                        + "\" doesn't exist under an allowed app internal storage directory");
            }
            mShell = rootShell;
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Failed to resolve the canonical path for the given directory: "
                            + directory.getPath(), e);
        }
    }

    /**
     * 返回带结尾斜杠的规范化目录路径。
     *
     * 结尾斜杠用于前缀比较，避免 /data/foo 误匹配 /data/foobar。
     */
    public static String getCanonicalDirPath(@NonNull File file) throws IOException {
        String canonicalPath = file.getCanonicalPath();
        if (!canonicalPath.endsWith("/")) canonicalPath += "/";
        return canonicalPath;
    }

    /**
     * 把 child 解析到 parent 下；若解析结果逃逸出 parent 则返回 null。
     *
     * 这是防止路径穿越（包括符号链接）的关键校验。
     */
    public static File getCanonicalFileIfChild(@NonNull File parent, @NonNull String child)
            throws IOException {
        String parentCanonicalPath = getCanonicalDirPath(parent);
        String childCanonicalPath = new File(parent, child).getCanonicalPath();
        if (childCanonicalPath.startsWith(parentCanonicalPath)) {
            return new File(childCanonicalPath);
        }
        return null;
    }

    // .svgz 是 gzip 压缩的 SVG，WebView 不认识该编码，需要先解压。
    @NonNull
    private static InputStream handleSvgzStream(@NonNull String path,
                                                @NonNull InputStream stream) throws IOException {
        return path.endsWith(".svgz") ? new GZIPInputStream(stream) : stream;
    }

    /**
     * 以 root 打开指定文件并返回可用于 WebView 的输入流。
     *
     * @param file  要打开的文件。
     * @param shell 具备 root 权限的 shell。
     */
    public static InputStream openFile(@NonNull File file, @NonNull Shell shell) throws IOException {
        SuFile suFile = new SuFile(file.getAbsolutePath());
        suFile.setShell(shell);
        InputStream fis = SuFileInputStream.open(suFile);
        return handleSvgzStream(file.getPath(), fis);
    }

    /**
     * Use {@link MimeUtil#getMimeFromFileName} to guess MIME type or return the
     * {@link #DEFAULT_MIME_TYPE} if it can't guess.
     *
     * @param filePath path of the file to guess its MIME type.
     * @return MIME type guessed from file extension or {@link #DEFAULT_MIME_TYPE}.
     *
     * 说明：无法识别时用 text/plain，保证 WebView 至少能展示文本而不是直接报错。
     */
    @NonNull
    public static String guessMimeType(@NonNull String filePath) {
        String mimeType = MimeUtil.getMimeFromFileName(filePath);
        return mimeType == null ? DEFAULT_MIME_TYPE : mimeType;
    }

    // 校验 webroot 是否落在禁止目录内；命中任一禁止前缀即拒绝。
    private boolean isAllowedInternalStorageDir(@NonNull Context context) throws IOException {
        String dir = getCanonicalDirPath(mDirectory);

        for (String forbiddenPath : FORBIDDEN_DATA_DIRS) {
            if (dir.startsWith(forbiddenPath)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Opens the requested file from the exposed data directory.
     * <p>
     * The matched prefix path used shouldn't be a prefix of a real web path. Thus, if the
     * requested file cannot be found or is outside the mounted directory a
     * {@link WebResourceResponse} object with a {@code null} {@link InputStream} will be
     * returned instead of {@code null}. This saves the time of falling back to network and
     * trying to resolve a path that doesn't exist. A {@link WebResourceResponse} with
     * {@code null} {@link InputStream} will be received as an HTTP response with status code
     * {@code 404} and no body.
     * <p class="note">
     * The MIME type for the file will be determined from the file's extension using
     * {@link java.net.URLConnection#guessContentTypeFromName}. Developers should ensure that
     * files are named using standard file extensions. If the file does not have a
     * recognised extension, {@code "text/plain"} will be used by default.
     *
     * @param path the suffix path to be handled.
     * @return {@link WebResourceResponse} for the requested file.
     *
     * 说明：除真实文件外还虚拟了 internal/insets.css 与 internal/colors.css，
     * 由宿主动态生成主题与安全区变量。该方法会在工作线程调用。
     */
    @Override
    @WorkerThread
    @NonNull
    public WebResourceResponse handle(@NonNull String path) {
        // 网页显式引用 insets.css 表示它需要安全区适配。
        if ("internal/insets.css".equals(path)) {
            if (mOnInsetsRequestedListener != null) {
                mOnInsetsRequestedListener.onInsetsRequested(true);
            }
            String css = mInsetsSupplier.get().getCss();
            return new WebResourceResponse(
                    "text/css",
                    "utf-8",
                    new ByteArrayInputStream(css.getBytes(StandardCharsets.UTF_8))
            );
        }
        // 主题色变量；只有 Material 或 Monet 动态取色模式才输出，避免覆盖网页自身配色。
        if ("internal/colors.css".equals(path)) {
            SharedPreferences prefs = mContext.getSharedPreferences("settings", Context.MODE_PRIVATE);
            int colorMode = prefs.getInt("color_mode", 0);
            String uiMode = prefs.getString("ui_mode", "miuix");
            String css = "";
            if ((colorMode >= 3 && colorMode <= 6) || "material".equals(uiMode)) {
                css = MonetColorsProvider.INSTANCE.getColorsCss();
            }
            return new WebResourceResponse(
                    "text/css",
                    "utf-8",
                    new ByteArrayInputStream(css.getBytes(StandardCharsets.UTF_8))
            );
        }
        try {
            File file = getCanonicalFileIfChild(mDirectory, path);
            if (file != null) {
                InputStream is = openFile(file, mShell);
                String mimeType = guessMimeType(path);
                return new WebResourceResponse(mimeType, null, is);
            } else {
                Log.e(TAG, String.format(
                        "The requested file: %s is outside the mounted directory: %s", path,
                        mDirectory));
            }
        } catch (IOException e) {
            Log.e(TAG, "Error opening the requested path: " + path, e);
        }
        // 返回 null 输入流等价于 HTTP 404，避免 WebView 再回退到网络解析。
        return new WebResourceResponse(null, null, null);
    }

    /** 提供当前窗口 insets，用于生成 internal/insets.css。 */
    public interface InsetsSupplier {
        @NonNull
        Insets get();
    }

    /** 网页请求 insets 时的回调，宿主据此开启/关闭 insets 模式。 */
    public interface OnInsetsRequestedListener {
        void onInsetsRequested(boolean enable);
    }
}
