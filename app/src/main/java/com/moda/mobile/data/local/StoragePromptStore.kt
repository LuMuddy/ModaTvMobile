package com.moda.mobile.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/**
 * 「所有文件访问」引导弹窗提示到哪个版本。
 *
 * 崩溃日志要落到 /sdcard/crash，Android 11+ 只有 MANAGE_EXTERNAL_STORAGE 能给这个能力，
 * 而它不是运行时权限——只能跳系统设置页让用户手动开，所以每次冷启动都弹是骚扰。
 * 这里记的是「上次为哪个 versionCode 提示过」：同一版本只提示一次，
 * 升级后自然再提示一次；判据用版本号而不是布尔，不需要任何「记得重置」的逻辑
 * （与 moda_copyright_notice 同一个思路，但单独一个文件，不往别人的域里塞键）。
 */
interface StoragePromptStore {
    /** 上次提示时的 versionCode；从未提示过返回 0 */
    suspend fun acknowledgedVersion(): Int

    suspend fun acknowledge(versionCode: Int)
}

private val Context.storagePromptDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "moda_storage_prompt")

private val ACK_VERSION_KEY = intPreferencesKey("ack_version")

object StoragePromptStoreImpl {
    @Volatile
    private var instance: StoragePromptStore? = null

    fun create(context: Context): StoragePromptStore =
        instance ?: synchronized(this) {
            instance ?: StoragePromptPreferencesStore(context.applicationContext.storagePromptDataStore)
                .also { instance = it }
        }
}

private class StoragePromptPreferencesStore(
    private val dataStore: DataStore<Preferences>,
) : StoragePromptStore {

    override suspend fun acknowledgedVersion(): Int =
        dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()[ACK_VERSION_KEY] ?: 0

    override suspend fun acknowledge(versionCode: Int) {
        dataStore.edit { prefs -> prefs[ACK_VERSION_KEY] = versionCode }
    }
}
