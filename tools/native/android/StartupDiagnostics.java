package org.triaevum.android;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.Build;
import java.util.*;

/** Local report containing checkpoints and recognized errors, without raw exception messages. */
public final class StartupDiagnostics extends Application {
    static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("startup-diagnostics", 0); }
    static synchronized void stage(Context c, String stage) { prefs(c).edit().putString("stage", stage).commit(); }
    static void graphics(Context c, int api, int vendor, int driver) {
        String value = ((api >>> 22) & 127) + "." + ((api >>> 12) & 1023) + "." + (api & 4095)
            + ", vendor=0x" + Integer.toHexString(vendor) + ", driver=0x" + Integer.toHexString(driver);
        prefs(c).edit().putString("runtime_vulkan", value).commit();
    }
    static void candidate(Context c, int api, int vendor, int driver) {
        String value = ((api >>> 22) & 127) + "." + ((api >>> 12) & 1023) + "." + (api & 4095)
            + ", vendor=0x" + Integer.toHexString(vendor) + ", driver=0x" + Integer.toHexString(driver);
        String previous = prefs(c).getString("vulkan_candidates", "");
        if (previous.length() < 1024) prefs(c).edit().putString("vulkan_candidates",
            previous.isEmpty() ? value : previous + "\n" + value).commit();
    }
    static synchronized void nativeException(Context c, String message) {
        String detail = StartupReport.nativeException(message);
        nativeStage(c, 14);
        prefs(c).edit().putString("failure", prefs(c).getString("failure", "")
            + "\nNative error: " + detail
            + "\nVulkan candidates:\n" + prefs(c).getString("vulkan_candidates", "not captured")).commit();
    }
    static synchronized void nativeStage(Context c, int code) {
        String[] stages = {"native entry", "loading game module", "game runtime setup", "reading process manifest",
            "loading fonts and UI resources", "allocating guest memory", "guest memory allocated", "creating renderer context",
            "Vulkan instance/device initialization", "Vulkan swapchain/resources", "preparing shaders",
            "starting guest execution", "guest initialization complete", "first frame processing", "native runtime exception",
            "creating Vulkan instance", "creating Vulkan surface", "selecting Vulkan physical device",
            "creating Vulkan logical device", "creating Vulkan pipeline cache", "creating Vulkan command resources"};
        if (code < 0 || code >= stages.length) return;
        String snapshot = "memory unavailable";
        try {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            c.getSystemService(ActivityManager.class).getMemoryInfo(memory);
            snapshot = "available MiB=" + memory.availMem / 1048576 + ", system lowMemory=" + memory.lowMemory
                + ", process PSS KiB=" + android.os.Debug.getPss()
                + ", native heap MiB=" + android.os.Debug.getNativeHeapAllocatedSize() / 1048576;
        } catch (RuntimeException ignored) { }
        android.content.SharedPreferences p = prefs(c);
        String[] old = p.getString("native_history", "").split("\n");
        StringBuilder history = new StringBuilder();
        for (int i = Math.max(0, old.length - 11); i < old.length; i++)
            if (!old[i].isEmpty()) history.append(old[i]).append('\n');
        history.append(stages[code]).append(": ").append(snapshot);
        android.content.SharedPreferences.Editor edit = p.edit().putString("stage", stages[code])
            .putString("native_stage", stages[code]).putString("native_history", history.toString());
        if (code == 14) edit.putBoolean("pending", true).putString("failure",
            "Native runtime returned an exception.\nLast native stage: " + p.getString("native_stage", "not captured")
                + "\nLoaded graphics driver: " + p.getString("active_driver", "not captured")
                + "\nRuntime Vulkan: " + p.getString("runtime_vulkan", "not captured")
                + "\nStartup checkpoints:\n" + history);
        edit.commit();
    }
    @Override public void onCreate() {
        super.onCreate();
        // ROM preparation has its own process and must not overwrite game startup evidence.
        if (!getPackageName().equals(Application.getProcessName())) return;
        android.content.SharedPreferences p = prefs(this);
        long previous = p.getLong("session", 0);
        String failure = "";
        if (Build.VERSION.SDK_INT >= 30 && previous > 0) {
            try {
                for (ApplicationExitInfo exit : getSystemService(ActivityManager.class).getHistoricalProcessExitReasons(null, 0, 12)) {
                    if (!getPackageName().equals(exit.getProcessName()) || exit.getTimestamp() < previous) continue;
                    int reason = exit.getReason();
                    if (reason == ApplicationExitInfo.REASON_CRASH || reason == ApplicationExitInfo.REASON_CRASH_NATIVE || reason == ApplicationExitInfo.REASON_ANR || reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE || reason == ApplicationExitInfo.REASON_LOW_MEMORY) {
                        failure = "Android exit reason=" + reason + " (" + exitName(reason) + "), status=" + exit.getStatus()
                            + "\nLast stage: " + p.getString("stage", "unknown")
                            + "\nLast sampled exit PSS KiB=" + exit.getPss() + ", RSS KiB=" + exit.getRss()
                            + "; these samples are not peak memory."
                            + "\nLast native stage: " + p.getString("native_stage", "not captured")
                            + "\nLoaded graphics driver: " + p.getString("active_driver", "not captured")
                            + "\nRuntime Vulkan: " + p.getString("runtime_vulkan", "not captured")
                            + "\nVulkan candidates:\n" + p.getString("vulkan_candidates", "not captured")
                            + "\nStartup checkpoints:\n" + p.getString("native_history", "not captured");
                        if (reason == ApplicationExitInfo.REASON_CRASH_NATIVE && Build.VERSION.SDK_INT >= 31) {
                            try { failure += "\n" + NativeCrashReport.read(exit.getTraceInputStream()); }
                            catch (java.io.IOException | RuntimeException ignored) { failure += "\nNative trace unavailable."; }
                        }
                    }
                    break;
                }
            } catch (RuntimeException ignored) { }
        }
        // Keep the structural Java trace recorded by the uncaught handler as well as Android's exit reason.
        if (!failure.isEmpty() && p.getLong("java_session", -1) == previous)
            failure += "\n" + p.getString("java_failure", "");
        android.content.SharedPreferences.Editor edit = p.edit().putLong("session", System.currentTimeMillis())
            .remove("active_driver").remove("native_stage").remove("native_history").remove("runtime_vulkan").remove("vulkan_candidates")
            .remove("capture_next_run");
        if (!failure.isEmpty()) edit.putString("failure", failure).putBoolean("pending", true);
        edit.commit();
        Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try { failure(this, error); }
            catch (Throwable ignored) { }
            if (previousHandler != null) previousHandler.uncaughtException(thread, error);
            else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
        });
    }
    private static String exitName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH: return "Java crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native crash";
            case ApplicationExitInfo.REASON_ANR: return "not responding";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "initialization failure";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "low memory";
            default: return "other";
        }
    }
    static void failure(Context c, Throwable error) {
        String detail = "Java failure at " + prefs(c).getString("stage", "unknown") + "\n" + StartupReport.exception(error);
        prefs(c).edit().putString("failure", detail).putString("java_failure", detail).putLong("java_session", prefs(c).getLong("session", 0)).putBoolean("pending", true).commit();
    }
    static boolean recover(Activity a) {
        if (!prefs(a).getBoolean("pending", false)) return false;
        a.startActivity(new Intent(a, StartupDiagnosticsActivity.class)); a.finish(); return true;
    }
    static String report(Context c) {
        String version = "unknown";
        try { version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (PackageManager.NameNotFoundException ignored) { }
        StringBuilder out = new StringBuilder("Ocarina ReTuned startup report\nApp: ").append(version)
            .append("\nDevice: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append("\nAndroid API: ").append(Build.VERSION.SDK_INT)
            .append("\nABI: ").append(Arrays.toString(Build.SUPPORTED_ABIS));
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo(); c.getSystemService(ActivityManager.class).getMemoryInfo(memory);
        out.append("\nRAM MiB: ").append(memory.totalMem / 1048576).append("; available MiB: ").append(memory.availMem / 1048576);
        for (FeatureInfo feature : c.getPackageManager().getSystemAvailableFeatures()) {
            if ("android.hardware.vulkan.version".equals(feature.name) || "android.hardware.vulkan.level".equals(feature.name)) out.append('\n').append(feature.name).append(": ").append(feature.version);
        }
        out.append("\nVulkan values are system-advertised capabilities, not a driver test.")
            .append("\nSelected graphics driver: ").append(GraphicsDriver.name(c))
            .append("\nLoaded graphics driver: ").append(prefs(c).getString("active_driver", "not captured"))
            .append("\nROM prepared: ").append(RomSetupActivity.ready(c))
            .append("\nCurrent stage: ").append(prefs(c).getString("stage", "unknown"))
            .append("\nCurrent native stage: ").append(prefs(c).getString("native_stage", "not captured"))
            .append("\nRuntime Vulkan: ").append(prefs(c).getString("runtime_vulkan", "not captured"))
            .append("\nVulkan candidates:\n").append(prefs(c).getString("vulkan_candidates", "not captured"))
            .append("\nPrevious failure: ").append(prefs(c).getString("failure", "No failure captured. Android 10 cannot report prior native exit reasons."));
        appendPerformance(out, c);
        appendPreparation(out, c);
        return out.toString();
    }

    private static double percentile(java.util.List<Double> sorted, double quantile) {
        if (sorted.isEmpty()) return -1;
        int index = Math.max(0, Math.min(sorted.size() - 1, (int)Math.ceil(quantile * sorted.size()) - 1));
        return sorted.get(index);
    }

    private static void appendVulkanFrameTrace(StringBuilder out, Context context) {
        java.io.File file = new java.io.File(context.getFilesDir(), "diagnostics/vulkan-frame-trace.json");
        if (!file.isFile() || file.length() <= 0 || file.length() > 16 * 1024 * 1024) {
            out.append("\nVulkan GPU trace: no completed trace is available.");
            return;
        }
        try {
            org.json.JSONObject root = new org.json.JSONObject(new String(
                java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            if (!"oot3d_vulkan_diagnostics_v1".equals(root.optString("format"))) {
                out.append("\nVulkan GPU trace: unsupported format.");
                return;
            }
            org.json.JSONArray frames = root.optJSONArray("frames");
            java.util.ArrayList<Double> cpu = new java.util.ArrayList<>();
            java.util.ArrayList<Double> gpu = new java.util.ArrayList<>();
            java.util.ArrayList<Double> pica = new java.util.ArrayList<>();
            if (frames != null) for (int i = 0; i < frames.length(); ++i) {
                org.json.JSONObject frame = frames.optJSONObject(i);
                if (frame == null) continue;
                double cpuMs = frame.optDouble("cpu_frame_ms", -1);
                if (Double.isFinite(cpuMs) && cpuMs > 0) cpu.add(cpuMs);
                org.json.JSONObject gpuFrame = frame.optJSONObject("gpu");
                if (gpuFrame == null) continue;
                double gpuMs = gpuFrame.optDouble("frame_ms", -1);
                if (Double.isFinite(gpuMs) && gpuMs > 0) gpu.add(gpuMs);
                double picaMs = gpuFrame.optDouble("native_pica_ms", -1);
                if (Double.isFinite(picaMs) && picaMs > 0) pica.add(picaMs);
            }
            java.util.Collections.sort(cpu);
            java.util.Collections.sort(gpu);
            java.util.Collections.sort(pica);
            out.append("\nVulkan trace retained frames: ").append(frames == null ? 0 : frames.length());
            if (!cpu.isEmpty())
                out.append("\nCPU frame ms: p50 ").append(percentile(cpu, 0.50))
                    .append("; p95 ").append(percentile(cpu, 0.95));
            if (!gpu.isEmpty())
                out.append("\nGPU frame ms: p50 ").append(percentile(gpu, 0.50))
                    .append("; p95 ").append(percentile(gpu, 0.95))
                    .append("; valid GPU samples ").append(gpu.size());
            else
                out.append("\nGPU frame timestamps: unavailable on this driver or renderer path");
            if (!pica.isEmpty())
                out.append("\nNative PICA GPU pass ms: p50 ").append(percentile(pica, 0.50))
                    .append("; p95 ").append(percentile(pica, 0.95));

            // Native PICA CPU counters identify host-side renderer work separately from GPU work.
            String[][] cpuMetrics = {
                {"total_ms", "PICA CPU total"},
                {"shader_variant_ms", "PICA shader variant"},
                {"shader_cache_ms", "PICA shader cache"},
                {"texture_ms", "PICA texture work"},
                {"draw_state_ms", "PICA draw state"},
                {"grass_surface_ms", "PICA grass surface"},
                {"scene_state_ms", "PICA scene state"},
                {"render_target_state_ms", "PICA render-target state"},
                {"directional_shadow_ms", "PICA directional shadow"},
                {"grass_render_ms", "PICA grass render"},
                {"reflection_environment_ms", "PICA reflection environment"},
                {"temporal_state_ms", "PICA temporal state"},
                {"pipeline_ms", "PICA pipeline work"},
                {"upload_ms", "PICA upload work"},
                {"descriptor_ms", "PICA descriptor work"},
                {"command_ms", "PICA command recording"}
            };
            for (String[] metric : cpuMetrics) {
                java.util.ArrayList<Double> samples = new java.util.ArrayList<>();
                if (frames != null) for (int i = 0; i < frames.length(); ++i) {
                    org.json.JSONObject frame = frames.optJSONObject(i);
                    org.json.JSONObject cpuFrame = frame == null ? null : frame.optJSONObject("native_pica_cpu");
                    if (cpuFrame == null) continue;
                    double value = cpuFrame.optDouble(metric[0], -1);
                    if (Double.isFinite(value) && value > 0) samples.add(value);
                }
                if (!samples.isEmpty()) {
                    java.util.Collections.sort(samples);
                    out.append("\n").append(metric[1]).append(" ms: p50 ")
                        .append(percentile(samples, 0.50)).append("; p95 ")
                        .append(percentile(samples, 0.95)).append("; samples ")
                        .append(samples.size());
                }
            }

            // These are independent GPU pass timestamps from the pinned TriAevum trace schema.
            // Keep each distribution separate: overlapping/nested passes must not be summed.
            String[][] passMetrics = {
                {"toon_raster_ms", "Toon raster"},
                {"grass_ms", "Grass"},
                {"ambient_occlusion_ms", "Ambient occlusion"},
                {"depth_preparation_ms", "Depth preparation"},
                {"reflection_ms", "Reflection"},
                {"global_illumination_ms", "Global illumination"},
                {"motion_vectors_ms", "Motion vectors"},
                {"scene_composite_ms", "Scene composite"},
                {"anti_aliasing_ms", "Anti-aliasing"},
                {"upscaler_ms", "Upscaler"},
                {"display_transfer_ms", "Display transfer"},
                {"scanout_ms", "Scanout"},
                {"overlay_ms", "Overlay"}
            };
            for (String[] metric : passMetrics) {
                java.util.ArrayList<Double> samples = new java.util.ArrayList<>();
                if (frames != null) for (int i = 0; i < frames.length(); ++i) {
                    org.json.JSONObject frame = frames.optJSONObject(i);
                    org.json.JSONObject gpuFrame = frame == null ? null : frame.optJSONObject("gpu");
                    if (gpuFrame == null) continue;
                    double value = gpuFrame.optDouble(metric[0], -1);
                    if (Double.isFinite(value) && value > 0) samples.add(value);
                }
                if (!samples.isEmpty()) {
                    java.util.Collections.sort(samples);
                    out.append("\nGPU ").append(metric[1]).append(" ms: p50 ")
                        .append(percentile(samples, 0.50)).append("; p95 ")
                        .append(percentile(samples, 0.95)).append("; samples ")
                        .append(samples.size());
                }
            }

            if (frames != null && frames.length() > 0) {
                org.json.JSONObject first = frames.optJSONObject(0);
                if (first != null)
                    out.append("\nVulkan adapter vendor/device IDs: 0x")
                        .append(Integer.toHexString(first.optInt("vulkan_adapter_vendor_id", 0)))
                        .append("/0x")
                        .append(Integer.toHexString(first.optInt("vulkan_adapter_device_id", 0)))
                        .append("; adapter index ")
                        .append(first.optInt("vulkan_adapter_index", -1));
            }
            org.json.JSONObject pipelines = root.optJSONObject("nri_pipeline_compilation");
            if (pipelines != null)
                out.append("\nNRI pipeline creations: ").append(pipelines.optLong("created", 0))
                    .append(" / ").append(pipelines.optLong("creation_attempts", 0))
                    .append("; initial cache bytes ").append(pipelines.optLong("initial_cache_bytes", 0))
                    .append("; pipeline creation time ms ")
                    .append(pipelines.optLong("creation_nanoseconds", 0) / 1000000.0);
            out.append("\nGPU timings use Vulkan timestamp queries when supported; missing values are unavailable, not zero.")
                .append("\nGPU pass timings are reported independently and may overlap; do not add them together.");
        } catch (Exception ignored) {
            out.append("\nVulkan GPU trace: summary could not be read.");
        }
    }

    private static void appendPerformance(StringBuilder out, Context context) {
        java.io.File file = new java.io.File(context.getFilesDir(), "diagnostics/native-runtime-summary.json");
        if (!file.isFile() || file.length() <= 0 || file.length() > 2 * 1024 * 1024) {
            out.append("\nPerformance capture: no completed host timing summary is available.");
        } else {
            try {
                org.json.JSONObject root = new org.json.JSONObject(new String(
                    java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8));
                org.json.JSONObject benchmark = root.optJSONObject("benchmark_window");
                org.json.JSONObject times = benchmark == null ? null : benchmark.optJSONObject("frame_times");
                out.append("\nHost performance capture (last completed session):");
                if (times == null) {
                    out.append("\nFrame-time samples: unavailable");
                } else {
                    out.append("\nFrame-time samples: ").append(times.optLong("samples", 0))
                        .append("; p50 upper ms: ").append(times.optDouble("p50_upper_ms", -1))
                        .append("; p95 upper ms: ").append(times.optDouble("p95_upper_ms", -1))
                        .append("; p99 upper ms: ").append(times.optDouble("p99_upper_ms", -1))
                        .append("; mean ms: ").append(times.optDouble("mean_ms", -1))
                        .append("; over 60 Hz budget: ").append(times.optLong("over_60hz_budget", -1))
                        .append("; over 30 Hz budget: ").append(times.optLong("over_30hz_budget", -1));
                }
                org.json.JSONObject preBackend = benchmark == null ? null : benchmark.optJSONObject("pre_backend_cpu");
                if (preBackend != null) {
                    out.append("\nPre-backend work per measured frame ms: mean ")
                        .append(preBackend.optDouble("mean_ms", -1))
                        .append("; p95 upper ").append(preBackend.optDouble("p95_upper_ms", -1))
                        .append("; maximum ").append(preBackend.optDouble("maximum_ms", -1));
                }
                org.json.JSONObject phases = root.optJSONObject("phase_timing");
                org.json.JSONArray slowFrames = phases == null ? null : phases.optJSONArray("slow_frames");
                out.append("\nRetained slow-frame phase samples: ").append(slowFrames == null ? 0 : slowFrames.length());
                out.append("\nHost frame intervals do not by themselves prove guest simulation rate or GPU execution time.");
            } catch (Exception ignored) {
                out.append("\nPerformance capture: host summary could not be read.");
            }
        }
        appendVulkanFrameTrace(out, context);
    }

    private static void appendPreparation(StringBuilder out, Context context) {
        try {
            java.io.File file = new java.io.File(context.getFilesDir(), "native-data/setup-activity.json");
            if (file.length() > 65536) return;
            org.json.JSONObject activity = new org.json.JSONObject(new String(
                java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            String stage = activity.optString("stage");
            if (!stage.matches("[a-z]{1,24}")) return;
            out.append("\nPreparation stage: ").append(stage)
                .append("; completed: ").append(activity.optLong("completed", -1))
                .append('/').append(activity.optLong("total", -1))
                .append("; workers: ").append(activity.optInt("workers", 0))
                .append("; available MiB: ").append(activity.optLong("available_bytes", 0) / 1048576)
                .append("; snapshot age seconds: ").append(Math.max(0,
                    System.currentTimeMillis() / 1000 - activity.optLong("updated_at", 0)))
                .append("; no CPU activity warning: ").append(activity.optBoolean("no_cpu_activity_warning"));
            org.json.JSONArray processes = activity.optJSONArray("processes");
            if (processes != null) for (int i = 0; i < Math.min(4, processes.length()); i++) {
                org.json.JSONObject worker = processes.getJSONObject(i);
                out.append("\nCompiler ").append(i + 1).append(": CPU ticks ")
                    .append(worker.optLong("cpu_ticks", -1)).append("; resident MiB ")
                    .append(worker.optLong("resident_bytes", 0) / 1048576);
            }
        } catch (Exception ignored) { }
    }
}
