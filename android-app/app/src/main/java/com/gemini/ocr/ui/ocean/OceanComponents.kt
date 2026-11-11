package com.gemini.ocr.ui.ocean

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun OceanCard(modifier: Modifier = Modifier, elevated: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = if (elevated) OceanColors.Elevated else OceanColors.Card),
        border = BorderStroke(1.dp, OceanColors.Outline.copy(alpha = .45f))) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
fun OceanPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                       enabled: Boolean = true, loading: Boolean = false) {
    Button(onClick, modifier.heightIn(min = 52.dp), enabled = enabled && !loading,
        shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(
            containerColor = OceanColors.Mint, contentColor = OceanColors.Base)) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), color = OceanColors.Muted, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, textAlign = TextAlign.Center)
    }
}

@Composable
fun OceanSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, OceanColors.Outline)) { Text(text, textAlign = TextAlign.Center) }
}

enum class OceanStatusTone { Neutral, Active, Warning, Error }

@Composable
fun OceanStatusIndicator(text: String, tone: OceanStatusTone = OceanStatusTone.Neutral, modifier: Modifier = Modifier) {
    val color = when (tone) {
        OceanStatusTone.Neutral -> OceanColors.Muted
        OceanStatusTone.Active -> OceanColors.Mint
        OceanStatusTone.Warning -> OceanColors.Warning
        OceanStatusTone.Error -> OceanColors.Error
    }
    Surface(modifier.semantics(mergeDescendants = true) {}, shape = RoundedCornerShape(14.dp), color = color.copy(alpha = .10f),
        border = BorderStroke(1.dp, color.copy(alpha = .35f))) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Text(text, color = color, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun OceanNotice(text: String, error: Boolean = false, loading: Boolean = false, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    Surface(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(16.dp),
        color = if (error) MaterialTheme.colorScheme.errorContainer.copy(alpha = .55f) else OceanColors.Elevated,
        border = BorderStroke(1.dp, (if (error) OceanColors.Error else OceanColors.Outline).copy(alpha = .35f))) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            if (loading && !error) CircularProgressIndicator(Modifier.size(20.dp), color = OceanColors.Cyan, strokeWidth = 2.dp)
            else Icon(if (error) Icons.Outlined.WarningAmber else Icons.Outlined.Info, null,
                tint = if (error) OceanColors.Error else OceanColors.Cyan, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

data class OceanDestination(val label: String, val icon: ImageVector, val tag: String)

@Composable
fun OceanNavigationBar(destinations: List<OceanDestination>, selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = OceanColors.Base, tonalElevation = 0.dp) {
        destinations.forEachIndexed { index, destination ->
            NavigationBarItem(selected == index, { onSelect(index) },
                icon = { Icon(destination.icon, null) },
                label = { Text(destination.label, maxLines = 2, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.testTag(destination.tag),
                colors = NavigationBarItemDefaults.colors(selectedIconColor = OceanColors.Mint,
                    selectedTextColor = OceanColors.Mint, indicatorColor = OceanColors.Elevated,
                    unselectedIconColor = OceanColors.Muted, unselectedTextColor = OceanColors.Muted))
        }
    }
}

@Composable
fun OceanNavigationRail(destinations: List<OceanDestination>, selected: Int, onSelect: (Int) -> Unit) {
    NavigationRail(containerColor = OceanColors.Base) {
        destinations.forEachIndexed { index, destination ->
            NavigationRailItem(selected == index, { onSelect(index) },
                icon = { Icon(destination.icon, null) },
                label = { Text(destination.label, maxLines = 2, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.testTag(destination.tag),
                colors = NavigationRailItemDefaults.colors(selectedIconColor = OceanColors.Mint,
                    selectedTextColor = OceanColors.Mint, indicatorColor = OceanColors.Elevated,
                    unselectedIconColor = OceanColors.Muted, unselectedTextColor = OceanColors.Muted))
        }
    }
}

/** This control is purely presentational; its caller owns the real service action. */
@Composable
fun OceanSessionButton(text: String, running: Boolean, paused: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick, modifier.widthIn(min = 168.dp, max = 240.dp).heightIn(min = 168.dp)
        .semantics { role = Role.Button; stateDescription = if (!running) "Chưa bắt đầu phiên dịch" else if (paused) "Đang tạm dừng" else "Phiên dịch đang chạy" },
        shape = CircleShape, color = Color.Transparent, contentColor = OceanColors.Text,
        border = BorderStroke(2.dp, Brush.linearGradient(listOf(OceanColors.Blue, OceanColors.Cyan, OceanColors.Mint)))) {
        Column(Modifier.background(Brush.radialGradient(listOf(OceanColors.Blue.copy(alpha = .32f), OceanColors.Card)))
            .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
            Icon(if (!running) Icons.Outlined.Translate else if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                null, tint = OceanColors.Cyan, modifier = Modifier.size(34.dp))
            Text(text, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            Text(if (!running) "Chạm để bắt đầu" else if (paused) "Chạm để tiếp tục" else "Chạm để tạm dừng",
                color = OceanColors.Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
        }
    }
}


