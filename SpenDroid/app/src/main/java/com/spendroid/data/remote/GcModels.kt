package com.spendroid.data.remote

import com.google.gson.annotations.SerializedName

data class TokenResponseDto(
    val access: String,
    @SerializedName("access_expires") val accessExpires: Long = 3600L,
    val refresh: String? = null,
    @SerializedName("refresh_expires") val refreshExpires: Long? = null,
)

data class InstitutionDto(
    val id: String,
    val name: String,
    @SerializedName("transaction_total_days") val transactionTotalDays: String? = null,
)

data class RequisitionRequestDto(
    val redirect: String,
    @SerializedName("institution_id") val institutionId: String,
    val reference: String,
    @SerializedName("user_language") val userLanguage: String = "EN",
)

data class RequisitionDto(
    val id: String? = null,
    val link: String? = null,
    val status: String? = null,
    val accounts: List<String> = emptyList(),
    /** When the requisition was created, as an ISO-8601 timestamp. */
    val created: String? = null,
)

data class BalancesDto(
    val balances: List<BalanceDto> = emptyList(),
)

data class BalanceDto(
    val balanceType: String? = null,
    val balanceAmount: AmountDto? = null,
    /**
     * Set by the bank when the figure has the credit limit folded into it. Such a balance
     * describes headroom, never what is owed, so it is never the number to show.
     */
    @SerializedName("creditLimitIncluded") val creditLimitIncluded: Boolean? = null,
)

data class AmountDto(
    val amount: String,
    val currency: String,
)

data class AccountDetailsDto(
    val id: String? = null,
    val created: String? = null,
    @SerializedName("last_accessed") val lastAccessed: String? = null,
    val iban: String? = null,
    val bban: String? = null,
    val status: String? = null,
    @SerializedName("institution_id") val institutionId: String? = null,
    @SerializedName("owner_name") val ownerName: String? = null,
    val name: String? = null,
)

data class AccountInfoWrapperDto(
    val account: AccountInfoDto? = null,
)

data class AccountInfoDto(
    val currency: String? = null,
    val iban: String? = null,
    val bban: String? = null,
    val sortCode: String? = null,
    val accountNumber: String? = null,
    /** A card's number with most digits hidden, which is still enough to tell cards apart. */
    val maskedPan: String? = null,
    // The bank's own description of the account. Previously unmodelled, which left account
    // type detection with nothing to read but a name most banks do not send.
    val name: String? = null,
    val product: String? = null,
    /** ISO 20022 external code. "CARD" identifies a credit card outright. */
    @SerializedName("cashAccountType") val cashAccountType: String? = null,
    val usage: String? = null,
    val details: String? = null,
)

data class TransactionsDto(
    val transactions: TransactionContainerDto = TransactionContainerDto(),
    val next: String? = null,
)

data class TransactionContainerDto(
    val booked: List<TransactionDto> = emptyList(),
    val pending: List<TransactionDto> = emptyList(),
)

data class TransactionDto(
    val transactionId: String? = null,
    val bookingDate: String? = null,
    val valueDate: String? = null,
    val transactionAmount: AmountDto? = null,
    val counterpartyName: String? = null,
    val debtorName: String? = null,
    val creditorName: String? = null,
    val ultimateDebtorName: String? = null,
    val ultimateCreditorName: String? = null,
    val remittanceInformationUnstructured: Any? = null,
    /**
     * What the merchant actually charged, before the bank converted it. Present only on a
     * foreign transaction, and only from banks that send it.
     */
    val instructedAmount: AmountDto? = null,
    /**
     * The conversion the bank applied, as PSD2 defines it. Stored rather than used for now:
     * rawJson is a re-serialisation of this class, so a field the class does not declare is
     * discarded before it is ever written, and capturing it has to come first.
     */
    val currencyExchange: List<CurrencyExchangeDto>? = null,
)

data class CurrencyExchangeDto(
    val sourceCurrency: String? = null,
    val targetCurrency: String? = null,
    val unitCurrency: String? = null,
    val exchangeRate: String? = null,
    val quotationDate: String? = null,
    val instructedAmount: AmountDto? = null,
)