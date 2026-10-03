package com.spendroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import com.spendroid.ui.theme.Motion
import com.spendroid.ui.theme.entrance
import kotlinx.coroutines.delay
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.spendroid.domain.toRecurringRule
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.draw.clip
import com.spendroid.ui.theme.Charcoal
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.spendroid.data.db.CategoryRuleEntity
import com.spendroid.data.db.TransactionEntity
import com.spendroid.domain.Category
import com.spendroid.domain.CategoryEngine
import com.spendroid.domain.RecurringAnalyzer
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val DISPLAY_TRANSACTION_LIMIT = 200

/** Minimum comfortable touch target; rows and controls should not fall below it. */
private val MinTouchTarget = 48.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    state: RootUiState,
    onRefresh: () -> Unit,
    onToggleRecurring: () -> Unit,
    onToggleInternal: () -> Unit,
    onQueryChange: (String) -> Unit,
    onAccountFilter: (String?) -> Unit,
    onOverrideCategory: (TransactionEntity, Category?) -> Unit,
    onAlwaysCategorise: (TransactionEntity, Category) -> Unit,
    onMarkTransfer: (TransactionEntity, Boolean) -> Unit,
    onMarkTransferGroup: (TransactionEntity, Boolean) -> Unit = { _, _ -> },
    onRestoreCategory: (TransactionEntity, String?) -> Unit = { _, _ -> },
    onMarkCardPayment: (TransactionEntity, Boolean) -> Unit,
    onCategoryFilter: (Category?) -> Unit,
    onTreatAsBill: ((TransactionEntity, com.spendroid.domain.Cadence) -> Unit)? = null,
    /** Only what is still pending: the Pending tab. */
    pendingOnly: Boolean = false,
) {
    var selected by remember { mutableStateOf<TransactionEntity?>(null) }
    // The last swipe, kept long enough to undo it or make it stick for the payee.
    var sorted by remember { mutableStateOf<SortedNote?>(null) }
    LaunchedEffect(sorted) {
        if (sorted != null) {
            delay(6_000)
            sorted = null
        }
    }
    val cardPaymentKeys = state.budget?.cardPaymentKeys.orEmpty()
    val cardAccountIds = state.budget?.creditCardAccountIds.orEmpty()
    fun suggestionsFor(tx: TransactionEntity) =
        CategoryEngine.suggest(tx, state.categoryRules, state.categoryHistory, cardPaymentKeys, cardAccountIds)

    val accountNames = remember(state.accounts) { state.accounts.associate { it.id to it.label } }
    val vanishedKeys = remember(state.duplicateQuestions) {
        state.duplicateQuestions.mapTo(HashSet()) { "${it.vanished.accountId}|${it.vanished.transactionId}" }
    }

    val visible = remember(
        state.transactions,
        state.showRecurringOnly,
        state.showInternalTransfers,
        state.transactionQuery,
        state.accountFilter,
        state.budget?.confirmedSettlementKeys,
        state.categoryFilter,
    ) {
        visibleTransactions(
            transactions = state.transactions,
            filters = ListFilters(
                recurringOnly = state.showRecurringOnly,
                transfersOnly = state.showInternalTransfers,
                query = state.transactionQuery,
                accountFilter = state.accountFilter,
                categoryFilter = state.categoryFilter,
            ),
            categoryRules = state.categoryRules,
            settlements = state.budget?.confirmedSettlementKeys.orEmpty(),
            payers = state.budget?.cardPayerKeys.orEmpty(),
            cardPaymentKeys = state.budget?.cardPaymentKeys.orEmpty(),
            creditCardAccountIds = state.budget?.creditCardAccountIds.orEmpty(),
        )
            .filter { !pendingOnly || it.isPending }
            .take(DISPLAY_TRANSACTION_LIMIT)
    }

    // Grouped by day, under headings in the accent, as nzb360 groups what is coming up.
    val months = remember(visible, state.categoryRules) {
        visible
            .groupBy { it.bookingDate }
            .toList()
            .sortedByDescending { it.first }
    }

    // The first screenful arrives in order the first time the list is shown; after that rows
    // are simply there, so scrolling never replays anything.
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(900)
        arrived = true
    }
    val arrivalOrder = remember(months) {
        months.flatMap { it.second }
            .take(14)
            .withIndex()
            .associate { (i, tx) -> "${tx.accountId}|${tx.transactionId}" to i }
    }

    PullToRefreshBox(
        isRefreshing = state.syncing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        // Room at the foot for the floating button, so it never sits over the last row.
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                OutlinedTextField(
                    value = state.transactionQuery,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Search ${state.transactions.size} transactions") },
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Charcoal.PanelHigh,
                        focusedContainerColor = Charcoal.PanelHigh,
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.transactionQuery.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (state.accounts.size > 1) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.accountFilter == null,
                            onClick = { onAccountFilter(null) },
                            label = { Text("All accounts") },
                        )
                        state.accounts.forEach { account ->
                            FilterChip(
                                selected = state.accountFilter == account.id,
                                onClick = {
                                    onAccountFilter(
                                        if (state.accountFilter == account.id) null else account.id,
                                    )
                                },
                                label = { Text(account.label, maxLines = 1) },
                            )
                        }
                    }
                }
            }

            item {
                // Categories that actually occur, commonest first, so the row is not a list
                // of every category the app knows about.
                // Classified exactly as the filter classifies, or a chip could be missing
                // for a category the filter would match - card bills in particular, which
                // only look like one once the payment keys are taken into account.
                val present = remember(
                    state.transactions,
                    state.categoryRules,
                    state.budget?.cardPaymentKeys,
                ) {
                    state.transactions
                        .filter { it.amountMinor < 0 }
                        .groupingBy {
                            CategoryEngine.classify(
                                it,
                                state.categoryRules,
                                state.budget?.cardPaymentKeys.orEmpty(),
                                state.budget?.creditCardAccountIds.orEmpty(),
                            )
                        }
                        .eachCount()
                        .entries
                        .sortedByDescending { it.value }
                        .map { it.key }
                }
                // Matched to the account row above, so the two read as one control.
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = state.categoryFilter == null,
                            onClick = { onCategoryFilter(null) },
                            label = { Text("All categories") },
                        )
                    }
                    items(present, key = { it.name }) { category ->
                        FilterChip(
                            selected = state.categoryFilter == category,
                            onClick = {
                                onCategoryFilter(
                                    if (state.categoryFilter == category) null else category,
                                )
                            },
                            label = { Text(category.label, maxLines = 1) },
                        )
                    }
                }
            }


            if (visible.isEmpty()) {
                item {
                    NoMatches(
                        hasQuery = state.transactionQuery.isNotEmpty(),
                        onClearFilters = {
                            onQueryChange("")
                            onAccountFilter(null)
                            if (state.showRecurringOnly) onToggleRecurring()
                            if (state.showInternalTransfers) onToggleInternal()
                        },
                    )
                }
            } else {
                // A list you can narrow but not add up still leaves you doing the
                // arithmetic, which is most of the reason to filter it in the first place.
                val narrowed = state.categoryFilter != null || state.accountFilter != null
                if (narrowed || state.transactions.size > DISPLAY_TRANSACTION_LIMIT) {
                    item {
                        val spent = visible.filter { it.amountMinor < 0 }.sumOf { -it.amountMinor }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (narrowed) {
                                    "${visible.size} of ${state.transactions.size} transactions"
                                } else {
                                    "Showing the latest ${visible.size} of ${state.transactions.size} transactions."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (narrowed && spent > 0L) {
                                Text(
                                    "−${formatMoney(spent, "GBP")}",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontFeatureSettings = "tnum",
                                    ),
                                )
                            }
                        }
                    }
                }

                months.forEach { (monthKey, rows) ->
                    item(key = "header-$monthKey") {
                        Box(modifier = Modifier.animateItem()) { DayHeader(monthKey, rows, state.budget?.asOf ?: java.time.LocalDate.now()) }
                    }
                    items(rows, key = { "${it.accountId}|${it.transactionId}" }) { tx ->
                        val rowKey = "${tx.accountId}|${tx.transactionId}"
                        // Re-sorted or re-filtered rows glide to their new place rather than
                        // jumping, which is how a re-categorised row can be followed.
                        Column(
                            modifier = Modifier
                                .animateItem()
                                .entrance(if (arrived) null else arrivalOrder[rowKey]),
                        ) {
                            SwipeToSort(
                                suggestions = remember(tx, state.categoryRules, state.categoryHistory) {
                                    suggestionsFor(tx).take(2)
                                },
                                onSort = { category ->
                                    sorted = SortedNote(tx, tx.categoryOverride, category)
                                    onOverrideCategory(tx, category)
                                },
                            ) {
                                TransactionRow(
                                    tx = tx,
                                    userRules = state.categoryRules,
                                    // Redundant once the list is filtered to a single account.
                                    accountName = if (state.accountFilter == null) {
                                        accountNames[tx.accountId]
                                    } else {
                                        null
                                    },
                                    cardPaymentKeys = state.budget?.cardPaymentKeys.orEmpty(),
                                    creditCardAccountIds =
                                        state.budget?.creditCardAccountIds.orEmpty(),
                                    onClick = { selected = tx },
                                    isNew = rowKey in state.newTransactionKeys,
                                    held = rowKey in state.heldDuplicateKeys,
                                    note = when (rowKey) {
                                        in state.heldDuplicateKeys -> "Possible duplicate"
                                        in vanishedKeys -> "No longer listed"
                                        else -> null
                                    },
                                )
                            }
                        }
                    }
                }
            }

            state.error?.let { error ->
                item {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        // nzb360's floating button: refresh, with arrows either side stepping between accounts
        // as nzb360 steps between services. Hidden while the undo bar is up, which sits there.
        if (sorted == null) {
            val order = listOf<String?>(null) + state.accounts.map { it.id }
            val at = order.indexOf(state.accountFilter).coerceAtLeast(0)
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (order.size > 1) {
                    IconButton(onClick = { onAccountFilter(order[(at - 1 + order.size) % order.size]) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous account", tint = Charcoal.Muted)
                    }
                }
                androidx.compose.material3.FloatingActionButton(
                    onClick = onRefresh,
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color(0xFF111111),
                ) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                if (order.size > 1) {
                    IconButton(onClick = { onAccountFilter(order[(at + 1) % order.size]) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next account", tint = Charcoal.Muted)
                    }
                }
            }
        }

        // Undo, or make it stick: a swipe changes only the one transaction.
        AnimatedVisibility(
            visible = sorted != null,
            enter = slideInVertically(Motion.arrive()) { it } + fadeIn(Motion.arrive()),
            exit = slideOutVertically(Motion.change(Motion.SHORT)) { it } + fadeOut(Motion.change(Motion.SHORT)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
        ) {
            val note = sorted
            if (note != null) {
                SortedBar(
                    note = note,
                    onUndo = {
                        onRestoreCategory(note.tx, note.previousOverride)
                        sorted = null
                    },
                    onAlways = {
                        onAlwaysCategorise(note.tx, note.category)
                        sorted = null
                    },
                )
            }
        }
    }

    selected?.let { tx ->
        val group = remember(tx) { RecurringAnalyzer.groupKey(tx) }
        val similar = remember(tx, state.transactions) {
            state.transactions.count { RecurringAnalyzer.groupKey(it) == group }
        }
        TransactionDetailSheet(
            transaction = tx,
            userRules = state.categoryRules,
            cardPaymentKeys = state.budget?.cardPaymentKeys.orEmpty(),
            creditCardAccountIds = state.budget?.creditCardAccountIds.orEmpty(),
            onDismiss = { selected = null },
            onOverrideCategory = { t, c -> onOverrideCategory(t, c); selected = null },
            onAlwaysCategorise = { t, c -> onAlwaysCategorise(t, c); selected = null },
            onMarkTransfer = { t, v -> onMarkTransfer(t, v); selected = null },
            similarCount = similar,
            suggestions = remember(tx) { suggestionsFor(tx).take(2) },
            accountLastSynced = state.accounts.firstOrNull { it.id == tx.accountId }?.lastSynced,
            groupMarkedAsTransfer = group in state.transferGroups,
            onMarkTransferGroup = { t, v -> onMarkTransferGroup(t, v); selected = null },
            onMarkCardPayment = { t, v -> onMarkCardPayment(t, v); selected = null },
            billNote = remember(tx, state.rules, state.manualRules, state.budget) { billNote(tx, state) },
            billGuess = remember(tx, state.transactions) { cadenceGuess(tx, state.transactions) },
            onTreatAsBill = onTreatAsBill?.let { treat -> { t: TransactionEntity, c: com.spendroid.domain.Cadence -> treat(t, c); selected = null } },
            accountLabel = accountNames[tx.accountId],
            payeeCycle = remember(tx, state.transactions, state.budget) { payeeCycle(tx, state) },
        )
    }
}

/**
 * Whether [tx] is a regular bill, and where it is paid from, said plainly: "Regular bill ·
 * counted in the Nectar Card bill". Matched loosely, as a pending payment is often named shorter
 * than the bill it belongs to. Null when it is not one.
 */
private fun billNote(tx: TransactionEntity, state: RootUiState): String? {
    if (tx.amountMinor >= 0) return null
    val rules = state.rules.filter { it.key !in state.ignoredRules } + state.manualRules.mapNotNull { it.toRecurringRule() }
    val rule = rules.firstOrNull { it.direction == com.spendroid.domain.Direction.OUT && com.spendroid.domain.RecurringAnalyzer.looselyMatches(it, tx) }
        ?: return null
    val how = when (rule.cadence) {
        com.spendroid.domain.Cadence.WEEKLY -> "Weekly"
        com.spendroid.domain.Cadence.FORTNIGHTLY -> "Fortnightly"
        com.spendroid.domain.Cadence.QUARTERLY -> "Quarterly"
        com.spendroid.domain.Cadence.ANNUAL -> "Yearly"
        else -> "Monthly"
    }
    val payer = rule.paidFrom?.let { id -> state.accounts.firstOrNull { it.id == id } }
    val where = when {
        payer == null -> null
        payer.accountType == com.spendroid.data.db.AccountType.CREDIT_CARD && state.cardTiming == com.spendroid.domain.CardTiming.AT_BILL ->
            "counted in the ${payer.label} bill"
        else -> "from ${payer.label}"
    }
    return listOfNotNull("$how regular bill", where).joinToString(" · ")
}

/** How often a payment like [tx] has come, from its history: yearly, quarterly, or monthly. */
private fun cadenceGuess(tx: TransactionEntity, all: List<TransactionEntity>): com.spendroid.domain.Cadence {
    fun firstWord(s: String) = s.lowercase().split(Regex("[^a-z0-9.]+")).firstOrNull { it.length >= 3 }
    val word = firstWord(tx.payee) ?: return com.spendroid.domain.Cadence.MONTHLY
    val dates = all.filter { it.amountMinor < 0 && firstWord(it.payee) == word }
        .mapNotNull { com.spendroid.domain.RecurringAnalyzer.parseBookingDate(it.bookingDate) }
        .distinct()
        .sorted()
    if (dates.size < 2) return com.spendroid.domain.Cadence.MONTHLY
    val gap = dates.zipWithNext { a, b -> java.time.temporal.ChronoUnit.DAYS.between(a, b) }.sorted().let { it[it.size / 2] }
    return when {
        gap >= 300 -> com.spendroid.domain.Cadence.ANNUAL
        gap >= 75 -> com.spendroid.domain.Cadence.QUARTERLY
        else -> com.spendroid.domain.Cadence.MONTHLY
    }
}

/** "Today", "Yesterday", else "Mon 28 Sep", with what went out that day on the right. */
@Composable
private fun DayHeader(date: String, rows: List<TransactionEntity>, today: java.time.LocalDate) {
    val day = runCatching { java.time.LocalDate.parse(date) }.getOrNull()
    val label = when (day) {
        null -> date
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "EEE d MMM" else "EEE d MMM yyyy", Locale.getDefault()))
    }
    val spent = rows.filter { it.amountMinor < 0 }.sumOf { -it.amountMinor }
    Row(Modifier.fillMaxWidth().padding(end = 18.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        DayHeading(label, Modifier.weight(1f))
        if (spent > 0L) Text(formatMoney(spent, rows.first().currency), style = MaterialTheme.typography.labelMedium, color = Charcoal.Muted)
    }
}

/**
 * How [tx]'s payee is going this pay cycle against the cycles before: the middle of the last
 * three, cycle-length windows back from this one's start.
 */
private fun payeeCycle(tx: TransactionEntity, state: RootUiState): PayeeCycle? {
    val budget = state.budget ?: return null
    if (tx.amountMinor >= 0) return null
    val payee = tx.payee.tidyPayee().lowercase()
    if (payee.isBlank()) return null
    val start = budget.cycleStart
    val length = budget.cycleEnd?.let { java.time.temporal.ChronoUnit.DAYS.between(start, it) + 1 } ?: 30L
    val mine = state.transactions.filter { it.amountMinor < 0 && it.payee.tidyPayee().lowercase() == payee }
    fun between(from: java.time.LocalDate, until: java.time.LocalDate) = mine.filter { t ->
        com.spendroid.domain.RecurringAnalyzer.parseBookingDate(t.bookingDate)?.let { !it.isBefore(from) && it.isBefore(until) } == true
    }
    val now = between(start, start.plusDays(length))
    if (now.isEmpty()) return null
    val past = (1..3).map { k -> between(start.minusDays(length * k), start.minusDays(length * (k - 1))).sumOf { -it.amountMinor } }
        .filter { it > 0L }
        .sorted()
    return PayeeCycle(now.sumOf { -it.amountMinor }, now.size, past.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] })
}

