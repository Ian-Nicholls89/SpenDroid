package com.spendroid.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spendroid.data.db.AccountEntity
import com.spendroid.domain.Category
import com.spendroid.ui.theme.Charcoal
import com.spendroid.ui.theme.Lato
import com.spendroid.ui.theme.Motion

/*
 * The pieces of nzb360's look, shared by every screen: panels on charcoal, spaced capitals for
 * headings, bars with their figure written inside, artwork tiles, and a coloured glow and
 * wordmark at the top of a screen.
 */

/** A heading in spaced capitals, with an optional note on the right. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier, trailing: String? = null, colour: Color = Color.Unspecified) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            fontFamily = Lato,
            fontWeight = FontWeight.Black,
            fontSize = 12.sp,
            letterSpacing = 1.6.sp,
            color = if (colour == Color.Unspecified) Color(0xFFE8E9EC) else colour,
            modifier = Modifier.weight(1f),
        )
        trailing?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted) }
    }
}

/** A panel on the charcoal: nzb360's card. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    colour: Color = Charcoal.Panel,
    brush: Brush? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (brush != null) Modifier.background(brush) else Modifier.background(colour))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        content = content,
    )
}

/**
 * A thick bar with its figure written inside, like Unraid's capacity bars. [tick] marks where
 * the fill would be on pace, as a share of the whole.
 */
@Composable
fun LabelledBar(
    fraction: Float,
    label: String,
    colour: Color,
    modifier: Modifier = Modifier,
    tick: Float? = null,
    height: Dp = 22.dp,
) {
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), Motion.change(Motion.LONG), label = "bar")
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(Color(0xFF33363E))
            .semantics { contentDescription = label },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(maxWidth * shown)
                .clip(RoundedCornerShape(height / 2))
                .background(colour),
        )
        tick?.let { t ->
            Box(
                Modifier
                    .offset(x = maxWidth * t.coerceIn(0f, 1f) - 1.5.dp)
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(Color.White, RoundedCornerShape(2.dp)),
            )
        }
        Text(
            label,
            fontFamily = Lato,
            fontWeight = FontWeight.Black,
            fontSize = if (height < 20.dp) 11.sp else 12.sp,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/** A category's "artwork": its icon on a rounded square of its colour. */
@Composable
fun CategoryTile(category: Category, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val visual = category.visual
    ArtTile(visual.color, modifier, size) {
        Icon(visual.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** A rounded square of colour, darker towards its foot, holding an icon or a letter. */
@Composable
fun ArtTile(colour: Color, modifier: Modifier = Modifier, size: Dp = 44.dp, height: Dp = size, content: @Composable () -> Unit) {
    Box(
        modifier
            .size(size, height)
            .clip(RoundedCornerShape(size * 0.22f))
            .background(Brush.linearGradient(listOf(colour, colour.darken(0.55f)))),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A letter for something with no icon of its own: a payee, an account. */
@Composable
fun Monogram(text: String, size: Dp = 44.dp) {
    Text(
        text.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "£",
        fontFamily = Lato,
        fontWeight = FontWeight.Black,
        fontSize = (size.value * 0.42f).sp,
        color = Color.White,
    )
}

/**
 * A poster, as nzb360 shows upcoming films: a tall tile with a date chip at the top right and
 * a big glyph at the foot, the name and figure beneath.
 */
@Composable
fun PosterTile(
    title: String,
    subtitle: String,
    chip: String?,
    colour: Color,
    modifier: Modifier = Modifier,
    titleColour: Color = Color.White,
    width: Dp = 96.dp,
    onClick: (() -> Unit)? = null,
    glyph: @Composable () -> Unit = { Monogram(title, 52.dp) },
) {
    Column(
        modifier
            .width(width)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(width * 1.33f)
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(colour, colour.darken(0.6f))))
                .padding(8.dp),
        ) {
            chip?.let {
                Text(
                    it,
                    fontFamily = Lato,
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Box(Modifier.align(Alignment.BottomStart)) { glyph() }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.labelMedium, color = titleColour, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, maxLines = 1)
    }
}

/** A small rounded label in a solid colour: "ON PACE", "Pending". */
@Composable
fun Chip(text: String, colour: Color, textColour: Color = Color(0xFF111111), modifier: Modifier = Modifier) {
    Text(
        text,
        fontFamily = Lato,
        fontWeight = FontWeight.Black,
        fontSize = 12.sp,
        color = textColour,
        maxLines = 1,
        modifier = modifier
            .background(colour, RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

/** Text tabs, the selected one white with a dot beneath it in the accent. */
@Composable
fun DotTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        tabs.forEachIndexed { i, label ->
            Column(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onSelect(i) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    fontFamily = Lato,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = if (i == selected) Color.White else Color(0xFF7D828C),
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (i == selected) MaterialTheme.colorScheme.primary else Color.Transparent),
                )
            }
        }
    }
}

/** A day heading in a list, in the accent: "Today", "Yesterday". */
@Composable
fun DayHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontFamily = Lato,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

/** The short accent bar above a section, and its title, as on nzb360's dashboard. */
@Composable
fun AccentTitle(title: String, note: String? = null, colour: Color = MaterialTheme.colorScheme.primary, onMore: (() -> Unit)? = null) {
    Column(Modifier.padding(horizontal = 18.dp)) {
        Box(Modifier.width(36.dp).height(3.dp).background(colour, RoundedCornerShape(2.dp)))
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onMore != null) Modifier.clickable(onClick = onMore) else Modifier),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(title, fontFamily = Lato, fontWeight = FontWeight.Black, fontSize = 17.sp)
            note?.let {
                Spacer(Modifier.width(10.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted, modifier = Modifier.padding(bottom = 2.dp))
            }
            Spacer(Modifier.weight(1f))
            if (onMore != null) Text("›", color = colour, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** A screen's name in spaced capitals, in [colour]: the "UNRAID" of nzb360. */
@Composable
fun Wordmark(text: String, colour: Color, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        fontFamily = Lato,
        fontWeight = FontWeight.Black,
        fontSize = 21.sp,
        letterSpacing = 4.sp,
        color = colour,
        maxLines = 1,
        modifier = modifier,
    )
}

/** The glow behind a screen's header, in [colour], fading into the charcoal. */
@Composable
fun HeaderGlow(colour: Color, modifier: Modifier = Modifier, height: Dp = 220.dp, strength: Float = 0.36f) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(Brush.verticalGradient(listOf(colour.copy(alpha = strength), Color.Transparent))),
    )
}

/** A big flat button on a panel colour, as nzb360's "Go to Movies". */
@Composable
fun BigButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, colour: Color = Charcoal.PanelHigh, textColour: Color = Color.White) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colour)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = textColour, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = textColour, maxLines = 1)
    }
}

