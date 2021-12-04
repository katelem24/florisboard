/*
 * Copyright (C) 2021 Patrick Goldinger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.os.UserManagerCompat
import dev.patrickgold.florisboard.app.prefs.florisPreferenceModel
import dev.patrickgold.florisboard.common.NativeStr
import dev.patrickgold.florisboard.common.toNativeStr
import dev.patrickgold.florisboard.crashutility.CrashUtility
import dev.patrickgold.florisboard.debug.Flog
import dev.patrickgold.florisboard.debug.LogTopic
import dev.patrickgold.florisboard.debug.flogError
import dev.patrickgold.florisboard.debug.flogInfo
import dev.patrickgold.florisboard.ime.core.SubtypeManager
import dev.patrickgold.florisboard.ime.dictionary.DictionaryManager
import dev.patrickgold.florisboard.ime.keyboard.KeyboardManager
import dev.patrickgold.florisboard.ime.spelling.SpellingManager
import dev.patrickgold.florisboard.ime.spelling.SpellingService
import dev.patrickgold.florisboard.ime.theme.ThemeManager
import dev.patrickgold.florisboard.res.AssetManager
import dev.patrickgold.florisboard.res.ext.ExtensionManager
import dev.patrickgold.florisboard.common.android.AndroidVersion
import dev.patrickgold.florisboard.ime.clipboard.ClipboardManager
import dev.patrickgold.jetpref.datastore.JetPrefManager
import java.io.File
import kotlin.Exception

@Suppress("unused")
class FlorisApplication : Application() {
    companion object {
        private const val ICU_DATA_ASSET_PATH = "icu/icudt69l.dat"

        private external fun nativeInitICUData(path: NativeStr): Int

        init {
            try {
                System.loadLibrary("florisboard-native")
            } catch (_: Exception) {
            }
        }
    }

    private val prefs by florisPreferenceModel()

    val assetManager = lazy { AssetManager(this) }
    val clipboardManager = lazy { ClipboardManager(this) }
    val extensionManager = lazy { ExtensionManager(this) }
    val keyboardManager = lazy { KeyboardManager(this) }
    val spellingManager = lazy { SpellingManager(this) }
    val spellingService = lazy { SpellingService(this) }
    val subtypeManager = lazy { SubtypeManager(this) }

    override fun onCreate() {
        super.onCreate()
        try {
            JetPrefManager.init(saveIntervalMs = 1_000)
            Flog.install(
                context = this,
                isFloggingEnabled = BuildConfig.DEBUG,
                flogTopics = LogTopic.ALL,
                flogLevels = Flog.LEVEL_ALL,
                flogOutputs = Flog.OUTPUT_CONSOLE,
            )
            CrashUtility.install(this)

            if (AndroidVersion.ATLEAST_API24_N && !UserManagerCompat.isUserUnlocked(this)) {
                val context = createDeviceProtectedStorageContext()
                initICU(context)
                prefs.initializeForContext(context)
                registerReceiver(BootComplete(), IntentFilter(Intent.ACTION_USER_UNLOCKED))
            } else {
                initICU(this)
                prefs.initializeForContext(this)
            }

            DictionaryManager.init(this)
            ThemeManager.init(this, assetManager.value)
        } catch (e: Exception) {
            CrashUtility.stageException(e)
            return
        }
    }

    fun initICU(context: Context): Boolean {
        try {
            val androidAssetManager = context.assets ?: return false
            val icuTmpDataFile = File(context.cacheDir, "icudt.dat")
            icuTmpDataFile.outputStream().use { os ->
                androidAssetManager.open(ICU_DATA_ASSET_PATH).use { it.copyTo(os) }
            }
            val status = nativeInitICUData(icuTmpDataFile.absolutePath.toNativeStr())
            icuTmpDataFile.delete()
            return if (status != 0) {
                flogError { "Native ICU data initializing failed with error code $status!" }
                false
            } else {
                flogInfo { "Successfully loaded ICU data!" }
                true
            }
        } catch (e: Exception) {
            flogError { e.toString() }
            return false
        }
    }

    private inner class BootComplete : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            if (intent.action == Intent.ACTION_USER_UNLOCKED) {
                try {
                    unregisterReceiver(this)
                } catch (e: Exception) {
                    flogError { e.toString() }
                }
                prefs.initializeForContext(this@FlorisApplication)
            }
        }
    }
}

private fun Context.florisApplication(): FlorisApplication {
    return when (this) {
        is FlorisApplication -> this
        else -> this.applicationContext as FlorisApplication
    }
}

fun Context.appContext() = lazy { this.florisApplication() }

fun Context.assetManager() = lazy { this.florisApplication().assetManager.value }

fun Context.clipboardManager() = lazy { this.florisApplication().clipboardManager.value }

fun Context.extensionManager() = lazy { this.florisApplication().extensionManager.value }

fun Context.keyboardManager() = lazy { this.florisApplication().keyboardManager.value }

fun Context.spellingManager() = lazy { this.florisApplication().spellingManager.value }

fun Context.spellingService() = lazy { this.florisApplication().spellingService.value }

fun Context.subtypeManager() = lazy { this.florisApplication().subtypeManager.value }
