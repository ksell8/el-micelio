package com.disturb.elmicelio

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.*
import kotlinx.coroutines.launch

// ─── Activity ────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        if (granted) {
            @Suppress("MissingPermission")
            viewModel.startScanning()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionLauncher.launch(arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CHANGE_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        ))

        setContent {
            MicelioTheme {
                MicelioApp(viewModel)
            }
        }
    }
}

// ─── Navigation ──────────────────────────────────────────────────────────────

@Composable
fun MicelioApp(viewModel: MainViewModel) {
    val navController = rememberNavController()

    NavHost(navController, startDestination = "nodes") {
        composable("nodes") {
            NodeListScreen(viewModel, navController)
        }
        composable("node/{nodeId}") { backStack ->
            val nodeId = backStack.arguments?.getString("nodeId") ?: return@composable
            val nodes by viewModel.nodes.collectAsState()
            val node = nodes.find { it.id == nodeId } ?: return@composable
            NodeDetailScreen(node, viewModel, navController)
        }
        composable("node/{nodeId}/web") {
            NodeWebScreen(navController)
        }
    }
}

// ─── Node List Screen ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeListScreen(viewModel: MainViewModel, navController: NavController) {
    val nodes by viewModel.nodes.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val error by viewModel.error.collectAsState()

    if (error != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("Error") },
            text = { Text(error ?: "") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) { Text("OK") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Micelio") },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        if (isScanning) {
                            val color = if (nodes.isEmpty()) Color(0xFF88CC88) else Color(0xFF44BB44)
                            Icon(
                                Icons.Default.FiberManualRecord,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            if (isScanning) "Scanning" else "Idle",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (nodes.isEmpty()) {
                ScanningEmptyView(isScanning, Modifier.align(Alignment.Center))
            } else {
                LazyColumn {
                    items(nodes, key = { it.id }) { node ->
                        NodeRow(node) { navController.navigate("node/${node.id}") }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
fun NodeRow(node: SemillaNode, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(node.displayName, fontWeight = FontWeight.SemiBold)
        },
        supportingContent = {
            Text(node.proximity.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingContent = {
            Icon(
                Icons.Default.Podcasts,
                contentDescription = null,
                tint = proximityColor(node.proximity),
                modifier = Modifier.size(32.dp)
            )
        },
        trailingContent = {
            Text(
                "${node.rssi} dBm",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    )
}

@Composable
fun ScanningEmptyView(isScanning: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Default.WifiTethering,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            if (isScanning) "Scanning for nodes…" else "Not scanning",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Move closer to a La Semilla node.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

// ─── Node Detail Screen ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDetailScreen(
    node: SemillaNode,
    viewModel: MainViewModel,
    navController: NavController
) {
    val scope = rememberCoroutineScope()
    val isNodeConnected by viewModel.isNodeConnected.collectAsState()
    var connectState by remember { mutableStateOf<ConnectState>(ConnectState.Idle) }
    var alertMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isNodeConnected) {
        if (isNodeConnected && connectState != ConnectState.Connecting) {
            connectState = ConnectState.Connected
        } else if (!isNodeConnected && connectState == ConnectState.Connected) {
            connectState = ConnectState.Idle
        }
    }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            title = { Text("Enter Password") },
            text = {
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text(WiFiConnector.STATIC_SSID) },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password"
                            )
                        }
                    },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPasswordDialog = false
                    scope.launch {
                        connectState = ConnectState.Connecting
                        @Suppress("MissingPermission")
                        val result = viewModel.connectToNode(node, passwordInput)
                        connectState = when (result) {
                            is WiFiConnectResult.Connected -> {
                                alertMessage = "Joined \"${WiFiConnector.STATIC_SSID}\" successfully."
                                ConnectState.Connected
                            }
                            is WiFiConnectResult.Unavailable -> {
                                alertMessage = "Could not find network \"${WiFiConnector.STATIC_SSID}\"."
                                ConnectState.Failed
                            }
                            is WiFiConnectResult.Failed -> {
                                alertMessage = "Failed: ${result.reason}"
                                ConnectState.Failed
                            }
                        }
                    }
                }) { Text("Connect") }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordDialog = false }) { Text("Cancel") }
            }
        )
    }

    alertMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { alertMessage = null },
            title = { Text("Connection") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { alertMessage = null }) { Text("OK") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(node.displayName) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DetailCard("Identity") {
                DetailRow("Node ID", node.displayName)
                DetailRow("Major", node.major.toString())
                DetailRow("Minor", node.minor.toString())
            }

            DetailCard("Signal") {
                DetailRow("Proximity", node.proximity.label)
                DetailRow("RSSI", "${node.rssi} dBm")
            }

            DetailCard("Network") {
                DetailRow("SSID", WiFiConnector.STATIC_SSID)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        passwordInput = ""
                        showPasswordDialog = true
                    },
                    enabled = connectState != ConnectState.Connecting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when (connectState) {
                        ConnectState.Idle -> Text("Connect to Node")
                        ConnectState.Connecting -> {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Connecting…")
                        }
                        ConnectState.Connected -> Text("Connected ✓")
                        ConnectState.Failed -> Text("Failed — Retry")
                    }
                }
                Text(
                    "Android will show a system prompt to join \"${WiFiConnector.STATIC_SSID}\".",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (connectState == ConnectState.Connected) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { navController.navigate("node/${node.id}/web") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Browse Node")
                    }
                    OutlinedButton(
                        onClick = { viewModel.disconnect() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Disconnect")
                    }
                }
            }
        }
    }
}

sealed class ConnectState {
    object Idle : ConnectState()
    object Connecting : ConnectState()
    object Connected : ConnectState()
    object Failed : ConnectState()
}

// ─── Shared UI helpers ────────────────────────────────────────────────────────

@Composable
fun DetailCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

fun proximityColor(proximity: SemillaNode.Proximity): Color = when (proximity) {
    SemillaNode.Proximity.IMMEDIATE -> Color(0xFF44BB44)
    SemillaNode.Proximity.NEAR      -> Color(0xFFCCAA00)
    SemillaNode.Proximity.FAR       -> Color(0xFFDD6600)
    SemillaNode.Proximity.UNKNOWN   -> Color(0xFF888888)
}

// ─── Theme ───────────────────────────────────────────────────────────────────

@Composable
fun MicelioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        content = content
    )
}
