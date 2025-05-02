package com.rosan.installer.data.installer.model.impl.installer

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import com.hjq.permissions.XXPermissions
import com.rosan.installer.data.app.model.entity.AnalyseExtraEntity
import com.rosan.installer.data.app.model.entity.AppEntity
import com.rosan.installer.data.app.model.entity.DataEntity
import com.rosan.installer.data.app.model.entity.InstallEntity
import com.rosan.installer.data.app.model.entity.InstallExtraEntity
import com.rosan.installer.data.app.model.impl.AnalyserRepoImpl
import com.rosan.installer.data.installer.model.entity.ProgressEntity
import com.rosan.installer.data.installer.model.entity.SelectInstallEntity
import com.rosan.installer.data.installer.model.exception.ResolveException
import com.rosan.installer.data.installer.model.impl.InstallerRepoImpl
import com.rosan.installer.data.installer.repo.InstallerRepo
import com.rosan.installer.data.recycle.util.useUserService
import com.rosan.installer.data.settings.model.room.entity.ConfigEntity
import com.rosan.installer.data.settings.util.ConfigUtil
import com.rosan.installer.data.settings.util.ConfigUtil.Companion.globalAuthorizer
import com.rosan.installer.data.settings.util.ConfigUtil.Companion.globalCustomizeAuthorizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.internal.closeQuietly
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.UUID

