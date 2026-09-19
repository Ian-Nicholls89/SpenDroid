package com.budgetapp.domain

import com.budgetapp.data.db.TransactionEntity

enum class Category(val label: String) {
    GROCERIES("Groceries"),
    TRANSPORT("Transport"),
    BILLS("Bills & Utilities"),
    ENTERTAINMENT("Entertainment"),
    SHOPPING("Shopping"),
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
)

object CategoryEngine {

    private val rules: Map<Category, List<String>> = mapOf(
        Category.GROCERIES to listOf(
            "tesco", "sainsbury", "asda", "morrisons", "aldi", "lidl",
            "waitrose", "coop", "co-op", "m&s", "marks and spencer",
            "iceland", "budgens", "ocado", "heron foods", "farmfoods",
            "spar", "local express", "meal deal", "supermarket", "grocery",
        ),
        Category.TRANSPORT to listOf(
            "uber", "tfl", "oyster", "national rail", "trainline", "avanti",
            "lner", "gwr", "southern", "thameslink", "southeastern",
            "bus", "metro", "tram", "cycle", "santander", "bp", "shell",
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
        Category.ENTERTAINMENT to listOf(
            "netflix", "spotify", "disney", "amazon prime", "prime video",
            "apple tv", "now tv", "hulu", "youtube", "cinema",
            "vue ", "odeon", "curzon", "cineworld", "restaurant",
            "takeaway", "deliveroo", "just eat", "uber eats",
            "mcdonald", "kfc", "burger", "pizza", "nando",
            "pub", "bar ", "barclay", "wetherspoon", "jd wetherspoon",
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

    fun classify(tx: TransactionEntity): Category {
        if (tx.amountMinor >= 0) return Category.SALARY  // positive = income → salary
        val payee = tx.payee.lowercase()
        val desc = tx.description?.lowercase() ?: ""
        val combined = "$payee $desc"

        for ((category, keywords) in rules) {
            if (category == Category.SALARY) continue // handled above
            for (keyword in keywords) {
                if (combined.contains(keyword)) return category
            }
        }
        return Category.OTHER
    }

    fun spendingBreakdown(transactions: List<TransactionEntity>): List<CategoryTotal> {
        val debits = transactions.filter { it.amountMinor < 0 && !it.isPending }
        val grouped = debits.groupBy { classify(it) }
        return grouped.map { (cat, txs) ->
            CategoryTotal(
                category = cat,
                amountMinor = txs.sumOf { -it.amountMinor },
                count = txs.size,
            )
        }.sortedByDescending { it.amountMinor }
    }
}