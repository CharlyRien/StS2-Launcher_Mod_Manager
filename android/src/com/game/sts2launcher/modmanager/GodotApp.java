package com.game.sts2launcher.modmanager;

import org.godotengine.godot.Godot;
import org.godotengine.godot.GodotActivity;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.core.content.FileProvider;
import androidx.core.splashscreen.SplashScreen;

import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Base64;

import org.fmod.FMOD;

// Main activity for the mobile launcher. Handles FMOD initialization, .NET assembly
// setup, PCK loading, LAN multicast, and Android Keystore encryption for credentials.
public class GodotApp extends GodotActivity {
	static {
		// FMOD must load before Godot's GDExtension or FMOD_JNI_GetEnv fails.
		System.loadLibrary("fmod");
		System.loadLibrary("fmodstudio");
		// Required for TLS/SSL (SteamKit2 WebSocket, HTTPS).
		System.loadLibrary("System.Security.Cryptography.Native.Android");
	}

	private static GodotApp instance;
	private WifiManager.MulticastLock multicastLock;
	private String gameDir;
	private static final String TAG = "STS2Mobile";
	private static final String KEYSTORE_ALIAS = "sts2mobile_credentials";
	private static final String PCK_FILE = "SlayTheSpire2.pck";
	private static final int REQ_SAF_ZIP = 4201;

	private volatile boolean pickerActive = false;
	private final java.util.List<String> lastPickedZipPaths =
			java.util.Collections.synchronizedList(new java.util.ArrayList<>());

	private Process logcatProcess;
	private Thread logcatThread;
	private File logcatFile;
	private static final String DEBUG_LOGCAT_PREF = "debug_logcat_enabled";
	private static final String EXTERNAL_LOGS_SUBDIR = "StS2LauncherMM/Logs";
	// Default ON so users hitting boot crashes still produce a log; toggle off
	// in-app to opt out. See LauncherController.OnDebugTogglePressed.
	private static final boolean DEBUG_LOGCAT_DEFAULT = true;
	private static final int MAX_LOG_FILES = 20;

	private final Runnable updateWindowAppearance = () -> {
		Godot godot = getGodot();
		if (godot != null) {
			godot.enableImmersiveMode(true, true);
			godot.enableEdgeToEdge(true, true);
			godot.setSystemBarsAppearance();
		}
	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		instance = this;
		gameDir = new File(getFilesDir(), "game").getAbsolutePath();

		// Start logcat capture as early as possible if the user previously enabled
		// debug logging — the goal is to capture from the very first init logs
		// (FMOD, BCL extraction, .NET boot) right through to gameplay.
		if (isLogcatCaptureEnabled()) {
			startLogcatCaptureInternal();
		}

		// Issue #11 진단 — 보고자 단말 분리용. 제조사/모델/Android/펌웨어 빌드 ID 1줄.
		Log.i(TAG, "[Diag/Fold] device=" + Build.MANUFACTURER + "/" + Build.MODEL
				+ " android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT
				+ " build=" + Build.DISPLAY);

		SplashScreen.installSplashScreen(this);
		EdgeToEdge.enable(this);

		// Must be called before any native FMOD calls.
		FMOD.init(this);

		// Debug-only: convert intent extras into marker files that the C# side
		// picks up after the launcher UI is up, to force-show specific dialogs
		// without going through GitHub / Steam round-trips. Gated by version
		// suffix so prod builds are completely inert.
		if (BuildConfig.VERSION_NAME != null && BuildConfig.VERSION_NAME.contains("-debug")) {
			handleDebugIntents();
		}

		setupAssemblies();
		extractAssetFile("FMOD_LOGOS/FMOD Logo White - Transparent Background.png", "fmod_logo.png");

		super.onCreate(savedInstanceState);

		// Android WiFi power saving drops broadcast packets without a MulticastLock.
		try {
			WifiManager wifiMgr = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
			multicastLock = wifiMgr.createMulticastLock("sts2_lan_discovery");
			multicastLock.setReferenceCounted(false);
			multicastLock.acquire();
			Log.i(TAG, "WiFi MulticastLock acquired for LAN discovery");
		} catch (Exception e) {
			Log.w(TAG, "Failed to acquire MulticastLock", e);
		}
	}

