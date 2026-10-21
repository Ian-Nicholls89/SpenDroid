package com.spendroid.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spendroid.data.db.AccountEntity
import com.spendroid.data.db.SeenSpendEntity
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.DuplicateCheck
import com.spendroid.domain.NotificationSpend
import com.spendroid.ui.theme.Charcoal
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")
private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Something Penny needs the user to answer: whether a payment was made once or twice, which
 * account one came from, whether one the bank never reported was cancelled, and whether a row
 * the bank stopped listing is gone or became another. Asked one at a time, in a sheet over the
 * app, in the order that matters most to the budget.
 */
internal sealed class PennyQuestion(val key: String) {
    class Repeat(val repeat: NotificationSpend.Repeat) : PennyQuestion("repeat:${repeat.spend.id}")
    class CardCheck(val spend: SeenSpendEntity) : PennyQuestion("check:${spend.id}")
    class NewBill(val rule: com.spendroid.domain.RecurringRule) : PennyQuestion("bill:${PennyBills.id(rule)}")
    class WhichAccount(val spend: SeenSpendEntity) : PennyQuestion("account:${spend.id}")
    class NotReported(val spend: SeenSpendEntity) : PennyQuestion("unreported:${spend.id}")
    class Vanished(val question: DuplicateCheck.Question) : PennyQuestion("vanished:${question.vanished.accountId}:${question.vanished.transactionId}")
}

/** Every question waiting, most pressing first: the ones that change today's figure lead. */
internal fun pennyQuestions(
    state: RootUiState,
    now: Long = System.currentTimeMillis(),
    /** New bills to ask about: from [PennyBills.toAsk]. */
    bills: List<com.spendroid.domain.RecurringRule> = emptyList(),
): List<PennyQuestion> {
    // A tiny payment is asked about as a possible card check first; which account can wait.
    val checks = NotificationSpend.cardChecks(state.seenSpends)
    val asked = checks.map { it.id }.toSet()
    return NotificationSpend.possibleRepeats(state.seenSpends).map { PennyQuestion.Repeat(it) } +
        checks.sortedByDescending { it.seenAt }.map { PennyQuestion.CardCheck(it) } +
        bills.map { PennyQuestion.NewBill(it) } +
        NotificationSpend.unanswered(state.seenSpends).filter { it.id !in asked }.sortedByDescending { it.seenAt }.map { PennyQuestion.WhichAccount(it) } +
        NotificationSpend.unconfirmed(state.seenSpends, now, state.manualCoverage).filter { it.id !in asked }.map { PennyQuestion.NotReported(it) } +
        state.duplicateQuestions.map { PennyQuestion.Vanished(it) }
}

/** What answering does: the same calls the cards made. */
internal class PennyAnswers(
    val onRepeat: (spendId: String, twinId: String, once: Boolean) -> Unit = { _, _, _ -> },
    val onAssign: (spendId: String, accountId: String) -> Unit = { _, _ -> },
    val onKeepSeen: (String) -> Unit = {},
    val onDismissSeen: (String) -> Unit = {},
    val onSame: (DuplicateCheck.Question) -> Unit = {},
    val onKeepVanished: (DuplicateCheck.Question) -> Unit = {},
    val onRemoveVanished: (DuplicateCheck.Question) -> Unit = {},
    val onCardCheck: (spendId: String, check: Boolean) -> Unit = { _, _ -> },
    /** "A regular bill, or just spending?" */
    val onBill: (rule: com.spendroid.domain.RecurringRule, isBill: Boolean) -> Unit = { _, _ -> },
)

/**
 * "Ask me later": the sheet doesn't open by itself again for six hours. A question stays on the
 * strip on Home and Spending meanwhile, and tapping Penny's notification opens it at once.
 */
internal object PennyAsk {
    private const val PREFS = "penny_ask"
    private const val LATER_UNTIL = "laterUntil"
    const val LATER_MS = 6 * 60 * 60 * 1000L

    fun due(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        now >= context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LATER_UNTIL, 0L)

    fun later(context: Context, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(LATER_UNTIL, now + LATER_MS).apply()
    }
}

/** Penny's answer said back, with the way to change it where there is one. */
private class Noted(val text: String, val change: Pair<String, () -> Unit>? = null)

