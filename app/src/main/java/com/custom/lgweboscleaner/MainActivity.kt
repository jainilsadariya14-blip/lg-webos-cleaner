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

        // 3. Clean TV & Repack Apps Catalog without Permission Denied
        findViewById<Button>(R.id.btnClean).setOnClickListener {
            savePrefs()
            runTask("Syncing TV apps & repacking launcher (bypassing read-only lock)...") {
                val session = createSshSession()
                try {
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

                    // Upload to /media/developer/temp (where prisoner ALWAYS has write permission)
                    execOnSession(session, "mkdir -p /media/developer/temp")
                    val localAppsJs = File(cacheDir, "apps.js")
                    localAppsJs.writeText(appsJsContent)
                    uploadViaSftp(session, localAppsJs, "/media/developer/temp/apps.js")

                    // Repack installed app into a fresh IPK in /media/developer/temp and reinstall via system service
                    val repackResult = repackAndReinstallLauncher(session)
                    "Success! Apps synced and launcher updated!\nNow click 'Add app (+)' on your TV screen!\n$repackResult"
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
                    execOnSession(
                        session,
                        "mkdir -p /media/developer/temp && " +
                        "cp /media/developer/apps/usr/palm/applications/com.gathin.tvhome/themes/$selectedTheme/config.json /media/developer/temp/config.json 2>/dev/null || true"
                    )
                    val res = repackAndReinstallLauncher(session)
                    "Theme '$selectedTheme' applied and launcher restarted!\n$res"
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

    /**
     * Copies the existing launcher into /media/developer/temp/repack, injects any updated
     * apps.js / wallpaper.jpg / config.json, builds a valid webOS .ipk, and installs it via appInstallService.
     */
    private fun repackAndReinstallLauncher(session: Session): String {
        val script = """
            rm -rf /media/developer/temp/repack
            mkdir -p /media/developer/temp/repack/data/usr/palm/applications/com.gathin.tvhome
            mkdir -p /media/developer/temp/repack/ctrl
            
            cp -r /media/developer/apps/usr/palm/applications/com.gathin.tvhome/* /media/developer/temp/repack/data/usr/palm/applications/com.gathin.tvhome/ 2>/dev/null || true
            
            if [ -f /media/developer/temp/apps.js ]; then
                cp /media/developer/temp/apps.js /media/developer/temp/repack/data/usr/palm/applications/com.gathin.tvhome/apps.js
            fi
            if [ -f /media/developer/temp/wallpaper.jpg ]; then
                cp /media/developer/temp/wallpaper.jpg /media/developer/temp/repack/data/usr/palm/applications/com.gathin.tvhome/wallpaper.jpg
                cp /media/developer/temp/wallpaper.jpg /media/developer/temp/repack/data/usr/palm/applications/com.gathin.tvhome/themes/ribbon/wallpaper.jpg 2>/dev/null || true
            fi
            if [ -f /media/developer/temp/config.json ]; then
                cp /media/developer/temp/config.json /media/developer/temp/repack/data/usr/palm/applications/com.gathin.tvhome/config.json
            fi
            
            cd /media/developer/temp/repack
            echo "2.0" > debian-binary
            cat << 'EOF' > ctrl/control
Package: com.gathin.tvhome
Version: 1.8.1
Architecture: all
Maintainer: CYTV
Description: Clean Your TV Home
EOF
            tar -czf control.tar.gz -C ctrl .
            tar -czf data.tar.gz -C data .
            ar r /media/developer/temp/cytv_patched.ipk debian-binary control.tar.gz data.tar.gz 2>/dev/null || tar -czf /media/developer/temp/cytv_patched.ipk debian-binary control.tar.gz data.tar.gz
            
            luna-send-pub -i 'luna://com.webos.appInstallService/dev/install' '{"id":"com.ares.defaultName","ipkUrl":"/media/developer/temp/cytv_patched.ipk","subscribe":true}'
        """.trimIndent()

        val out = execOnSession(session, script, timeoutMs = 25000)
        execOnSession(
            session,
            "luna-send-pub -n 1 'luna://com.webos.applicationManager/closeByAppId' '{\"id\":\"com.gathin.tvhome\"}' 2>/dev/null; " +
            "sleep 1; " +
            "luna-send-pub -n 1 'luna://com.webos.applicationManager/launch' '{\"id\":\"com.gathin.tvhome\"}'"
        )
        return out.takeLast(160)
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
        runTask(if (isWallpaper) "Uploading wallpaper & updating launcher..." else "Uploading & installing .ipk add-on...") {
            val session = createSshSession()
            try {
                execOnSession(session, "mkdir -p /media/developer/temp")
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

                    // Upload to /media/developer/temp/wallpaper.jpg (no permission error!)
                    uploadViaSftp(session, tempFile, "/media/developer/temp/wallpaper.jpg")
                    val res = repackAndReinstallLauncher(session)
                    "Success! Custom wallpaper packaged into launcher and restarted!\n$res"
                } else {
                    val tempIpk = File(cacheDir, "addon_install.ipk")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempIpk).use { out -> input.copyTo(out) }
                    }
                    val remoteIpk = "/media/developer/temp/addon_install.ipk"
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
