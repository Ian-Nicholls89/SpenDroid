package com.spendroid.wear

import android.graphics.Color
import android.os.Build
import androidx.wear.watchface.complications.data.ColorRamp
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.WeightedElementsComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * SpenDroid's complication: what is left to spend, drawn as the watch face asks.
 *
 * - A segmented arc where the face takes one (Wear OS 4 and later): budget left in green, and
 *   an amber band for how far behind even pace the spending is.
 * - Otherwise a single arc of the budget left, coloured for the pace.
 * - Otherwise just the figure.
 */
class BudgetComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val reading = BudgetReading.load(this) ?: fetchFromPhone() ?: return null
        return build(request.complicationType, reading)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, BudgetReading("£430", budgetLeft = 0.52f, cycleLeft = 0.60f, pace = "TIGHT", updatedAt = 0L))

    /**
     * The phone's last figures, asked for directly when nothing has arrived here yet - on a
     * complication added before the phone next synced, which would otherwise sit empty.
     */
    private suspend fun fetchFromPhone(): BudgetReading? = runCatching {
        val items = Wearable.getDataClient(this).dataItems.await()
        try {
            items.firstOrNull { it.uri.path == BudgetReading.PATH }
                ?.let { BudgetReading.from(DataMapItem.fromDataItem(it).dataMap) }
                ?.also { it.save(this) }
        } finally {
            items.release()
        }
    }.getOrNull()

    private fun build(type: ComplicationType, reading: BudgetReading): ComplicationData? {
        val text = PlainComplicationText.Builder(reading.available).build()
        val description = describe(reading)
        // Segments only exist from Wear OS 4 (API 33); even naming the type before that is an error.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && type == ComplicationType.WEIGHTED_ELEMENTS) {
            return segmented(reading, text, description)
        }
        return when (type) {
            ComplicationType.RANGED_VALUE ->
                RangedValueComplicationData.Builder(
                    value = reading.budgetLeft.coerceIn(0f, 1f),
                    min = 0f,
                    max = 1f,
                    contentDescription = description,
                )
                    .setText(text)
                    .apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val colour = paceColour(BudgetArc.pace(reading.pace))
                            setColorRamp(ColorRamp(intArrayOf(colour, colour), false))
                        }
                    }
                    .build()

            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(text, description)
                    .setTitle(PlainComplicationText.Builder("left").build())
                    .build()

            else -> null
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun segmented(
        reading: BudgetReading,
        text: ComplicationText,
        description: ComplicationText,
    ): ComplicationData {
        val max = WeightedElementsComplicationData.getMaxElements()
        val elements = BudgetArc.segments(reading.budgetLeft, reading.cycleLeft)
            .take(max)
            .map { segment ->
                WeightedElementsComplicationData.Element(
                    segment.weight,
                    when (segment.kind) {
                        BudgetArc.Kind.LEFT -> GREEN
                        BudgetArc.Kind.BEHIND -> AMBER
                        BudgetArc.Kind.SPENT -> SPENT
                    },
                )
            }
        return WeightedElementsComplicationData.Builder(elements, description)
            .setText(text)
            .build()
    }

    private fun describe(reading: BudgetReading): ComplicationText {
        val pace = when (BudgetArc.pace(reading.pace)) {
            BudgetArc.Pace.ON_TRACK -> "on track"
            BudgetArc.Pace.TIGHT -> "a little behind"
            BudgetArc.Pace.OVER -> "well behind"
        }
        return PlainComplicationText.Builder("${reading.available} left to spend, $pace").build()
    }

    private fun paceColour(pace: BudgetArc.Pace): Int = when (pace) {
        BudgetArc.Pace.ON_TRACK -> GREEN
        BudgetArc.Pace.TIGHT -> AMBER
        BudgetArc.Pace.OVER -> RED
    }

    private companion object {
        val GREEN = Color.rgb(0x66, 0xBB, 0x6A)
        val AMBER = Color.rgb(0xFF, 0xB7, 0x4D)
        val RED = Color.rgb(0xEF, 0x53, 0x50)
        val SPENT = Color.rgb(0x3A, 0x3A, 0x40)
    }
}
