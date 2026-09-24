package com.spendroid.domain

import com.spendroid.data.db.CategoryRuleEntity
import com.spendroid.data.db.TransactionEntity

enum class Category(val label: String) {
    GROCERIES("Groceries"),
    TRANSPORT("Transport"),
    BILLS("Bills & Utilities"),
    ENTERTAINMENT("Entertainment"),
    SHOPPING("Shopping"),
    EATING_OUT("Eating out"),
    WORK_LUNCH("Work lunches"),
    CHARITY("Charity"),
    FAMILY("Family"),
    LIFE_EVENTS("Life events"),
    CARD_BILL("Credit card bill"),
    SALARY("Salary"),
    TRANSFERS("Transfers"),
    SAVINGS("Savings"),
    HEALTH("Health"),
    EDUCATION("Education"),
    OTHER("Other"),
}

data class CategoryTotal(
    val category: Category,
    val amountMinor: Long,
    val count: Int,
    val currency: String,
)

object CategoryEngine {

    private val rules: Map<Category, List<String>> = mapOf(
        // First, because a card is named after its issuer: "TESCO BANK CREDIT CARD" hits
        // the groceries list on "tesco", and a card bill is not a shop.
        Category.CARD_BILL to listOf(
            "credit card", "creditcard", "card payment", "card bill",
            "cc payment", "barclaycard", "amex", "american express",
        ),
        // Money put aside for someone else is gone in a way savings are not: the balance
        // is not the user's to spend, and in these cases not visible to the app at all.
        Category.FAMILY to listOf(
            "beanstalk", "junior isa", "jisa", "child trust", "nursery", "childcare",
            "school fees", "tumbletots", "playgroup", "childminder",
        ),
        Category.GROCERIES to listOf(
            "tesco", "sainsbury", "asda", "morrisons", "aldi", "lidl",
            "waitrose", "coop", "co-op", "m&s", "marks and spencer",
            "iceland", "budgens", "ocado", "heron foods", "farmfoods",
            "spar", "local express", "meal deal", "supermarket", "grocery",
        ),
        Category.TRANSPORT to listOf(
            "uber", "tfl", "oyster", "national rail", "trainline", "avanti",
            "lner", "gwr", "southern", "thameslink", "southeastern",
            "bus", "metro", "tram", "cycle", "santander cycle", "bp", "shell",
            "esso", "total", "parking", "fuel", "petrol", "diesel",
            "toll", "congestion", "ulez", "rail", "flight", "ryanair",
            "easyjet", "british airways", "ba ", "eurowings", "logain",
        ),
        Category.BILLS to listOf(
            "direct debit", "standing order", "utility", "electric",
            "gas", "water", "council tax", " broadband", "wifi",
            "sky ", "bt ", "virgin media", "vodafone", "ee ", "three ",
            "o2 ", "giffgaff", "mobile", "phone", "insurance",
            "home insurance", "car insurance", "life insurance",
            "mortgage", "rent", "service charge",
        ),
        // Checked before ENTERTAINMENT, which used to swallow every restaurant.
        Category.EATING_OUT to listOf(
            "restaurant", "takeaway", "take away", "deliveroo", "just eat",
            "uber eats", "mcdonald", "kfc", "burger", "pizza", "nando",
            "greggs", "pret", "costa", "starbucks", "caffe nero", "coffee",
            "cafe", "café", "five guys", "wagamama", "bistro", "brasserie",
            "grill", "diner", "tapas", "sushi", "curry", "kebab", "noodle",
            "fish and chip", "chippy", "bakery", "patisserie", "creperie",
            "pub", "bar", "tavern", "inn", "wetherspoon", "jd wetherspoon",
            "brewdog", "beer", "wine bar",
        ),

        Category.CHARITY to listOf(
            "charity", "donation", "donate", "justgiving", "just giving",
            "gofundme", "crowdfunder", "oxfam", "red cross", "cancer research",
            "rspca", "rspb", "nspcc", "macmillan", "unicef", "wwf",
            "save the children", "comic relief", "air ambulance", "hospice",
            "barnardo", "marie curie", "samaritans", "british heart",
            "guide dogs", "dogs trust", "salvation army", "food bank",
            "foodbank", "lifeboat", "rnli", "wateraid", "amnesty",
        ),

        Category.ENTERTAINMENT to listOf(
            "netflix", "spotify", "disney", "amazon prime", "prime video",
            "apple tv", "now tv", "hulu", "youtube",
            "vue", "odeon", "curzon", "cineworld",
            "gym", "fitness", "leisure", "hobby", "gaming", "steam",
            "cinema", "theatre", "concert", "gig", "festival",
        ),
        Category.SHOPPING to listOf(
            "amazon", "ebay", "argos", "john lewis", "next ",
            "primark", "h&m", "zara", "asos", "boohoo", "shein",
            "clothing", "fashion", "shoe", "footwear", "electronics",
            "currys", "pc world", "apple store", "google store",
            "ikea", "dunelm", "habitat", "b&q", "wickes",
            "homebase", "diy", "furniture", "decoration",
        ),
        Category.SALARY to listOf(
            "salary", "payroll", "wages", "hmrc", "tax rebate",
            "hm revenue", "employer", "payment from",
        ),
        Category.TRANSFERS to listOf(
            "transfer", "sent to", "received from", "payment between",
            "between accounts", "moving money",
        ),
        Category.SAVINGS to listOf(
            "savings", "isa", "investment", "pension", "vanguard",
            "hsbc invest", "nutmeg", "moneybox", "plum",
        ),
        Category.HEALTH to listOf(
            "pharmacy", "boots", "superdrug", "chemist", "nhs",
            "hospital", "gp ", "doctor", "dentist", "optician",
            "specscavers", "specsavers", "health", "medical",
        ),
        Category.EDUCATION to listOf(
            "university", "student loan", "course", "book",
            "waterstones", "education", "school", "college",
            "udemy", "coursera", "learning",
        ),
    )

