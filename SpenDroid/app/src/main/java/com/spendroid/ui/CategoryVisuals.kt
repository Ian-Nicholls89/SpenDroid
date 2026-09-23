package com.spendroid.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.spendroid.domain.Category

/**
 * One icon and one colour per category, shared by the transaction list and the breakdown
 * chart.
 *
 * The chart previously coloured bars by their position in the sorted list, so a category
 * changed colour whenever the ranking changed. Keying off the category itself keeps the
 * colour stable and lets a row's icon and its bar agree.
 */
data class CategoryVisual(val icon: ImageVector, val color: Color)

private val visuals: Map<Category, CategoryVisual> = mapOf(
    Category.GROCERIES to CategoryVisual(Icons.Filled.ShoppingCart, Color(0xFF43A047)),
    Category.TRANSPORT to CategoryVisual(Icons.Filled.DirectionsTransit, Color(0xFF1E88E5)),
    Category.BILLS to CategoryVisual(Icons.AutoMirrored.Filled.ReceiptLong, Color(0xFFFB8C00)),
    Category.ENTERTAINMENT to CategoryVisual(Icons.Filled.Movie, Color(0xFF8E24AA)),
    Category.SHOPPING to CategoryVisual(Icons.Filled.ShoppingBag, Color(0xFFE53935)),
    Category.SALARY to CategoryVisual(Icons.Filled.Payments, Color(0xFF00897B)),
    Category.TRANSFERS to CategoryVisual(Icons.Filled.SwapHoriz, Color(0xFF3949AB)),
    Category.SAVINGS to CategoryVisual(Icons.Filled.Savings, Color(0xFF00ACC1)),
    Category.HEALTH to CategoryVisual(Icons.Filled.LocalHospital, Color(0xFFF4511E)),
    Category.EDUCATION to CategoryVisual(Icons.Filled.School, Color(0xFF6D4C41)),
    Category.OTHER to CategoryVisual(Icons.Filled.Category, Color(0xFF757575)),
)

val Category.visual: CategoryVisual
    get() = visuals[this] ?: CategoryVisual(Icons.Filled.Category, Color(0xFF757575))
