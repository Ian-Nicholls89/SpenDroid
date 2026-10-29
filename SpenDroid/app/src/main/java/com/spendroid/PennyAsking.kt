package com.spendroid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spendroid.ui.RootViewModel

/**
 * Penny asks as the app opens - each time it comes back to the front - with nothing else over it,
 * unless "Ask me later" was said in the last six hours. Her notification asks at once. [clear]:
 * nothing else is over the app.
 */
@Composable
internal fun PennyAsking(
    state: com.spendroid.ui.RootUiState,
    viewModel: RootViewModel,
    questions: List<com.spendroid.ui.PennyQuestion>,
    askingState: MutableState<Boolean>,
    clear: Boolean,
    askPenny: MutableState<Boolean>,
) {
    var asking by askingState
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var opened by remember { mutableIntStateOf(0) }
    var offered by remember { mutableIntStateOf(-1) }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val watch = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) opened++
        }
        lifecycle.addObserver(watch)
        onDispose { lifecycle.removeObserver(watch) }
    }
    LaunchedEffect(opened, questions.isNotEmpty(), clear, askPenny.value, state.budget == null) {
        // Her notification tapped once the question was already answered: nothing to
        // ask, and nothing left waiting to open the sheet at the next question.
        if (askPenny.value && questions.isEmpty() && state.budget != null) askPenny.value = false
        if (questions.isEmpty() || !clear) return@LaunchedEffect
        if (askPenny.value) {
            asking = true
            askPenny.value = false
        } else if (offered != opened && com.spendroid.ui.PennyAsk.due(context)) {
            asking = true
            offered = opened
        }
    }
    // Open until it closes itself: answering the last question empties the list at once,
    // and the "noted" page - and its Change - must still be seen.
    if (asking) {
        com.spendroid.ui.PennyQuestionSheet(
            questions = questions,
            accounts = state.accounts,
            sources = viewModel.spendSources.collectAsStateWithLifecycle().value,
            answers = com.spendroid.ui.PennyAnswers(
                onRepeat = viewModel::answerRepeat,
                onAssign = { id, account -> viewModel.assignSeenSpend(id, account, always = false) },
                onKeepSeen = viewModel::keepSeenSpend,
                onDismissSeen = viewModel::dismissSeenSpend,
                onSame = viewModel::confirmDuplicate,
                onKeepVanished = viewModel::keepVanished,
                onRemoveVanished = viewModel::removeVanished,
                onCardCheck = viewModel::answerCardCheck,
                onBill = { rule, isBill ->
                    com.spendroid.ui.PennyBills.answer(context, rule, isBill)
                    viewModel.setRuleIgnored(rule.key, !isBill)
                },
                onRenewed = { id, payment, ends ->
                    com.spendroid.data.RenewalStore(context).renewed(id, payment, ends)
                    viewModel.reloadLocal()
                },
                onNotRenewed = { id, key ->
                    com.spendroid.data.RenewalStore(context).notIt(id, key)
                    viewModel.reloadLocal()
                },
            ),
            onLater = {
                com.spendroid.ui.PennyAsk.later(context)
                asking = false
            },
            onDone = { asking = false },
        )
    }
}
