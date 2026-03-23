package com.fmradio.rtlsdr

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

/**
 * RTL-TCP client for connecting to rtl_tcp server.
 * The user runs `rtl_tcp -a 127.0.0.1` on Windows (comes with RTL-SDR driver package),
 * then this client connects via TCP to receive IQ samples.
 *
 * Protocol:
 *   - First 12 bytes from server: "RTL0" magic + tuner type (4 bytes) + gain count (4 bytes)
 *   - Commands: 5-byte packets (1 byte cmd + 4 bytes parameter, big-endian)
 *   - Data: raw unsigned 8-bit IQ interleaved
 */
class RtlTcpClient {

    companion object {
        private const val CMD_SET_FREQUENCY = 0x01
        private const val CMD_SET_SAMPLE_RATE = 0x02
        private const val CMD_SET_GAIN_MODE = 0x03
        private const val CMD_SET_GAIN = 0x04
        private const val CMD_SET_AGC_MODE = 0x08
        private const val CMD_SET_DIRECT_SAMPLING = 0x09
        private const val CMD_SET_BIAS_TEE = 0x0E

        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 1234
        const val DEFAULT_SAMPLE_RATE = 1152000
    }

    private var socket: Socket? = null
    private var dataIn: DataInputStream? = null
    private var dataOut: DataOutputStream? = null

    @Volatile
    var isConnected = false
        private set

    @Volatile
    var isStreaming = false
        private set

    var tunerType = 0
        private set
    var gainCount = 0
        private set

    fun connect(host: String = DEFAULT_HOST, port: Int = DEFAULT_PORT): Boolean {
        return try {
            socket = Socket(host, port).also {
                it.tcpNoDelay = true
                it.receiveBufferSize = 262144
            }
            dataIn = DataInputStream(socket!!.getInputStream())
            dataOut = DataOutputStream(socket!!.getOutputStream())

            // Read 12-byte header: "RTL0" + tuner_type(4) + gain_count(4)
            val header = ByteArray(4)
            dataIn!!.readFully(header)
            val magic = String(header)
            if (magic != "RTL0") {
                println("Invalid rtl_tcp magic: $magic")
                disconnect()
                return false
            }
            tunerType = dataIn!!.readInt()
            gainCount = dataIn!!.readInt()

            isConnected = true
            println("Connected to rtl_tcp (tuner=$tunerType, gains=$gainCount)")
            true
        } catch (e: Exception) {
            println("Failed to connect to rtl_tcp: ${e.message}")
            disconnect()
            false
        }
    }

    fun setFrequency(frequencyHz: Long) {
        sendCommand(CMD_SET_FREQUENCY, frequencyHz.toInt())
    }

    fun setSampleRate(rate: Int) {
        sendCommand(CMD_SET_SAMPLE_RATE, rate)
    }

    fun setAutoGain(enabled: Boolean) {
        sendCommand(CMD_SET_GAIN_MODE, if (enabled) 0 else 1) // 0=auto, 1=manual
        sendCommand(CMD_SET_AGC_MODE, if (enabled) 1 else 0)
    }

    fun setGain(gainTenths: Int) {
        sendCommand(CMD_SET_GAIN_MODE, 1)
        sendCommand(CMD_SET_GAIN, gainTenths)
    }

    fun setBiasTee(enabled: Boolean) {
        sendCommand(CMD_SET_BIAS_TEE, if (enabled) 1 else 0)
    }

    private fun sendCommand(cmd: Int, param: Int) {
        try {
            val out = dataOut ?: return
            out.writeByte(cmd)
            out.writeInt(param)
            out.flush()
        } catch (e: IOException) {
            println("Error sending command $cmd: ${e.message}")
        }
    }

    fun readSamples(length: Int): ByteArray? {
        if (!isConnected) return null
        return try {
            val buffer = ByteArray(length)
            dataIn!!.readFully(buffer)
            buffer
        } catch (e: IOException) {
            println("Error reading samples: ${e.message}")
            null
        }
    }

    fun startStreaming(bufferSize: Int = 65536, callback: (ByteArray) -> Unit): Thread {
        isStreaming = true
        val thread = Thread({
            println("Streaming started")
            while (isStreaming && isConnected) {
                val data = readSamples(bufferSize)
                if (data != null) {
                    callback(data)
                } else {
                    break
                }
            }
            println("Streaming stopped")
        }, "RtlTcpStreaming")
        thread.isDaemon = true
        thread.start()
        return thread
    }

    fun stopStreaming() {
        isStreaming = false
    }

    fun disconnect() {
        isStreaming = false
        isConnected = false
        try { dataIn?.close() } catch (_: Exception) {}
        try { dataOut?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        dataIn = null
        dataOut = null
        socket = null
    }
}
