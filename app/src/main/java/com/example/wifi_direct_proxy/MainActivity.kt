package com.example.wifi_direct_proxy

import android.Manifest
import android.content.*
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.*
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import com.example.wifi_direct_proxy.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel

    private val TAG = "WiFiDirect"

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms.values.all { it }
        if (granted) {
            Log.d(TAG, "✅ 권한 모두 허용됨")
            startWiFiDirectFlow()
        } else {
            Log.e(TAG, "❌ 권한 거부됨")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnStart.setOnClickListener {
            val port = binding.etPort.text.toString().toIntOrNull() ?: 1081
            startService(HttpProxyService.newStartIntent(this, port))
            binding.etPort.setText(port.toString())
        }
        binding.btnStop.setOnClickListener {
            stopService(HttpProxyService.newStopIntent(this))
        }
        useSystemProxy = binding.cbUseSystemProxy.isChecked
        binding.cbUseSystemProxy.setOnCheckedChangeListener { _, isChecked ->
            useSystemProxy = isChecked
        }

        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)

        permissionsLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        )

        registerReceiver(wifiDirectReceiver, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))
    }

    private fun startWiFiDirectFlow() {
        resetWifiState {
            clearPersistentGroups {
                removeCurrentGroup {
                    Handler(Looper.getMainLooper()).postDelayed(@androidx.annotation.RequiresPermission(
                        allOf = [android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.NEARBY_WIFI_DEVICES]
                    ) {
                        createWifiDirectGroup()
                    }, 1000)
                }
            }
        }
    }

    private fun resetWifiState(onComplete: () -> Unit) {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiManager.isWifiEnabled) {
            wifiManager.isWifiEnabled = false
            Handler(Looper.getMainLooper()).postDelayed({
                wifiManager.isWifiEnabled = true
                Log.d(TAG, "📶 Wi-Fi 리셋 완료")
                onComplete()
            }, 1500)
        } else {
            wifiManager.isWifiEnabled = true
            Handler(Looper.getMainLooper()).postDelayed(onComplete, 1000)
        }
    }

    private fun clearPersistentGroups(onComplete: () -> Unit) {
        try {
            val method = manager.javaClass.getMethod(
                "deletePersistentGroup",
                WifiP2pManager.Channel::class.java,
                Int::class.javaPrimitiveType,
                WifiP2pManager.ActionListener::class.java
            )
            var pending = 32
            for (i in 0 until 32) {
                method.invoke(manager, channel, i, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "🧹 Persistent group $i deleted")
                        if (--pending == 0) onComplete()
                    }

                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "⚠️ Failed to delete group $i: $reason")
                        if (--pending == 0) onComplete()
                    }
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Persistent group 제거 실패", e)
            onComplete()
        }
    }

    private fun removeCurrentGroup(onComplete: () -> Unit) {
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "✅ 기존 그룹 제거됨")
                onComplete()
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "⚠️ 기존 그룹 제거 실패: $reason → 그래도 진행")
                onComplete()
            }
        })
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    private fun createWifiDirectGroup() {
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "✅ 그룹 생성됨")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "❌ 그룹 생성 실패: $reason")
            }
        })
    }

    private val wifiDirectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                val group = intent.getParcelableExtra<WifiP2pGroup>(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                if (group != null && group.isGroupOwner) {
                    val ssid = group.networkName
                    val pass = group.passphrase
                    Log.d(TAG, "📡 그룹 정보 - SSID: $ssid, PASSWORD: $pass")
                    // 화면에 띄우거나 QR 코드로 공유 가능
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(HttpProxyService.newStopIntent(this))
        unregisterReceiver(wifiDirectReceiver)
    }
}