	// Reads `--es debug_force_*` intent extras from `adb shell am start` and
	// drops marker files into getFilesDir(). The C# side polls these on its
	// first-UI hook and routes them to the matching dialog handler. Used by
	// developers to verify dialog UI without uploading a real GitHub release.
	private void handleDebugIntents() {
		Intent intent = getIntent();
		if (intent == null) {
			return;
		}
		try {
			if ("1".equals(intent.getStringExtra("debug_force_update_dialog"))) {
				String version = intent.getStringExtra("debug_force_update_version");
				String body = intent.getStringExtra("debug_force_update_body");
				if (version == null) version = "0.0.0";
				if (body == null) body = "";
				File f = new File(getFilesDir(), ".debug_force_update_dialog");
				try (FileWriter w = new FileWriter(f)) {
					w.write(version);
					w.write("\n");
					w.write(body);
				}
				Log.i(TAG, "[Debug] Update-dialog marker written: version=" + version);
			}
		} catch (IOException ex) {
			Log.w(TAG, "[Debug] handleDebugIntents IO failure", ex);
		}
	}

	private boolean isNewVersion() {
		SharedPreferences prefs = getSharedPreferences("sts2mobile", MODE_PRIVATE);
		int lastVersion = prefs.getInt("installed_version_code", -1);
		int currentVersion = BuildConfig.VERSION_CODE;
		if (lastVersion == currentVersion) {
			return false;
		}
		Log.i(TAG, "Version changed: " + lastVersion + " -> " + currentVersion);
		prefs.edit().putInt("installed_version_code", currentVersion).apply();
		return true;
	}

	// Copies .NET BCL from APK assets and game assemblies from the download
	// directory
	// into the location Godot expects. Skips if already done unless the APK version
	// changed.
	private void setupAssemblies() {
		File srcDir = findAssembliesDir();
		File destDir = new File(getFilesDir(), ".godot/mono/publish/arm64");

		boolean versionChanged = isNewVersion();

		File patcherMarker = new File(destDir, "STS2Mobile.dll");
		File sts2Marker = new File(destDir, "sts2.dll");
		if (sts2Marker.exists() && patcherMarker.exists() && !versionChanged) {
			Log.i(TAG, "Assemblies already set up at: " + destDir.getAbsolutePath());
			return;
		}

		if (versionChanged) {
			Log.i(TAG, "New version detected, re-copying all assemblies");
		}

		destDir.mkdirs();

		java.util.Set<String> bclNames = new java.util.HashSet<>();
		try {
			String[] bclFiles = getAssets().list("dotnet_bcl");
			if (bclFiles != null) {
				int count = 0;
				for (String name : bclFiles) {
					try (InputStream in = getAssets().open("dotnet_bcl/" + name);
							OutputStream out = new FileOutputStream(new File(destDir, name))) {
						byte[] buf = new byte[8192];
						int len;
						while ((len = in.read(buf)) > 0) {
							out.write(buf, 0, len);
						}
						bclNames.add(name);
						count++;
					}
				}
				Log.i(TAG, "Copied " + count + " BCL assemblies from assets");
			}
		} catch (IOException e) {
			Log.e(TAG, "Failed to copy BCL assemblies", e);
		}

		// Only copy game assemblies that don't already exist in BCL. The depot has
		// desktop
		// CoreCLR versions that are incompatible with Android's Mono runtime.
		if (!srcDir.exists() || !srcDir.isDirectory()) {
			Log.w(TAG, "Game assemblies source dir not found: " + srcDir.getAbsolutePath());
			return;
		}

		Log.i(TAG, "Copying game assemblies from " + srcDir + " to " + destDir);
		File[] files = srcDir.listFiles();
		if (files == null)
			return;

		int count = 0;
		int skipped = 0;
		int bclProtected = 0;
		for (File src : files) {
			if (src.isFile()) {
				String name = src.getName();
				if (name.endsWith(".so")) {
					continue;
				}
				// CRITICAL: depot 의 game assembly 디렉토리에는 desktop CoreCLR
				// 버전의 System.*, mscorlib 같은 BCL dll 이 들어있음. 이것들은
				// Android Mono 와 호환되지 않으므로 absolutely 덮어쓰면 안 됨.
				// (System.Diagnostics.MonoStackFrame not found → Mono 부팅 실패)
				if (bclNames.contains(name)) {
					bclProtected++;
					continue;
				}
				File dest = new File(destDir, name);
				// Issue #5 진짜 root cause: 이전에는 dest.exists() 면 무조건 skip
				// 이라 게임이 update 되어 src 의 sts2.dll 이 새 버전으로 갱신돼도
				// dest 의 옛 dll 이 그대로 남아 NCard.cs 가 새 .tscn 의
				// %AncientHighlight 같은 노드를 못 찾는 mismatch 발생.
				// BCL 충돌 방지 후 size+mtime 으로 동기화 여부 판단해 다르면 덮어쓰기.
				if (dest.exists()
						&& dest.length() == src.length()
						&& dest.lastModified() >= src.lastModified()) {
					skipped++;
					continue;
				}
				try {
					copyFile(src, dest);
					count++;
				} catch (IOException e) {
					Log.e(TAG, "Failed to copy: " + name, e);
				}
			}
		}
		Log.i(TAG, "Copied " + count + " game assembly files (skipped " + skipped
				+ " up-to-date, " + bclProtected + " BCL-protected)");
	}

