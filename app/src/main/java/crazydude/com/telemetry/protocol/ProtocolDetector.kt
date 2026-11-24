package crazydude.com.telemetry.protocol

import crazydude.com.telemetry.protocol.decoder.DataDecoder

class ProtocolDetector(private val callback: Callback) {

    private val hits = arrayOf(0, 0, 0, 0)
    private val crsfProtocol =
        CrsfProtocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onSuccessDecode() {
                hits[0]++
            }
        })
    private val mavLinkProtocol =
        MAVLinkProtocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onSuccessDecode() {
                hits[1]++
            }
        })
    private val mavLink2Protocol =
        MAVLink2Protocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onSuccessDecode() {
                hits[2]++
            }
        })

    private val linkTestProtocol =
        LinkTestProtocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onSuccessDecode() {
                hits[3]++
            }
        })

    fun feedData(data: Int) {
        crsfProtocol.process(data)
        mavLinkProtocol.process(data)
        mavLink2Protocol.process(data)
        linkTestProtocol.process(data)

        hits.forEachIndexed { index, i ->
            if (i >= 2) {
                when (index) {
                    0 -> callback.onProtocolDetected(crsfProtocol)
                    1 -> callback.onProtocolDetected(mavLinkProtocol)
                    2 -> callback.onProtocolDetected(mavLink2Protocol)
                    3 -> callback.onProtocolDetected(linkTestProtocol)
                }
            }
        }
    }

    interface Callback {
        fun onProtocolDetected(protocol: Protocol?)
    }
}