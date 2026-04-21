package com.example.trailheaderstest

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.trailheaderstest.ui.theme.TrailHeadersTestTheme
import kotlinx.coroutines.launch

private fun grpcHostClientError(host: String, blankMessage: String, zeroMessage: String): String? {
    val h = host.trim()
    if (h.isEmpty()) return blankMessage
    if (h.equals("0.0.0.0", ignoreCase = true)) return zeroMessage
    return null
}

private fun formatGrpcFailure(throwable: Throwable, alpnHint: String): String {
    val text = throwable.stackTraceToString()
    var c: Throwable? = throwable
    while (c != null) {
        if (c.message.orEmpty().contains("ALPN", ignoreCase = true)) {
            return "$text\n\n$alpnHint"
        }
        c = c.cause
    }
    return text
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrailHeadersTestTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ReproScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun ReproScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val sendingUnary = stringResource(R.string.log_sending_unary)
    val sendingUnaryDenied = stringResource(R.string.log_sending_unary_denied)
    val sendingStream = stringResource(R.string.log_sending_stream)
    val alpnHint = stringResource(R.string.error_grpc_alpn)
    val hostBlankError = stringResource(R.string.error_host_blank)
    val hostZeroError = stringResource(R.string.error_host_zero)
    var host by remember { mutableStateOf("10.0.2.2") }
    var port by remember { mutableStateOf("50051") }
    var useTls by remember { mutableStateOf(false) }
    var unaryBody by remember { mutableStateOf("proxyman-trailing-metadata-repro-body") }
    var log by remember { mutableStateOf("") }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = stringResource(R.string.repro_title), style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(R.string.repro_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.field_host)) },
            singleLine = true,
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.field_port)) },
            singleLine = true,
        )
        Column {
            Text(stringResource(R.string.field_tls))
            Switch(checked = useTls, onCheckedChange = { useTls = it })
        }
        OutlinedTextField(
            value = unaryBody,
            onValueChange = { unaryBody = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.field_unary_body)) },
            minLines = 2,
        )
        Button(
            onClick = {
                scope.launch {
                    log = sendingUnary
                    grpcHostClientError(host, hostBlankError, hostZeroError)?.let { msg ->
                        Log.w(GrpcRepro.LOG_TAG, "invalid host=${host.trim()}")
                        log = msg
                        return@launch
                    }
                    val hostTrimmed = host.trim()
                    runCatching {
                        val p = port.toIntOrNull() ?: error("bad port")
                        GrpcRepro.unary(hostTrimmed, p, useTls, unaryBody.trim())
                    }.fold(
                        onSuccess = {
                            Log.i(GrpcRepro.LOG_TAG, "UI: unary success")
                            log = it
                        },
                        onFailure = {
                            Log.e(GrpcRepro.LOG_TAG, "UI: unary failed", it)
                            log = formatGrpcFailure(it, alpnHint)
                        },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_unary))
        }
        Button(
            onClick = {
                scope.launch {
                    log = sendingUnaryDenied
                    grpcHostClientError(host, hostBlankError, hostZeroError)?.let { msg ->
                        Log.w(GrpcRepro.LOG_TAG, "invalid host=${host.trim()}")
                        log = msg
                        return@launch
                    }
                    val hostTrimmed = host.trim()
                    runCatching {
                        val p = port.toIntOrNull() ?: error("bad port")
                        GrpcRepro.unaryDenied(hostTrimmed, p, useTls, unaryBody.trim())
                    }.fold(
                        onSuccess = {
                            Log.i(GrpcRepro.LOG_TAG, "UI: unaryDenied success")
                            log = it
                        },
                        onFailure = {
                            Log.e(GrpcRepro.LOG_TAG, "UI: unaryDenied failed", it)
                            log = formatGrpcFailure(it, alpnHint)
                        },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_unary_denied))
        }
        Button(
            onClick = {
                scope.launch {
                    log = sendingStream
                    grpcHostClientError(host, hostBlankError, hostZeroError)?.let { msg ->
                        Log.w(GrpcRepro.LOG_TAG, "invalid host=${host.trim()}")
                        log = msg
                        return@launch
                    }
                    val hostTrimmed = host.trim()
                    runCatching {
                        val p = port.toIntOrNull() ?: error("bad port")
                        val chunks = listOf("a", "b", "c").map { "$it-${System.currentTimeMillis()}" }
                        GrpcRepro.clientStream(hostTrimmed, p, useTls, chunks)
                    }.fold(
                        onSuccess = {
                            Log.i(GrpcRepro.LOG_TAG, "UI: clientStream success")
                            log = it
                        },
                        onFailure = {
                            Log.e(GrpcRepro.LOG_TAG, "UI: clientStream failed", it)
                            log = formatGrpcFailure(it, alpnHint)
                        },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_client_stream))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(stringResource(R.string.section_log), style = MaterialTheme.typography.titleSmall)
        Text(
            text = log.ifBlank { stringResource(R.string.log_empty) },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
