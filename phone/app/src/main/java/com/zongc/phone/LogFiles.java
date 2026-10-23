package com.zongc.phone;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.zongc.phone.core.CsvLog;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 实验数据文件。
 *
 * <ul>
 *   <li>安卓 10 及以上：写到公共"下载"目录的 MiniDiDi 文件夹（Download/MiniDiDi），
 *       文件管理器里直接能看到，用数据线连电脑也能拷出来，不需要存储权限；</li>
 *   <li>安卓 8~9：写到 App 专属目录 Android/data/com.zongc.phone/files/logs。</li>
 * </ul>
 */
final class LogFiles {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 一次记录：位姿 + 原始传感器两个文件。 */
    static final class Recording {
        final CsvLog pose;
        final CsvLog imu;
        /** 给用户看的保存位置。 */
        final String where;
        final String stamp;

        Recording(CsvLog pose, CsvLog imu, String where, String stamp) {
            this.pose = pose;
            this.imu = imu;
            this.where = where;
            this.stamp = stamp;
        }

        void close() {
            pose.close();
            imu.close();
        }

        String error() {
            return pose.error() != null ? pose.error() : imu.error();
        }
    }

    private LogFiles() {
    }

    static Recording open(Context ctx, long t0) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        String poseName = "pose_" + stamp + ".csv";
        String imuName = "imu_" + stamp + ".csv";
        OutputStream poseOut;
        OutputStream imuOut;
        String where;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            poseOut = openDownload(ctx, poseName);
            imuOut = openDownload(ctx, imuName);
            where = "手机存储 / Download / MiniDiDi";
        } else {
            File dir = new File(ctx.getExternalFilesDir(null), "logs");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建目录 " + dir);
            poseOut = new FileOutputStream(new File(dir, poseName));
            imuOut = new FileOutputStream(new File(dir, imuName));
            where = dir.getAbsolutePath();
        }
        CsvLog pose = new CsvLog(writer(poseOut), CsvLog.POSE_HEADER, t0);
        CsvLog imu = new CsvLog(writer(imuOut), CsvLog.IMU_HEADER, t0);
        return new Recording(pose, imu, where, stamp);
    }

    private static Writer writer(OutputStream os) {
        return new BufferedWriter(new OutputStreamWriter(os, UTF8), 1 << 16);
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private static OutputStream openDownload(Context ctx, String name) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MiniDiDi");
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("无法在“下载”目录创建 " + name);
        OutputStream os = cr.openOutputStream(uri);
        if (os == null) throw new IOException("无法写入 " + name);
        return os;
    }
}