class ActionHandler(scope: CoroutineScope, installer: InstallerRepo) :
    Handler(scope, installer), KoinComponent {
    override val installer: InstallerRepoImpl = super.installer as InstallerRepoImpl

    private var job: Job? = null

    private val context by inject<Context>()

    private val cacheParcelFileDescriptors = mutableListOf<ParcelFileDescriptor>()

    private val cacheDirectory = "${context.externalCacheDir?.absolutePath}/${installer.id}".apply {
        File(this).mkdirs()
    }

    override suspend fun onStart() {
        job = scope.launch {
            installer.action.collect {
                // 异步处理请求
                launch {
                    when (it) {
                        is InstallerRepoImpl.Action.Resolve -> resolve(it.activity)
                        is InstallerRepoImpl.Action.Analyse -> analyse()
                        is InstallerRepoImpl.Action.Install -> install()
                        is InstallerRepoImpl.Action.Finish -> finish()
                    }
                }
            }
        }
    }

    override suspend fun onFinish() {
        cacheParcelFileDescriptors.forEach {
            it.closeQuietly()
        }
        cacheParcelFileDescriptors.clear()
        File(cacheDirectory).deleteRecursively()
        job?.cancel()
    }

    private suspend fun resolve(activity: Activity) {
        installer.progress.emit(ProgressEntity.Resolving)
        kotlin.runCatching {
            requestNotificationPermission(
                activity
            )
            installer.config = resolveConfig(activity)
        }.getOrElse {
            installer.error = it
            installer.progress.emit(ProgressEntity.ResolvedFailed)
            return
        }
        if (installer.config.installMode == ConfigEntity.InstallMode.Ignore) {
            installer.progress.emit(ProgressEntity.Finish)
            return
        }
        installer.data = kotlin.runCatching {
            resolveData(activity)
        }.getOrElse {
            installer.error = it
            installer.progress.emit(ProgressEntity.ResolvedFailed)
            return
        }
        installer.progress.emit(ProgressEntity.ResolveSuccess)
    }

    private suspend fun resolveConfig(activity: Activity): ConfigEntity {
        val packageName = activity.callingPackage
            ?: (activity.referrer?.host)
        var config = ConfigUtil.getByPackageName(packageName)
        if (config.installer == null) config = config.copy(
            installer = packageName
        )
        return config
    }

    private suspend fun requestNotificationPermission(activity: Activity) {
        callbackFlow<Any?> {
            val permissions = listOf(Manifest.permission.POST_NOTIFICATIONS)
            if (XXPermissions.isGranted(activity, permissions)) {
                send(null)
            } else {
                XXPermissions.with(activity).permission(permissions).request { _, all ->
                    if (all) trySend(null)
                    else close()
                }
            }
            awaitClose { }
        }.first()
    }

    private suspend fun resolveData(activity: Activity): List<DataEntity> {
        requestStoragePermissions(activity)
        val uris = resolveDataUris(activity)
        val data = mutableListOf<DataEntity>()
        uris.forEach {
            data.addAll(resolveDataUri(activity, it))
        }
        return data
    }

    private suspend fun requestStoragePermissions(activity: Activity) {
        callbackFlow<Any?> {
            val permissions = listOf(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
            if (XXPermissions.isGranted(activity, permissions)) {
                send(null)
            } else {
                XXPermissions.with(activity).permission(permissions).request { _, all ->
                    if (all) trySend(null)
                    else close()
                }
            }
            awaitClose { }
        }.first()
    }

    private fun resolveDataUris(activity: Activity): List<Uri> {
        val intent = activity.intent ?: throw ResolveException(
            action = null, uris = emptyList()
        )
        val intentAction = intent.action ?: throw ResolveException(
            action = null, uris = emptyList()
        )

        val uris = when (intentAction) {
            Intent.ACTION_SEND -> {
                val uri =
                    intent.getParcelableExtra(
                        Intent.EXTRA_STREAM, Uri::class.java
                    )
                if (uri == null) emptyList() else listOf(uri)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                (intent.getParcelableArrayListExtra(
                    Intent.EXTRA_STREAM, Uri::class.java
                )) ?: emptyList()
            }

            else -> {
                val uri = intent.data
                if (uri == null) emptyList()
                else if (uri.host == "com.oppo.packageinstaller.fileprovider") {
                    /*
                    ====== Intent Details ======
                    Action: android.intent.action.VIEW
                    Data: content://com.oppo.packageinstaller.fileprovider/root-dir/vmdl24437923.tmp/base.apk
                    Type: application/vnd.android.package-archive
                    Package: null
                    Component: com.rosan.installer.x.revived/com.rosan.installer.ui.activity.InstallerActivity
                    Flags: 0x13800000 (327155712)
                    Categories: null
                    Extras:
                    - callerpkg (null): null
                    - apkPath (String): /data/app/vmdl24437923.tmp/base.apk
                    - installFlags (Integer): 272629874
                    ClipData: null
                    ===========================
                    */
                    val apkPath = intent.getStringExtra("apkPath")
                    if (apkPath == null) emptyList()
                    else {
                        val apkFile = File(apkPath)
                        // 有可能没有权限所以不在这里判断而是直接添加，后续再判断处理，
                        // 不能直接传原始intent.data，会识别不到读取者同样读取不了，
                        listOf(Uri.fromFile(apkFile))
                    }
                } else listOf(uri)
            }
        }

        if (uris.isEmpty()) throw ResolveException(
            action = intentAction, uris = uris
        )
        return uris
    }

    private fun resolveDataUri(activity: Activity, uri: Uri): List<DataEntity> {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return resolveDataFileUri(activity, uri)
        return resolveDataContentFile(activity, uri)
    }

    private fun resolveDataFileUri(activity: Activity, uri: Uri): List<DataEntity> {
        var path = uri.path ?: throw Exception("can't get uri path: $uri")
        val file = File(path)
        if (!file.canRead()) {
            // 没权限的文件转给shizuku等处理，简单复制成到缓存中，
            val tempFile =
                File.createTempFile(UUID.randomUUID().toString(), null, File(cacheDirectory))
            useUserService(
                ConfigEntity.default.copy(
                    authorizer = globalAuthorizer,
                    customizeAuthorizer = globalCustomizeAuthorizer
                ), null
            ) {
                it.privileged.cacheUri(uri.toString(), tempFile.absolutePath)
            }
            path = tempFile.absolutePath
        }
        val data = DataEntity.FileEntity(path)
        data.source = DataEntity.FileEntity(path)
        return listOf(data)
    }

    private fun resolveDataContentFile(
        activity: Activity,
        uri: Uri,
        retry: Int = 3
    ): List<DataEntity> {
        // wait for PermissionRecords ok.
        // if not, maybe show Uri Read Permission Denied
        if (activity.checkCallingOrSelfUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            ) != PackageManager.PERMISSION_GRANTED &&
            retry > 0
        ) {
            Thread.sleep(50)
            return resolveDataContentFile(activity, uri, retry - 1)
        }
        val assetFileDescriptor = activity.contentResolver?.openAssetFileDescriptor(uri, "r")
            ?: throw Exception("can't open file descriptor: $uri")
        val parcelFileDescriptor = assetFileDescriptor.parcelFileDescriptor
        val pid = Os.getpid()
        val descriptor = parcelFileDescriptor.fd
        val path = "/proc/$pid/fd/$descriptor"

        // only full file, can't handle a sub-section of a file
        if (assetFileDescriptor.declaredLength < 0) {

            // file descriptor can't be pipe or socket
            val source = Os.readlink(path)
            if (source.startsWith('/')) {
                cacheParcelFileDescriptors.add(parcelFileDescriptor)
                val file = File(path)
                val data = if (file.exists() && file.canRead() && kotlin.runCatching {
                        file.inputStream().use { }
                        return@runCatching true
                    }.getOrDefault(false)) DataEntity.FileEntity(path)
                else DataEntity.FileDescriptorEntity(pid, descriptor)
                data.source = DataEntity.FileEntity(source)
                return listOf(data)
            }
        }

        // cache it
        val tempFile = File.createTempFile(UUID.randomUUID().toString(), null, File(cacheDirectory))
        tempFile.outputStream().use { output ->
            assetFileDescriptor.use {
                it.createInputStream().copyTo(output)
            }
        }
        return listOf(DataEntity.FileEntity(tempFile.absolutePath))
    }

    private suspend fun analyse() {
        installer.progress.emit(ProgressEntity.Analysing)
        installer.entities = kotlin.runCatching {
            analyseEntities(installer.data)
        }.getOrElse {
            installer.error = it
            installer.progress.emit(ProgressEntity.AnalysedFailed)
            return
        }.sortedWith(compareBy({
            it.packageName
        }, {
            when (it) {
                is AppEntity.BaseEntity -> it.name
                is AppEntity.SplitEntity -> it.name
                is AppEntity.DexMetadataEntity -> it.name
            }
        })).map {
            SelectInstallEntity(
                app = it, selected = true
            )
        }
        installer.progress.emit(ProgressEntity.AnalysedSuccess)
    }

    private suspend fun analyseEntities(data: List<DataEntity>): List<AppEntity> =
        AnalyserRepoImpl.doWork(installer.config, data, AnalyseExtraEntity(cacheDirectory))

    private suspend fun install() {
        installer.progress.emit(ProgressEntity.Installing)
        kotlin.runCatching {
            installEntities(installer.config, installer.entities.filter { it.selected }.map {
                InstallEntity(
                    name = it.app.name,
                    packageName = it.app.packageName,
                    data = when (val app = it.app) {
                        is AppEntity.BaseEntity -> app.data
                        is AppEntity.SplitEntity -> app.data
                        is AppEntity.DexMetadataEntity -> app.data
                    }
                )
            }, InstallExtraEntity(Os.getuid() / 100000, cacheDirectory))
        }.getOrElse {
            installer.error = it
            installer.progress.emit(ProgressEntity.InstallFailed)
            return
        }
        installer.progress.emit(ProgressEntity.InstallSuccess)
    }

    private suspend fun installEntities(
        config: ConfigEntity, entities: List<InstallEntity>, extra: InstallExtraEntity
    ) = com.rosan.installer.data.app.model.impl.InstallerRepoImpl.doWork(config, entities, extra)

    private suspend fun finish() = installer.progress.emit(ProgressEntity.Finish)
}