    /**
     * Keywords matched on word boundaries rather than as bare substrings.
     *
     * Plain `contains` was quietly wrong in both directions: "netflix" contains "tfl", so
     * Netflix was filed as Transport, and "visa" contains "isa", so every Visa payment was
     * filed as Savings. Compiled once - rebuilding a couple of hundred expressions for every
     * transaction would be felt on a long list.
     */
    private val compiledRules: List<Pair<Category, List<Regex>>> by lazy {
        rules.map { (category, keywords) ->
            category to keywords.map { keyword ->
                // A trailing plural is still the same word: "sainsburys" is "sainsbury",
                // "santander cycles" is "santander cycle". The leading boundary is what stops
                // "tfl" matching inside "netflix", and it stays strict.
                Regex(
                    "\\b" + Regex.escape(keyword.trim()) + "(?:s|es)?\\b",
                    RegexOption.IGNORE_CASE,
                )
            }
        }
    }

    /*
     * LIFE_EVENTS has none either, for the opposite reason: a wedding venue or a removals
     * firm is named after itself and shares nothing with the next one. It is set by hand,
     * and kept out of the trends ranking - a one-off would head a list of what is moving
     * every time and drown the habits that list exists to surface.
     *
     * WORK_LUNCH deliberately has no keyword list. The merchants are the same ones as
     * EATING_OUT - a sandwich shop cannot be told apart from a dinner out by its name, and
     * booking dates carry no time of day, so "weekday lunchtime" is not available either.
     * It is populated by hand instead: categorise one transaction from a regular haunt and
     * use "Always Work lunches for ..." to make it stick for that merchant.
     */

    /**
     * Precedence runs: this transaction's own override, then the user's own rules, then the
     * built-in keyword list. A correction the user made by hand always wins - otherwise the
     * next sync would quietly re-guess it.
     */
    fun classify(
        tx: TransactionEntity,
        userRules: List<CategoryRuleEntity> = emptyList(),
        cardPaymentKeys: Set<String> = emptySet(),
        creditCardAccountIds: Set<String> = emptySet(),
    ): Category {
        tx.categoryOverride
            ?.let { name -> runCatching { Category.valueOf(name) }.getOrNull() }
            ?.let { return it }

        // Structure beats spelling. A payment matched to a card by CreditCardEngine is a
        // card bill whatever the bank called it, and no keyword can be as sure.
        if ("${tx.accountId}|${tx.transactionId}" in cardPaymentKeys) return Category.CARD_BILL

        val payee = tx.payee.lowercase()
        val desc = tx.description?.lowercase() ?: ""
        val combined = "$payee $desc"

        // Longest pattern first, so a specific rule beats a broader one.
        userRules
            .sortedByDescending { it.pattern.length }
            .firstOrNull { combined.contains(it.pattern.lowercase()) }
            ?.let { rule -> runCatching { Category.valueOf(rule.category) }.getOrNull() }
            ?.let { return it }

        // A positive amount is income - except on a credit card, where it is a refund or a
        // bill settlement and never earnings. Settlements are named above, so what is left
        // is a refund, and a refund is best described by whoever sent it: falling through to
        // the keyword list files an Amazon refund under the same heading as an Amazon order.
        if (tx.amountMinor >= 0 && tx.accountId !in creditCardAccountIds) return Category.SALARY

        for ((category, patterns) in compiledRules) {
            if (category == Category.SALARY) continue // handled above
            for (pattern in patterns) {
                if (pattern.containsMatchIn(combined)) return category
            }
        }
        return Category.OTHER
    }

