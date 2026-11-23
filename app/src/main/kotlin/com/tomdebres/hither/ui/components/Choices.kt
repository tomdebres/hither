package com.tomdebres.hither.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.ui.theme.HitherType
import com.tomdebres.hither.ui.theme.Overlay
import com.tomdebres.hither.ui.theme.Tokens

// Choices at ten feet, for the issue form (M6, DECISIONS 106): a list to pick one from, and Seerr's select box that
// opens it. The Movies and Series pages had them too, and a chip to switch on and off, until A8 put their sort in a list
// of tv-material rows and their filters in A5's `SidePanel`.

/** One choice in a [PickerModal]: its value, its words, and its test tag. */
data class Pick(val value: String, val label: String, val tag: String)

/**
 * A list to pick one from (Seerr's select, at ten feet): the title, the choices with the current one
 * ticked and focused on arrival; Select picks and closes, Back closes. Focus stays inside.
 */
@Composable
fun PickerModal(title: String, picks: List<Pick>, selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit, tag: String) {
    val at = picks.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    val entry = remember { FocusRequester() }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (at - 3).coerceAtLeast(0))
    FocusOnArrival(entry)
    BackHandler { onDismiss() }
    val shape = RoundedCornerShape(8.dp)
    Overlay(Modifier.testTag(tag)) {
        Column(
            Modifier
                .align(Alignment.Center)
                .width(360.dp)
                .heightIn(max = 440.dp)
                .clip(shape)
                .background(Tokens.Surface)
                .padding(vertical = 16.dp),
        ) {
            BasicText(title, style = HitherType.Title.copy(color = Tokens.Text), modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
            ) {
                itemsIndexed(picks, key = { _, pick -> pick.tag }) { index, pick ->
                    PickRow(
                        pick = pick,
                        on = pick.value == selected,
                        onSelect = { onPick(pick.value) },
                        modifier = if (index == at) Modifier.focusRequester(entry) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun PickRow(pick: Pick, on: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    ReportRecomposition("pick ${pick.tag}")
    var focused by remember { mutableStateOf(false) }
    val amount = focusAmount(focused)
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .onFocusChanged { focused = it.isFocused }
            .selectBeforeKeyboard(onSelect = onSelect)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSelect)
            .focusScale(focused, scale = 1.02f, amount = amount)
            .clip(shape)
            .focusFill(shape, Color.Transparent, Tokens.SurfaceRaised, amount)
            .semantics { selected = on }
            .padding(horizontal = 10.dp)
            .testTag("pick ${pick.tag}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            pick.label,
            style = HitherType.Body.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
            color = focusColor(Tokens.Text, Tokens.Text, amount),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (on) Image(HitherIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp), colorFilter = ColorFilter.tint(Tokens.Text))
    }
}

/** Seerr's select box: gray-700, a gray-500 border; focused, gray-600 and a white border. */
@Composable
fun SelectBox(text: String, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val amount = focusAmount(focused)
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .onFocusChanged { focused = it.isFocused }
            .selectBeforeKeyboard(onSelect = onSelect)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSelect)
            .focusScale(focused, scale = 1.02f, amount = amount)
            .clip(shape)
            .focusFill(shape, Tokens.SurfaceRaised, Tokens.Edge, amount)
            .focusBorder(1.dp, shape, Tokens.Edge, Tokens.Text, amount)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text, style = HitherType.Body, color = focusColor(Tokens.Text, Tokens.Text, amount), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Image(HitherIcons.SortDown, contentDescription = null, modifier = Modifier.size(14.dp), colorFilter = ColorFilter.tint(Tokens.TextSecondary))
    }
}

