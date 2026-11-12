package com.droiddeck.launcher.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.droiddeck.launcher.FrontEndActivity
import com.droiddeck.launcher.R

class RuntimeInstallService : Service() {
    override fun attachBaseContext(newBase: android.content.Context) =
        super.attachBaseContext(com.droiddeck.launcher.core.AppLanguage.wrap(newBase))

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PACKAGE) return holdPackage(startId)
        if (intent?.action == ACTION_CONTAINER_REMOVE) return removeContainer(intent, startId)
        val removal = intent?.action == ACTION_REMOVE
        val release = intent?.let(::releaseFrom)
        startForeground(NOTIFICATION_ID, notification(getString(if (LinuxRuntimeInstaller.isMaintaining()) R.string.desktop_removing else if (removal) R.string.rtinst_removing else R.string.user_apps_starting), -1))
        if (release == null && !removal) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        Thread({
            val manager = getSystemService(NotificationManager::class.java)
            var shown = ""
            RuntimeOperation.begin(getString(R.string.runtime_mgr_working))
            val progress = LinuxRuntimeInstaller.ProgressListener { stage, percent ->
                RuntimeOperation.progress(stage, percent)
                if ("$stage:$percent" != shown) {
                    shown = "$stage:$percent"
                    manager?.notify(NOTIFICATION_ID, notification(stage, percent))
                }
            }
            if (removal) LinuxRuntimeInstaller.attach(progress)
            else {
                val target = intent.getStringExtra(EXTRA_TARGET)?.let { java.io.File(it) }
                val ok = if (target == null) LinuxRuntimeInstaller.install(applicationContext, release!!, progress)
                         else LinuxRuntimeInstaller.installTo(applicationContext, release!!, target, progress)
                if (ok && target != null) {
                    val container = intent.getStringExtra(EXTRA_CONTAINER)
                    val label = intent.getStringExtra(EXTRA_LABEL)
                    if (container != null && label != null) {
                        val distro = intent.getStringExtra(EXTRA_DISTRO) ?: "ubuntu"
                        com.droiddeck.launcher.runtime.RuntimeStore.add(
                            applicationContext,
                            com.droiddeck.launcher.runtime.RuntimeStore.Entry(container, label, target.path, false, distro = distro),
                            activate = true,
                        )
                    }
                }
            }
            RuntimeOperation.end()
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf(startId)
        }, "runtime-install").start()
        return START_NOT_STICKY
    }

    private fun holdPackage(startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(packageStage, packagePercent, packageTitle))
        Thread({
            val manager = getSystemService(NotificationManager::class.java)
            var shown = ""
            while (packageHolds.get() > 0) {
                val now = "$packageStage:$packagePercent"
                if (now != shown) {
                    shown = now
                    manager?.notify(NOTIFICATION_ID, notification(packageStage, packagePercent, packageTitle))
                }
                Thread.sleep(500)
            }
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf(startId)
        }, "package-install").start()
        return START_NOT_STICKY
    }

    private fun removeContainer(intent: Intent, startId: Int): Int {
        val container = intent.getStringExtra(EXTRA_CONTAINER) ?: run { stopSelf(startId); return START_NOT_STICKY }
        val rootfs = intent.getStringExtra(EXTRA_TARGET)
        startForeground(NOTIFICATION_ID, notification(getString(R.string.runtime_mgr_removing), -1))
        Thread({
            RuntimeOperation.begin(getString(R.string.runtime_mgr_removing))
            runCatching { com.droiddeck.launcher.runtime.container.ContainerRuntime.stopContainer(applicationContext, container) }
            com.droiddeck.launcher.runtime.RuntimeStore.remove(applicationContext, container)
            runCatching { com.droiddeck.launcher.runtime.container.ContainerRuntime.removeContainer(applicationContext, container) }
            if (rootfs != null) runCatching {
                com.droiddeck.launcher.runtime.container.SuShell.exec(
                    "rm -rf " + com.droiddeck.launcher.runtime.container.SuShell.quote(rootfs), 180_000)
            }
            RuntimeOperation.end()
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf(startId)
        }, "runtime-remove").start()
        return START_NOT_STICKY
    }

    private fun notification(stage: String, percent: Int, title: String? = null): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.setup_runtime),
            NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.rtinst_channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        })
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, FrontEndActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(title ?: getString(if (LinuxRuntimeInstaller.isMaintaining()) R.string.desktop_removing else if (LinuxRuntimeInstaller.isRemoving()) R.string.rtinst_title_removing else R.string.rtinst_title_installing))
            .setContentText(stage)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "runtime-install"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_REMOVE = "com.droiddeck.launcher.REMOVE_RUNTIME"
        private const val ACTION_PACKAGE = "com.droiddeck.launcher.INSTALL_PACKAGE"
        private const val ACTION_CONTAINER_REMOVE = "com.droiddeck.launcher.REMOVE_CONTAINER"
        private val packageHolds = java.util.concurrent.atomic.AtomicInteger()
        @Volatile private var packageTitle = ""
        @Volatile private var packageStage = ""
        @Volatile private var packagePercent = -1
        private const val EXTRA_VERSION = "version"
        private const val EXTRA_URL = "url"
        private const val EXTRA_SHA256 = "sha256"
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_CONTAINER = "container"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_DISTRO = "distro"

        fun start(context: Context, release: LinuxRuntimeInstaller.Release) {
            start(context, release, null, null, null, null)
        }

        fun start(
            context: Context,
            release: LinuxRuntimeInstaller.Release,
            targetRoot: java.io.File?,
            container: String?,
            label: String?,
            distro: String?,
        ) {
            val intent = Intent(context, RuntimeInstallService::class.java)
                .putExtra(EXTRA_VERSION, release.version)
                .putExtra(EXTRA_URL, release.url)
                .putExtra(EXTRA_SHA256, release.sha256)
                .putExtra(EXTRA_SIZE, release.size)
            targetRoot?.let { intent.putExtra(EXTRA_TARGET, it.absolutePath) }
            container?.let { intent.putExtra(EXTRA_CONTAINER, it) }
            label?.let { intent.putExtra(EXTRA_LABEL, it) }
            distro?.let { intent.putExtra(EXTRA_DISTRO, it) }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun removeContainer(context: Context, container: String, rootfs: String?) {
            val intent = Intent(context, RuntimeInstallService::class.java)
                .setAction(ACTION_CONTAINER_REMOVE)
                .putExtra(EXTRA_CONTAINER, container)
            rootfs?.let { intent.putExtra(EXTRA_TARGET, it) }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun keepRemovalAlive(context: Context) {
            val intent = Intent(context, RuntimeInstallService::class.java).setAction(ACTION_REMOVE)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun holdForPackage(context: Context, title: String): () -> Unit {
            packageTitle = title
            packageStage = title
            packagePercent = -1
            packageHolds.incrementAndGet()
            val intent = Intent(context, RuntimeInstallService::class.java).setAction(ACTION_PACKAGE)
            runCatching { if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent) }
                .onFailure { android.util.Log.w("RuntimeInstallService", "package install runs without a foreground service: $it") }
            val released = java.util.concurrent.atomic.AtomicBoolean()
            return { if (released.compareAndSet(false, true)) packageHolds.decrementAndGet() }
        }

        fun packageProgress(stage: String, percent: Int) {
            packageStage = stage
            packagePercent = percent
        }

        private fun releaseFrom(intent: Intent): LinuxRuntimeInstaller.Release? {
            val version = intent.getStringExtra(EXTRA_VERSION) ?: return null
            val url = intent.getStringExtra(EXTRA_URL) ?: return null
            val sha256 = intent.getStringExtra(EXTRA_SHA256) ?: return null
            return LinuxRuntimeInstaller.Release(version, url, sha256, intent.getLongExtra(EXTRA_SIZE, 0L))
        }
    }
}
