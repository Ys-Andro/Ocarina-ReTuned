    private File retunedPerformanceProfile(File source) throws Exception {
        File directory = new File(getFilesDir(), "diagnostics");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create private diagnostics directory");
        File summary = new File(directory, "native-runtime-summary.json");
        if (summary.exists() && !summary.delete())
            throw new IOException("Cannot clear previous performance summary");
        JSONObject profile = new JSONObject(new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8));
        org.json.JSONArray original = profile.getJSONArray("arguments");
        org.json.JSONArray arguments = new org.json.JSONArray();
        for (int index = 0; index < original.length(); ++index) {
            String argument = original.getString(index);
            if ("--output".equals(argument)) {
                if (index + 1 < original.length()) ++index;
                continue;
            }
            arguments.put(argument);
            if ("--title-plugin".equals(argument) && index + 1 < original.length())
                arguments.put(original.getString(++index));
        }
        arguments.put("--output");
        arguments.put(summary.getAbsolutePath());
        profile.put("arguments", arguments);
        File diagnosticProfile = new File(source.getParentFile(), "native-launch-diagnostics.json");
        Files.write(diagnosticProfile.toPath(), profile.toString().getBytes(StandardCharsets.UTF_8));
        return diagnosticProfile;
    }

    @Override protected String[] getArguments() {
        File profile = new File(getExternalFilesDir(null), "native-launch.json");
        File selectedProfile = profile;
        if (StartupDiagnostics.prefs(this).getBoolean("capture_active", false)) {
            try {
                selectedProfile = retunedPerformanceProfile(profile);
            } catch (Exception error) {
                StartupDiagnostics.prefs(this).edit().putBoolean("capture_active", false).commit();
                Log.w("ReTunedSetup", "Performance capture profile unavailable", error);
            }
            StartupDiagnostics.prefs(this).edit().putBoolean("capture_active", false).commit();
        }
        try {
            org.json.JSONArray arguments = new JSONObject(new String(Files.readAllBytes(selectedProfile.toPath()), StandardCharsets.UTF_8)).getJSONArray("arguments");
            for (int index = 0; index + 1 < arguments.length(); ++index)
                if ("--title-plugin".equals(arguments.getString(index)))
                    return new String[] {"--launch-profile", selectedProfile.getAbsolutePath()};
        } catch (Exception error) { Log.w("ReTunedSetup", "Using legacy module location", error); }
        return new String[] {
            "--launch-profile", selectedProfile.getAbsolutePath(),
            "--title-plugin", new File(getFilesDir(), "native/triaevum_title_aot.so").getAbsolutePath()
        };
    }
