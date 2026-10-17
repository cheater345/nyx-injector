package dev.nyx.injector.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

private const val MAX_OBJECTS_SHOWN = 60

@Composable
fun NyxInjectorApp(vm: InjectorViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var payloadTarget by remember { mutableStateOf<Pair<Int, Long?>?>(null) }

    val openBundle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::openBundle)
    }
    val openPayload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = payloadTarget
        if (uri != null && target != null) {
            val (nodeIndex, pathId) = target
            if (pathId == null) vm.pickNodePayload(nodeIndex, uri) else vm.pickObjectPayload(nodeIndex, pathId, uri)
        }
    }
    val savePatched = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        uri?.let(vm::savePatched)
    }

    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Nyx Injector", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "UnityFS unpack / payload injection — originals stay untouched, patched build is saved as a new file",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Bundle") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Journal") })
                }
                when (tab) {
                    0 -> BundleTab(
                        state = state,
                        onOpenBundle = { openBundle.launch(arrayOf("*/*")) },
                        onPickNodePayload = { nodeIndex ->
                            payloadTarget = nodeIndex to null
                            openPayload.launch(arrayOf("*/*"))
                        },
                        onPickObjectPayload = { nodeIndex, pathId ->
                            payloadTarget = nodeIndex to pathId
                            openPayload.launch(arrayOf("*/*"))
                        },
                        onSavePatched = { savePatched.launch("patched.bundle") },
                    )
                    1 -> JournalTab(state)
                }
                if (state.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    state.status,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun BundleTab(
    state: InjectorState,
    onOpenBundle: () -> Unit,
    onPickNodePayload: (Int) -> Unit,
    onPickObjectPayload: (Int, Long) -> Unit,
    onSavePatched: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onOpenBundle) { Text("Open bundle") }
            if (state.pendingLabel != null) {
                Button(onClick = onSavePatched) { Text("Save patched bundle") }
            }
        }

        if (state.loaded) {
            Text(
                "${state.bundleName}\nunity ${state.unityVersion} · ${state.compression} · ${state.nodes.size} nodes" +
                    if (state.pendingLabel != null) "\nready: ${state.pendingLabel}" else "",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.nodes, key = { it.index }) { node ->
                NodeCard(node, onPickNodePayload, onPickObjectPayload)
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun NodeCard(
    node: NodeView,
    onPickNodePayload: (Int) -> Unit,
    onPickObjectPayload: (Int, Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "[${node.index}]",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    node.path + if (node.serialized) "  ·  ${node.objects.size} objects" else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Text("${node.size} B", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onPickNodePayload(node.index) }) { Text("Replace node") }
                if (node.serialized && node.objects.isNotEmpty()) {
                    OutlinedButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Hide objects" else "Objects")
                    }
                }
            }
            if (expanded) {
                val shown = node.objects.take(MAX_OBJECTS_SHOWN)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    shown.forEach { obj ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "0x${obj.pathId.toString(16)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(80.dp),
                            )
                            Text(
                                "${obj.className} · ${obj.byteSize} B",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = { onPickObjectPayload(node.index, obj.pathId) }) {
                                Text("Replace", fontSize = 11.sp)
                            }
                        }
                    }
                    if (node.objects.size > MAX_OBJECTS_SHOWN) {
                        Text(
                            "… ${node.objects.size - MAX_OBJECTS_SHOWN} more not shown (use the CLI for the full list)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JournalTab(state: InjectorState) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        if (state.journal.isEmpty()) {
            item { Text("nothing journaled yet — every open and inject lands here") }
        }
        items(state.journal) { line ->
            val parts = line.split("\t")
            Text(
                if (parts.size >= 4) "${parts[1]} · ${parts[2]} · ${parts[3]}" else line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}
