package com.custom.lgweboscleaner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties

class MainActivity : AppCompatActivity() {

    private lateinit var etTvIp: EditText
    private lateinit var etPassphrase: EditText
    private lateinit var rbNoRoot: RadioButton
    private lateinit var spThemes: Spinner
    private lateinit var tvStatus: TextView

    private val pickWallpaperLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleFileUpload(it, isWallpaper = true) }
    }

    private val pickIpkLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleFileUpload(it, isWallpaper = false) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etTvIp = findViewById(R.id.etTvIp)
        etPassphrase = findViewById(R.id.etPassphrase)
        rbNoRoot = findViewById(R.id.rbNoRoot)
        spThemes = findViewById(R.id.spThemes)
        tvStatus = findViewById(R.id.tvStatus)

        val prefs = getSharedPreferences("lg_prefs", Context.MODE_PRIVATE)
        etTvIp.setText(prefs.getString("ip", ""))
        etPassphrase.setText(prefs.getString("pass", ""))

        val themes = arrayOf("classic", "liquid-glass", "ribbon", "alpine", "minimal", "retro")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, themes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spThemes.adapter = adapter

        // 1. Pair & Test Connection
        findViewById<Button>(R.id.btnPair).setOnClickListener {
            savePrefs()
            runTask("Checking TV connection & installed launcher...") {
                val out = execSshCommand(
                    "luna-send-pub -n 1 'luna://com.webos.notification/createToast' '{\"message\":\"Phone Connected!\"}' && " +
                    "ls -1 /media/developer/apps/usr/palm/applications/ 2>/dev/null || true"
                )
                if (out.contains("com.gathin.tvhome")) {
                    "Connected! Clean Your TV launcher is installed on TV."
                } else {
                    "Connected! Note: com.gathin.tvhome not found yet."
                }
            }
        }

        // 2. Restore Original LG Home Screen
        findViewById<View>(R.id.cardRestoreHome).setOnClickListener {
            savePrefs()
            AlertDialog.Builder(this)
                .setTitle("Restore LG Home")
                .setMessage("Stop custom Home interception and open the original LG Home screen?")
                .setPositiveButton("Restore") { _, _ ->
                    runTask("Restoring original LG Home screen...") {
                        val cmd =
                            "luna-send-pub -n 1 'luna://com.local.nativehome.service/disable' '{}' 2>/dev/null; " +
                            "luna-send-pub -n 1 'luna://com.webos.service.eim/deleteDevice' '{\"appId\":\"com.gathin.tvhome\"}' 2>/dev/null; " +
                            "luna-send-pub -n 1 'luna://com.webos.applicationManager/launch' '{\"id\":\"com.webos.app.home\"}'"
                        val res = execSshCommand(cmd)
                        "Original LG Home launched!\n$res"
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // 3. Clean TV & Upload Apps Catalog via SFTP
        findViewById<Button>(R.id.btnClean).setOnClickListener {
            savePrefs()
            runTask("Uploading TV apps catalog via SFTP & restarting launcher...") {
                val session = createSshSession()
                try {
                    val appDir = "/media/developer/apps/usr/palm/applications/com.gathin.tvhome"
                    
                    // Query actual installed apps from webOS if possible
                    val rawLp = execOnSession(
                        session,
                        "luna-send-pub -n 1 'luna://com.webos.applicationManager/listLaunchPoints' '{}' 2>/dev/null || true"
                    )

                    val appsJsContent = """
window.TV_APPS = {
  "apps": [
    {"id": "youtube.leanback.v4", "title": "YouTube", "icon": "", "iconColor": "#FF0000"},
    {"id": "netflix", "title": "Netflix", "icon": "", "iconColor": "#E50914"},
    {"id": "amazon", "title": "Prime Video", "icon": "", "iconColor": "#00A8E1"},
    {"id": "hotstar", "title": "JioHotstar", "icon": "", "iconColor": "#0F1014"},
    {"id": "com.jio.media.jiotvplus", "title": "JioTV+", "icon": "", "iconColor": "#0057E7"},
    {"id": "sonyliv", "title": "SonyLIV", "icon": "", "iconColor": "#111111"},
    {"id": "com.zee5.app", "title": "ZEE5", "icon": "", "iconColor": "#8230C6"},
    {"id": "com.webos.app.livetv", "title": "Live TV", "icon": "", "iconColor": "#2B2B2B"},
    {"id": "com.webos.app.browser", "title": "Web Browser", "icon": "", "iconColor": "#0078D7"},
    {"id": "com.webos.app.photovideo", "title": "Photos & Video", "icon": "", "iconColor": "#6B4FBB"},
    {"id": "com.webos.app.miracast", "title": "Screen Share", "icon": "", "iconColor": "#00897B"},
    {"id": "com.palm.app.settings", "title": "TV Settings", "icon": "", "iconColor": "#555555"},
    {"id": "com.webos.app.home", "title": "Original LG Home", "icon": "", "iconColor": "#A50034"},
    {"id": "org.webosbrew.hbchannel", "title": "Homebrew Channel", "icon": "", "iconColor": "#333333"}
  ],
  "inputs": [
    {"appId": "com.webos.app.hdmi1", "label": "HDMI 1"},
    {"appId": "com.webos.app.hdmi2", "label": "HDMI 2"},
    {"appId": "com.webos.app.hdmi3", "label": "HDMI 3"}
  ]
};
""".trimIndent()

                    // Save to phone cache and upload via SFTP (100% reliable)
                    val localAppsJs = File(cacheDir, "apps.js")
                    localAppsJs.writeText(appsJsContent)
                    uploadViaSftp(session, localAppsJs, "$appDir/apps.js")

                    // Clean temp cache & restart the launcher so it reloads apps.js
                    execOnSession(
                        session,
                        "rm -rf /tmp/* /media/developer/temp/* 2>/dev/null; " +
                        "luna-send-pub -n 1 'luna://com.webos.applicationManager/closeByAppId' '{\"id\":\"com.gathin.tvhome\"}' 2>/dev/null; " +
                        "sleep 1; " +
                        "luna-send-pub -n 1 'luna://com.webos.applicationManager/launch' '{\"id\":\"com.gathin.tvhome\"}'"
                    )
                    "Apps catalog uploaded! Now click 'Add app (+)' on your TV with your remote to add YouTube, Netflix, etc. to the screen!"
                } finally {
                    session.disconnect()
                }
            }
        }

        // 4. Custom Wallpaper from Phone
        findViewById<Button>(R.id.btnWallpaper).setOnClickListener {
            savePrefs()
            pickWallpaperLauncher.launch("image/*")
        }

        // 5. Apply Selected Theme
        findViewById<Button>(R.id.btnApplyTheme).setOnClickListener {
            savePrefs()
            val selectedTheme = spThemes.selectedItem?.toString() ?: "classic"
            runTask("Applying '$selectedTheme' theme...") {
                val session = createSshSession()
                try {
                    val appDir = "/media/developer/apps/usr/palm/applications/com.gathin.tvhome"
                    // Copy the selected theme's config.json over the active config.json
                    val cmd =
                        "if [ -f \"$appDir/themes/$selectedTheme/config.json\" ]; then " +
                        "cp \"$appDir/themes/$selectedTheme/config.json\" \"$appDir/config.json\"; " +
                        "fi; " +
                        "luna-send-pub -n 1 'luna://com.webos.applicationManager/closeByAppId' '{\"id\":\"com.gathin.tvhome\"}' 2>/dev/null; " +
                        "sleep 1; " +
                        "luna-send-pub -n 1 'luna://com.webos.applicationManager/launch' '{\"id\":\"com.gathin.tvhome\"}'"
                    execOnSession(session, cmd)
                    "Theme '$selectedTheme' applied and launcher restarted!"
                } finally {
                    session.disconnect()
                }
            }
        }

        // 6. Install Add-On (.ipk)
        findViewById<Button>(R.id.btnInstallAddon).setOnClickListener {
            savePrefs()
            pickIpkLauncher.launch("*/*")
        }

        // 7. Quick Settings
        findViewById<Button>(R.id.btnSettingsPicture).setOnClickListener { openTvSettings("picture") }
        findViewById<Button>(R.id.btnSettingsSound).setOnClickListener { openTvSettings("sound") }
        findViewById<Button>(R.id.btnSettingsNetwork).setOnClickListener { openTvSettings("network") }
    }

    private fun openTvSettings(target: String) {
        savePrefs()
        runTask("Opening TV $target settings...") {
            val cmd =
                "luna-send-pub -n 1 'luna://com.palm.app.settings/launch' '{\"target\":\"$target\"}' 2>/dev/null || " +
                "luna-send-pub -n 1 'luna://com.webos.applicationManager/launch' '{\"id\":\"com.palm.app.settings\",\"params\":{\"target\":\"$target\"}}'"
            val res = execSshCommand(cmd)
            "TV Settings opened!\n$res"
        }
    }

    private fun handleFileUpload(uri: Uri, isWallpaper: Boolean) {
        runTask(if (isWallpaper) "Uploading wallpaper into TV launcher..." else "Uploading & installing .ipk add-on...") {
            val session = createSshSession()
            try {
                if (isWallpaper) {
                    val tempFile = File(cacheDir, "wallpaper.jpg")
                    contentResolver.openInputStream(uri)?.use { input ->
                        val original = BitmapFactory.decodeStream(input)
                            ?: throw Exception("Could not decode selected image.")
                        val scaled = Bitmap.createScaledBitmap(original, 1920, 1080, true)
                        FileOutputStream(tempFile).use { out ->
                            scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                    }

                    val appDir = "/media/developer/apps/usr/palm/applications/com.gathin.tvhome"
                    uploadViaSftp(session, tempFile, "$appDir/wallpaper.jpg")
                    execOnSession(
                        session,
                        "cp $appDir/wallpaper.jpg $appDir/themes/ribbon/wallpaper.jpg 2>/dev/null || true; " +
                        "luna-send-pub -n 1 'luna://com.webos.applicationManager/closeByAppId' '{\"id\":\"com.gathin.tvhome\"}' 2>/dev/null; " +
                        "sleep 1; " +
                        "luna-send-pub -n 1 'luna://com.webos.applicationManager/launch' '{\"id\":\"com.gathin.tvhome\"}'"
                    )
                    "Success! Custom wallpaper injected into Clean Your TV and relaunched!"
                } else {
                    val tempIpk = File(cacheDir, "addon_install.ipk")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempIpk).use { out -> input.copyTo(out) }
                    }
                    val remoteIpk = "/media/developer/temp/addon_install.ipk"
                    execOnSession(session, "mkdir -p /media/developer/temp")
                    uploadViaSftp(session, tempIpk, remoteIpk)

                    val installCmd = "luna-send-pub -i 'luna://com.webos.appInstallService/dev/install' '{\"id\":\"com.ares.defaultName\",\"ipkUrl\":\"$remoteIpk\",\"subscribe\":true}'"
                    val out = execOnSession(session, installCmd, timeoutMs = 25000)
                    "IPK Install Response:\n${out.take(250)}"
                }
            } finally {
                session.disconnect()
            }
        }
    }

    private fun fetchDevKeyFromTv(host: String): File {
        val keyFile = File(filesDir, "webos_rsa_$host")
        try {
            val url = URL("http://$host:9991/webos_rsa")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            if (conn.responseCode == 200) {
                val keyText = conn.inputStream.bufferedReader().use { it.readText() }
                if (keyText.contains("BEGIN") && keyText.contains("PRIVATE KEY")) {
                    keyFile.writeText(keyText)
                    return keyFile
                }
            }
        } catch (_: Exception) {}
        if (keyFile.exists() && keyFile.length() > 100) {
            return keyFile
        }
        throw Exception("Could not fetch SSH key from TV. Turn ON 'Key Server' in Developer Mode app!")
    }

    private fun createSshSession(): Session {
        val host = etTvIp.text.toString().trim()
        val pass = etPassphrase.text.toString().trim()
        if (host.isEmpty()) throw Exception("Please enter your TV IP Address first.")

        val isRoot = !rbNoRoot.isChecked
        val port = if (isRoot) 22 else 9922
        val user = if (isRoot) "root" else "prisoner"

        val jsch = JSch()
        if (!isRoot) {
            val keyFile = fetchDevKeyFromTv(host)
            if (pass.isNotEmpty()) {
                jsch.addIdentity(keyFile.absolutePath, pass.toByteArray(Charsets.UTF_8))
            } else {
                jsch.addIdentity(keyFile.absolutePath)
            }
        }

        val session = jsch.getSession(user, host, port)
        if (pass.isNotEmpty()) session.setPassword(pass)

        session.userInfo = object : UserInfo, UIKeyboardInteractive {
            override fun getPassphrase(): String = pass
            override fun getPassword(): String = pass
            override fun promptPassword(message: String?): Boolean = true
            override fun promptPassphrase(message: String?): Boolean = true
            override fun promptYesNo(message: String?): Boolean = true
            override fun showMessage(message: String?) {}
            override fun promptKeyboardInteractive(
                destination: String?, name: String?, instruction: String?,
                prompt: Array<out String>?, echo: BooleanArray?
            ): Array<String> = Array(prompt?.size ?: 0) { pass }
        }

        val config = Properties()
        config["StrictHostKeyChecking"] = "no"
        config["PreferredAuthentications"] = "publickey,keyboard-interactive,password"
        config["PubkeyAcceptedAlgorithms"] = "+ssh-rsa"
        config["HostKeyAlgorithms"] = "+ssh-rsa"
        session.setConfig(config)

        session.connect(10000)
        return session
    }

    private fun execSshCommand(command: String): String {
        val session = createSshSession()
        return try {
            execOnSession(session, command)
        } finally {
            session.disconnect()
        }
    }

    private fun execOnSession(session: Session, command: String, timeoutMs: Int = 10000): String {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val outStream = ByteArrayOutputStream()
        channel.outputStream = outStream
        channel.setErrStream(outStream)
        channel.connect(8000)

        var elapsed = 0
        while (!channel.isClosed && elapsed < timeoutMs) {
            Thread.sleep(150)
            elapsed += 150
            val currentOut = outStream.toString("UTF-8")
            if (currentOut.contains("installed") || currentOut.contains("FAILED") || currentOut.contains("\"returnValue\"")) {
                if (!command.contains("appInstallService")) break
            }
        }
        channel.disconnect()
        return outStream.toString("UTF-8").trim()
    }

    private fun uploadViaSftp(session: Session, localFile: File, remotePath: String) {
        val sftp = session.openChannel("sftp") as ChannelSftp
        sftp.connect(8000)
        sftp.put(localFile.absolutePath, remotePath)
        sftp.disconnect()
    }

    private fun savePrefs() {
        getSharedPreferences("lg_prefs", Context.MODE_PRIVATE).edit()
            .putString("ip", etTvIp.text.toString().trim())
            .putString("pass", etPassphrase.text.toString().trim())
            .apply()
    }

    private fun runTask(loadingMsg: String, block: () -> String) {
        tvStatus.text = loadingMsg
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
            }
            tvStatus.text = result
        }
    }
}
