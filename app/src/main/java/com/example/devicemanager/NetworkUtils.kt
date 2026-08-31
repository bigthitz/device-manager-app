package com.example.devicemanager

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class LoginResult(
    val token: String,
    val accountId: String,
    val userName: String,
    val machineId: String
)

data class DeviceInfo(
    val machineId: String,
    val accountId: String,
    val grade: String,
    val sex: String,
    val school: String,
    val lastLoginModule: String,
    val loginState: String,
    val isMultiple: String,
    val isSupportSend: String,
    val isLoggedByNewWay: String,
    val parentHeadPortrait: String,
    val parentUserAlias: String,
    val publish: String,
    val userId: String,
    val parentChildVos: List<Map<String, String>>
)

object NetworkUtils {

    private const val BASE_URL_ACCOUNT = "https://account.eebbk.net"
    private const val BASE_URL_ASSISTANT = "https://assistant-pad.eebbk.net"
    private const val DEVICE_TYPE = "S6"
    private const val APP_VERSION_CODE = "4130000"
    private const val APP_VERSION = "4130000"
    private const val DEVICE_OS_VERSION = "V3.2.2_221111"

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val client: OkHttpClient by lazy {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())

        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun queryDeviceInfo(machineId: String): Result<DeviceInfo> = withContext(Dispatchers.IO) {
        try {
            // 先获取 token
            val loginResult = getToken(machineId).getOrElse {
                return@withContext Result.failure(it)
            }

            val token = loginResult.token
            val accountId = loginResult.accountId

            val url = "$BASE_URL_ASSISTANT/api/newParentGroup/verifyToken/getBindAccountListToPad?machineId=$machineId"

            val request = Request.Builder()
                .url(url)
                .header("machineId", machineId)
                .header("apkPackageName", "com.bbk.personal")
                .header("apkVersionCode", APP_VERSION_CODE)
                .header("deviceModel", DEVICE_TYPE)
                .header("deviceOSVersion", DEVICE_OS_VERSION)
                .header("token", token)
                .header("accountId", accountId)
                .header("os", "android")
                .header("appVersion", APP_VERSION)
                .header("Content-Length", "0")
                .header("Host", "assistant-pad.eebbk.net")
                .header("Connection", "Keep-Alive")
                .header("User-Agent", "okhttp/3.12.0")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext Result.failure(Exception("响应为空"))

            val json = JSONObject(body)
            if (json.optString("code") != "0") {
                return@withContext Result.failure(Exception("接口返回错误: ${json.optString("desc", "未知错误")}"))
            }

            val data = json.optJSONObject("data") ?: return@withContext Result.failure(Exception("数据为空"))
            val childVo = data.optJSONObject("childAccountVo") ?: return@withContext Result.failure(Exception("childAccountVo为空"))

            // 解析 parentChildVos
            val parentList = mutableListOf<Map<String, String>>()
            val parentArr = data.optJSONArray("parentChildVos")
            if (parentArr != null) {
                for (i in 0 until parentArr.length()) {
                    val p = parentArr.optJSONObject(i) ?: continue
                    parentList.add(mapOf(
                        "parentAccount" to (p.optString("parentAccount", "-")),
                        "managerRole" to (p.optString("managerRole", "-")),
                        "id" to (p.optString("id", "-")),
                        "childId" to (p.optString("childId", "-")),
                        "facePath" to (p.optString("facePath", ""))
                    ))
                }
            }

            val info = DeviceInfo(
                machineId = childVo.optString("machineId", ""),
                accountId = childVo.optString("accountId", ""),
                grade = childVo.optString("grade", ""),
                sex = childVo.optString("sex", ""),
                school = childVo.optString("school", ""),
                lastLoginModule = childVo.optString("lastLoginModule", ""),
                loginState = childVo.optString("loginState", ""),
                isMultiple = childVo.optString("isMultiple", ""),
                isSupportSend = childVo.optString("isSupportSend", ""),
                isLoggedByNewWay = childVo.optString("isLoggedByNewWay", ""),
                parentHeadPortrait = childVo.optString("parentHeadPortrait", ""),
                parentUserAlias = childVo.optString("parentUserAlias", ""),
                publish = childVo.optString("publish", ""),
                userId = childVo.optString("userId", ""),
                parentChildVos = parentList
            )

            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getToken(machineId: String): Result<LoginResult> = withContext(Dispatchers.IO) {
        try {
            // 前置请求：上报版本
            val appVersionToken = CryptoUtils.createAppVersionToken(machineId)
            try {
                val reportUrl = "$BASE_URL_ACCOUNT/appVersion/report?machineId=$machineId&deviceModel=$DEVICE_TYPE&versionName=4.13.0.0.H&versionCode=$APP_VERSION_CODE"
                val reportReq = Request.Builder()
                    .url(reportUrl)
                    .header("appVersionToken", appVersionToken)
                    .header("machineId", machineId)
                    .header("apkPackageName", "com.bbk.personal")
                    .header("apkVersionCode", APP_VERSION_CODE)
                    .header("deviceModel", DEVICE_TYPE)
                    .header("deviceOSVersion", DEVICE_OS_VERSION)
                    .header("token", "")
                    .header("accountId", "VA$machineId")
                    .header("os", "android")
                    .header("appVersion", APP_VERSION)
                    .header("Host", "account.eebbk.net")
                    .header("Connection", "Keep-Alive")
                    .header("User-Agent", "okhttp/3.12.0")
                    .header("Content-Length", "0")
                    .post("".toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(reportReq).execute().close()
            } catch (_: Exception) {
                // 忽略前置请求错误
            }

            // 获取登录信息
            val loginToken = CryptoUtils.createLoginInfoToken(machineId)
            val loginUrl = "$BASE_URL_ACCOUNT/appVersion/getLoginInfo?machineId=$machineId"
            val loginReq = Request.Builder()
                .url(loginUrl)
                .header("User-Agent", "okhttp/3.12.0")
                .header("machineId", machineId)
                .header("apkPackageName", "com.bbk.personal")
                .header("loginInfoToken", loginToken)
                .header("apkVersionCode", APP_VERSION_CODE)
                .header("deviceModel", DEVICE_TYPE)
                .header("deviceOSVersion", DEVICE_OS_VERSION)
                .header("Host", "account.eebbk.net")
                .header("Connection", "Keep-Alive")
                .get()
                .build()

            val loginResp = client.newCall(loginReq).execute()
            val loginBody = loginResp.body?.string() ?: return@withContext Result.failure(Exception("获取登录信息响应为空"))
            val loginJson = JSONObject(loginBody)

            val code = loginJson.optString("code", "")
            if (code !in listOf("0", "1", "6", "7")) {
                return@withContext Result.failure(Exception("获取登录信息失败: $loginBody"))
            }

            val encrypted = loginJson.optString("loginInfoVo", "")
            if (encrypted.isEmpty()) {
                return@withContext Result.failure(Exception("loginInfoVo为空"))
            }

            val decrypted = CryptoUtils.decryptAesCbc(encrypted, "aa1afads23213fas")
                ?: return@withContext Result.failure(Exception("解密失败"))

            val info = JSONObject(decrypted)
            val accountId = info.optString("accountId", "")
            val verificationCode = info.optString("code", info.optString("verificationCode", ""))

            if (accountId.isEmpty() || verificationCode.isEmpty()) {
                return@withContext Result.failure(Exception("未获取到账号ID或验证码"))
            }

            // 子账号登录
            val loginData = FormBody.Builder()
                .add("accountId", accountId)
                .add("machineId", machineId)
                .add("verificationCode", verificationCode)
                .build()

            val childLoginReq = Request.Builder()
                .url("$BASE_URL_ACCOUNT/app/newAccountSystemLogin/childAccountLogin")
                .header("User-Agent", "okhttp/3.12.0")
                .header("machineId", machineId)
                .header("apkPackageName", "com.bbk.personal")
                .header("apkVersionCode", APP_VERSION_CODE)
                .header("deviceModel", DEVICE_TYPE)
                .header("deviceOSVersion", DEVICE_OS_VERSION)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Host", "account.eebbk.net")
                .header("Connection", "Keep-Alive")
                .post(loginData)
                .build()

            val childResp = client.newCall(childLoginReq).execute()
            val childBody = childResp.body?.string() ?: return@withContext Result.failure(Exception("登录响应为空"))
            val childJson = JSONObject(childBody)

            if (childJson.optInt("resultCode") != 101002) {
                return@withContext Result.failure(Exception("登录失败: ${childJson.optString("resultMessage", "未知错误")}"))
            }

            Result.success(LoginResult(
                token = childJson.optString("token", ""),
                accountId = accountId,
                userName = childJson.optString("userName", ""),
                machineId = machineId
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout(loginInfo: LoginResult): Result<String> = withContext(Dispatchers.IO) {
        try {
            val serialNumber = UUID.randomUUID().toString()

            val formBody = FormBody.Builder()
                .add("serialNumber", serialNumber)
                .add("userName", loginInfo.userName)
                .add("accountId", loginInfo.accountId)
                .build()

            val request = Request.Builder()
                .url("$BASE_URL_ACCOUNT/app/newAccountSystemLogin/childAccountLogout")
                .header("token", loginInfo.token)
                .header("machineId", loginInfo.machineId)
                .header("accountId", loginInfo.accountId)
                .header("apkPackageName", "com.bbk.personal")
                .header("apkVersionCode", APP_VERSION_CODE)
                .header("deviceModel", DEVICE_TYPE)
                .header("deviceOSVersion", DEVICE_OS_VERSION)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Host", "account.eebbk.net")
                .header("Connection", "Keep-Alive")
                .header("User-Agent", "okhttp/3.12.0")
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext Result.failure(Exception("响应为空"))
            val json = JSONObject(body)

            if (json.optInt("resultCode") == 101002) {
                Result.success(json.optString("resultMessage", "成功"))
            } else {
                Result.failure(Exception(json.optString("resultMessage", "未知错误")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}