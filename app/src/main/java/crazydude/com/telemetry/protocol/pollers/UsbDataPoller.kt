package crazydude.com.telemetry.protocol.pollers

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import crazydude.com.telemetry.protocol.*
import crazydude.com.telemetry.protocol.decoder.DataDecoder
import crazydude.com.telemetry.protocol.sniffer.SerialLink
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

class UsbDataPoller(
    private val listener: DataDecoder.Listener,
    private val serialPort: UsbSerialPort,
    private val baudrate: Int,
    private val connection: UsbDeviceConnection,
    private val logFile: FileOutputStream?
) : DataPoller, SerialLink {
    private var outputManager: SerialInputOutputManager? = null
    private var selectedProtocol: Protocol? = null
    private var connectedOnce = false
    private var isDisconnected = true

    /** Sees every byte received, before the protocol decoding (sniffer receiver commands). */
    @Volatile
    override var rawListener: ((ByteArray) -> Unit)? = null

    init {
        connectedOnce = false
        try {
            serialPort.open(connection)
            connectedOnce = true;
            isDisconnected = false;
        } catch (e: IOException) {
            listener.onConnectionFailed()
            logFile?.close()
        }

        serialPort.setParameters(
            baudrate,
            8,
            UsbSerialPort.STOPBITS_1,
            UsbSerialPort.PARITY_NONE
        )

        listener.onConnected()

        val protocolDetector =
            ProtocolDetector(object :
                ProtocolDetector.Callback {
                override fun onProtocolDetected(protocol: Protocol?) {
                    when (protocol) {
                        is FrSkySportProtocol -> {
                            selectedProtocol =
                                FrSkySportProtocol(
                                    listener
                                )
                            listener?.onProtocolDetected("FrSky")
                        }

                        is CrsfProtocol -> {
                            selectedProtocol =
                                CrsfProtocol(
                                    listener
                                )
                            listener?.onProtocolDetected("CRSF")
                        }

                        is LTMProtocol -> {
                            selectedProtocol =
                                LTMProtocol(
                                    listener
                                )
                            listener?.onProtocolDetected("LTM")
                        }

                        is MAVLinkProtocol -> {
                            selectedProtocol =
                                MAVLinkProtocol(
                                    listener
                                )
                            listener?.onProtocolDetected("Mavlink v1")
                        }

                        is MAVLink2Protocol -> {
                            selectedProtocol = MAVLink2Protocol(
                                listener
                            )
                            listener?.onProtocolDetected("Mavlink v2")
                        }

                        is LinkTestProtocol -> {
                            selectedProtocol = LinkTestProtocol(
                                listener
                            )
                            listener?.onProtocolDetected("Link Test")
                        }

                        else -> {
                            logFile?.close()
                            listener.onConnectionFailed()
                            outputManager?.stop()
                            closePort()
                            return
                        }
                    }
                }
            })

        outputManager =
            SerialInputOutputManager(serialPort, object : SerialInputOutputManager.Listener {
                override fun onRunError(e: Exception?) {
                    if ( isDisconnected == false )
                    {
                        isDisconnected == true;
                        if (connectedOnce)
                        {
                            listener.onDisconnected()
                        } else {
                            listener.onConnectionFailed()
                        }
                        logFile?.close()
                    }
                }

                override fun onNewData(data: ByteArray?) {
                    data?.let {
                        rawListener?.invoke(data)
                        logFile?.write(data)
                        data.forEach {
                            if (selectedProtocol != null) {
                                listener?.onTelemetryByte();
                                selectedProtocol?.process(it.toUByte().toInt())
                            } else {
                                listener?.onTelemetryByte();
                                protocolDetector.feedData(it.toUByte().toInt())
                            }
                        }
                        listener?.commit();
                    }
                }
            })
        Executors.newSingleThreadExecutor().submit(outputManager!!)
    }


    override fun disconnect() {
        if ( isDisconnected == false )
        {
            isDisconnected = true;
            outputManager?.stop()
            closePort()
            logFile?.close()
            listener.onDisconnected()
        }
    }

    /** Commands to the receiver, written next to the reading of the telemetry. */
    override fun write(data: ByteArray) {
        if (isDisconnected) throw IOException("not connected")
        serialPort.write(data, WRITE_TIMEOUT_MS)
    }

    /** Frees the port, so that it can be opened again (e.g. by the binding phrase settings). */
    private fun closePort() {
        try {
            serialPort.close()
        } catch (e: Exception) {
        }
    }

    companion object {
        private const val WRITE_TIMEOUT_MS = 500
    }
}