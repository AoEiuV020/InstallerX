package com.rosan.installer;

import android.content.ComponentName;
import android.net.Uri;

interface IPrivilegedService {
    void cacheUri(in String uriString, in String tempFilePath);

    void delete(in String[] paths);

    void setDefaultInstaller(in ComponentName component, boolean enable);
}
