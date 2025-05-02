# InstallerX

基于wxxsfxyzm/InstallerX， 适配一加13, 修复adb安装，  

oppo手机（具体是一加13）先通过CrossProfileTestApp.apk禁用默认安装程序重启后adb安装也可以选择installX,这时候会报权限错误，原因是intent.data不可读取，  
但intent.extras有apk文件绝对路径可以读取，所以这里针对com.oppo.packageinstaller.fileprovider改成取出文件路径使用，  
然而文件本身也没权限读取，所以交给shizuku等复制一份缓存文件再继续，  
值得注意的是如果app已存在的话更新安装是不会触发installX的，  
另外有时会有不明原因没有拉起installX直接报错 Failure [-99] 这时候需要手动kill掉 com.android.packageinstaller 进程，重新adb install就正常了，  
还有配置文件需要覆盖adb安装的情况需要勾选 com.android.packageinstaller 系统应用，  
至于f-droid,需要在f-droid开启“强制使用旧安装器”才会使用installX安装，有效，但f-droid无法监听到安装成功的事件，  

## 介绍

一款应用安装程序，为什么不试试【InstallerX】？

在国产系统的魔改下，许多系统的自带安装程序体验并不是很好，你可以使用【InstallerX】替换掉系统默认安装程序。

当然，相对于原生系统，【InstallerX】也带来了更多的安装选项：对话框安装、通知栏安装、自动安装、声明安装者、选择是否安装到所有用户空间、允许测试包、允许降级安装、安装后自动删除安装包。

## 支持版本

Android 14 以上

如有旧版本要求可以前往原仓库下载

## 变更内容

修复了原仓库项目无法正确删除安装包的问题

## 开源协议

InstallerX目前基于 [**GNU General Public License v3 (GPL-3)**](http://www.gnu.org/copyleft/gpl.html)
开源，但不保证未来依然继续遵循此协议或开源，有权更改开源协议或开源状态。

当您选择基于InstallerX进行开发时，需遵循所当前依赖的上游源码所规定的开源协议，不受新上游源码的开源协议影响。