/**
 * The colour of the glow and wordmark at the top of the screen. A screen about one account sets
 * it to that account's colour, as nzb360 takes each service's; null is the accent.
 */
class GlowState {
    var colour by androidx.compose.runtime.mutableStateOf<Color?>(null)
}

val LocalGlow = androidx.compose.runtime.staticCompositionLocalOf { GlowState() }

/** Whether accounts wear their own colours; set once at the top of the app from the user's choice. */
val LocalAccountColours = androidx.compose.runtime.staticCompositionLocalOf { true }

/** [account]'s colour as the user has things set: its own, or the accent. */
@Composable
fun colourOf(account: AccountEntity?, accounts: List<AccountEntity>): Color =
    accountColour(account, accounts, LocalAccountColours.current, MaterialTheme.colorScheme.primary)

/** Each account's own colour, so the screens about it, its rows and its bars are known by it. */
private val AccountPalette = listOf(
    Color(0xFF5A5FD8), // indigo
    Color(0xFF9B5DE5), // purple
    Color(0xFF2FB5A5), // teal
    Color(0xFF1B9BD8), // blue
    Color(0xFFE04A7A), // pink
    Color(0xFFE0803B), // orange
    Color(0xFFB8A12D), // olive
    Color(0xFF43B05C), // green
)

/**
 * [account]'s colour among [accounts]: by position once sorted by id, so each keeps its colour
 * from one launch to the next and no two of the first eight share one. With account colours
 * off, every account takes [fallback].
 */
fun accountColour(account: AccountEntity?, accounts: List<AccountEntity>, enabled: Boolean, fallback: Color): Color {
    if (!enabled || account == null) return fallback
    val index = accounts.map { it.id }.sorted().indexOf(account.id)
    return if (index < 0) fallback else AccountPalette[index % AccountPalette.size]
}

/** [this] towards black by [amount], 0 to 1. */
fun Color.darken(amount: Float): Color =
    Color(red * (1 - amount), green * (1 - amount), blue * (1 - amount), alpha)


/**
 * A question for the user, as nzb360's banners: a header strip in [colour] so it reads as
 * something to act on rather than more of the list.
 */
@Composable
fun QuestionCard(title: String, colour: Color, modifier: Modifier = Modifier, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Charcoal.Panel)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(colour, colour.darken(0.3f))))
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeading(title, Modifier.weight(1f), colour = Color(0xFF111111))
            trailing?.let { Text(it, fontFamily = Lato, fontWeight = FontWeight.Black, fontSize = 12.sp, color = Color(0xFF111111)) }
        }
        Column(Modifier.padding(14.dp), content = content)
    }
}

/** A choice as a pill in its own colour, big enough to tap: "Personal", "Nectar", "Ignore". */
@Composable
fun ColourPill(text: String, colour: Color, onClick: () -> Unit, textColour: Color = Color.White) {
    Text(
        text,
        fontFamily = Lato,
        fontWeight = FontWeight.Black,
        fontSize = 13.sp,
        color = textColour,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colour)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