@Composable
private fun MonthHeader(monthKey: String, rows: List<TransactionEntity>) {
    val label = remember(monthKey) {
        runCatching {
            YearMonth.parse(monthKey).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
        }.getOrDefault(monthKey)
    }
    val spent = rows.filter { it.amountMinor < 0 }.sumOf { -it.amountMinor }
    val currency = rows.firstOrNull()?.currency ?: "GBP"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            formatMoney(spent, currency),
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoMatches(hasQuery: Boolean, onClearFilters: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.FilterAltOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasQuery) "Nothing matches that search" else "Nothing matches these filters",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onClearFilters) { Text("Clear filters") }
    }
}

@Composable
private fun TransactionRow(
    tx: TransactionEntity,
    userRules: List<CategoryRuleEntity>,
    accountName: String? = null,
    cardPaymentKeys: Set<String> = emptySet(),
    creditCardAccountIds: Set<String> = emptySet(),
    onClick: () -> Unit,
    isNew: Boolean = false,
    /** Waiting on a "Same transaction?" answer. */
    note: String? = null,
    /** Held out of the budget until then, and greyed to say so. */
    held: Boolean = false,
) {
    val category = CategoryEngine.classify(tx, userRules, cardPaymentKeys, creditCardAccountIds)
    // A row the latest sync brought glows briefly, then settles like the rest.
    var glowing by remember(tx.transactionId) { mutableStateOf(isNew) }
    LaunchedEffect(isNew) {
        if (isNew) {
            glowing = true
            delay(1600)
            glowing = false
        }
    }
    val glow by animateColorAsState(
        if (glowing) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
        Motion.change(Motion.LONG),
        label = "new row",
    )
    // Counted like any other, but it can still change or vanish, so it says so.
    val pendingNote = if (tx.isPending) "Pending" else null
    val visual = category.visual
    val amount = formatMoney(tx.amountMinor, tx.currency)
    val name = tx.payee.tidyPayee().ifBlank { tx.description?.tidyPayee()?.ifBlank { "Unknown" } ?: "Unknown" }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Charcoal.Panel)
            .background(glow)
            .alpha(if (held) 0.5f else 1f)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            // One announcement for the row rather than four disconnected fragments.
            .semantics(mergeDescendants = true) {
                contentDescription = "$name, $category.label, ${tx.bookingDate}, $amount"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryTile(category)
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                buildList {
                    note?.let(::add)
                    add(category.label)
                    accountName?.let(::add)
                    if (tx.categoryOverride != null) add("edited")
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 84.dp)) {
            Text(
                amount,
                // Colour marks the exception, not the rule: an ordinary debit is the most common
                // thing on this screen and does not need the loudest colour on the palette.
                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Black,
                color = if (tx.amountMinor >= 0) InColor else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
            )
            // A word under the amount, as nzb360 puts "Downloaded": what state the row is in.
            val status = when {
                held -> "Check" to Charcoal.Warn
                note != null -> "Not listed" to Charcoal.Warn
                pendingNote != null -> "Pending" to Color(0xFF29B6F6)
                tx.isInternalTransfer -> "Transfer" to Charcoal.Muted
                tx.isRecurring -> "Regular" to Charcoal.Muted
                else -> null
            }
            status?.let { (word, colour) ->
                Text(word, style = MaterialTheme.typography.labelSmall, color = colour, fontWeight = FontWeight.Black)
            }
        }
    }
}

