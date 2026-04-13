import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.content.pm.PackageInfo;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.*;
import java.util.concurrent.*;
import me.yxp.qfun.BuildConfig;
import me.yxp.qfun.loader.hookapi.HookEngineManager;
import me.yxp.qfun.utils.log.LogUtils;

boolean isKillerRunning = true;
Thread.UncaughtExceptionHandler originalHandler;
ExecutorService logExecutor = Executors.newSingleThreadExecutor();

void writeZip(ZipOutputStream z, String n, String c) {
    try {
        z.putNextEntry(new ZipEntry(n));
        z.write(c.getBytes("UTF-8"));
        z.closeEntry();
    } catch (Exception e) {}
}

void processCrash(String type, Thread t, Throwable e) {
    Date d = new Date();
    String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(d);
    String lt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    String env = LogUtils.INSTANCE.getEnvironmentInfo();
    
    StringBuilder trace = new StringBuilder();
    trace.append("Thread:").append(t.getName()).append("\n");
    trace.append("Exception:").append(e.toString()).append("\n\n");
    if (e.toString().contains("bsh.")) {
        try {
            java.lang.reflect.Method m = e.getClass().getMethod("getScriptStackTrace", new Class[0]);
            trace.append((String)m.invoke(e, new Object[0])).append("\n");
        } catch (Exception ignored) {}
    }
    StringWriter sw = new StringWriter();
    e.printStackTrace(new PrintWriter(sw));
    trace.append(sw.toString());

    StringBuilder mainTrace = new StringBuilder();
    for (StackTraceElement s : Looper.getMainLooper().getThread().getStackTrace()) mainTrace.append(s).append("\n");

    StringBuilder allTs = new StringBuilder();
    for (Map.Entry entry : Thread.getAllStackTraces().entrySet()) {
        Thread thr = (Thread)entry.getKey();
        allTs.append("Thread: ").append(thr.getName()).append(" [ID: ").append(thr.getId()).append("]\n");
        for (StackTraceElement ste : (StackTraceElement[])entry.getValue()) allTs.append("  at ").append(ste).append("\n");
        allTs.append("\n");
    }

    logExecutor.submit(() -> {
        try {
            File root = new File(pluginPath, "Log");
            root.mkdirs();
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(new File(root, "Crash_" + ts + ".zip")));

            writeZip(zos, "java_trace.txt", trace.toString());
            writeZip(zos, "env_info.txt", env + "\nCrash Time: " + lt + "\nInterception Type: " + type);
            writeZip(zos, "RecentTrace/host_main_thread_trace.txt", mainTrace.toString());
            writeZip(zos, "all_threads.txt", allTs.toString());

            try {
                BufferedReader br = new BufferedReader(new FileReader("/proc/self/status"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
                br.close();
                writeZip(zos, "proc_status.txt", sb.toString());
            } catch (Exception ignored) {}

            try {
                Process p = Runtime.getRuntime().exec("logcat -d -v threadtime -t 3000");
                InputStream is = p.getInputStream();
                zos.putNextEntry(new ZipEntry("logcat.txt"));
                byte[] b = new byte[8192];
                int l;
                while ((l = is.read(b)) > 0) zos.write(b, 0, l);
                zos.closeEntry();
                is.close();
            } catch (Exception ignored) {}

            zos.close();
            qqToast(1, "已阻止" + type + "崩溃 日志: Crash_" + ts + ".zip");
        } catch (Exception fatal) {
            log("Log/fatal.log", android.util.Log.getStackTraceString(fatal));
        }
    });
}

new Handler(Looper.getMainLooper()).post(() -> {
    while (isKillerRunning) {
        try {
            Looper.loop();
        } catch (Throwable e) {
            if (!isKillerRunning) break;
            processCrash("主线程", Thread.currentThread(), e);
        }
    }
});

try {
    originalHandler = Thread.getDefaultUncaughtExceptionHandler();
    java.lang.reflect.Field f = Thread.class.getDeclaredField("defaultUncaughtExceptionHandler");
    f.setAccessible(true);
    f.set(null, (Thread.UncaughtExceptionHandler) (t, e) -> processCrash("子线程", t, e));
} catch (Exception e) {
    Thread.setDefaultUncaughtExceptionHandler((t, e) -> processCrash("子线程", t, e));
}

void unLoadPlugin() {
    isKillerRunning = false;
    if (originalHandler != null) Thread.setDefaultUncaughtExceptionHandler(originalHandler);
    logExecutor.shutdown();
}

/*
 * 如果想知道脚本有没有生效
 * 你只需要把这些注释语句删除后重新启动脚本
 * 如果看到了提示“已阻止xxx闪退”
 * 那么就证明脚本已经生效了

	new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
	        throw new RuntimeException("主线程崩溃测试");
	    });
	    
    new Thread(() -> {
            throw new RuntimeException("子线程崩溃测试");
        }).start();
*/