	private File findAssembliesDir() {
		File gameDirFile = new File(gameDir);
		if (gameDirFile.exists() && gameDirFile.isDirectory()) {
			File[] children = gameDirFile.listFiles();
			if (children != null) {
				for (File child : children) {
					if (child.isDirectory() && child.getName().startsWith("data_")) {
						Log.i(TAG, "Found assemblies dir: " + child.getName());
						return child;
					}
				}
			}
		}
		return new File(gameDir, "data_sts2_windows_x86_64");
	}

	private void copyFile(File src, File dest) throws IOException {
		try (InputStream in = new FileInputStream(src);
				OutputStream out = new FileOutputStream(dest)) {
			byte[] buf = new byte[8192];
			int len;
			while ((len = in.read(buf)) > 0) {
				out.write(buf, 0, len);
			}
		}
	}

	// Extracts a single file from APK assets to the files directory.
	private void extractAssetFile(String assetPath, String destName) {
		File dest = new File(getFilesDir(), destName);
		if (dest.exists())
			return;
		try (InputStream in = getAssets().open(assetPath);
				OutputStream out = new FileOutputStream(dest)) {
			byte[] buf = new byte[4096];
			int len;
			while ((len = in.read(buf)) > 0) {
				out.write(buf, 0, len);
			}
		} catch (IOException e) {
			Log.w(TAG, "Failed to extract " + assetPath, e);
		}
	}

	@Override
	public List<String> getCommandLine() {
		List<String> commands = new ArrayList<>(super.getCommandLine());
		File pckFile = new File(gameDir, PCK_FILE);
		if (pckFile.exists()) {
			commands.add("--main-pack");
			commands.add(pckFile.getAbsolutePath());
			Log.i(TAG, "Loading PCK from: " + pckFile.getAbsolutePath());
		} else {
			// No game files yet; use bootstrap PCK so Godot can initialize for the
			// launcher.
			String bootstrapPck = extractBootstrapPck();
			if (bootstrapPck != null) {
				commands.add("--main-pack");
				commands.add(bootstrapPck);
				Log.i(TAG, "Using bootstrap PCK for launcher-only mode");
			}
		}
		return commands;
	}

