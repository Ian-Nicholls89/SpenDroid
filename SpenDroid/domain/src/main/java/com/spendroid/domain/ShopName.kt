package com.spendroid.domain

/**
 * A shop's name fit for a sentence - "Penny has a question about your spend at Tesco" - from
 * what a bank or card alert calls it: "TESCO STORES 2231", "SumUp *Pie and Piper", "eBay O*20-
 * 15250-6". Null when there's no clean, short name to be had; the caller says something general.
 *
 * The branch goes too (0.7.1): "Waterstones Salisbury" is Waterstones to anyone reading it. A
 * place is known from a list of UK towns, or learned from the user's own payees ([placesIn]):
 * a last word that follows three or more different shops is where they are, not who.
 */
object ShopName {

    fun clean(raw: String, places: Set<String> = emptySet()): String? {
        var name = raw.trim()
        // "SumUp *Pie and Piper": a card processor, then the shop. "eBay O*20-...": the shop,
        // then a reference.
        if ('*' in name) {
            val before = name.substringBefore('*').trim()
            val after = name.substringAfter('*').trim()
            name = if (before.lowercase() in PROCESSORS) after else before
        }
        val words = words(name).toMutableList()
        if (words.isEmpty()) return null
        // The branch, from the end: "Tesco Salisbury Retail Park" is Tesco. A name of its own is
        // left - never stripped to nothing, nor to a scrap.
        while (words.size > 1) {
            val cut = (minOf(3, words.size - 1) downTo 1).firstOrNull { n ->
                val tail = words.takeLast(n).joinToString(" ") { it.lowercase() }
                tail in PLACES || tail in LOCATION_WORDS || (n == 1 && tail in places)
            } ?: break
            val kept = words.dropLast(cut)
            if (kept.joinToString("").count(Char::isLetter) < 3 || kept.last().lowercase() in JOINERS) break
            repeat(cut) { words.removeAt(words.size - 1) }
        }
        val shouting = words.all { w -> w.none(Char::isLowerCase) }
        val joined = words.joinToString(" ") { w -> if (shouting) w.lowercase().replaceFirstChar(Char::uppercase) else w }
        return joined.takeIf { it.length in 2..24 && it.any(Char::isLetter) }
    }

    /**
     * Places learned from [payees]: a last word after three or more different first words -
     * "Boots Salisbury", "Tesco Salisbury", "Greggs Salisbury" - that isn't a kind of shop.
     */
    fun placesIn(payees: Collection<String>): Set<String> {
        val before = mutableMapOf<String, MutableSet<String>>()
        payees.forEach { p ->
            val w = words(if ('*' in p) p.substringAfter('*') else p)
            if (w.size >= 2) before.getOrPut(w.last().lowercase()) { mutableSetOf() } += w.first().lowercase()
        }
        return before.filter { (last, firsts) -> firsts.size >= 3 && last !in KINDS && last.all(Char::isLetter) }.keys
    }

    private fun words(name: String) = name.split(Regex("\\s+"))
        .filter { w -> w.none(Char::isDigit) && w.length >= 2 && w.lowercase().trim('.', ',') !in FILLER }

    private val PROCESSORS = setOf("sumup", "sq", "square", "paypal", "pp", "zettle", "izettle", "iz", "sp", "crv", "stripe")
    private val FILLER = setOf("stores", "store", "ltd", "limited", "plc", "uk", "gb", "superstore", "llp", "inc")

    /** Not where a shop is but what it is: never learned as a place. */
    private val KINDS = setOf(
        "coffee", "cafe", "café", "bar", "kitchen", "express", "local", "extra", "metro", "food", "foods", "pharmacy",
        "garage", "petrol", "bakery", "deli", "restaurant", "pizza", "grill", "pub", "inn", "arms", "hotel", "books",
        "shop", "market", "co", "company", "group", "services", "online", "direct", "travel", "taxis", "taxi", "parking",
        "car", "wash", "salon", "barbers", "and", "the", "of", "club", "centre", "house", "studio", "studios", "home",
    )

    /** Words a name ends on that leave it unfinished: "Marks and" isn't a shop. */
    private val JOINERS = setOf("and", "&", "the", "of", "at", "on", "in", "de", "la", "le")

    /** Where in a town, after the town or on its own. */
    private val LOCATION_WORDS = setOf(
        "high st", "high street", "retail park", "shopping centre", "city centre", "town centre", "station",
        "branch", "airport", "services", "west end",
    )

    /**
     * UK towns and cities a card statement names as the branch. Words that are just as often
     * part of a name are left out: Reading's in, but not Street, Deal, Sale, March, Ware or Wells.
     */
    private val PLACES = """
        aberdeen aberystwyth abingdon accrington aldershot alton altrincham amersham amesbury andover ashford aylesbury
        ayr banbury bangor barnsley barnstaple barrow basildon basingstoke bath bedford belfast beverley bexhill
        bicester birkenhead birmingham blackburn blackpool blandford bognor bolton boston bournemouth bracknell bradford
        braintree brentwood bridgend bridgwater brighton bristol bromley burnley burton bury cambridge camberley canterbury
        cardiff carlisle chatham chelmsford cheltenham chester chesterfield chichester chippenham christchurch
        cirencester colchester corby coventry crawley crewe croydon darlington dartford derby devizes doncaster dorchester
        dover dudley dundee dunstable durham eastbourne eastleigh edinburgh ely epsom exeter falkirk fareham farnborough
        farnham folkestone frome gateshead gillingham glasgow gloucester godalming gosport gravesend grantham grimsby
        guildford halifax harlow harrogate hartlepool hastings havant hereford hertford high wycombe hinckley horsham
        huddersfield hull huntingdon inverness ipswich kendal kettering kidderminster kingston leamington leeds leicester
        lewes lichfield lincoln liverpool llandudno london loughborough luton lymington maidenhead maidstone manchester
        mansfield margate marlborough melksham middlesbrough milton keynes newbury newcastle newport newquay northampton
        norwich nottingham nuneaton oldham oxford paignton peterborough perth plymouth poole portsmouth preston ramsgate
        reading redditch redhill reigate richmond ringwood rochdale romford romsey rotherham rugby runcorn salford salisbury
        scarborough scunthorpe sevenoaks sheffield shrewsbury slough solihull southampton southend southport stafford
        staines stevenage stirling stockport stockton stoke stratford sunderland sutton swansea swindon tamworth taunton
        telford tewkesbury tonbridge torquay trowbridge truro tunbridge wells wakefield walsall warminster warrington
        warwick watford wellingborough westbury weston weymouth wigan wilton winchester windsor woking wokingham
        wolverhampton worcester worthing wrexham yeovil york
    """.trimIndent().split(Regex("\\s+")).toSet() + setOf(
        "milton keynes", "high wycombe", "tunbridge wells", "st albans", "st ives", "st austell", "weston super mare",
        "weston-super-mare", "stoke on trent", "stratford upon avon", "newcastle upon tyne", "kings lynn", "bury st edmunds",
    ) - setOf("high", "milton", "keynes", "wells", "tunbridge")
}