/** The sheet over the app: Penny, large, asking each question in turn. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PennyQuestionSheet(
    questions: List<PennyQuestion>,
    accounts: List<AccountEntity>,
    sources: List<NotificationSpend.Source>,
    answers: PennyAnswers,
    onLater: () -> Unit,
    onDone: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onLater, sheetState = sheet, containerColor = Charcoal.Panel) {
        PennyQuestionBody(questions, accounts, sources, answers, onLater, onDone)
    }
}

/** What the sheet holds, apart from the sheet: for screenshots too. */
@Composable
internal fun PennyQuestionBody(
    questions: List<PennyQuestion>,
    accounts: List<AccountEntity>,
    sources: List<NotificationSpend.Source>,
    answers: PennyAnswers,
    onLater: () -> Unit,
    onDone: () -> Unit,
    /** For screenshots: open on an answer already given. */
    startNoted: String? = null,
) {
    // Answered in this sheet: kept out while the change reaches the list, and counted for "1 of 3".
    val answered = remember { mutableStateOf(setOf<String>()) }
    var noted by remember { mutableStateOf(startNoted?.let { Noted(it, "Change: it was twice" to {}) }) }
    val waiting = questions.filter { it.key !in answered.value }
    val current = waiting.firstOrNull()
    LaunchedEffect(noted == null, current == null) { if (noted == null && current == null) onDone() }

    fun answer(q: PennyQuestion, said: Noted) {
        answered.value = answered.value + q.key
        noted = said
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 22.dp).padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val n = noted
        if (n != null) {
            NotedPage(n, onDone = { noted = null })
            return@Column
        }
        val q = current ?: return@Column
        val total = answered.value.size + waiting.size
        Box(Modifier.fillMaxWidth()) {
            if (total > 1) {
                Text(
                    "${answered.value.size + 1} of $total",
                    color = Charcoal.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                key(q.key) { Droid(DroidNow.face, 112.dp, moment = DroidMoment.QUESTION) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("PENNY HAS A QUESTION", color = Charcoal.Warn, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(10.dp))
        when (q) {
            is PennyQuestion.Repeat -> RepeatQuestion(q, answers) { answer(q, it) }
            is PennyQuestion.CardCheck -> CardCheckQuestion(q.spend, answers) { answer(q, it) }
            is PennyQuestion.NewBill -> BillQuestion(q.rule, answers) { answer(q, it) }
            is PennyQuestion.WhichAccount -> AccountQuestion(q.spend, accounts, sources, answers) { answer(q, it) }
            is PennyQuestion.NotReported -> UnreportedQuestion(q.spend, answers) { answer(q, it) }
            is PennyQuestion.Vanished -> VanishedQuestion(q.question, accounts, answers) { answer(q, it) }
        }
        Text(
            "Ask me later",
            color = Charcoal.Muted, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onLater).padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun Question(text: String, detail: String) {
    Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, textAlign = TextAlign.Center, lineHeight = 26.sp)
    Spacer(Modifier.height(6.dp))
    Text(detail, color = Charcoal.Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(18.dp))
}

private fun whenSaid(millis: Long): String {
    val day = NotificationSpend.dateOf(millis)
    return when (day) {
        LocalDate.now() -> "today"
        LocalDate.now().minusDays(1) -> "yesterday"
        else -> "on ${day.format(DAY)}"
    }
}

private fun timeOf(millis: Long): String = java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(TIME)

private fun shopOf(spend: SeenSpendEntity): String = com.spendroid.work.SpendQuestions.shopName(spend.merchant) ?: spend.merchant

@Composable
private fun RepeatQuestion(q: PennyQuestion.Repeat, answers: PennyAnswers, said: (Noted) -> Unit) {
    val spend = q.repeat.spend
    val twin = q.repeat.twin
    val money = formatMoney(-spend.amountMinor, spend.currency)
    val shop = shopOf(spend)
    val (first, second) = listOf(twin.seenAt, spend.seenAt).sorted()
    Question(
        "Did you pay $money at $shop once or twice ${whenSaid(spend.seenAt)}?",
        "I saw it at ${timeOf(first)} and again at ${timeOf(second)}. Both are counted until you tell me.",
    )
    fun noted(once: Boolean): Noted = Noted(
        if (once) "Counted once: $money at $shop. I've removed the extra." else "Counted twice: both payments at $shop stay.",
        (if (once) "Change: it was twice" else "Change: it was once") to {
            answers.onRepeat(spend.id, twin.id, !once)
            said(noted(!once))
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BigButton("Once, remove the extra", null, { answers.onRepeat(spend.id, twin.id, true); said(noted(true)) }, Modifier.fillMaxWidth(), colour = Charcoal.Warn, textColour = Color(0xFF111111))
        BigButton("Twice, keep both", null, { answers.onRepeat(spend.id, twin.id, false); said(noted(false)) }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun CardCheckQuestion(spend: SeenSpendEntity, answers: PennyAnswers, said: (Noted) -> Unit) {
    // "1p", as it's said, rather than "£0.01".
    val money = if (spend.currency == "GBP") "${-spend.amountMinor}p" else formatMoney(-spend.amountMinor, spend.currency)
    val shop = shopOf(spend)
    Question(
        "Did you really pay $money at $shop ${whenSaid(spend.seenAt)}, or was it a card check?",
        "Shops and banks sometimes take a penny or two to check a card works, then give it straight back. It's counted until you tell me.",
    )
    fun noted(check: Boolean): Noted = Noted(
        if (check) "Not counted: $money at $shop was a card check." else "Counted: $money at $shop was a real payment.",
        (if (check) "Change: it was a real payment" else "Change: it was a card check") to {
            answers.onCardCheck(spend.id, !check)
            said(noted(!check))
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BigButton("A card check, don't count it", null, { answers.onCardCheck(spend.id, true); said(noted(true)) }, Modifier.fillMaxWidth(), colour = Charcoal.Warn, textColour = Color(0xFF111111))
        BigButton("A real payment, count it", null, { answers.onCardCheck(spend.id, false); said(noted(false)) }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun BillQuestion(rule: com.spendroid.domain.RecurringRule, answers: PennyAnswers, said: (Noted) -> Unit) {
    val money = formatMoney(kotlin.math.abs(rule.amountMinor), rule.currency)
    val payee = com.spendroid.work.SpendQuestions.shopName(rule.payee) ?: rule.payee.tidyPayee().ifBlank { rule.payee }
    Question(
        "Is $payee a regular bill?",
        "I've seen $money there ${PennyBills.often(rule)}. If it's a bill, I'll plan for it before each payday; if not, it's just spending. I'm planning for it until you tell me.",
    )
    fun noted(isBill: Boolean): Noted = Noted(
        if (isBill) "Noted: $payee is a regular bill. I'll plan for it." else "Noted: $payee is just spending, not a bill.",
        (if (isBill) "Change: it's just spending" else "Change: it's a regular bill") to {
            answers.onBill(rule, !isBill)
            said(noted(!isBill))
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BigButton("Yes, a regular bill", null, { answers.onBill(rule, true); said(noted(true)) }, Modifier.fillMaxWidth(), colour = Charcoal.Warn, textColour = Color(0xFF111111))
        BigButton("No, just spending", null, { answers.onBill(rule, false); said(noted(false)) }, Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountQuestion(spend: SeenSpendEntity, accounts: List<AccountEntity>, sources: List<NotificationSpend.Source>, answers: PennyAnswers, said: (Noted) -> Unit) {
    val money = formatMoney(-spend.amountMinor, spend.currency)
    val shop = shopOf(spend)
    // The accounts it could be: the ones its app covers, or the Wallet cards.
    val choices = NotificationSpend.candidatesFor(spend.source, sources, accounts)?.takeIf { it.isNotEmpty() }
        ?: accounts.filter { it.walletLinked }.ifEmpty { accounts }
    Question(
        "You paid $money at $shop ${whenSaid(spend.seenAt)}. Which account was it from?",
        if (spend.provisionalAccountId != null) "It's counted in your spending already - I just need to know where it came from."
        else "I saw it in a notification. I won't count it until you tell me.",
    )
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEach { account ->
            ColourPill(account.label, colourOf(account, accounts), {
                answers.onAssign(spend.id, account.id)
                said(Noted("Noted: $money at $shop came from ${account.label}. You can change it on the payment's details."))
            })
        }
        ColourPill("Ignore", Charcoal.PanelHigh, {
            answers.onDismissSeen(spend.id)
            said(Noted("Ignored: $money at $shop isn't counted."))
        }, Color(0xFFAAB0BB))
    }
}

@Composable
private fun UnreportedQuestion(spend: SeenSpendEntity, answers: PennyAnswers, said: (Noted) -> Unit) {
    val money = formatMoney(-spend.amountMinor, spend.currency)
    val shop = shopOf(spend)
    Question(
        "I saw $money at $shop ${whenSaid(spend.seenAt)}, but your bank still hasn't reported it. Was it cancelled?",
        "It's counted until you tell me.",
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BigButton("Yes, remove it", null, {
            answers.onDismissSeen(spend.id)
            said(Noted("Removed: $money at $shop isn't counted any more."))
        }, Modifier.fillMaxWidth(), colour = Charcoal.Warn, textColour = Color(0xFF111111))
        BigButton("No, keep counting it", null, {
            answers.onKeepSeen(spend.id)
            said(Noted("Kept: I'll keep counting $money at $shop."))
        }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun VanishedQuestion(q: DuplicateCheck.Question, accounts: List<AccountEntity>, answers: PennyAnswers, said: (Noted) -> Unit) {
    val bank = accounts.firstOrNull { it.id == q.vanished.accountId }?.label ?: "Your bank"
    val money = formatMoney(-q.vanished.amountMinor, q.vanished.currency)
    val payee = q.vanished.payee.tidyPayee().ifBlank { "a payee" }
    val candidate = q.candidate
    if (candidate != null) {
        Question(
            "$bank stopped listing one $money payment and listed another. Are they the same one?",
            "The new one isn't counted until you tell me.",
        )
        QuestionRow(q.vanished, "No longer listed", struck = true)
        Spacer(Modifier.height(6.dp))
        QuestionRow(candidate, "New · not counted yet")
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Yes, the same", null, {
                answers.onSame(q)
                said(Noted("Noted: it's one payment of $money, counted once."))
            }, Modifier.fillMaxWidth(), colour = Charcoal.Warn, textColour = Color(0xFF111111))
            BigButton("No, count both", null, {
                answers.onKeepVanished(q)
                said(Noted("Noted: they're two payments, and both are counted."))
            }, Modifier.fillMaxWidth())
        }
    } else {
        Question(
            "$bank no longer lists $money at $payee. Was it refunded or cancelled?",
            "It's counted until you tell me.",
        )
        QuestionRow(q.vanished, bank)
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Yes, remove it", null, {
                answers.onRemoveVanished(q)
                said(Noted("Removed: $money at $payee isn't counted any more."))
            }, Modifier.fillMaxWidth(), colour = Charcoal.Warn, textColour = Color(0xFF111111))
            BigButton("No, keep it", null, {
                answers.onKeepVanished(q)
                said(Noted("Kept: $money at $payee stays counted."))
            }, Modifier.fillMaxWidth())
        }
    }
}

/** The answer said back, Penny nodding; it moves on by itself after a moment. */
@Composable
private fun NotedPage(noted: Noted, onDone: () -> Unit) {
    val progress = remember(noted) { Animatable(0f) }
    LaunchedEffect(noted) {
        progress.animateTo(1f, tween(NOTED_MS, easing = LinearEasing))
        onDone()
    }
    key(noted) { Droid(DroidNow.face, 112.dp, moment = DroidMoment.NOTED) }
    Spacer(Modifier.height(8.dp))
    Text("PENNY'S NOTED IT", color = Charcoal.Good, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.5.sp)
    Spacer(Modifier.height(10.dp))
    Text(noted.text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center, lineHeight = 24.sp)
    Spacer(Modifier.height(18.dp))
    noted.change?.let { (label, change) ->
        // Changing says the new answer back in turn, so it can be changed again.
        BigButton(label, null, change, Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
    }
    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Charcoal.Line)) {
        Box(Modifier.fillMaxWidth(progress.value).height(3.dp).background(Charcoal.Good))
    }
    Text(
        "Done",
        color = Charcoal.Muted, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onDone).padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

private const val NOTED_MS = 4_000

/** Questions put off: a slim strip at the top of Home and Spending, the way back to them. */
@Composable
internal fun PennyQuestionsStrip(count: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF3A2E14)).clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (count == 1) "1 question from Penny" else "$count questions from Penny",
            color = Charcoal.Warn, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Text("Answer ›", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
    }
}

/** One of the two rows being compared, as a small card of its own. */
@Composable
internal fun QuestionRow(tx: TransactionEntity, note: String, struck: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Charcoal.PanelHigh).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtTile(payeeColour(tx.payee), size = 36.dp) { Monogram(tx.payee, 36.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                tx.payee.tidyPayee().ifBlank { "Unknown" },
                style = MaterialTheme.typography.titleSmall,
                color = if (struck) Charcoal.Muted else Color.White,
                textDecoration = if (struck) TextDecoration.LineThrough else null,
                maxLines = 1,
            )
            val day = runCatching { LocalDate.parse(tx.bookingDate).format(DAY) }.getOrDefault(tx.bookingDate)
            Text("$note · $day", style = MaterialTheme.typography.labelSmall, color = Charcoal.Muted)
        }
        Text(formatMoney(tx.amountMinor, tx.currency), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black)
    }
}
