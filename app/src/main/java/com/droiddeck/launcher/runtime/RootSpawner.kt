package com.droiddeck.launcher.runtime

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import com.droiddeck.launcher.runtime.container.ContainerControl
import com.droiddeck.launcher.runtime.container.ContainerPaths
import com.droiddeck.launcher.runtime.container.DroidspacesCli
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object RootSpawner {
    private const val TAG = "RootSpawner"
    private const val PID_MARKER = "DROIDDECK_ROOT_PID"
    private const val MAX_STDIN = 8 * 1024 * 1024
    private const val ROOT_COMMAND_TIMEOUT_MS = 24 * 60 * 60 * 1000

    fun socket(context: Context): String = "${context.packageName}.root"
    private val seq = AtomicInteger()
    @Volatile private var server: LocalServerSocket? = null

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        val name = socket(app)
        val socket = try {
            LocalServerSocket(name)
        } catch (e: IOException) {
            Log.e(TAG, "cannot listen on $name", e)
            return
        }
        server = socket
        Thread({
            while (true) {
                val client = try { socket.accept() } catch (e: IOException) { Log.e(TAG, "accept", e); break }
                Thread({ serve(app, client) }, "root-${seq.get() + 1}").apply { isDaemon = true; start() }
            }
        }, "root-server").apply { isDaemon = true; start() }
    }

    private fun serve(context: Context, client: LocalSocket) {
        val id = seq.incrementAndGet()
        val received = ArrayList<FileDescriptor>()
        client.use {
            val out = DataOutputStream(client.outputStream)
            try {
                if (client.peerCredentials.uid != Process.myUid()) {
                    Log.w(TAG, "refused root for uid ${client.peerCredentials.uid}")
                    return
                }
                val input = DataInputStream(client.inputStream)
                val kind = input.readUnsignedByte()
                client.ancillaryFileDescriptors?.let { received.addAll(it) }
                val body = ByteArray(input.readInt()).also { input.readFully(it) }
                client.ancillaryFileDescriptors?.let { received.addAll(it) }
                if (kind != 'R'.code) return
                run(context, JSONObject(String(body, Charsets.UTF_8)), received, input, out, id)
            } catch (e: Exception) {
                Log.e(TAG, "root $id", e)
                runCatching {
                    frame(out, 'E', (e.message ?: e.toString()).toByteArray())
                    frame(out, 'X', "1".toByteArray())
                }
            } finally {
                received.forEach { fd -> runCatching { android.system.Os.close(fd) } }
            }
        }
    }

    private fun run(context: Context, request: JSONObject, fds: List<FileDescriptor>, input: DataInputStream,
                    out: DataOutputStream, id: Int) {
        val argv = request.getJSONArray("argv").let { a -> (0 until a.length()).map { a.getString(it) } }
        if (argv.isEmpty()) throw IOException("no command")
        val cwd = request.optString("cwd", "/root").ifEmpty { "/root" }
        val env = LinkedHashMap<String, String>()
        request.optJSONObject("env")?.let { e -> e.keys().forEach { k -> env[k] = e.getString(k) } }
        val stdin = readStdin(fds.getOrNull(0))
        // `droidspaces run` always runs as root, like the old proot -0; the marker line lets the
        // caller signal the guest process tree through the container control script.
        val script = buildString {
            append("echo ").append(PID_MARKER).append(":$$; ")
            append("cd ").append(LinuxRuntime.shellQuote(cwd)).append(" && exec /usr/bin/env -i")
            env.forEach { (key, value) -> append(' ').append(LinuxRuntime.shellQuote("$key=$value")) }
            argv.forEach { append(' ').append(LinuxRuntime.shellQuote(it)) }
        }
        val cancelled = AtomicBoolean(false)
        val guestPid = AtomicInteger(-1)
        val output = fds.getOrNull(1)?.let { runCatching { FileOutputStream(it) }.getOrNull() }
            ?: fds.getOrNull(2)?.let { runCatching { FileOutputStream(it) }.getOrNull() }
            ?: OutputStream.nullOutputStream()
        val watcher = Thread({
            try {
                while (true) {
                    val kind = input.readUnsignedByte()
                    val body = ByteArray(input.readInt()).also { input.readFully(it) }
                    if (kind == 'S'.code) {
                        val signal = String(body).trim().toIntOrNull() ?: continue
                        val pid = guestPid.get()
                        if (pid > 1) runCatching { ContainerControl.signal(context, signal, pid) }
                    }
                }
            } catch (e: IOException) {
            }
            if (!cancelled.get()) {
                Log.i(TAG, "root $id: its caller is gone; stopping it")
                cancelled.set(true)
                // The app uid cannot signal the root su/droidspaces client, so stop the guest
                // command through the container control script instead.
                val pid = guestPid.get()
                if (pid > 1) GuestCommand.killGuestProcess(context, pid)
            }
        }, "root-$id-watch").apply { isDaemon = true; start() }
        try {
            GuestCommand.ensureContainer(context)
            val result = DroidspacesCli.exec(context, ContainerPaths.CONTAINER_NAME, null,
                listOf("/bin/sh", "-c", script), stdin, ROOT_COMMAND_TIMEOUT_MS,
                { line ->
                    if (guestPid.get() <= 0 && line.startsWith("$PID_MARKER:")) {
                        line.removePrefix("$PID_MARKER:").trim().toIntOrNull()?.takeIf { it > 1 }?.let { pid ->
                            guestPid.set(pid)
                            Log.i(TAG, "root $id guest pid $pid: ${argv.joinToString(" ")}")
                            runCatching { synchronized(out) { frame(out, 'P', pid.toString().toByteArray()) } }
                        }
                    } else {
                        runCatching { output.write((line + "\n").toByteArray()); output.flush() }
                    }
                }, cancelled)
            Log.i(TAG, "root $id exited ${result.exitCode}")
            runCatching { synchronized(out) { frame(out, 'X', result.exitCode.toString().toByteArray()) } }
        } finally {
            watcher.interrupt()
            runCatching { output.close() }
        }
    }

    private fun readStdin(fd: FileDescriptor?): String {
        if (fd == null) return ""
        return try {
            FileInputStream(fd).use { input ->
                val buffer = ByteArray(16384)
                val data = java.io.ByteArrayOutputStream()
                while (data.size() < MAX_STDIN) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_STDIN - data.size()))
                    if (count < 0) break
                    data.write(buffer, 0, count)
                }
                String(data.toByteArray(), Charsets.UTF_8)
            }
        } catch (e: IOException) {
            ""
        }
    }

    private fun frame(out: DataOutputStream, kind: Char, payload: ByteArray) {
        out.writeByte(kind.code)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }
}
