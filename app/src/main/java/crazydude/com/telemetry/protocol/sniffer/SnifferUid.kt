package crazydude.com.telemetry.protocol.sniffer

import java.security.MessageDigest

/**
 * The commands which switch an ELRS sniffer receiver (firmware built with -DRX_SNIFFER) to
 * another pilot's link over its UART, see changing-binding-phrase-over-uart.md in the ELRS
 * sniffer firmware repository.
 *
 * The receiver follows the link of one binding phrase, identified by its 6 byte UID.
 */
object SnifferUid {

    const val UID_SIZE = 6

    private const val SYNC = 0xC8
    private const val TYPE_COMMAND = 0x32
    private const val ADDRESS_RECEIVER = 0xEC
    private const val ADDRESS_APP = 0xC8
    private const val CMD_RECEIVER = 0x10
    private const val SUBCMD_SET_UID = 0xE0
    private const val SUBCMD_GET_UID = 0xE1
    const val SUBCMD_UID_REPORT = 0xE2

    const val POLY_COMMAND = 0xBA
    const val POLY_FRAME = 0xD5

    /** Report flag: this request changed the UID. */
    const val FLAG_CHANGED = 0x01
    /** Report flag: the UID in use is not all zero, the receiver follows a link. */
    const val FLAG_BOUND = 0x02

    /**
     * The UID of a binding phrase, the way the ELRS web UI and configurator compute it: 4 to 6
     * comma separated numbers 0-255 are a literal UID, anything else is hashed.
     */
    fun fromPhrase(input: String): ByteArray {
        val phrase = input.trim()
        val parts = phrase.split(',').map { it.trim() }
        if (parts.size in 4..UID_SIZE && parts.all { isByte(it) }) {
            return ByteArray(UID_SIZE - parts.size) + parts.map { it.toInt().toByte() }.toByteArray()
        }
        val md5 = MessageDigest.getInstance("MD5")
            .digest("-DMY_BINDING_PHRASE=\"$phrase\"".toByteArray(Charsets.UTF_8))
        return md5.copyOf(UID_SIZE)
    }

    private fun isByte(text: String): Boolean {
        return text.isNotEmpty() && text.length <= 3 && text.all { it in '0'..'9' } && text.toInt() < 256
    }

    /**
     * Only bytes 2-5 make the link: a UID with those all zero means "no link", the receiver
     * refuses it.
     */
    fun isUsable(uid: ByteArray): Boolean {
        return uid.size == UID_SIZE && (2 until UID_SIZE).any { uid[it].toInt() != 0 }
    }

    /** As the ELRS web UI shows it: "133,75,181,162,104,215". */
    fun format(uid: ByteArray): String {
        return uid.joinToString(",") { (it.toInt() and 0xFF).toString() }
    }

    /** CRC-8, MSB first, initial value 0, over data[from, to). */
    fun crc8(data: ByteArray, from: Int, to: Int, poly: Int): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor poly) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc
    }

    /** [sync] [len] [type .. payload] [crc_cmd] [crc_frame], dest the receiver, orig the app. */
    private fun commandFrame(subcommand: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(9 + payload.size)
        frame[0] = SYNC.toByte()
        frame[1] = (frame.size - 2).toByte()
        frame[2] = TYPE_COMMAND.toByte()
        frame[3] = ADDRESS_RECEIVER.toByte()
        frame[4] = ADDRESS_APP.toByte()
        frame[5] = CMD_RECEIVER.toByte()
        frame[6] = subcommand.toByte()
        payload.copyInto(frame, 7)
        val crcCommand = frame.size - 2
        frame[crcCommand] = crc8(frame, 2, crcCommand, POLY_COMMAND).toByte()
        frame[frame.size - 1] = crc8(frame, 2, frame.size - 1, POLY_FRAME).toByte()
        return frame
    }

    fun setUidFrame(uid: ByteArray): ByteArray {
        require(uid.size == UID_SIZE) { "a UID has $UID_SIZE bytes" }
        return commandFrame(SUBCMD_SET_UID, uid)
    }

    fun getUidFrame(): ByteArray = commandFrame(SUBCMD_GET_UID, ByteArray(0))

    /** What the receiver answers to every valid SET_UID and GET_UID. */
    class Report(val uid: ByteArray, val flags: Int) {
        val changed: Boolean get() = flags and FLAG_CHANGED != 0
        val bound: Boolean get() = flags and FLAG_BOUND != 0
    }

    /**
     * Picks the UID reports out of the receiver's output, which also carries telemetry and
     * forwarded command frames heard over the air. Feed it every byte received.
     */
    class ReportParser(private val onReport: (Report) -> Unit) {

        private val buffer = ByteArray(64)
        private var size = 0

        fun feed(data: ByteArray, length: Int = data.size) {
            for (i in 0 until length) feed(data[i])
        }

        fun feed(byte: Byte) {
            if (size == 0 && byte.toInt() and 0xFF != SYNC) return
            buffer[size++] = byte
            if (size == 2) {
                val len = buffer[1].toInt() and 0xFF
                if (len < 2 || len > 62) {
                    resync()
                }
                return
            }
            if (size < 2 || size < 2 + (buffer[1].toInt() and 0xFF)) return
            // a whole frame
            if (crc8(buffer, 2, size - 1, POLY_FRAME) == buffer[size - 1].toInt() and 0xFF) {
                checkReport()
                size = 0
            } else {
                resync()
            }
        }

        /** Drops the first byte of what is buffered and looks for the next sync byte in the rest. */
        private fun resync() {
            val rest = buffer.copyOfRange(1, size)
            size = 0
            rest.forEach { feed(it) }
        }

        private fun checkReport() {
            if (size != 16) return
            if (buffer[2].toInt() and 0xFF != TYPE_COMMAND) return
            if (buffer[4].toInt() and 0xFF != ADDRESS_RECEIVER) return
            if (buffer[5].toInt() and 0xFF != CMD_RECEIVER) return
            if (buffer[6].toInt() and 0xFF != SUBCMD_UID_REPORT) return
            if (crc8(buffer, 2, 14, POLY_COMMAND) != buffer[14].toInt() and 0xFF) return
            onReport(Report(buffer.copyOfRange(7, 13), buffer[13].toInt() and 0xFF))
        }
    }
}
