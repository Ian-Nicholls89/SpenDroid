package com.spendroid.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.spendroid.BudgetApplication
import com.spendroid.MainActivity
import com.spendroid.domain.CreditCardEngine
import com.spendroid.ui.CardPaceLine
import com.spendroid.ui.cardPaceLine
import com.spendroid.ui.formatMoney
import java.time.format.DateTimeFormatter

/**
 * What is owed on each credit card, split across the statement boundary.
 *
 * Deliberately not a second copy of the budget widget: no hero figure and no pace colour, so
 * the two never read as the same tile twice. The total on each row is the bank's own balance,
 * the same figure the accounts screen shows, and the bar underneath is how much of it is
 * settled on a closed statement versus still accruing.
 */
class CardBillsWidget : GlanceAppWidget() {

    // Exact, so widths drawn from the size match the widget as it really is; see the balance widget.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val cards = loadCards(context)
        provideContent { Body(cards) }
    }

    private data class CardRow(
        val label: String,
        val total: String,
        val detail: String,
        /** Share of the total already on a closed statement, for the split bar. */
        val billedShare: Float,
        /** How the statement now building is going; null until there is a measure. */
        val pace: CardPaceLine? = null,
        /** The statement against its limit, once there is a limit to measure against. */
        val rail: WidgetGeometry.CardRail? = null,
    )

    private data class Cards(val rows: List<CardRow>, val updated: String?)

    private suspend fun loadCards(context: Context): Cards? {
        val app = context.applicationContext as? BudgetApplication ?: return null
        return runCatching {
            val snapshot = app.repository.budgetSnapshot() ?: return Cards(emptyList(), null)
            Cards(
                rows = snapshot.cardBills
                    // A card with nothing owed still has a limit worth watching once it is set.
                    .filter { it.outstandingMinor > 0L || it.capMinor != null }
                    .map { bill -> bill.toRow(snapshot.asOf) },
                updated = updatedLabel(app.repository.accounts()),
            )
        }.getOrNull()
    }

    private fun CreditCardEngine.CardBill.toRow(today: java.time.LocalDate): CardRow {
        val rail = WidgetGeometry.cardRail(this)
        val cap = capMinor
        if (rail != null && cap != null) {
            // Measured against the limit: what is on the statement now building, and when the
            // last one's payment leaves.
            val whose = if (capSource == CreditCardEngine.CapSource.USER) "your" else "usual"
            val countdown = dueDate?.let { date ->
                val days = java.time.temporal.ChronoUnit.DAYS.between(today, date)
                val amount = dueMinor.takeIf { it > 0L }?.let { "${formatMoney(it, currency)} " }.orEmpty()
                when {
                    days <= 0L -> "${amount}due today"
                    days == 1L -> "${amount}due tomorrow"
                    else -> "${amount}due in $days days"
                }
            }
            return CardRow(
                label = cardLabel,
                total = formatMoney(outstandingMinor, currency),
                detail = listOfNotNull(
                    "${formatMoney(unbilledMinor, currency)} of $whose ${formatMoney(cap, currency)}",
                    countdown,
                ).joinToString(" · "),
                billedShare = 0f,
                pace = cardPaceLine(this),
                rail = rail,
            )
        }
        val due = dueDate?.let { "due ${it.format(DUE_FORMAT)}" }
        val detail = when {
            unbilledMinor <= 0L -> listOfNotNull("All billed", due).joinToString(" · ")
            billedMinor <= 0L -> listOfNotNull("All since statement", due).joinToString(" · ")
            else -> listOfNotNull(
                "${formatMoney(billedMinor, currency)} billed",
                statementClose?.let { "${formatMoney(unbilledMinor, currency)} since ${it.format(DUE_FORMAT)}" }
                    ?: "${formatMoney(unbilledMinor, currency)} since statement",
                due,
            ).joinToString(" · ")
        }
        return CardRow(
            label = cardLabel,
            total = formatMoney(outstandingMinor, currency),
            detail = detail,
            billedShare = (billedMinor.toFloat() / outstandingMinor.toFloat()).coerceIn(0f, 1f),
            pace = cardPaceLine(this),
        )
    }

    private fun CardRow.statusLabel(): String? = when {
        rail == null -> null
        pace?.over == true -> "▲ Over pace"
        else -> "● Fine"
    }

    @Composable
    private fun Body(cards: Cards?) {
        GlanceTheme {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(GlanceTheme.colors.surface)
                    .cornerRadius(18.dp)
                    .padding(14.dp)
                    .clickable(actionStartActivity<MainActivity>()),
            ) {
                when {
                    cards == null -> Centred("Open SpenDroid")
                    cards.rows.isEmpty() -> Centred("No card balances yet")
                    else -> CardList(cards)
                }
            }
        }
    }

    @Composable
    private fun CardList(cards: Cards) {
        val size = LocalSize.current
        val room = if (size.height >= TALL.height) 4 else 2
        val narrow = size.width < STANDARD.width

        Column(modifier = GlanceModifier.fillMaxSize()) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Text(
                    "Card bills",
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                if (!narrow) {
                    cards.updated?.let {
                        Text(it, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 9.sp))
                    }
                }
                Text(
                    "↻",
                    modifier = GlanceModifier.clickable(actionRunCallback<RefreshWidgetsAction>()).padding(start = 6.dp),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                )
            }
            Spacer(GlanceModifier.height(8.dp))
            cards.rows.take(room).forEach { card ->
                CardRowView(card, width = size.width - 28.dp, showDetail = !narrow)
                Spacer(GlanceModifier.height(9.dp))
            }
        }
    }

    @Composable
    private fun CardRowView(card: CardRow, width: Dp, showDetail: Boolean) {
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Text(
                    card.label,
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                // In words as well as colour, so the state is never colour alone.
                if (showDetail) {
                    card.statusLabel()?.let { status ->
                        Text(
                            status,
                            maxLines = 1,
                            modifier = GlanceModifier.padding(end = 8.dp),
                            style = TextStyle(
                                color = if (card.pace?.over == true) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                }
                Text(
                    card.total,
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
            if (showDetail) {
                Text(
                    card.detail,
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 10.sp,
                    ),
                )
                card.pace?.let { pace ->
                    Text(
                        pace.text,
                        maxLines = 1,
                        style = TextStyle(
                            color = if (pace.over) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 10.sp,
                            fontWeight = if (pace.over) FontWeight.Medium else FontWeight.Normal,
                        ),
                    )
                }
            }
            Spacer(GlanceModifier.height(5.dp))
            val rail = card.rail
            if (rail != null) {
                Image(
                    provider = ImageProvider(
                        cardRailBitmap(rail, fill = RailFill, track = RailTrack, tick = RailTick, over = card.pace?.over == true),
                    ),
                    contentDescription = card.detail,
                    modifier = GlanceModifier.fillMaxWidth().height(9.dp),
                    // The full width of the row, as the numbers beside it assume.
                    contentScale = ContentScale.FillBounds,
                )
            } else {
                SplitBar(card.billedShare, width)
            }
        }
    }

    /** Solid is settled and due on the date shown; pale is still accruing. */
    @Composable
    private fun SplitBar(billedShare: Float, width: Dp) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(5.dp)
                .cornerRadius(3.dp)
                .background(GlanceTheme.colors.secondaryContainer),
        ) {
            if (billedShare > 0f) {
                Box(
                    modifier = GlanceModifier
                        .width(width * billedShare)
                        .height(5.dp)
                        .cornerRadius(3.dp)
                        .background(GlanceTheme.colors.primary),
                ) {}
            }
        }
    }

    @Composable
    private fun Centred(message: String) {
        Box(
            modifier = GlanceModifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                message,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }

    private companion object {
        val COMPACT = DpSize(110.dp, 120.dp)
        val STANDARD = DpSize(250.dp, 120.dp)
        val TALL = DpSize(250.dp, 190.dp)

        val DUE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

        // The widget follows the phone's light or dark theme, so the drawn bar uses colours
        // that read on both rather than white on an assumed dark background.
        const val RailFill = 0xFF3987E5.toInt()
        const val RailTrack = 0x40808080
        const val RailTick = 0xFF8596B0.toInt()
    }
}

class CardBillsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CardBillsWidget()
}

/** Refreshes every placed card-bills widget, alongside the budget one. */
internal suspend fun refreshCardWidgets(context: Context) {
    runCatching { CardBillsWidget().updateAll(context) }
}
