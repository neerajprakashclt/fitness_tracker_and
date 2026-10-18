package app.fitness.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fitness.android.ui.theme.Fitness
import app.fitness.android.ui.theme.LayoutStyle

/*
 * The themed building blocks. Screens use these instead of raw Material components so a theme can change structure
 * (cards vs grouped blocks vs bordered terminal panels), not just colour.
 */

/** A block of content: a rounded card (Strava), an inset grouped block (iOS) or a bordered panel (terminal). */
@Composable
fun ThemedCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = Fitness.theme
    val shape = RoundedCornerShape(t.cornerRadius)
    val color = if (selected) t.accent.copy(alpha = 0.14f) else t.surface
    val border = when {
        selected -> BorderStroke(if (t.borderWidth > 0.dp) t.borderWidth else 1.5.dp, t.accent)
        t.borderWidth > 0.dp -> BorderStroke(t.borderWidth, t.borderColor)
        else -> null
    }
    val click = if (onClick != null || onLongClick != null)
        Modifier.clip(shape).combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick) else Modifier
    Surface(
        modifier = modifier.fillMaxWidth(), shape = shape, color = color, border = border,
        shadowElevation = t.cardElevation, contentColor = t.text,
    ) {
        Column(Modifier.then(click).padding(14.dp), content = content)
    }
}

/** A section title in the theme's own style. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    val t = Fitness.theme
    when (t.layout) {
        LayoutStyle.CARDS -> Text(text, modifier.padding(top = 8.dp, bottom = 6.dp), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        LayoutStyle.GROUPED -> Text(text.uppercase(), modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp), color = t.muted, fontSize = 13.sp)
        LayoutStyle.TERMINAL -> Text("## $text", modifier.padding(top = 10.dp, bottom = 4.dp), color = t.accent, fontSize = 14.sp)
    }
}

/** A big number with a small label, the activity-detail stat grid cell. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    val t = Fitness.theme
    if (t.layout == LayoutStyle.TERMINAL) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, color = t.text, fontSize = 18.sp)
            Text(label.lowercase(), color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
        }
    } else {
        Column(modifier) {
            Text(value, color = t.text, fontSize = 22.sp, fontWeight = t.numeralWeight, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, color = t.muted, fontSize = 12.sp)
        }
    }
}

/** A pick-one control: an iOS pill in the grouped theme, bordered toggles elsewhere. */
@Composable
fun <T> SegmentedControl(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val t = Fitness.theme
    val shape = RoundedCornerShape(if (t.layout == LayoutStyle.GROUPED) 9.dp else t.cornerRadius / 2)
    Row(
        modifier.fillMaxWidth().clip(shape)
            .background(if (t.layout == LayoutStyle.GROUPED) t.surfaceAlt else Color.Transparent)
            .border(if (t.layout == LayoutStyle.GROUPED) 0.dp else 1.dp, t.divider, shape).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (o in options) {
            val on = o == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(if (t.layout == LayoutStyle.GROUPED) 7.dp else t.cornerRadius / 2))
                    .background(if (on) (if (t.layout == LayoutStyle.GROUPED) t.surface else t.accent) else Color.Transparent)
                    .clickable { onSelect(o) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val text = if (t.layout == LayoutStyle.TERMINAL) (if (on) "[${label(o)}]" else " ${label(o)} ") else label(o)
                Text(
                    text, fontSize = 14.sp, maxLines = 1,
                    color = if (on) (if (t.layout == LayoutStyle.GROUPED) t.text else t.onAccent) else t.muted,
                    fontWeight = if (on && t.layout != LayoutStyle.TERMINAL) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** A labelled on/off row. */
@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val t = Fitness.theme
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = t.text, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, color = t.muted, fontSize = 12.sp)
        }
        Box(Modifier.width(12.dp))
        if (t.layout == LayoutStyle.TERMINAL) {
            Text(if (checked) "[x]" else "[ ]", color = if (checked) t.accent else t.muted, fontSize = 16.sp)
        } else {
            Switch(
                checked = checked, onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = t.accent, checkedThumbColor = t.onAccent),
            )
        }
    }
}

@Composable
fun ThemedDivider(modifier: Modifier = Modifier) {
    val t = Fitness.theme
    Box(modifier.fillMaxWidth().height(if (t.layout == LayoutStyle.GROUPED) 0.5.dp else 1.dp).background(t.divider))
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val t = Fitness.theme
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        shape = RoundedCornerShape(if (t.layout == LayoutStyle.GROUPED) 12.dp else t.cornerRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) t.danger else t.accent, contentColor = t.onAccent,
            disabledContainerColor = t.divider, disabledContentColor = t.muted,
        ),
    ) { Text(if (t.layout == LayoutStyle.TERMINAL) "[ $text ]" else text) }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false) {
    val t = Fitness.theme
    TextButton(onClick = onClick, modifier = modifier) {
        Text(if (t.layout == LayoutStyle.TERMINAL) "[$text]" else text, color = if (danger) t.danger else t.accent)
    }
}

/** A selectable pill used for the filters above the feed. */
@Composable
fun FilterPill(text: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = Fitness.theme
    val shape = RoundedCornerShape(if (t.layout == LayoutStyle.TERMINAL) 0.dp else 20.dp)
    Box(
        modifier.clip(shape).background(if (active) t.accent else t.surface)
            .border(1.dp, if (active) t.accent else t.divider, shape).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text, color = if (active) t.onAccent else t.text, fontSize = 14.sp, maxLines = 1)
    }
}