	private String extractBootstrapPck() {
		File dest = new File(getFilesDir(), "bootstrap.pck");
		if (dest.exists()) {
			return dest.getAbsolutePath();
		}
		try (InputStream in = getAssets().open("bootstrap.pck");
				OutputStream out = new FileOutputStream(dest)) {
			byte[] buf = new byte[4096];
			int len;
			while ((len = in.read(buf)) > 0) {
				out.write(buf, 0, len);
			}
			return dest.getAbsolutePath();
		} catch (IOException e) {
			Log.e(TAG, "Failed to extract bootstrap PCK", e);
			return null;
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		updateWindowAppearance.run();
	}

	private long lastBackPressTimeMs = 0L;
	private Toast lastBackPressToast;
	private static final long BACK_PRESS_CONFIRM_WINDOW_MS = 2000L;

	// Intercept the hardware back button before Godot's render view swallows it.
	// First press shows a toast and is discarded; a second press within 2s is
	// allowed through so NGame.Quit (and therefore restartApp()) runs. Prevents
	// accidentally dropping an in-progress run with a stray swipe.
	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		if (event.getKeyCode() != KeyEvent.KEYCODE_BACK) {
			return super.dispatchKeyEvent(event);
		}
		long now = System.currentTimeMillis();
		boolean withinWindow = (now - lastBackPressTimeMs) < BACK_PRESS_CONFIRM_WINDOW_MS;

		if (event.getAction() == KeyEvent.ACTION_DOWN) {
			// Second press: pass DOWN through so Godot receives the full pair.
			// First press: swallow DOWN so the render view cannot act on it.
			return withinWindow ? super.dispatchKeyEvent(event) : true;
		}
		if (event.getAction() == KeyEvent.ACTION_UP) {
			if (withinWindow) {
				if (lastBackPressToast != null) {
					lastBackPressToast.cancel();
					lastBackPressToast = null;
				}
				lastBackPressTimeMs = 0L;
				return super.dispatchKeyEvent(event);
			}
			lastBackPressTimeMs = now;
			if (lastBackPressToast != null) {
				lastBackPressToast.cancel();
			}
			lastBackPressToast = Toast.makeText(
				this,
				"Press back again to exit",
				Toast.LENGTH_SHORT
			);
			lastBackPressToast.show();
			return true;
		}
		return super.dispatchKeyEvent(event);
	}

	@Override
	public void onGodotMainLoopStarted() {
		super.onGodotMainLoopStarted();
		runOnUiThread(updateWindowAppearance);
	}

	@Override
	protected void onDestroy() {
		if (multicastLock != null && multicastLock.isHeld()) {
			multicastLock.release();
			Log.i(TAG, "WiFi MulticastLock released");
		}
		FMOD.close();
		super.onDestroy();
	}

	public static GodotApp getInstance() {
		return instance;
	}

	public String getGameDir() {
		return gameDir;
	}

	public String getVersionName() {
		return BuildConfig.VERSION_NAME;
	}

	public void restartApp() {
		Log.i(TAG, "Restarting app...");
		Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
		if (intent != null) {
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
			startActivity(intent);
		}
		Runtime.getRuntime().exit(0);
	}

