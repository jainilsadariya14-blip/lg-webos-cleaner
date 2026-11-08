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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

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

        val themes = arrayOf("Classic", "Liquid Glass", "Ribbon", "Alpine", "Minimal", "Retro")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, themes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spThemes.adapter = adapter

        // 1. Pair & Test Connection
        findViewById<Button>(R.id.btnPair).setOnClickListener {
            savePrefs()
            runTask("Connecting to TV via SSH...") {
                val cmd = "luna-send-pub -n 1 'luna://com.webos.notification/createToast' '{\"message\":\"Phone Connected to LG webOS Studio!\"}'"
                execSshCommand(cmd)
                "Connected! Toast notification displayed on TV."
            }
        }

        // 2. Restore Original LG Home Screen
        findViewById<View>(R.id.cardRestoreHome).setOnClickListener {
            savePrefs()
            AlertDialog.Builder(this)
                .setTitle("Restore LG Home")
                .setMessage("Are you sure you want to restore your TV's original LG Home screen?")
                .setPositiveButton("Restore") { _, _ ->
                    runTask("Restoring original LG Home screen...") {
                        val isRoot = !rbNoRoot.isChecked
                        val cmd = if (isRoot) {
                            "rm -f /var/lib/webosbrew/init.d/99-cleanyourtv /var/lib/clean-your-tv/autostart; " +
                            "luna-send-pub -n 1 'luna://com.webos.applicationManager/launch' '{\"id\":\"com.webos.app.home\"}'; " +
                            "luna-send-pub -n 1 'luna://com.webos.notification/createToast' '{\"message\":\"Original LG Home Restored!\"}'"
                        } else {
                            "luna-send-pub -n 1 'luna://com.webos.appInstallService/dev/remove' '{\"id\":\"com.cleanyourtv.home\"}'; " +
                            "luna-send-pub -n 1 'luna://com.webos.applicationManager/launch' '{\"id\":\"com.webos.app.home\"}'; " +
                            "luna-send-pub -n 1 'luna://com.webos.notification/createToast' '{\"message\":\"Original LG Home Restored!\"}'"
                        }
                        execSshCommand(cmd)
                        "Success: Original LG Home restored and launched!"
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // 3. Clean TV
        findViewById<Button>(R.id.btnClean).setOnClickListener {
            savePrefs()
            runTask("Cleaning TV background bloat & cache...") {
                val isRoot = !rbNoRoot.isChecked
                val cacheCmd = if (isRoot) {
                    "rm -rf /tmp/* /var/cache/* && sync && echo 3 > /proc/sys/vm/drop_caches"
                } else {
                    "rm -rf /tmp/cytv-* /media/developer/temp/*"
                }
                val closeBloatCmd =
                    "luna-send-pub -n 1 'luna://com.webos.applicationManager/closeByAppId' '{\"id\":\"com.webos.app.discovery\"}'; " +
                    "luna-send-pub -n 1 'luna://com.webos.applicationManager/closeByAppId' '{\"id\":\"com.webos.app.browser\"}'; " +
                    "luna-send-pub -n 1 'luna://com.webos.applicationManager/closeByAppId' '{\"id\":\"com.webos.app.shopping\"}'; " +
                    "luna-send-pub -n 1 'luna://com.webos.applicationManager/closeByAppId' '{\"id\":\"com.webos.app.lgchannels\"}'; " +
                    "$cacheCmd; " +
                    "luna-send-pub -n 1 'luna://com.webos.notification/createToast' '{\"message\":\"TV Cleaned: Bloat Closed & Cache Cleared!\"}'"
                execSshCommand(closeBloatCmd)
                "Cleaned! Closed background bloatware and cleared temporary cache."
            }
        }

        // 4. Custom Wallpaper
        findViewById<Button>(R.id.btnWallpaper).setOnClickListener {
            savePrefs()
            pickWallpaperLauncher.launch("image/*")
        }

        // 5. Apply Theme
        findViewById<Button>(R.id.btnApplyTheme).setOnClickListener {
            savePrefs()
            val selectedTheme = spThemes.selectedItem?.toString() ?: "Classic"
            runTask("Applying '$selectedTheme' theme...") {
                val isRoot = !rbNoRoot.isChecked
                val cfgPath = if (isRoot) {
                    "/var/lib/clean-your-tv/settings.json"
                } else {
                    "/media/developer/apps/usr/palm/applications/com.cleanyourtv.home/state/settings.json"
                }
                val cmd = "mkdir -p \$(dirname $cfgPath) && echo '{\"theme\":\"$selectedTheme\"}' > $cfgPath && " +
                        "luna-send-pub -n 1 'luna://com.webos.applicationManager/launch' '{\"id\":\"com.cleanyourtv.home\"}'; " +
                        "luna-send-pub -n 1 'luna://com.webos.notification/createToast' '{\"message\":\"Theme Applied: $selectedTheme\"}'"
                execSshCommand(cmd)
                "Theme '$selectedTheme' applied to TV!"
            }
        }

        // 6. Install Add-On
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
            val cmd = "luna-send-pub -n 1 'luna://com.webos.applicationManager/launch' '{\"id\":\"com.palm.app.settings\",\"params\":{\"target\":\"$target\"}}'"
            execSshCommand(cmd)
            "Opened TV $target settings on screen!"
        }
    }

    private fun handleFileUpload(uri: Uri, isWallpaper: Boolean) {
        runTask(if (isWallpaper) "Optimizing & uploading custom wallpaper..." else "Uploading & installing .ipk add-on...") {
            val session = createSshSession()
            try {
                if (isWallpaper) {
                    val tempFile = File(cacheDir, "custom_wallpaper.jpg")
                    contentResolver.openInputStream(uri)?.use { input ->
                        val original = BitmapFactory.decodeStream(input)
                            ?: throw Exception("Could not decode selected image.")
                        val scaled = Bitmap.createScaledBitmap(original, 1920, 1080, true)
                        FileOutputStream(tempFile).use { out ->
                            scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
                        }
                    }

                    val isRoot = !rbNoRoot.isChecked
                    val remoteDir = if (isRoot) {
                        "/var/lib/clean-your-tv/wallpapers"
                    } else {
                        "/media/developer/apps/usr/palm/applications/com.cleanyourtv.home/wallpapers"
                    }
                    val remoteFile = "$remoteDir/custom_wallpaper.jpg"

                    execOnSession(session, "mkdir -p $remoteDir")
                    uploadViaSftp(session, tempFile, remoteFile)

                    val applyCmd = "luna-send-pub -n 1 'luna://com.cleanyourtv.home.service/setWallpaper' '{\"path\":\"$remoteFile\",\"active\":true}'; " +
                            "luna-send-pub -n 1 'luna://com.webos.notification/createToast' '{\"message\":\"Custom Wallpaper Applied from Phone!\"}'"
                    execOnSession(session, applyCmd)
                    "Success: Custom wallpaper uploaded and applied to TV!"
                } else {
                    val tempIpk = File(cacheDir, "addon_install.ipk")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempIpk).use { out -> input.copyTo(out) }
                    }
                    val remoteIpk = "/tmp/addon_install.ipk"
                    uploadViaSftp(session, tempIpk, remoteIpk)

                    val installCmd = "luna-send-pub -i 'luna://com.webos.appInstallService/dev/install' '{\"id\":\"com.ares.defaultName\",\"ipkUrl\":\"$remoteIpk\",\"subscribe\":true}'"
                    val out = execOnSession(session, installCmd)
                    "Add-on IPK uploaded and installed!\n${out.take(180)}"
                }
            } finally {
                session.disconnect()
            }
        }
    }

    private fun createSshSession(): Session {
        val host = etTvIp.text.toString().trim()
        val pass = etPassphrase.text.toString().trim()
        if (host.isEmpty()) throw Exception("Please enter your TV IP Address first.")

        val isRoot = !rbNoRoot.isChecked
        val port = if (isRoot) 22 else 9922
        val user = if (isRoot) "root" else "prisoner"

        val jsch = JSch()
        
        // If using developer mode key-based auth with a passphrase
        val keyFile = File(filesDir, "webos_rsa")
        if (!isRoot && pass.length == 6 && !keyFile.exists()) {
            // Write a dummy or fallback connection handler for webOS key server
        }

        val session = jsch.getSession(user, host, port)
        session.setPassword(pass)
        
        // Force keyboard-interactive / password fallback for webOS developer daemon
        val cipherConfig = java.util.Properties()
        cipherConfig.put("StrictHostKeyChecking", "no")
        cipherConfig.put("PreferredAuthentications", "publickey,password,keyboard-interactive")
        session.setConfig(cipherConfig)
        
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

    private fun execOnSession(session: Session, command: String): String {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val outStream = ByteArrayOutputStream()
        channel.outputStream = outStream
        channel.setErrStream(outStream)
        channel.connect(8000)

        var elapsed = 0
        while (!channel.isClosed && elapsed < 15000) {
            Thread.sleep(100)
            elapsed += 100
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