/** The list's narrowing controls: each one, when on, shows less. */
internal data class ListFilters(
    val recurringOnly: Boolean = false,
    val transfersOnly: Boolean = false,
    val query: String = "",
    val accountFilter: String? = null,
    val categoryFilter: Category? = null,
)

/**
 * What the transactions list shows.
 *
 * Transfers between your own accounts are left out of the ordinary list, since they are
 * neither spending nor income. "Transfers only" used to be a switch that put them back
 * alongside everything else - labelled like "Recurring only", which narrows, but doing the
 * opposite, so with a handful among hundreds of rows it looked as though it did nothing.
 * Now it narrows too: on, the list is the transfers and nothing else.
 */
internal fun visibleTransactions(
    transactions: List<TransactionEntity>,
    filters: ListFilters,
    categoryRules: List<CategoryRuleEntity> = emptyList(),
    settlements: Set<String> = emptySet(),
    payers: Set<String> = emptySet(),
    cardPaymentKeys: Set<String> = emptySet(),
    creditCardAccountIds: Set<String> = emptySet(),
): List<TransactionEntity> {
    val query = filters.query.tidyPayee().lowercase()
    return transactions.filter { tx ->
        val id = "${tx.accountId}|${tx.transactionId}"
        // Paying a card posts a credit on the card and a debit on the account that paid.
        // They are one event, and the debit is the half that matters: it is the money
        // actually leaving. The credit only restates it, in green, in a list of spending -
        // so it is dropped unless you are looking at that card's own ledger, which has to
        // balance, or at transfers, which it is one half of.
        val duplicateHalf = id in settlements &&
            filters.accountFilter != tx.accountId &&
            !filters.transfersOnly
        // Pairing may also have flagged the paying debit as a transfer. By shape it is one;
        // by consequence it is spending, so the ordinary list keeps it.
        val realOutflow = id in payers
        val transfer = tx.isInternalTransfer || id in settlements

        !duplicateHalf &&
            (filters.accountFilter == null || tx.accountId == filters.accountFilter) &&
            (
                filters.categoryFilter == null ||
                    CategoryEngine.classify(tx, categoryRules, cardPaymentKeys, creditCardAccountIds) ==
                    filters.categoryFilter
                ) &&
            (!filters.recurringOnly || tx.isRecurring) &&
            (if (filters.transfersOnly) transfer else !tx.isInternalTransfer || realOutflow) &&
            (
                query.isEmpty() ||
                    tx.payee.tidyPayee().lowercase().contains(query) ||
                    tx.description?.tidyPayee()?.lowercase()?.contains(query) == true
                )
    }
}

