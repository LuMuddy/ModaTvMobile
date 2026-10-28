package com.moda.mobile.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.moda.mobile.model.HomeTab
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/**
 * 首页分类 tab 的用户自定义顺序。
 *
 * 独立文件 moda_home_tabs（一个存储域一个 DataStore，不往 moda_player_settings 里塞键），
 * 消费方：设置页的排序界面、首页 tab 行与分页。
 */
interface HomeTabOrderStore {
    /**
     * 用户自定义顺序。**缺键（用户从未改过）返回默认顺序 [HomeTab.entries]**，
     * 不抛异常、也不需要任何迁移逻辑；返回值永远是 [HomeTab.entries].size 项，一项不丢。
     */
    suspend fun load(): List<HomeTab>

    /** 落盘一份完整顺序；只写 [HomeTab.name]，不写中文文案（文案跟着系统语言走） */
    suspend fun save(order: List<HomeTab>)

    /** 恢复默认 = **删键**，不是写一份看起来一样默认的值 */
    suspend fun clear()
}

// 与 moda_player_settings / moda_copyright_notice 同款：按 applicationContext 缓存实例，
// 重复 create 不会触发 multiple DataStores active 崩溃
private val Context.homeTabOrderDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "moda_home_tabs")

private val ORDER_KEY = stringPreferencesKey("order")

object HomeTabOrderStoreImpl {
    @Volatile
    private var instance: HomeTabOrderStore? = null

    fun create(context: Context): HomeTabOrderStore =
        instance ?: synchronized(this) {
            instance ?: DataStoreHomeTabOrderStore(context.applicationContext.homeTabOrderDataStore)
                .also { instance = it }
        }
}

private class DataStoreHomeTabOrderStore(
    private val dataStore: DataStore<Preferences>,
) : HomeTabOrderStore {

    override suspend fun load(): List<HomeTab> = decodeHomeTabOrder(
        dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()[ORDER_KEY],
    )

    override suspend fun save(order: List<HomeTab>) {
        // 枚举落盘只存 name：改中文文案不能让旧顺序失联
        dataStore.edit { prefs -> prefs[ORDER_KEY] = order.joinToString(",") { it.name } }
    }

    override suspend fun clear() {
        dataStore.edit { prefs -> prefs.remove(ORDER_KEY) }
    }
}

/**
 * 反序列化。三条容错规则都在这里：
 *
 * 1. 缺键（null）或空串 = 用户从未改过 -> 静默退回默认顺序 [HomeTab.entries]；
 *    读不出来只说明「没改过」，不是错误，不抛异常也不需要迁移代码。
 * 2. 未知 / 已删除的 name 直接忽略：HomeTab.of 返回 null，脏数据不该让首页崩掉。
 * 3. 读回顺序里缺失的 tab 按枚举原序追加到末尾：旧版本存的串里没有后加的 tab，
 *    不补的话它就从首页凭空消失（19 项一个不丢）。
 *
 * 顺带用 LinkedHashSet 去重：同一项出现两次时只认第一次，否则分页会多出一页。
 */
private fun decodeHomeTabOrder(raw: String?): List<HomeTab> {
    if (raw.isNullOrBlank()) return HomeTab.entries
    val ordered = LinkedHashSet<HomeTab>(HomeTab.entries.size)
    raw.split(",").forEach { name -> HomeTab.of(name.trim())?.let { ordered.add(it) } }
    HomeTab.entries.forEach { ordered.add(it) }
    return ordered.toList()
}
