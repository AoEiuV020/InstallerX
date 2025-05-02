package com.rosan.installer.data.recycle.model.entity

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import androidx.core.net.toUri
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.installer.data.recycle.util.InstallIntentFilter
import com.rosan.installer.data.recycle.util.delete
import java.io.File

class DhizukuPrivilegedService : BasePrivilegedService() {
    private val devicePolicyManager: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    override fun cacheUri(uriString: String, tempFilePath: String) {
        val uri = uriString.toUri()
        val inputStream = context.contentResolver.openInputStream(uri)!!
        val tempFile = File(tempFilePath)
        tempFile.outputStream().use { output ->
            inputStream.use {
                it.copyTo(output)
            }
        }
    }

    override fun delete(paths: Array<out String>) = paths.delete()

    override fun setDefaultInstaller(component: ComponentName, enable: Boolean) {
        devicePolicyManager.clearPackagePersistentPreferredActivities(
            // TODO
            // DhizukuVariables.PARAM_COMPONENT
            Dhizuku.getOwnerComponent(),
            component.packageName
        )
        if (!enable) return
        devicePolicyManager.addPersistentPreferredActivity(
            // TODO
            // DhizukuVariables.PARAM_COMPONENT,
            Dhizuku.getOwnerComponent(),
            InstallIntentFilter, component
        )
    }
}