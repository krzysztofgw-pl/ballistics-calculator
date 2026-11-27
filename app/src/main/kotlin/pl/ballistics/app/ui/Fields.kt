package pl.ballistics.app.ui

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pl.ballistics.app.R
import pl.ballistics.app.toNumber

@Composable
fun Section(
    title: String,
    icon: ImageVector? = null,
    /** Given, the header shows the icon and the title side by side with this (a main switch) at the end. */
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    trailing()
                }
            } else if (icon != null) {
                // An icon stands in for the title where one says it as well; the title stays for screen readers.
                Icon(icon, title, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            } else {
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
fun NumberField(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    optional: Boolean = false,
    placeholder: String? = null,
    /** Given, the unit becomes a button that switches it. */
    onUnitClick: (() -> Unit)? = null,
    /** Given, a ? at the end of the field opens an explanation. */
    onHelp: (() -> Unit)? = null,
    /** False greys the field out and leaves it in place, so the layout does not move. */
    enabled: Boolean = true,
    onChange: (String) -> Unit,
) {
    // While focused, what is typed lives here; the stored value stays as the placeholder and in effect
    // until a number is typed (or, in an optional field, until it is typed over and cleared).
    val replaceOnFocus = LocalReplaceOnFocus.current
    var typed by remember { mutableStateOf<String?>(null) }
    val shown = typed ?: value
    val hint = if (typed != null) value.ifBlank { null } ?: placeholder else placeholder
    OutlinedTextField(
        value = shown,
        onValueChange = { v ->
            if (replaceOnFocus) typed = v
            if (!replaceOnFocus || v.toNumber() != null || (optional && v.isBlank())) onChange(v)
        },
        enabled = enabled,
        label = { Text(label) },
        // The slot is always given: Material3 does not show a placeholder that only appears once the field is focused.
        placeholder = { hint?.let { Text(it) } },
        suffix = { if (onUnitClick == null) Text(unit) },
        trailingIcon = if (onHelp != null) {
            {
                IconButton(onClick = onHelp) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_help), stringResource(R.string.title_help))
                }
            }
        } else if (onUnitClick == null) null else {
            {
                Text(
                    unit,
                    Modifier
                        .clickable(onClickLabel = stringResource(R.string.action_switch_unit), onClick = onUnitClick)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        singleLine = true,
        isError = enabled && !(shown.isBlank() && (optional || typed != null)) && shown.toNumber() == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        keyboardActions = nextFieldActions(),
        modifier = modifier.fillMaxWidth().onFocusChanged { f ->
            if (replaceOnFocus) typed = if (f.isFocused) typed ?: "" else null
        },
    )
}

/**
 * Whether number fields empty on focus so typing replaces the value. On everywhere but the
 * profile editors, where a value is usually adjusted rather than retyped.
 */
val LocalReplaceOnFocus = staticCompositionLocalOf { true }

/**
 * Enter goes to the next field, so a form fills in without tapping each box; after the last
 * one it closes the keyboard.
 */
@Composable
fun nextFieldActions(): KeyboardActions {
    val focus = LocalFocusManager.current
    return KeyboardActions(onNext = { if (!focus.moveFocus(FocusDirection.Next)) focus.clearFocus() })
}

val NextFieldOptions = KeyboardOptions(imeAction = ImeAction.Next)

/** Two fields side by side. */
@Composable
fun FieldRow(content: @Composable (Modifier) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        content(Modifier.weight(1f))
    }
}

/** A choice between two named options, as two chips; [onChange] gets whether the first is picked. */
@Composable
fun TwoOptions(label: String, first: String, second: String, firstSelected: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f))
        FilterChip(selected = firstSelected, onClick = { onChange(true) }, label = { Text(first) })
        FilterChip(selected = !firstSelected, onClick = { onChange(false) }, label = { Text(second) })
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun <T> Choice(
    label: String,
    selected: T,
    options: List<T>,
    name: @Composable (T) -> String,
    icon: ImageVector? = null,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The label keeps its width; a long choice wraps inside its button instead of squeezing the label.
        if (icon != null) Icon(icon, label, Modifier.padding(end = 12.dp))
        else Text(label, Modifier.padding(end = 12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            // The menu shares a box with the button only, so it drops down from the button.
            Box {
                FilledTonalButton(onClick = { open = true }) { Text(name(selected), textAlign = TextAlign.Center) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    options.forEach {
                        DropdownMenuItem(text = { Text(name(it)) }, onClick = { onSelect(it); open = false })
                    }
                }
            }
        }
    }
}
