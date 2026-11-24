package crazydude.com.telemetry.e2e

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Environment
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import crazydude.com.telemetry.operation.Flight
import crazydude.com.telemetry.service.DataService
import org.junit.Assert.fail
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What the end-to-end tests share: the app's data service, waiting, logs on the device. */
object E2E {

    val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var connection: ServiceConnection? = null

    /** Starts the data service as the app does, and binds to it. */
    fun service(): DataService {
        val intent = Intent(context, DataService::class.java)
        context.startForegroundService(intent)
        val latch = CountDownLatch(1)
        var service: DataService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as DataService.DataBinder).getService()
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        context.bindService(intent, conn, 0)
        connection = conn
        if (!latch.await(10, TimeUnit.SECONDS)) fail("the data service did not bind")
        return service!!
    }

    fun unbind() {
        connection?.let { context.unbindService(it) }
        connection = null
    }

    fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    /** Waits until [condition] holds, checking every 100 ms. */
    fun waitFor(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("timed out waiting for: $what")
    }

    fun flight(service: DataService): Flight? = service.operation.read { flight, _ -> flight }

    val logs: File
        get() = Environment.getExternalStoragePublicDirectory("TelemetryLogs")

    /**
     * A recorded flight with GPS on the device: the first whose name contains the instrumentation
     * argument "log" (e.g. -e log 2026-11-02), else the largest .tlm there; null when there is none.
     */
    fun flightLog(): File? {
        InstrumentationRegistry.getArguments().getString("log")?.let { part ->
            return logs.listFiles { f -> f.extension == "tlm" && f.name.contains(part) }?.firstOrNull()
        }
        return logs.listFiles { f -> f.extension == "tlm" }?.maxBy { it.length() }
    }

    /** Sends a debug link command (debug builds: a log played as a live link). */
    fun link(cmd: String, extras: Intent.() -> Unit = {}) {
        context.sendBroadcast(Intent(DataService.DEBUG_ACTION).putExtra("cmd", cmd).apply(extras).setPackage(context.packageName))
    }
}
