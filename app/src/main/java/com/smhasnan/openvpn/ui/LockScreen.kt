package com.smhasnan.openvpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smhasnan.openvpn.ui.components.BrandMark
import com.smhasnan.openvpn.ui.components.MessageBanner

@Composable
fun LockScreen(
    authenticating: Boolean,
    error: String?,
    unavailable: Boolean,
    onUnlock: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(colors.background, colors.primaryContainer.copy(alpha = 0.45f), colors.background),
                ),
            )
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(94.dp)
                    .background(colors.surface, RoundedCornerShape(30.dp)),
                contentAlignment = Alignment.Center,
            ) {
                BrandMark(size = 54.dp)
            }
            Spacer(Modifier.height(26.dp))
            Text(
                "App locked",
                style = MaterialTheme.typography.displaySmall,
                color = colors.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Unlock to manage profiles and your VPN connection.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "An active VPN continues to run while this screen is locked.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (error != null) {
                Spacer(Modifier.height(18.dp))
                MessageBanner(message = error, isError = true)
            }
            if (unavailable) {
                Spacer(Modifier.height(18.dp))
                MessageBanner(
                    message = "Device authentication is not available. Set a screen lock and enroll a fingerprint, or open security settings.",
                    isError = true,
                )
                Spacer(Modifier.height(18.dp))
                OutlinedButton(onClick = onOpenSecuritySettings) {
                    Text("Open security settings")
                }
            } else {
                Spacer(Modifier.height(26.dp))
                Button(
                    onClick = onUnlock,
                    enabled = !authenticating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(if (authenticating) "Waiting for authentication…" else "Unlock with fingerprint")
                }
            }
        }
    }
}
