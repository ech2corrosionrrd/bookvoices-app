package ua.nichnyk.listen.ui.components

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.BluetoothAudio
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ua.nichnyk.listen.ui.theme.cardSurface
import ua.nichnyk.listen.ui.theme.Spacing
import ua.nichnyk.listen.ui.theme.CardShape
import ua.nichnyk.listen.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallBottomSheet(
    isPro: Boolean,
    formattedPrice: String?,
    onBuy: (Activity) -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val activity = context as? Activity

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xxl)
                .padding(bottom = Spacing.xxxl)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header Icon
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "💎",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }

            Spacer(Modifier.height(Spacing.l))

            // Title
            Text(
                text = stringResource(R.string.pro_title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(Spacing.xs))

            // Tagline
            Text(
                text = stringResource(R.string.pro_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(Spacing.xl))

            // Benefits Card
            Surface(
                shape = CardShape,
                color = cardSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.l),
                    verticalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    // Рівно те, що справді обмежене перевіркою isPro (див. UserPrefs).
                    // Раніше тут стояли ще тема OLED, WebDAV, статистика й експорт —
                    // усі чотири були й лишаються безкоштовними, тобто список обіцяв
                    // те, чого покупка не давала. Зворотна помилка теж була: висота
                    // тону замикається (`pitch = if (pro) … else 1f`), але в списку
                    // не значилася — покупка давала більше, ніж обіцяла.
                    BenefitRow(Icons.AutoMirrored.Outlined.VolumeOff, stringResource(R.string.pro_benefit_skip_silence))
                    BenefitRow(Icons.AutoMirrored.Outlined.VolumeUp, stringResource(R.string.pro_benefit_volume_boost))
                    BenefitRow(Icons.Outlined.Equalizer, stringResource(R.string.pro_benefit_equalizer))
                    BenefitRow(Icons.Outlined.Tune, stringResource(R.string.pro_benefit_pitch))
                    BenefitRow(Icons.Outlined.BluetoothAudio, stringResource(R.string.pro_benefit_bluetooth))
                    BenefitRow(Icons.Outlined.Person, stringResource(R.string.pro_benefit_characters))
                }
            }

            Spacer(Modifier.height(Spacing.xxl))

            if (isPro) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.l),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(Spacing.s))
                        Text(
                            text = stringResource(R.string.pro_already_active),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            } else {
                // Buy Button
                val buttonText = if (formattedPrice != null) {
                    stringResource(R.string.pro_buy_button, formattedPrice)
                } else {
                    stringResource(R.string.pro_buy_button_default)
                }

                Button(
                    onClick = { activity?.let(onBuy) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(
                        text = buttonText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(Modifier.height(Spacing.s))

                // Restore Purchases Button
                TextButton(onClick = onRestore) {
                    Text(
                        text = stringResource(R.string.pro_restore_button),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun BenefitRow(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(Spacing.m))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}