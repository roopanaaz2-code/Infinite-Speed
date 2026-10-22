package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import com.example.ui.MainViewModel
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.DarkNavyCard
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.RedDanger
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextWhite
import com.example.util.QrCodeGenerator

@Composable
fun ReceiveScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val incomingRequest by viewModel.incomingRequest.collectAsState()

    DisposableEffect(Unit) {
        viewModel.startReceiving()
        onDispose {
            viewModel.stopReceiving()
        }
    }

    val ip = viewModel.localIp
    val qrPayload = remember(ip, viewModel.deviceName, viewModel.deviceId, viewModel.pairingPin) {
        Uri.Builder()
            .scheme("infinitespeed")
            .authority("pair")
            .appendQueryParameter("v", "1")
            .appendQueryParameter("device", viewModel.deviceId)
            .appendQueryParameter("port", "8990")
            .appendQueryParameter("pin", viewModel.pairingPin)
            .appendQueryParameter("name", viewModel.deviceName)
            .build()
            .toString()
    }
    val qrImageBitmap = remember(qrPayload) {
        QrCodeGenerator.generateQrImageBitmap(qrPayload, 480)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top App Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { viewModel.navigateBack() },
                modifier = Modifier.testTag("receive_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = "Receive Files",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // QR Code Card
        Card(
            modifier = Modifier
                .size(260.dp)
                .testTag("receiver_qr_code_card"),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = qrImageBitmap,
                    contentDescription = "Receiver Pairing QR Code",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Device Name & Pairing PIN
        Text(
            text = viewModel.deviceName,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            color = TextWhite
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = DarkNavyCard)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Pairing PIN: ",
                    fontSize = 14.sp,
                    color = TextMuted
                )
                Text(
                    text = viewModel.pairingPin,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                    color = CyanElectric
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Network connection indicator
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = DarkNavyCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        tint = EmeraldSuccess,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Listening on: $ip:8990",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = TextWhite
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Offline Mode: No cellular data or internet will be consumed. Sender can scan this QR or select your device in Radar.",
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Hotspot Fallback Tip
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = DarkNavyCard.copy(alpha = 0.6f))
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.WifiTethering,
                    contentDescription = null,
                    tint = CyanElectric,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "No Wi-Fi router nearby?",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = TextWhite
                    )
                    Text(
                        text = "Enable your phone's Personal Hotspot and connect the other phone to it. Infinite Speed will achieve full Wi-Fi speeds completely offline!",
                        fontSize = 11.sp,
                        color = TextMuted,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }

    // Incoming Transfer Request Approval Dialog
    incomingRequest?.let { req ->
        AlertDialog(
            onDismissRequest = { /* Require choice */ },
            title = {
                Text(
                    text = "Incoming Transfer Request",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column {
                    Text(
                        text = "${req.senderName} wants to send you:",
                        fontSize = 13.sp,
                        color = TextWhite
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkNavyCard)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "${req.files.size} files",
                                fontWeight = FontWeight.Bold,
                                color = CyanElectric
                            )
                            Text(
                                text = "Total size: ${formatBytes(req.totalSize)}",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            req.files.take(3).forEach { f ->
                                Text(
                                    text = "• ${f.fileName} (${formatBytes(f.fileSize)})",
                                    fontSize = 11.sp,
                                    color = TextWhite,
                                    maxLines = 1
                                )
                            }
                            if (req.files.size > 3) {
                                Text(
                                    text = "+ ${req.files.size - 3} more files",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.acceptIncomingTransfer() },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldSuccess),
                    modifier = Modifier.testTag("accept_transfer_button")
                ) {
                    Text("Accept & Receive", color = Color(0xFF080D1A), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.declineIncomingTransfer() },
                    modifier = Modifier.testTag("decline_transfer_button")
                ) {
                    Text("Decline", color = RedDanger)
                }
            }
        )
    }
}
