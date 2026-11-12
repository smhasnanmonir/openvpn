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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import com.smhasnan.openvpn.ui.theme.Dimens

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
                    listOf(colors.background, colors.surfaceContainer, colors.background),
                ),
            )
            .padding(horizontal = Dimens.ScreenPadding, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .background(colors.surfaceContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                BrandMark(size = 52.dp)
            }
            Spacer(Modifier.height(22.dp))
            Text(
                "App locked",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Unlock to manage profiles and your VPN connection.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
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
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onOpenSecuritySettings,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        "Open security settings",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            } else {
                Spacer(Modifier.height(26.dp))
                Button(
                    onClick = onUnlock,
                    enabled = !authenticating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimens.PrimaryActionHeight),
                    shape = RoundedCornerShape(50),
                ) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = if (authenticating) "Waiting for authentication…" else "Unlock with fingerprint",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
