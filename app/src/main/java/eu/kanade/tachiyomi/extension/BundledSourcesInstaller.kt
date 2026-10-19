package eu.kanade.tachiyomi.extension

import android.content.Context
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * Ships the app "with sources included": on first launch it adds the Keiyoushi
 * repository and silently installs every extension it offers, using the private
 * installer so no system install prompt appears per extension. Only Arabic and
 * English are enabled in Browse at first; other languages, individual sources
 * and NSFW sources are shown or hidden from the existing source filter and
 * browse settings. Extensions that fail are retried on the next launch;
 * once everything is in, the bootstrap never runs again, so sources the user
 * uninstalls stay uninstalled.
 */
class BundledSourcesInstaller(
    private val context: Context,
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val createExtensionRepo: CreateExtensionRepo = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
    private val network: NetworkHelper = Injekt.get(),
    preferenceStore: PreferenceStore = Injekt.get(),
) {

    private val completed = preferenceStore.getBoolean(Preference.appStateKey("bundled_sources_installed"), false)

    suspend fun installIfNeeded() = withContext(Dispatchers.IO) {
        if (completed.get()) return@withContext

        createExtensionRepo.await(REPO_URL)
        basePreferences.extensionInstaller().set(BasePreferences.ExtensionInstaller.PRIVATE)
        sourcePreferences.enabledLanguages().getAndSet { it + LANGUAGES }
        sourcePreferences.showNsfwSource().set(false)

        extensionManager.isInitialized.first { it }
        extensionManager.findAvailableExtensions()
        val available = withTimeoutOrNull(1.minutes) {
            extensionManager.availableExtensionsFlow.first { it.isNotEmpty() }
        } ?: return@withContext

        val installed = extensionManager.installedExtensionsFlow.value.map { it.pkgName }.toSet()
        val targets = available.filter { it.pkgName !in installed }

        val semaphore = Semaphore(PARALLEL_DOWNLOADS)
        val failures = coroutineScope {
            targets.map { ext ->
                async { semaphore.withPermit { install(ext) } }
            }.awaitAll()
        }.count { !it }

        logcat { "Bundled sources: installed ${targets.size - failures}/${targets.size}" }
        if (failures == 0) completed.set(true)
    }

    private suspend fun install(extension: Extension.Available): Boolean {
        val file = File(context.cacheDir, "bundled_${extension.pkgName}.apk")
        return try {
            network.client.newCall(GET(apkUrl(extension))).awaitSuccess().use { response ->
                file.outputStream().use { response.body.byteStream().copyTo(it) }
            }
            ExtensionLoader.installPrivateExtensionFile(context, file)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to install bundled source ${extension.pkgName}" }
            false
        } finally {
            file.delete()
        }
    }

    private fun apkUrl(extension: Extension.Available): String =
        if (extension.apkName.startsWith("http://") || extension.apkName.startsWith("https://")) {
            extension.apkName
        } else {
            "${extension.repoUrl}/apk/${extension.apkName}"
        }

    private companion object {
        const val REPO_URL = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
        val LANGUAGES = setOf("ar", "en")
        const val PARALLEL_DOWNLOADS = 6
    }
}
