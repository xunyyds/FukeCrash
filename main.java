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

boolean isKillerRunning = true;
Thread.UncaughtExceptionHandler originalHandler;
ExecutorService logExecutor = Executors.newSingleThreadExecutor();

String getEnvInfo() {
    StringBuilder sb = new StringBuilder();
    try {
        PackageInfo p = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        sb.append("HostPackage: ").append(context.getPackageName()).append("\n");
        sb.append("HostVersion: ").append(p.versionName).append("(").append(Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode).append(")\n");
        sb.append("ModuleVersion: ").append(BuildConfig.VERSION_NAME).append("(").append(BuildConfig.VERSION_CODE).append(")\n");
        sb.append("Framework: ").append(HookEngineManager.engine.getFrameworkName()).append("\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        sb.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n");
    } catch (Exception e) {}
    return sb.toString();
}

void writeToZip(ZipOutputStream zos, String fileName, String content) {
    try {
        zos.putNextEntry(new ZipEntry(fileName));
        zos.write(content.getBytes("UTF-8"));
        zos.closeEntry();
    } catch (Exception e) {}
}

void processCrash(String type, Thread t, Throwable e) {
    Date d = new Date();
    String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(d);
    String logTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    String envData = getEnvInfo();
    
    StringBuilder javaTrace = new StringBuilder();
    javaTrace.append("Thread:").append(t.getName()).append("\n");
    javaTrace.append("Exception:").append(e.toString()).append("\n\n");
    
    if (e instanceof bsh.EvalError || e.toString().contains("bsh.")) {
        try {
            java.lang.reflect.Method m = e.getClass().getMethod("getScriptStackTrace", new Class[0]);
            String scriptStack = (String) m.invoke(e, new Object[0]);
            javaTrace.append(scriptStack).append("\n");
        } catch (Exception ignored) {}
    }
    
    StringWriter sw = new StringWriter();
    e.printStackTrace(new PrintWriter(sw));
    javaTrace.append(sw.toString());

    StringBuilder mainThreadTrace = new StringBuilder();
    StackTraceElement[] mainSt = Looper.getMainLooper().getThread().getStackTrace();
    for (StackTraceElement ste : mainSt) mainThreadTrace.append(ste.toString()).append("\n");

    StringBuilder allThreads = new StringBuilder();
    for (Map.Entry entry : Thread.getAllStackTraces().entrySet()) {
        Thread thr = (Thread) entry.getKey();
        allThreads.append("Thread: ").append(thr.getName()).append(" [ID: ").append(thr.getId()).append(", State: ").append(thr.getState()).append("]\n");
        for (StackTraceElement ste : (StackTraceElement[]) entry.getValue()) allThreads.append("  at ").append(ste).append("\n");
        allThreads.append("\n");
    }

    logExecutor.submit(() -> {
        try {
            File root = new File(pluginPath, "Log");
            if (!root.exists()) root.mkdirs();
            File zipFile = new File(root, "Crash_" + ts + ".zip");
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));

            writeToZip(zos, "java_trace.txt", javaTrace.toString());
            writeToZip(zos, "env_info.txt", envData + "CrashTime: " + logTime + "\nInterception: " + type);
            
            try {
                BufferedReader br = new BufferedReader(new FileReader("/proc/self/status"));
                StringBuilder sbStatus = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sbStatus.append(line).append("\n");
                br.close();
                writeToZip(zos, "proc_status.txt", sbStatus.toString());
            } catch (Exception ignored) {}

            writeToZip(zos, "RecentTrace/host_main_thread_snapshot.txt", mainThreadTrace.toString());
            writeToZip(zos, "all_threads.txt", allThreads.toString());

            try {
                Process p = Runtime.getRuntime().exec("logcat -d -v threadtime -t 2500");
                InputStream is = p.getInputStream();
                zos.putNextEntry(new ZipEntry("logcat.txt"));
                byte[] b = new byte[8192];
                int len;
                while ((len = is.read(b)) > 0) zos.write(b, 0, len);
                zos.closeEntry();
                is.close();
            } catch (Exception ignored) {}

            zos.close();
            qqToast(1, "已阻止" + type + "闪退 日志: Crash_" + ts + ".zip");
        } catch (Exception fatal) {
            log("critical_crash_report_error.txt", android.util.Log.getStackTraceString(fatal));
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
    Thread.setDefaultUncaughtExceptionHandler((t, e) -> processCrash("子线程", t, e));
} catch (Exception e) {
    log("error.log", "ExceptionHandler Error: " + e.getMessage());
}

void unLoadPlugin() {
    isKillerRunning = false;
    if (originalHandler != null) Thread.setDefaultUncaughtExceptionHandler(originalHandler);
    logExecutor.shutdown();
}

/*
 * 如果想知道脚本有没有生效
 * 你只需要把这些注释语句删除后重新启动脚本
 * 如果看到了提示“拦截到xxx崩溃”
 * 那么就证明脚本已经生效了

	new Handler(Looper.getMainLooper()).post(() -> {
	        throw new RuntimeException("主线程崩溃测试");
	    });
*/