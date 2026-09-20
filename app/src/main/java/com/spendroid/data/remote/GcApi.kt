package com.spendroid.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

interface GcAuthApi {
    @POST("api/v2/token/new/")
    suspend fun newToken(@Body body: Map<String, String>): TokenResponseDto

    @POST("api/v2/token/refresh/")
    suspend fun refreshToken(@Body body: Map<String, String>): TokenResponseDto
}

interface GcDataApi {

    @GET("api/v2/institutions/")
    suspend fun institutions(@Query("country") country: String): List<InstitutionDto>

    @POST("api/v2/requisitions/")
    suspend fun createRequisition(@Body body: RequisitionRequestDto): RequisitionDto

    @GET("api/v2/requisitions/{id}/")
    suspend fun requisition(@Path("id") id: String): RequisitionDto

    @GET("api/v2/accounts/{id}/")
    suspend fun accountMetadata(@Path("id") id: String): AccountDetailsDto

    @GET("api/v2/accounts/{id}/details/")
    suspend fun accountDetails(@Path("id") id: String): AccountInfoWrapperDto

    @GET("api/v2/accounts/{id}/balances/")
    suspend fun accountBalances(@Path("id") id: String): BalancesDto

    @GET("api/v2/accounts/{id}/transactions/")
    suspend fun accountTransactions(
        @Path("id") id: String,
        @Query("date_from") dateFrom: String?,
        @Query("date_to") dateTo: String?,
    ): TransactionsDto

    @GET
    suspend fun transactionsPage(@Url url: String): TransactionsDto
}