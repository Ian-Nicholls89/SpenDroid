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
private const val GREEN = 0xFFC4E84A.toInt()
private const val RED = 0xFFFF5C3C.toInt()
private const val AMBER = 0xFFFFB74D.toInt()
private const val BLUE = 0xFFBED8FF.toInt()
private const val TRACK = 0xFF2D303A.toInt()
private const val MUTED = 0xFFA0AABE.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

private fun paceArgb(pace: String) = when (pace) {
    "OVER" -> RED
    "TIGHT" -> AMBER
    else -> GREEN
}

private fun tile(layout: LayoutElement): TileBuilders.Tile =
    TileBuilders.Tile.Builder()
        .setResourcesVersion(RESOURCES)
        // The phone asks for a redraw whenever it sends new figures; this is only a backstop.
        .setFreshnessIntervalMillis(60 * 60 * 1000L)
        .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
        .build()

private fun MaterialScope.openButton(context: Context): LayoutElement =
    textEdgeButton(
        onClick = clickable(launchAction(ComponentName(context, BudgetActivity::class.java)), id = "open"),
        labelContent = { text("Open".layoutString) },
    )

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

/** The budget: the figure, the three-part ring, days left and the daily amount. */
fun budgetTileLayout(context: Context, device: DeviceParameters, reading: BudgetReading?): LayoutElement =
    materialScope(context, device) {
        primaryLayout(
            titleSlot = { text("Left to spend".layoutString) },
            mainSlot = {
                if (reading == null) return@primaryLayout noFigures()
                // Spent, behind even pace, left - in proportion - round the edge of the slot.
                val arc = LayoutElementBuilders.Arc.Builder()
                    .setAnchorAngle(degrees(ARC_START))
                    .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
                    .apply {
                        BudgetArc.drawOrder(BudgetArc.segments(reading.budgetLeft, reading.cycleLeft)).forEach { s ->
                            val colour = when (s.kind) {
                                BudgetArc.Kind.SPENT -> BLUE
                                BudgetArc.Kind.BEHIND -> RED
                                BudgetArc.Kind.LEFT -> GREEN
                            }
                            addContent(
                                LayoutElementBuilders.ArcLine.Builder()
                                    .setLength(degrees((ARC_SWEEP * s.weight - GAP).coerceAtLeast(1f)))
                                    .setThickness(dp(6f))
                                    .setColor(argb(colour))
                                    .build(),
                            )
                            addContent(LayoutElementBuilders.ArcSpacer.Builder().setLength(degrees(GAP)).build())
                        }
                    }
                    .build()
                LayoutElementBuilders.Box.Builder()
                    .setWidth(expand())
                    .setHeight(expand())
                    .addContent(arc)
                    .addContent(
                        column(
                            text(reading.available.layoutString, typography = Typography.NUMERAL_MEDIUM),
                            text(reading.text("daysLine").ifEmpty { " " }.layoutString, typography = Typography.BODY_SMALL),
                            gap(4f),
                            text(
                                when (reading.pace) { "OVER" -> "▲ Over"; "TIGHT" -> "◆ Tight"; else -> "● On track" }.layoutString,
                                typography = Typography.LABEL_MEDIUM,
                                color = paceArgb(reading.pace).argb,
                            ),
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
            titleSlot = { text("This cycle".layoutString) },
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
                                        LayoutElementBuilders.Spacer.Builder().setWidth(weight(share)).setHeight(dp(4f))
                                            .setModifiers(background(if (c.over) RED else c.colour, 2f)).build(),
                                    )
                                    .addContent(
                                        LayoutElementBuilders.Spacer.Builder().setWidth(weight(1f - share + 0.0001f)).setHeight(dp(4f))
                                            .setModifiers(background(TRACK, 2f)).build(),
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
private const val ARC_START = 218f
private const val ARC_SWEEP = 284f
private const val GAP = 3f

abstract class SpenDroidTileService : TileService() {
    abstract fun layout(device: DeviceParameters, reading: BudgetReading?): LayoutElement

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture {
            tile(layout(requestParams.deviceConfiguration, BudgetReading.load(this@SpenDroidTileService)))
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture { ResourceBuilders.Resources.Builder().setVersion(RESOURCES).build() }

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
