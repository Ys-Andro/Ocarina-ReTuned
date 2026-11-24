package org.triaevum.android;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.widget.*;

/** Java-only recovery UI: deliberately does not initialize SDL or the renderer. */
public final class StartupDiagnosticsActivity extends Activity {
    private static final int EXPORT_SUMMARY = 7121;
    private static final int EXPORT_VULKAN_TRACE = 7122;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24,24,24,24);
        TextView title = new TextView(this); title.setText("Ocarina ReTuned diagnostics"); InterfaceTypography.heading(title); root.addView(title);
        TextView help = new TextView(this); help.setText("If the app closed unexpectedly, copy this report into your issue. Nothing is sent automatically. Your ROM and saves are unchanged."); InterfaceTypography.body(help); root.addView(help);
        String report = StartupDiagnostics.report(this);
        ScrollView scroll = new ScrollView(this); TextView text = new TextView(this); text.setText(report); text.setTypeface(android.graphics.Typeface.MONOSPACE); text.setTextSize(13); text.setTextIsSelectable(true); scroll.addView(text); root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
        Button copy = new Button(this); copy.setText("Copy report"); InterfaceTypography.button(copy,false); copy.setOnClickListener(v -> { getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Startup report", report)); Toast.makeText(this,"Report copied",Toast.LENGTH_SHORT).show(); }); root.addView(copy);
        Button retry = new Button(this); retry.setText("Continue to app"); InterfaceTypography.button(retry,false); retry.setOnClickListener(v -> { StartupDiagnostics.prefs(this).edit().putBoolean("pending",false).commit(); startActivity(new Intent(this,TriAevumActivity.class)); finish(); }); root.addView(retry);
        TextView captureHelp = new TextView(this); captureHelp.setText("Collect frame-time percentiles and renderer phase timings for one game session. The report stays on this device; capture adds a small amount of measurement overhead."); InterfaceTypography.body(captureHelp); root.addView(captureHelp);
        Button capture = new Button(this); capture.setText("Capture performance on next run"); InterfaceTypography.button(capture,false);
        capture.setOnClickListener(v -> { StartupDiagnostics.prefs(this).edit().putBoolean("pending",false).putBoolean("capture_next_run",true).commit(); startActivity(new Intent(this,TriAevumActivity.class)); finish(); }); root.addView(capture);
        Button exportSummary = new Button(this); exportSummary.setText("Export performance summary"); InterfaceTypography.button(exportSummary,false);
        exportSummary.setOnClickListener(v -> exportDiagnostic("diagnostics/native-runtime-summary.json", "native-runtime-summary.json", EXPORT_SUMMARY)); root.addView(exportSummary);
        Button exportTrace = new Button(this); exportTrace.setText("Export Vulkan frame trace"); InterfaceTypography.button(exportTrace,false);
        exportTrace.setOnClickListener(v -> exportDiagnostic("diagnostics/vulkan-frame-trace.json", "vulkan-frame-trace.json", EXPORT_VULKAN_TRACE)); root.addView(exportTrace);
        TextView driverStatus = new TextView(this); driverStatus.setText(GraphicsDriver.status(this)); InterfaceTypography.body(driverStatus); root.addView(driverStatus);
        Button driver = new Button(this); driver.setText("Choose custom driver"); InterfaceTypography.button(driver,false);
        driver.setOnClickListener(v -> GraphicsDriver.choose(this)); root.addView(driver);
        Button system = new Button(this); system.setText("Use system driver"); InterfaceTypography.button(system,false);
        system.setOnClickListener(v -> {GraphicsDriver.useSystem(this);driverStatus.setText(GraphicsDriver.status(this));}); root.addView(system);
        setContentView(root);
    }
    private void exportDiagnostic(String relativePath, String suggestedName, int requestCode) {
        java.io.File source = new java.io.File(getFilesDir(), relativePath);
        if (!source.isFile() || source.length() <= 0) {
            Toast.makeText(this, "No completed diagnostic file is available.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, suggestedName);
        startActivityForResult(intent, requestCode);
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==GraphicsDriver.PICK_REQUEST){GraphicsDriver.result(data);recreate();return;}
        if ((request == EXPORT_SUMMARY || request == EXPORT_VULKAN_TRACE) && result == RESULT_OK
                && data != null && data.getData() != null) {
            String relativePath = request == EXPORT_SUMMARY
                ? "diagnostics/native-runtime-summary.json" : "diagnostics/vulkan-frame-trace.json";
            java.io.File source = new java.io.File(getFilesDir(), relativePath);
            try (java.io.InputStream input = new java.io.FileInputStream(source);
                 java.io.OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                if (output == null) throw new java.io.IOException("Destination unavailable");
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                Toast.makeText(this, "Diagnostic file exported.", Toast.LENGTH_SHORT).show();
            } catch (java.io.IOException | RuntimeException error) {
                Toast.makeText(this, "Could not export diagnostic file.", Toast.LENGTH_LONG).show();
            }
        }
    }
}