/** What the last swipe did, so it can be taken back or made to stick. */
private data class SortedNote(
    val tx: TransactionEntity,
    /** The per-transaction category it had before - usually none - so undo restores exactly that. */
    val previousOverride: String?,
    val category: Category,
)

/**
 * Swipe right to file a transaction under the app's best alternative for it, left for the
 * second best. The category shows under the row as you drag, with a tick and a light buzz
 * once letting go will act; short of that the row springs back. Either way the row returns
 * to its place, re-filed, because a swipe here sorts rather than removes.
 */
@Composable
private fun SwipeToSort(
    suggestions: List<Category>,
    onSort: (Category) -> Unit,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // The choices are held still while a swipe is under way. Filing the transaction reloads the
    // list, and the reloaded suggestions no longer include what it was just filed under - so
    // right and left swap. Material calls onDismiss again whenever it is handed a new lambda
    // before the row has settled, and with the pair swapped each call filed it under the other:
    // the row flicked between two categories until it happened to come to rest.
    var held by remember { mutableStateOf(suggestions) }
    if (state.settledValue == SwipeToDismissBoxValue.Settled && !state.isAnimatingOrDragged()) {
        held = suggestions
    }
    val right = held.getOrNull(0)
    val left = held.getOrNull(1)
    // Each swipe acts once, however many times it is reported.
    var handling by remember { mutableStateOf(false) }

    val armed = state.targetValue != SwipeToDismissBoxValue.Settled
    LaunchedEffect(armed) {
        if (armed) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = right != null,
        enableDismissFromEndToStart = left != null,
        onDismiss = { value ->
            if (!handling) {
                handling = true
                val chosen = if (value == SwipeToDismissBoxValue.StartToEnd) right else left
                scope.launch {
                    // Back in place first, then filed: once the row has settled there is nothing
                    // left for a second report to act on.
                    state.reset()
                    chosen?.let(onSort)
                    handling = false
                }
            }
        },
        backgroundContent = {
            val towards = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> right
                SwipeToDismissBoxValue.EndToStart -> left
                else -> null
            }
            if (towards != null) {
                val visual = towards.visual
                val fromStart = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(visual.color.copy(alpha = if (armed) 0.9f else 0.55f))
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (fromStart) Arrangement.Start else Arrangement.End,
                ) {
                    Icon(visual.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        (if (armed) "✓ " else "") + towards.label,
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
    ) {
        // A swipe is out of reach with a screen reader, so the same two choices are offered
        // as actions on the row.
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .semantics {
                    customActions = listOfNotNull(right, left).map { category ->
                        CustomAccessibilityAction("File under ${category.label}") {
                            onSort(category)
                            true
                        }
                    }
                },
        ) { content() }
    }
}

@Composable
private fun SortedBar(note: SortedNote, onUndo: () -> Unit, onAlways: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp)) {
            Text(
                "Filed under ${note.category.label}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onUndo) {
                    Text("Undo", color = MaterialTheme.colorScheme.inversePrimary)
                }
                TextButton(onClick = onAlways) {
                    Text(
                        "Always for ${note.tx.payee.tidyPayee().take(18)}",
                        color = MaterialTheme.colorScheme.inversePrimary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** True while the row is moving, whether under a finger or springing back. */
private fun SwipeToDismissBoxState.isAnimatingOrDragged(): Boolean =
    currentValue != targetValue || progress in 0.001f..0.999f