	// AES-256-GCM encryption via Android Keystore (hardware-backed TEE).
	private SecretKey getOrCreateKeystoreKey() throws Exception {
		KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
		keyStore.load(null);

		if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
			return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEYSTORE_ALIAS, null)).getSecretKey();
		}

		KeyGenerator keyGen = KeyGenerator.getInstance(
				android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
		keyGen.init(new android.security.keystore.KeyGenParameterSpec.Builder(
				KEYSTORE_ALIAS,
				android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
						| android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(256)
				.build());
		return keyGen.generateKey();
	}

	public String encryptString(String plaintext) {
		try {
			SecretKey key = getOrCreateKeystoreKey();
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key);
			byte[] iv = cipher.getIV();
			byte[] ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"));

			// Format: [iv_length (1 byte)] [iv] [ciphertext]
			byte[] result = new byte[1 + iv.length + ciphertext.length];
			result[0] = (byte) iv.length;
			System.arraycopy(iv, 0, result, 1, iv.length);
			System.arraycopy(ciphertext, 0, result, 1 + iv.length, ciphertext.length);
			return Base64.encodeToString(result, Base64.NO_WRAP);
		} catch (Exception e) {
			Log.e(TAG, "Encryption failed", e);
			return null;
		}
	}

	public String decryptString(String encrypted) {
		try {
			byte[] blob = Base64.decode(encrypted, Base64.NO_WRAP);
			int ivLength = blob[0] & 0xFF;
			byte[] iv = new byte[ivLength];
			System.arraycopy(blob, 1, iv, 0, ivLength);
			byte[] ciphertext = new byte[blob.length - 1 - ivLength];
			System.arraycopy(blob, 1 + ivLength, ciphertext, 0, ciphertext.length);

			SecretKey key = getOrCreateKeystoreKey();
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
			byte[] plaintext = cipher.doFinal(ciphertext);
			return new String(plaintext, "UTF-8");
		} catch (Exception e) {
			Log.e(TAG, "Decryption failed", e);
			return null;
		}
	}

	// Returns true if the app has permission to write to shared external storage.
	public boolean hasStoragePermission() {
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
			return android.os.Environment.isExternalStorageManager();
		}
		return checkSelfPermission(
				android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
	}

	// Requests external storage permission. On Android 11+, opens the system
	// settings
	// page for "All files access". On older versions, shows the runtime permission
	// dialog.
	public void requestStoragePermission() {
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
			try {
				Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
				intent.setData(android.net.Uri.parse("package:" + getPackageName()));
				startActivity(intent);
			} catch (Exception e) {
				Log.w(TAG, "Failed to open app-specific storage settings, trying general", e);
				Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
				startActivity(intent);
			}
		} else {
			requestPermissions(new String[] { android.Manifest.permission.WRITE_EXTERNAL_STORAGE }, 1);
		}
	}

	// Opens the system document picker (SAF) so the user can select one or more
	// mod zips from any provider the device exposes (Downloads, Drive, etc.).
	// The result is handled in onActivityResult; each picked file is copied into
	// the app cache, and the absolute paths are drained by C# via
	// consumePickedZipPaths().
	public void openZipPicker() {
		Log.i(TAG, "[Mods] openZipPicker invoked from C#");
		pickerActive = true;
		lastPickedZipPaths.clear();
		runOnUiThread(() -> {
			try {
				Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
				intent.addCategory(Intent.CATEGORY_OPENABLE);
				intent.setType("*/*");
				intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
						"application/zip",
						"application/x-zip-compressed",
						"application/octet-stream"
				});
				intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
				Log.i(TAG, "[Mods] Starting SAF ACTION_OPEN_DOCUMENT intent (multi)");
				startActivityForResult(intent, REQ_SAF_ZIP);
				Log.i(TAG, "[Mods] startActivityForResult returned");
			} catch (Exception e) {
				Log.e(TAG, "[Mods] Failed to start zip picker", e);
				pickerActive = false;
			}
		});
	}

	public boolean isPickerActive() {
		return pickerActive;
	}

	// Returns all copied zip paths as a single newline-separated string and clears
	// the buffer. Returns empty string when the user cancelled or nothing was picked.
	public String consumePickedZipPaths() {
		synchronized (lastPickedZipPaths) {
			if (lastPickedZipPaths.isEmpty())
				return "";
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < lastPickedZipPaths.size(); i++) {
				if (i > 0) sb.append('\n');
				sb.append(lastPickedZipPaths.get(i));
			}
			lastPickedZipPaths.clear();
			return sb.toString();
		}
	}

	// Kept for backward compatibility with any caller that still grabs a single path.
	public String consumeLastPickedZipPath() {
		synchronized (lastPickedZipPaths) {
			if (lastPickedZipPaths.isEmpty())
				return null;
			String path = lastPickedZipPaths.remove(0);
			return path;
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		Log.i(TAG, "[Mods] onActivityResult requestCode=" + requestCode + " resultCode=" + resultCode);
		if (requestCode != REQ_SAF_ZIP) {
			return;
		}
		try {
			if (resultCode == RESULT_OK && data != null) {
				java.util.List<android.net.Uri> uris = new java.util.ArrayList<>();
				android.content.ClipData clip = data.getClipData();
				if (clip != null) {
					for (int i = 0; i < clip.getItemCount(); i++) {
						android.net.Uri u = clip.getItemAt(i).getUri();
						if (u != null) uris.add(u);
					}
				} else if (data.getData() != null) {
					uris.add(data.getData());
				}
				Log.i(TAG, "[Mods] Picked " + uris.size() + " file(s)");

				long ts = System.currentTimeMillis();
				for (int i = 0; i < uris.size(); i++) {
					android.net.Uri uri = uris.get(i);
					File dest = new File(getCacheDir(), "mod_import_" + ts + "_" + i + ".zip");
					try (InputStream in = getContentResolver().openInputStream(uri);
							FileOutputStream out = new FileOutputStream(dest)) {
						byte[] buf = new byte[16384];
						int len;
						while ((len = in.read(buf)) > 0) {
							out.write(buf, 0, len);
						}
					}
					lastPickedZipPaths.add(dest.getAbsolutePath());
					Log.i(TAG, "[Mods] Mod zip copied to: " + dest.getAbsolutePath());
				}
			}
		} catch (Exception e) {
			Log.e(TAG, "Failed to read picked zip(s)", e);
		} finally {
			pickerActive = false;
		}
	}

	// === Launcher self-update (issue #12) ===

	public String getCacheDirPath() {
		return getCacheDir().getAbsolutePath();
	}

	// On Android 8+, "install unknown apps" is a per-source toggle. Without it the
	// install Intent silently no-ops, so the UI must check this and route the user
	// to settings before downloading.
	public boolean canRequestInstallPackages() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			return getPackageManager().canRequestPackageInstalls();
		}
		return true;
	}

	public void requestInstallPackagesPermission() {
		try {
			Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
			i.setData(Uri.parse("package:" + getPackageName()));
			i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			startActivity(i);
		} catch (Exception e) {
			Log.e(TAG, "REQUEST_INSTALL_PACKAGES intent failed", e);
		}
	}

	// Hands the downloaded APK to the system installer via FileProvider.
	// FILE_GRANT_READ_URI_PERMISSION is required so the installer (a different
	// process) can read the cache file.
	public void installApk(String apkPath) {
		try {
			File f = new File(apkPath);
			Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
			Intent intent = new Intent(Intent.ACTION_VIEW);
			intent.setDataAndType(uri, "application/vnd.android.package-archive");
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
			startActivity(intent);
		} catch (Exception e) {
			Log.e(TAG, "installApk failed for " + apkPath, e);
		}
	}

	// === Debug logcat capture ===
	//
	// When enabled, spawn a logcat process scoped to our own PID (--pid) so no
	// READ_LOGS permission is needed, and stream every line to a timestamped
	// file under /storage/emulated/0/StS2LauncherMM/Logs/. The toggle persists
	// in SharedPreferences so the next launch can re-attach the capture from
	// onCreate, before any of our patches or the .NET runtime even boot — that
	// way the user can collect a complete launch-to-gameplay log just by
	// turning Debug on, force-stopping the app, and relaunching.

	public boolean isLogcatCaptureEnabled() {
		return getSharedPreferences("sts2mobile", MODE_PRIVATE)
				.getBoolean(DEBUG_LOGCAT_PREF, DEBUG_LOGCAT_DEFAULT);
	}

	public String startLogcatCapture() {
		getSharedPreferences("sts2mobile", MODE_PRIVATE).edit()
				.putBoolean(DEBUG_LOGCAT_PREF, true).apply();
		return startLogcatCaptureInternal();
	}

	public void stopLogcatCapture() {
		getSharedPreferences("sts2mobile", MODE_PRIVATE).edit()
				.putBoolean(DEBUG_LOGCAT_PREF, false).apply();
		stopLogcatCaptureInternal();
	}

	public String getLogcatFilePath() {
		return logcatFile != null ? logcatFile.getAbsolutePath() : null;
	}

	public String getLogcatLogsDirPath() {
		File logsDir = new File(Environment.getExternalStorageDirectory(), EXTERNAL_LOGS_SUBDIR);
		return logsDir.getAbsolutePath();
	}

	private synchronized String startLogcatCaptureInternal() {
		if (logcatProcess != null) {
			Log.i(TAG, "[Debug] Logcat capture already running -> " + logcatFile);
			return logcatFile != null ? logcatFile.getAbsolutePath() : null;
		}

		try {
			File logsDir = new File(Environment.getExternalStorageDirectory(), EXTERNAL_LOGS_SUBDIR);
			if (!logsDir.exists() && !logsDir.mkdirs()) {
				Log.e(TAG, "[Debug] Failed to create logs dir: " + logsDir.getAbsolutePath());
				return null;
			}

			pruneOldLogFiles(logsDir);

			String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
			logcatFile = new File(logsDir, "launcher_" + timestamp + ".log");

			int pid = android.os.Process.myPid();
			ProcessBuilder pb = new ProcessBuilder(
					"logcat", "-v", "time", "--pid", String.valueOf(pid));
			pb.redirectErrorStream(true);
			logcatProcess = pb.start();

			final Process proc = logcatProcess;
			final File outFile = logcatFile;
			logcatThread = new Thread(() -> {
				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(proc.getInputStream()));
						BufferedWriter writer = new BufferedWriter(
								new FileWriter(outFile, true))) {
					writer.write("=== Logcat capture started for PID " + pid + " ===\n");
					writer.flush();
					String line;
					while ((line = reader.readLine()) != null) {
						writer.write(line);
						writer.write('\n');
						writer.flush();
					}
				} catch (IOException e) {
					Log.w(TAG, "[Debug] Logcat capture stream ended: " + e.getMessage());
				}
			}, "sts2-logcat-capture");
			logcatThread.setDaemon(true);
			logcatThread.start();

			Log.i(TAG, "[Debug] Logcat capture started -> " + logcatFile.getAbsolutePath());
			return logcatFile.getAbsolutePath();
		} catch (Exception e) {
			Log.e(TAG, "[Debug] Failed to start logcat capture", e);
			logcatProcess = null;
			logcatFile = null;
			return null;
		}
	}

	// Keep at most MAX_LOG_FILES launcher_*.log files in the logs dir; delete the
	// oldest by lastModified() until we're under the cap. Runs cheaply once per
	// capture start, so a never-cleaned-up history can't grow unbounded.
	private void pruneOldLogFiles(File logsDir) {
		try {
			File[] files = logsDir.listFiles((dir, name) ->
					name.startsWith("launcher_") && name.endsWith(".log"));
			if (files == null || files.length < MAX_LOG_FILES) return;

			java.util.Arrays.sort(files, (a, b) ->
					Long.compare(a.lastModified(), b.lastModified()));
			int toDelete = files.length - (MAX_LOG_FILES - 1);
			for (int i = 0; i < toDelete; i++) {
				if (files[i].delete()) {
					Log.i(TAG, "[Debug] Pruned old log: " + files[i].getName());
				}
			}
		} catch (Exception e) {
			Log.w(TAG, "[Debug] Log prune failed: " + e.getMessage());
		}
	}

	private synchronized void stopLogcatCaptureInternal() {
		if (logcatProcess != null) {
			try {
				logcatProcess.destroy();
			} catch (Exception ignored) { }
			logcatProcess = null;
		}
		if (logcatThread != null) {
			try {
				logcatThread.interrupt();
			} catch (Exception ignored) { }
			logcatThread = null;
		}
		Log.i(TAG, "[Debug] Logcat capture stopped");
	}

	public void deleteKeystoreKey() {
		try {
			KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
			keyStore.load(null);
			keyStore.deleteEntry(KEYSTORE_ALIAS);
		} catch (Exception e) {
			Log.e(TAG, "Failed to delete keystore key", e);
		}
	}
}
