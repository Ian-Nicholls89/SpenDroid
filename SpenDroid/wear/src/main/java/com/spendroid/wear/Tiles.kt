package com.spendroid.wear

import android.content.ComponentName
import android.content.Context
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders.launchAction
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.weight
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.types.argb
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

/**
 * The watch's tiles, a swipe from the watch face: the budget (the watch app's first screen, at a
 * glance) and this cycle's categories. Material 3 tile layouts; tapping Open opens the watch app.
 */
private const val RESOURCES = "1"
private const val DEFAULT_ACCENT = 0xFF3DDC84.toInt()

// The leading number changes whenever her pictures do, or tiles keep the old ones they have:
// 3 from watch 1.10.2, her poses from the shared Penny; 4 from 1.10.4, payday without its clipped piece.
// 5 from watch 0.1.14: her asleep picture.
private fun versionFor(accent: Int) = "5-" + Integer.toHexString(accent)
private fun droidId(mood: WatchMood) = "droid_" + mood.name.lowercase()

/** The droid as a tile image: tiles take pictures, sent once per version. */
private fun droidImage(mood: WatchMood, accent: Int): ResourceBuilders.ImageResource {
    val size = 72
    val bytes = java.io.ByteArrayOutputStream().also { watchDroidBitmap(mood, accent, size).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    return ResourceBuilders.ImageResource.Builder()
        .setInlineResource(
            ResourceBuilders.InlineImageResource.Builder()
                .setData(bytes)
                .setWidthPx(size).setHeightPx(size)
                .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                .build(),
        )
        .build()
}
private const val GREEN = 0xFF43B05C.toInt()
private const val RED = 0xFFE5534B.toInt()
private const val AMBER = 0xFFF5A623.toInt()
private const val BLUE = 0xFFBED8FF.toInt()
private const val TRACK = 0xFF33363E.toInt()
private const val MUTED = 0xFFA0AABE.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

private fun paceArgb(pace: String) = when (pace) {
    "OVER" -> RED
    "TIGHT" -> AMBER
    else -> GREEN
}

private fun tile(layout: LayoutElement, version: String = RESOURCES): TileBuilders.Tile =
    TileBuilders.Tile.Builder()
        .setResourcesVersion(version)
        // The phone asks for a redraw whenever it sends new figures; this is only a backstop.
        // Hourly, or sooner when she falls asleep or wakes, so the tile changes then.
        .setFreshnessIntervalMillis(minOf(60 * 60 * 1000L, com.spendroid.ui.PennySleep.untilNextChange(java.time.LocalDateTime.now()).toMillis() + 1_000L))
        .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
        .build()

private fun MaterialScope.openButton(context: Context): LayoutElement =
    textEdgeButton(
        onClick = clickable(launchAction(ComponentName(context, BudgetActivity::class.java)), id = "open"),
        labelContent = { text("Open".layoutString) },
    )

/** A tile's name with the droid in front, wearing the budget's face - as the phone's headers. */
private fun MaterialScope.droidTitle(label: String, reading: BudgetReading?): LayoutElement =
    LayoutElementBuilders.Row.Builder()
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .addContent(
            LayoutElementBuilders.Image.Builder()
                .setResourceId(droidId(tileMoodOf(reading)))
                .setWidth(dp(18f)).setHeight(dp(18f))
                .build(),
        )
        .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(4f)).build())
        .addContent(text(label.layoutString, color = (reading?.accent ?: DEFAULT_ACCENT).argb, typography = Typography.LABEL_MEDIUM))
        .build()

private fun MaterialScope.noFigures(): LayoutElement =
    text("Open SpenDroid on your phone".layoutString, typography = Typography.BODY_MEDIUM, maxLines = 3)

private fun column(vararg items: LayoutElement): LayoutElement =
    LayoutElementBuilders.Column.Builder()
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .apply { items.forEach { addContent(it) } }
        .build()

private fun gap(height: Float): LayoutElement = LayoutElementBuilders.Spacer.Builder().setHeight(dp(height)).build()

private fun background(colour: Int, radius: Float) =
    ModifiersBuilders.Modifiers.Builder()
        .setBackground(
            ModifiersBuilders.Background.Builder()
                .setColor(argb(colour))
                .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(radius)).build())
                .build(),
        )
        .build()

/**
 * The budget, in the phone's look: the name in the accent along the top, what is left as one
 * thick arc in the pace's colour round the edge, the figure, a pace chip, and the daily amount.
 */
