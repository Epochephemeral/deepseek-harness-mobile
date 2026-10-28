@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.wire.dto.SessionHistoryRecord
import com.labteto.dshmobile.core.wire.dto.SessionReaderCoverage
import com.labteto.dshmobile.core.wire.dto.SessionPage
import java.io.FilterInputStream
import java.io.InputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.decodeFromStream

/** No records list is retained. throughSeq is the request's pinned log cut, not a new host cursor. */
data class SessionPageMetadata(val hasMore: Boolean, val recordCount: Long, val throughSeq: Int, val reader: SessionReaderCoverage? = null)

internal data class TypedServerResponse<T>(val type: String, val rpcId: String, val result: RpcResult<T>)

/** Unlike RpcResultJsonSerializer, this decodes the DTO directly from the JSON token stream. */
internal class TypedRpcResultSerializer<T>(private val valueSerializer: KSerializer<T>) : KSerializer<RpcResult<T>> {
    override val descriptor = buildClassSerialDescriptor("TypedRpcResult") {
        element<Boolean>("ok", isOptional = true)
        element("value", valueSerializer.descriptor, isOptional = true)
        element("error", RpcError.serializer().descriptor, isOptional = true)
    }

    override fun deserialize(decoder: Decoder): RpcResult<T> {
        val fields = decoder.beginStructure(descriptor)
        var ok = false
        var hasValue = false
        var value: T? = null
        var error: RpcError? = null
        while (true) {
            when (val index = fields.decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break
                0 -> ok = fields.decodeBooleanElement(descriptor, 0)
                1 -> { value = fields.decodeSerializableElement(descriptor, 1, valueSerializer); hasValue = true }
                2 -> error = fields.decodeSerializableElement(descriptor, 2, RpcError.serializer())
                else -> throw SerializationException("Unexpected result field $index")
            }
        }
        fields.endStructure(descriptor)
        return if (ok) {
            @Suppress("UNCHECKED_CAST")
            RpcResult.Ok(if (hasValue) value as T else WireJson.decodeFromString(valueSerializer, "{}"))
        } else RpcResult.Err(error ?: RpcError("internal", "server response carried no error details"))
    }

    override fun serialize(encoder: Encoder, value: RpcResult<T>) = error("Read-only RPC decoder")
}

internal class TypedServerResponseSerializer<T>(valueSerializer: KSerializer<T>) : KSerializer<TypedServerResponse<T>> {
    private val resultSerializer = TypedRpcResultSerializer(valueSerializer)
    override val descriptor = buildClassSerialDescriptor("TypedServerResponse") {
        element<String>("type", isOptional = true)
        element<String>("rpcId")
        element("result", resultSerializer.descriptor)
    }

    override fun deserialize(decoder: Decoder): TypedServerResponse<T> {
        val fields = decoder.beginStructure(descriptor)
        var type = "server-response"
        var rpcId: String? = null
        var result: RpcResult<T>? = null
        while (true) {
            when (val index = fields.decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break
                0 -> type = fields.decodeStringElement(descriptor, 0)
                1 -> rpcId = fields.decodeStringElement(descriptor, 1)
                2 -> result = fields.decodeSerializableElement(descriptor, 2, resultSerializer)
                else -> throw SerializationException("Unexpected envelope field $index")
            }
        }
        fields.endStructure(descriptor)
        require(type == "server-response") { "expected a server-response envelope but got type \"$type\"" }
        return TypedServerResponse(type, rpcId ?: throw SerializationException("Missing rpcId"),
            result ?: throw SerializationException("Missing result"))
    }

    override fun serialize(encoder: Encoder, value: TypedServerResponse<T>) = error("Read-only RPC decoder")
}

/** Blocks retain at most the current record; no page-sized JsonElement or records List is built. */
internal class SessionPageConsumerSerializer(
    private val throughSeq: Int,
    private val onRecord: (SessionHistoryRecord) -> Unit,
) : KSerializer<SessionPageMetadata> {
    override val descriptor = SessionPage.serializer().descriptor
    private var count = 0L
    private val records = object : KSerializer<Unit> {
        private val record = SessionHistoryRecord.serializer()
        override val descriptor = ListSerializer(record).descriptor
        override fun deserialize(decoder: Decoder) {
            val array = decoder.beginStructure(descriptor)
            while (true) {
                val index = array.decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                onRecord(array.decodeSerializableElement(descriptor, index, record))
                count++
            }
            array.endStructure(descriptor)
        }
        override fun serialize(encoder: Encoder, value: Unit) = error("Read-only page consumer")
    }
    override fun deserialize(decoder: Decoder): SessionPageMetadata {
        count = 0
        val fields = decoder.beginStructure(descriptor)
        var hasMore = false
        var reader: SessionReaderCoverage? = null
        while (true) {
            when (val index = fields.decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break
                0 -> fields.decodeSerializableElement(descriptor, 0, records)
                1 -> hasMore = fields.decodeBooleanElement(descriptor, 1)
                2 -> reader = fields.decodeNullableSerializableElement(descriptor, 2, SessionReaderCoverage.serializer())
                else -> throw SerializationException("Unexpected page field $index")
            }
        }
        fields.endStructure(descriptor)
        return SessionPageMetadata(hasMore, count, throughSeq, reader)
    }
    override fun serialize(encoder: Encoder, value: SessionPageMetadata) = error("Read-only page consumer")
}

/** Parsing is synchronous; make every refill observe cancellation without retaining another body. */
internal fun InputStream.cancellable(job: Job?): InputStream = object : FilterInputStream(this) {
    override fun read(): Int { job?.ensureActive(); return super.read() }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        job?.ensureActive()
        return `in`.read(buffer, offset, length)
    }
}

internal fun <T> RpcHttpResponse.decodeStreaming(serializer: KSerializer<T>, job: Job?): T =
    openInputStream().cancellable(job).use { WireJson.decodeFromStream(serializer, it) }
