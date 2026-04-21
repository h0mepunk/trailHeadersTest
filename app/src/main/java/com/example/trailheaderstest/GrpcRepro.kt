package com.example.trailheaderstest

import android.util.Log
import com.example.trailheaderstest.grpc.ReproGrpc
import com.example.trailheaderstest.grpc.StreamChunk
import com.example.trailheaderstest.grpc.StreamSummary
import com.example.trailheaderstest.grpc.UnaryRequest
import io.grpc.Metadata
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object GrpcRepro {

    const val LOG_TAG = "TrailHeadersGrpc"

    private const val RPC_DEADLINE_SEC = 15L

    private const val CHANNEL_SHUTDOWN_AWAIT_MS = 400L

    private fun isConnectionHangDeadline(e: StatusRuntimeException): Boolean {
        if (e.status.code != Status.Code.DEADLINE_EXCEEDED) return false
        val haystack = buildString {
            append(e.message)
            append(e.status.description)
        }
        if (haystack.contains("waiting_for_connection")) return true
        var c: Throwable? = e.cause
        while (c != null) {
            if (c.message.orEmpty().contains("waiting_for_connection")) return true
            c = c.cause
        }
        return false
    }

    suspend fun unary(
        host: String,
        port: Int,
        useTls: Boolean,
        body: String,
    ): String = withContext(Dispatchers.IO) {
        Log.i(LOG_TAG, "unary start host=$host port=$port tls=$useTls bodyLen=${body.length}")
        useChannel(host, port, useTls) { channel ->
            val stub = ReproGrpc.newBlockingStub(channel)
                .withDeadlineAfter(RPC_DEADLINE_SEC, TimeUnit.SECONDS)
            val request = UnaryRequest.newBuilder().setBody(body).build()
            Log.i(
                LOG_TAG,
                "channel state before unary=${channel.getState(true)} (true=try connect)",
            )
            Log.i(LOG_TAG, "unary invoking trailheaders.v1.Repro/Unary serializedBytes=${request.serializedSize}")
            val response = stub.unary(request)
            Log.i(LOG_TAG, "unary response ok echoLen=${response.echo.length}")
            "Unary echo=${response.echo}"
        }.also { Log.i(LOG_TAG, "unary finished uiSummaryLen=${it.length}") }
    }

    suspend fun unaryDenied(
        host: String,
        port: Int,
        useTls: Boolean,
        body: String,
    ): String = withContext(Dispatchers.IO) {
        Log.i(LOG_TAG, "unaryDenied start host=$host port=$port tls=$useTls bodyLen=${body.length}")
        repeat(2) { attempt ->
            try {
                val result = useChannel(host, port, useTls) { channel ->
                    val stub = ReproGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(RPC_DEADLINE_SEC, TimeUnit.SECONDS)
                    val request = UnaryRequest.newBuilder().setBody(body).build()
                    Log.i(
                        LOG_TAG,
                        "unaryDenied invoking trailheaders.v1.Repro/UnaryDenied attempt=${attempt + 1}",
                    )
                    try {
                        stub.unaryDenied(request)
                        error("expected PERMISSION_DENIED")
                    } catch (e: StatusRuntimeException) {
                        if (e.status.code != Status.Code.PERMISSION_DENIED) {
                            throw e
                        }
                        val trailers = e.trailers ?: Metadata()
                        val key = Metadata.Key.of(
                            "x-plata-generic-challenge-v3",
                            Metadata.ASCII_STRING_MARSHALLER,
                        )
                        val challenge = runCatching { trailers.get(key) }.getOrNull()
                        Log.i(
                            LOG_TAG,
                            "unaryDenied got PERMISSION_DENIED trailersKeys=${trailers.keys()} challengeLen=${challenge?.length}",
                        )
                        buildString {
                            append("UnaryDenied (как банк: gRPC ошибка + trailers).\n")
                            append("code=${e.status.code} desc=${e.status.description}\n")
                            append("trailer x-plata-generic-challenge-v3 len=${challenge?.length ?: 0}\n")
                            if (!challenge.isNullOrEmpty()) {
                                append("preview=")
                                append(challenge.take(200))
                                if (challenge.length > 200) append("…")
                            }
                        }
                    }
                }
                return@withContext result.also {
                    Log.i(LOG_TAG, "unaryDenied finished summaryLen=${it.length}")
                }
            } catch (e: StatusRuntimeException) {
                if (attempt == 0 && isConnectionHangDeadline(e)) {
                    Log.w(LOG_TAG, "unaryDenied: connection hang, retry after 500ms", e)
                    delay(500)
                } else {
                    throw e
                }
            }
        }
        error("unaryDenied retry loop")
    }

    suspend fun clientStream(
        host: String,
        port: Int,
        useTls: Boolean,
        chunks: List<String>,
    ): String = withContext(Dispatchers.IO) {
        Log.i(
            LOG_TAG,
            "clientStream start host=$host port=$port tls=$useTls chunks=${chunks.size}",
        )
        useChannel(host, port, useTls) { channel ->
            val stub = ReproGrpc.newStub(channel)
                .withDeadlineAfter(RPC_DEADLINE_SEC, TimeUnit.SECONDS)
            val done = CountDownLatch(1)
            val summaryRef = AtomicReference<StreamSummary?>(null)
            val errorRef = AtomicReference<Throwable?>(null)
            val requestObserver = stub.clientStream(
                object : StreamObserver<StreamSummary> {
                    override fun onNext(value: StreamSummary) {
                        summaryRef.set(value)
                    }

                    override fun onError(t: Throwable) {
                        Log.e(LOG_TAG, "clientStream onError", t)
                        errorRef.set(t)
                        done.countDown()
                    }

                    override fun onCompleted() {
                        done.countDown()
                    }
                },
            )
            chunks.forEachIndexed { index, chunk ->
                val msg = StreamChunk.newBuilder().setBody(chunk).build()
                Log.i(LOG_TAG, "clientStream sending chunk#$index len=${chunk.length} serializedBytes=${msg.serializedSize}")
                requestObserver.onNext(msg)
            }
            Log.i(LOG_TAG, "clientStream halfClose (onCompleted)")
            requestObserver.onCompleted()
            val finished = done.await(30, TimeUnit.SECONDS)
            if (!finished) {
                error("timeout")
            }
            errorRef.get()?.let { throw it }
            val summary = summaryRef.get() ?: error("no summary")
            Log.i(LOG_TAG, "clientStream response ok receivedChunks=${summary.receivedChunks}")
            "ClientStream chunks=${summary.receivedChunks}"
        }.also { Log.i(LOG_TAG, "clientStream finished $it") }
    }

    private inline fun <T> useChannel(
        host: String,
        port: Int,
        useTls: Boolean,
        block: (ManagedChannel) -> T,
    ): T {
        val builder = OkHttpChannelBuilder.forAddress(host, port)
        if (useTls) {
            builder.useTransportSecurity()
        } else {
            builder.usePlaintext()
        }
        val channel = builder.build()
        Log.i(LOG_TAG, "channel created target=$host:$port tls=$useTls")
        if (useTls) {
            Log.w(
                LOG_TAG,
                "TLS enabled: server must negotiate ALPN h2 (gRPC). Plaintext repro_server.py needs tls=false.",
            )
        }
        return try {
            block(channel)
        } finally {
            channel.shutdownNow()
            val terminated = channel.awaitTermination(
                CHANNEL_SHUTDOWN_AWAIT_MS,
                TimeUnit.MILLISECONDS,
            )
            if (!terminated) {
                Log.w(LOG_TAG, "channel not terminated in ${CHANNEL_SHUTDOWN_AWAIT_MS}ms (ok for repro)")
            }
        }
    }
}