    /** A payee reduced to what stays the same from one month's statement to the next. */
    fun payeeKey(tx: TransactionEntity): String = tx.payee.lowercase().trim().replace(Regex("\\s+"), " ")

    /**
     * How the user has filed each payee by hand, as payee key to category counts. Built from
     * per-transaction corrections, which are the strongest evidence there is: the user has
     * already looked at this merchant and said what it is.
     */
    fun history(transactions: List<TransactionEntity>): Map<String, Map<Category, Int>> =
        transactions
            .mapNotNull { tx ->
                val category = tx.categoryOverride?.let { name -> runCatching { Category.valueOf(name) }.getOrNull() }
                category?.let { payeeKey(tx) to it }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, categories) -> categories.groupingBy { it }.eachCount() }

    /**
     * The categories this transaction most likely belongs in, best first, leaving out the one
     * it is already shown under - that is the app's first guess already, and a suggestion is
     * only useful as an alternative to it. Always at least two, so both swipe directions mean
     * something.
     *
     * Evidence in order of strength: how the user filed this payee before; their own rules
     * that match; every built-in keyword that matches, not just the first; then, for money in,
     * the two things money in usually is; and last, the categories spending most often lands in.
     */
    fun suggest(
        tx: TransactionEntity,
        userRules: List<CategoryRuleEntity> = emptyList(),
        history: Map<String, Map<Category, Int>> = emptyMap(),
        cardPaymentKeys: Set<String> = emptySet(),
        creditCardAccountIds: Set<String> = emptySet(),
    ): List<Category> {
        val current = classify(tx, userRules, cardPaymentKeys, creditCardAccountIds)
        val moneyIn = tx.amountMinor > 0 && tx.accountId !in creditCardAccountIds
        val ranked = LinkedHashSet<Category>()

        history[payeeKey(tx)]
            ?.entries
            ?.sortedByDescending { it.value }
            ?.forEach { ranked += it.key }

        val combined = "${tx.payee.lowercase()} ${tx.description?.lowercase().orEmpty()}"
        userRules
            .sortedByDescending { it.pattern.length }
            .filter { combined.contains(it.pattern.lowercase()) }
            .mapNotNull { runCatching { Category.valueOf(it.category) }.getOrNull() }
            .forEach { ranked += it }

        compiledRules
            .filter { (category, patterns) ->
                category != Category.SALARY && patterns.any { it.containsMatchIn(combined) }
            }
            .forEach { ranked += it.first }

        ranked += if (moneyIn) MONEY_IN_FALLBACK else SPENDING_FALLBACK

        return ranked
            .filter { it != current }
            // Money in is not spending, and spending is not a salary.
            .filter { if (moneyIn) it in MONEY_IN_CATEGORIES else it != Category.SALARY }
    }

    private val MONEY_IN_FALLBACK = listOf(Category.TRANSFERS, Category.SALARY, Category.SAVINGS, Category.OTHER)
    private val MONEY_IN_CATEGORIES = MONEY_IN_FALLBACK.toSet()
    private val SPENDING_FALLBACK = listOf(
        Category.GROCERIES,
        Category.EATING_OUT,
        Category.SHOPPING,
        Category.TRANSPORT,
        Category.BILLS,
        Category.ENTERTAINMENT,
        Category.OTHER,
    )

    fun spendingBreakdown(
        transactions: List<TransactionEntity>,
        userRules: List<CategoryRuleEntity> = emptyList(),
        cardPaymentKeys: Set<String> = emptySet(),
        creditCardAccountIds: Set<String> = emptySet(),
    ): List<CategoryTotal> {
        // Pending counts here too, or the breakdown would not add up to the total the
        // home screen reports from the same transactions.
        val debits = transactions.filter { it.amountMinor < 0 }
        val grouped = debits.groupBy {
            classify(it, userRules, cardPaymentKeys, creditCardAccountIds)
        }
        return grouped.map { (cat, txs) ->
            val dominant = txs.maxByOrNull { -it.amountMinor }?.currency ?: "GBP"
            CategoryTotal(
                category = cat,
                amountMinor = txs.sumOf { -it.amountMinor },
                count = txs.size,
                currency = dominant,
            )
        }.sortedByDescending { it.amountMinor }
    }
}