package crazydude.com.telemetry.protocol.sniffer

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A serial port carrying the sniffer receiver's output, which commands can be written to. */
interface SerialLink {
    /** Called on the port's reading thread with every chunk received; null: nobody listens. */
    var rawListener: ((ByteArray) -> Unit)?

    @Throws(IOException::class)
    fun write(data: ByteArray)
}

/**
 * Switches the sniffer receiver to the link of another binding phrase (the recommended flow of
 * the sniffer's UART specification): asks for the UID in use, and sets the new one if it is a
 * different one. Blocking, call it on a background thread.
 */
class SnifferUidChanger(
    private val link: SerialLink,
    private val replyTimeoutMs: Long = 500,
    private val attempts: Int = 3,
    /** No commands faster than this, the receiver asks for it. */
    private val commandIntervalMs: Long = 100
) {

    sealed class Result {
        /** The receiver now follows [uid]; [changed] false: it was on it already. */
        class Done(val uid: ByteArray, val changed: Boolean) : Result()

        /** The receiver answered, but kept [uid]: it is in WiFi or firmware update mode. */
        class Refused(val uid: ByteArray) : Result()

        /** No answer to any attempt. */
        object NoReply : Result()

        class Failed(val message: String) : Result()
    }

    private val reports = LinkedBlockingQueue<SnifferUid.Report>()
    private var lastCommandAt = 0L

    fun apply(uid: ByteArray): Result {
        if (!SnifferUid.isUsable(uid)) return Result.Failed("This binding phrase gives an all-zero UID")
        val parser = SnifferUid.ReportParser { reports.offer(it) }
        link.rawListener = { parser.feed(it) }
        try {
            val current = request(SnifferUid.getUidFrame()) ?: return Result.NoReply
            if (current.uid.contentEquals(uid)) return Result.Done(uid, false)
            val report = request(SnifferUid.setUidFrame(uid)) ?: return Result.NoReply
            // an earlier attempt may have changed it already and lost its reply: the UID decides
            return if (report.uid.contentEquals(uid)) Result.Done(uid, true) else Result.Refused(report.uid)
        } catch (e: IOException) {
            return Result.Failed("Cannot write to the USB serial port: ${e.message}")
        } catch (e: InterruptedException) {
            return Result.Failed("Interrupted")
        } finally {
            link.rawListener = null
        }
    }

    /** Sends [frame] until a report comes back, at most [attempts] times. */
    private fun request(frame: ByteArray): SnifferUid.Report? {
        repeat(attempts) {
            val wait = lastCommandAt + commandIntervalMs - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            reports.clear()
            link.write(frame)
            lastCommandAt = System.currentTimeMillis()
            reports.poll(replyTimeoutMs, TimeUnit.MILLISECONDS)?.let { return it }
        }
        return null
    }
}
