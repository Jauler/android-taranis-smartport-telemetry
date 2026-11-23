package crazydude.com.telemetry.protocol.sniffer

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executors

/**
 * A USB serial port opened only to send commands to the sniffer receiver, while the app is not
 * connected to it for telemetry.
 */
class UsbSerialLink(
    private val port: UsbSerialPort,
    connection: UsbDeviceConnection,
    baudrate: Int
) : SerialLink, Closeable {

    @Volatile
    override var rawListener: ((ByteArray) -> Unit)? = null

    private var ioManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()

    init {
        port.open(connection)
        try {
            port.setParameters(baudrate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // some adapters wire these to the receiver's reset or boot pins
            try {
                port.dtr = false
                port.rts = false
            } catch (e: Exception) {
            }
        } catch (e: Exception) {
            close()
            throw e
        }
        val manager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray?) {
                data?.let { rawListener?.invoke(it) }
            }

            override fun onRunError(e: Exception?) {
            }
        })
        ioManager = manager
        executor.submit(manager)
    }

    override fun write(data: ByteArray) {
        port.write(data, WRITE_TIMEOUT_MS)
    }

    override fun close() {
        ioManager?.stop()
        ioManager = null
        executor.shutdown()
        try {
            port.close()
        } catch (e: IOException) {
        }
    }

    companion object {
        private const val WRITE_TIMEOUT_MS = 500
    }
}
