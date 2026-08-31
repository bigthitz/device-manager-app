package com.example.devicemanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.devicemanager.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var loginResult: LoginResult? = null
    private var unbindUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTabs()
        setupQueryTab()
        setupUnbindTab()
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        binding.layoutQuery.visibility = View.VISIBLE
                        binding.layoutUnbind.visibility = View.GONE
                    }
                    1 -> {
                        binding.layoutQuery.visibility = View.GONE
                        binding.layoutUnbind.visibility = View.VISIBLE
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    // ==================== 查询信息 Tab ====================

    private fun setupQueryTab() {
        binding.btnQuery.setOnClickListener { queryDeviceInfo() }
    }

    private fun queryDeviceInfo() {
        val machineId = binding.etMachineIdQuery.text.toString().trim()
        if (machineId.isEmpty()) {
            Toast.makeText(this, "请输入设备序列号", Toast.LENGTH_SHORT).show()
            return
        }

        setQueryLoading(true)
        binding.infoCard.visibility = View.GONE

        CoroutineScope(Dispatchers.Main).launch {
            val result = NetworkUtils.queryDeviceInfo(machineId)
            setQueryLoading(false)

            result.onSuccess { info ->
                displayDeviceInfo(info)
            }.onFailure { e ->
                Toast.makeText(this@MainActivity, "查询失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun displayDeviceInfo(info: DeviceInfo) {
        val sb = StringBuilder()

        addInfoRow(sb, "设备序列号", info.machineId)
        addInfoRow(sb, "账号ID", info.accountId)
        addInfoRow(sb, "年级", info.grade)

        val sexText = when (info.sex) {
            "1" -> "男"
            "2" -> "女"
            "0" -> "未设置"
            else -> "-"
        }
        addInfoRow(sb, "性别", sexText)
        addInfoRow(sb, "学校", info.school)
        addInfoRow(sb, "登录模块", info.lastLoginModule)
        addInfoRow(sb, "用户ID", info.userId)
        addInfoRow(sb, "家长别名", info.parentUserAlias)

        val loginStateText = when (info.loginState) {
            "0" -> "未登录"
            "1" -> "已登录"
            else -> "-"
        }
        addInfoRow(sb, "登录状态", loginStateText)

        sb.append("\n📚 教材版本: ")
        sb.append(if (info.publish.isNotEmpty()) info.publish else "未设置教材")

        if (info.parentChildVos.isNotEmpty()) {
            sb.append("\n\n👨‍👩‍👦 绑定的家长账号:")
            info.parentChildVos.forEachIndexed { i, p ->
                sb.append("\n  ${i + 1}. ${p["parentAccount"] ?: "-"}")
                sb.append(" | ${if (p["managerRole"] == "1") "管理员" else "成员"}")
            }
        }

        binding.tvDeviceInfo.text = sb.toString()
        binding.infoCard.visibility = View.VISIBLE
    }

    private fun addInfoRow(sb: StringBuilder, label: String, value: String) {
        val displayValue = if (value.isNotEmpty()) value else "-"
        sb.append("$label: $displayValue\n")
    }

    private fun setQueryLoading(loading: Boolean) {
        binding.btnQuery.isEnabled = !loading
        binding.btnQuery.text = if (loading) "查询中..." else "查询设备信息"
        binding.progressQuery.visibility = if (loading) View.VISIBLE else View.GONE
    }

    // ==================== 解绑设备 Tab ====================

    private fun setupUnbindTab() {
        binding.btnGetToken.setOnClickListener { getToken() }
        binding.btnLogout.setOnClickListener { logout() }
        binding.btnShowQr.setOnClickListener { showQrCode() }
        binding.btnCopyLink.setOnClickListener { copyLink() }
    }

    private fun getToken() {
        val machineId = binding.etMachineIdUnbind.text.toString().trim()
        if (machineId.isEmpty()) {
            Toast.makeText(this, "请输入设备序列号", Toast.LENGTH_SHORT).show()
            return
        }

        setUnbindLoading(true)
        hideUnbindResults()

        CoroutineScope(Dispatchers.Main).launch {
            val result = NetworkUtils.getToken(machineId)
            setUnbindLoading(false)

            result.onSuccess { login ->
                loginResult = login
                binding.tvTokenResult.text = """
                    ✅ Token 获取成功
                    
                    Account ID: ${login.accountId}
                    UserName: ${login.userName}
                    Token: ${login.token}
                """.trimIndent()
                binding.tokenCard.visibility = View.VISIBLE
                binding.btnLogout.visibility = View.VISIBLE
                binding.btnGetToken.text = "重新获取 Token"
            }.onFailure { e ->
                Toast.makeText(this@MainActivity, "获取失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun logout() {
        val login = loginResult ?: return

        if (login.token.isEmpty()) {
            Toast.makeText(this, "请先获取 Token", Toast.LENGTH_SHORT).show()
            return
        }

        setUnbindLoading(true)

        CoroutineScope(Dispatchers.Main).launch {
            val result = NetworkUtils.logout(login)
            setUnbindLoading(false)

            result.onSuccess { msg ->
                unbindUrl = CryptoUtils.generateUnbindUrl(login.machineId)
                binding.tvLogoutResult.text = "✅ 退出登录成功: $msg\n\n🔗 解绑链接已生成"
                binding.logoutCard.visibility = View.VISIBLE
                binding.btnShowQr.visibility = View.VISIBLE
                binding.btnCopyLink.visibility = View.VISIBLE
            }.onFailure { e ->
                Toast.makeText(this@MainActivity, "退出失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showQrCode() {
        if (unbindUrl.isEmpty()) {
            Toast.makeText(this, "请先执行退出登录", Toast.LENGTH_SHORT).show()
            return
        }

        val bitmap = QrCodeUtils.generateQrCode(unbindUrl, 512)
        binding.ivQrCode.setImageBitmap(bitmap)
        binding.qrCard.visibility = View.VISIBLE
    }

    private fun copyLink() {
        if (unbindUrl.isEmpty()) return

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("unbind_url", unbindUrl)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "链接已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun setUnbindLoading(loading: Boolean) {
        binding.btnGetToken.isEnabled = !loading
        binding.progressUnbind.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun hideUnbindResults() {
        binding.tokenCard.visibility = View.GONE
        binding.logoutCard.visibility = View.GONE
        binding.qrCard.visibility = View.GONE
        binding.btnLogout.visibility = View.GONE
        binding.btnShowQr.visibility = View.GONE
        binding.btnCopyLink.visibility = View.GONE
        unbindUrl = ""
    }
}