fun budgetTileLayout(context: Context, device: DeviceParameters, reading: BudgetReading?): LayoutElement =
    materialScope(context, device) {
        val accent = reading?.accent ?: 0xFFF5A623.toInt()
        primaryLayout(
            titleSlot = { droidTitle("spendroid", reading) },
            mainSlot = {
                if (reading == null) return@primaryLayout noFigures()
                val colour = when (reading.pace) { "OVER" -> RED; "TIGHT" -> AMBER; else -> accent }
                fun arc(length: Float, c: Int, from: Float = ARC_START, thickness: Float = 14f) = LayoutElementBuilders.Arc.Builder()
                    .setAnchorAngle(degrees(from))
                    .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
                    .addContent(
                        LayoutElementBuilders.ArcLine.Builder()
                            .setLength(degrees(length.coerceAtLeast(1f)))
                            .setThickness(dp(thickness))
                            .setColor(argb(c))
                            .build(),
                    )
                    .build()
                LayoutElementBuilders.Box.Builder()
                    .setWidth(expand())
                    .setHeight(expand())
                    .addContent(arc(ARC_SWEEP, TRACK))
                    // The visor round the edge: the budget used, and a white tick where the days are.
                    .addContent(arc(ARC_SWEEP * (reading.used ?: (1f - reading.budgetLeft)).coerceIn(0f, 1f), colour))
                    .apply { reading.elapsed?.let { addContent(arc(1.5f, WHITE, ARC_START + ARC_SWEEP * it.coerceIn(0f, 1f) - 0.75f, 20f)) } }
                    .addContent(
                        column(
                            text(reading.available.layoutString, typography = Typography.NUMERAL_MEDIUM),
                            gap(2f),
                            LayoutElementBuilders.Box.Builder()
                                .setModifiers(background(colour, 5f))
                                .addContent(
                                    LayoutElementBuilders.Box.Builder()
                                        .setModifiers(ModifiersBuilders.Modifiers.Builder().setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(6f)).setEnd(dp(6f)).build()).build())
                                        .addContent(
                                            text(
                                                when (reading.pace) { "OVER" -> "OVER PACE"; "TIGHT" -> "TIGHT"; else -> "ON PACE" }.layoutString,
                                                typography = Typography.LABEL_SMALL,
                                                color = 0xFF111111.toInt().argb,
                                            ),
                                        )
                                        .build(),
                                )
                                .build(),
                            gap(4f),
                            text(reading.text("daysLine").ifEmpty { " " }.layoutString, typography = Typography.BODY_SMALL, color = MUTED.argb),
                        ),
                    )
                    .build()
            },
            bottomSlot = { openButton(context) },
        )
    }

/** This cycle's top three categories, against the user's limits where set. */
fun categoriesTileLayout(context: Context, device: DeviceParameters, reading: BudgetReading?): LayoutElement =
    materialScope(context, device) {
        primaryLayout(
            titleSlot = { droidTitle("most spent", reading) },
            mainSlot = {
                val rows = reading?.categories.orEmpty().take(3)
                if (reading == null || rows.isEmpty()) return@primaryLayout noFigures()
                val biggest = rows.maxOf { it.minor }.coerceAtLeast(1L)
                LayoutElementBuilders.Column.Builder()
                    .setWidth(expand())
                    .apply {
                        rows.forEachIndexed { i, c ->
                            if (i > 0) addContent(gap(6f))
                            addContent(
                                LayoutElementBuilders.Row.Builder()
                                    .setWidth(expand())
                                    .addContent(text(c.label.layoutString, typography = Typography.LABEL_MEDIUM, color = WHITE.argb))
                                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(weight(1f)).build())
                                    .addContent(
                                        text(
                                            (if (c.limit.isNotEmpty()) "${c.spent} of ${c.limit}" else c.spent).layoutString,
                                            typography = Typography.LABEL_SMALL,
                                            color = (if (c.over) RED else MUTED).argb,
                                        ),
                                    )
                                    .build(),
                            )
                            // Against the limit where there is one, else against the largest here.
                            val share = (c.share ?: (c.minor.toFloat() / biggest))
                                .coerceIn(0.03f, 1f)
                            addContent(gap(2f))
                            addContent(
                                LayoutElementBuilders.Row.Builder()
                                    .setWidth(expand())
                                    .addContent(
                                        LayoutElementBuilders.Spacer.Builder().setWidth(weight(share)).setHeight(dp(8f))
                                            .setModifiers(background(if (c.over) RED else c.colour, 2.4f)).build(),
                                    )
                                    .addContent(
                                        LayoutElementBuilders.Spacer.Builder().setWidth(weight(1f - share + 0.0001f)).setHeight(dp(8f))
                                            .setModifiers(background(TRACK, 2.4f)).build(),
                                    )
                                    .build(),
                            )
                        }
                    }
                    .build()
            },
            bottomSlot = { openButton(context) },
        )
    }

/** Tiles measure angles from twelve o'clock, clockwise: the arc starts at the lower left. */
private const val ARC_START = 230f
private const val ARC_SWEEP = 260f
private const val GAP = 3f

abstract class SpenDroidTileService : TileService() {
    abstract fun layout(device: DeviceParameters, reading: BudgetReading?): LayoutElement

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture {
            val reading = BudgetReading.load(this@SpenDroidTileService)
            // The droid's pictures are drawn in the phone's colour, so a new colour is a new version.
            tile(layout(requestParams.deviceConfiguration, reading), versionFor(reading?.accent ?: DEFAULT_ACCENT))
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture {
            val accent = requestParams.version.substringAfter("-", "").toLongOrNull(16)?.toInt() ?: DEFAULT_ACCENT
            ResourceBuilders.Resources.Builder().setVersion(requestParams.version).apply {
                WatchMood.entries.forEach { mood -> addIdToImageMapping(droidId(mood), droidImage(mood, accent)) }
            }.build()
        }

    companion object {
        /** Asks both tiles to redraw: new figures have arrived from the phone. */
        fun requestUpdates(context: Context) {
            runCatching {
                getUpdater(context).requestUpdate(BudgetTileService::class.java)
                getUpdater(context).requestUpdate(CategoriesTileService::class.java)
            }
        }
    }
}

class BudgetTileService : SpenDroidTileService() {
    override fun layout(device: DeviceParameters, reading: BudgetReading?) = budgetTileLayout(this, device, reading)
}

class CategoriesTileService : SpenDroidTileService() {
    override fun layout(device: DeviceParameters, reading: BudgetReading?) = categoriesTileLayout(this, device, reading)
}
