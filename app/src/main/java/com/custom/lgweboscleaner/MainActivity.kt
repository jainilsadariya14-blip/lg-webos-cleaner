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
    private lateinit var tvStatus: TextView

    private val pickWallpaperLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleWallpaperUpload(it) }
    }

    private val pickIpkLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleIpkUpload(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etTvIp = findViewById(R.id.etTvIp)
        etPassphrase = findViewById(R.id.etPassphrase)
        rbNoRoot = findViewById(R.id.rbNoRoot)
        tvStatus = findViewById(R.id.tvStatus)

        val prefs = getSharedPreferences("lg_prefs", Context.MODE_PRIVATE)
        etTvIp.setText(prefs.getString("ip", ""))
        etPassphrase.setText(prefs.getString("pass", ""))

        // 1. Pair & Test Connection
        findViewById<Button>(R.id.btnPair).setOnClickListener {
            savePrefs()
            runTask("Checking TV connection...") {
                val out = execSshCommand("luna-send-pub -n 1 'luna://com.webos.notification/createToast' '{\"message\":\"Phone Connected to TV!\"}'")
                "Connected successfully! $out"
            }
        }

        // 2. Restore Original LG Home
        findViewById<View>(R.id.cardRestoreHome).setOnClickListener {
            savePrefs()
            AlertDialog.Builder(this)
                .setTitle("Restore LG Home")
                .setMessage("Stop custom launcher and open original LG Home?")
                .setPositiveButton("Restore") { _, _ ->
                    runTask("Restoring original LG Home...") {
                        val cmd = "luna-send-pub -n 1 'luna://com.local.nativehome.service/disable' '{}' 2>/dev/null; " +
                                  "luna-send-pub -n 1 'luna://com.webos.applicationManager/launch' '{\"id\":\"com.webos.app.home\"}'"
                        val res = execSshCommand(cmd)
                        "Original LG Home restored!\n$res"
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // 3. Clean TV Cache Safely (Without Touching App Configurations)
        findViewById<Button>(R.id.btnClean).setOnClickListener {
            savePrefs()
            runTask("Cleaning background system cache safely...") {
                val cmd = "rm -rf /tmp/* /media/developer/temp/* 2>/dev/null; " +
                          "luna-send-pub -n 1 'luna://com.webos.applicationManager/closeByAppId' '{\"id\":\"com.webos.app.discovery\"}' 2>/dev/null; " +
                          "luna-send-pub -n 1 'luna://com.webos.applicationManager/closeByAppId' '{\"id\":\"com.webos.app.browser\"}' 2>/dev/null; " +
                          "luna-send-pub -n 1 'luna://com.webos.notification/createToast' '{\"message\":\"TV Cache Cleaned Successfully!\"}'"
                val res = execSshCommand(cmd)
                "System cache cleaned successfully while keeping your app layout intact!\n$res"
            }
        }

        // 4. Set Custom Wallpaper from Phone
        findViewById<Button>(R.id.btnWallpaper).setOnClickListener {
            savePrefs()
            pickWallpaperLauncher.launch("image/*")
        }

        // 5. Install Add-On (.ipk)
        findViewById<Button>(R.id.btnInstallAddon).setOnClickListener {
            savePrefs()
            pickIpkLauncher.launch("*/*")
        }

        // 6. Quick Settings
        findViewById<Button>(R.id.btnSettingsPicture).setOnClickListener { openTvSettings("picture") }
        findViewById<Button>(R.id.btnSettingsSound).setOnClickListener { openTvSettings("sound") }
        findViewById<Button>(R.id.btnSettingsNetwork).setOnClickListener { openTvSettings("network") }
    }

    private fun openTvSettings(target: String) {
        savePrefs()
        runTask("Opening TV $target settings...") {
            val cmd = "luna-send-pub -n 1 'luna://com.webos.applicationManager/launch' '{\"id\":\"com.palm.app.settings\",\"params\":{\"target\":\"$target\"}}'"
            val res = execSshCommand(cmd)
            "TV Settings opened!\n$res"
        }
    }

    private fun handleWallpaperUpload(uri: Uri) {
        runTask("Optimizing & applying custom phone wallpaper...") {
            val session = createSshSession()
            try {
                execOnSession(session, "mkdir -p /media/developer/temp")
                val tempFile = File(cacheDir, "wallpaper.jpg")
                contentResolver.openInputStream(uri)?.use { input ->
                    val original = BitmapFactory.decodeStream(input)
                        ?: throw Exception("Could not decode selected image.")
                    val scaled = Bitmap.createScaledBitmap(original, 1920, 1080, true)
                    FileOutputStream(tempFile).use { out ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                }
                uploadViaSftp(session, tempFile, "/media/developer/temp/wallpaper.jpg")
                val cmd = "cp /media/developer/temp/wallpaper.jpg /media/developer/apps/usr/palm/applications/com.gathin.tvhome/wallpaper.jpg 2>/dev/null || true; " +
                          "cp /media/developer/temp/wallpaper.jpg /media/developer/apps/usr/palm/applications/com.gathin.tvhome/themes/ribbon/wallpaper.jpg 2>/dev/null || true; " +
                          "luna-send-pub -n 1 'luna://com.webos.applicationManager/closeByAppId' '{\"id\":\"com.gathin.tvhome\"}' 2>/dev/null; " +
                          "sleep 1; " +
                          "luna-send-pub -n 1 'luna://com.webos.applicationManager/launch' '{\"id\":\"com.gathin.tvhome\"}'"
                execOnSession(session, cmd)
                "Success! Custom wallpaper applied from your phone!"
            } finally {
                session.disconnect()
            }
        }
    }

    private fun handleIpkUpload(uri: Uri) {
        runTask("Uploading & installing .ipk add-on...") {
            val session = createSshSession()
            try {
                execOnSession(session, "mkdir -p /media/developer/temp")
                val tempIpk = File(cacheDir, "addon_install.ipk")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempIpk).use { out -> input.copyTo(out) }
                }
                uploadViaSftp(session, tempIpk, "/media/developer/temp/addon_install.ipk")
                val cmd = "luna-send-pub -i 'luna://com.webos.appInstallService/dev/install' '{\"id\":\"com.ares.defaultName\",\"ipkUrl\":\"/media/developer/temp/addon_install.ipk\",\"subscribe\":true}'"
                val out = execOnSession(session, cmd, 25000)
                "Add-on installed successfully!\n${out.take(200)}"
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
        if (keyFile.exists() && keyFile.length() > 100) return keyFile
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
