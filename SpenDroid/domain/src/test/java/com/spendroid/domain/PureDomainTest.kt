package com.spendroid.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The budget's maths stays plain Kotlin (0.7.4): nothing in :domain may use Android, or the app's
 * own data, screens or workers - it's an Android library only so lint checks it against minSdk.
 */
class PureDomainTest {

    private val sources = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `nothing in the domain uses Android or the app`() {
        assertTrue("sources not found from ${File(".").absolutePath}", sources.size > 20)
        // Room's annotations are the one exception: plain data, marked for the app's database.
        val allowed = setOf("androidx.room.Entity", "androidx.room.Index", "androidx.room.PrimaryKey")
        val bad = sources.flatMap { f ->
            f.readLines().filter { it.startsWith("import ") }.map { it.removePrefix("import ").trim() }
                .filter { (it.startsWith("android.") || it.startsWith("androidx.") || it.startsWith("com.spendroid.ui") || it.startsWith("com.spendroid.work") || it.startsWith("com.spendroid.widget")) && it !in allowed }
                .map { "${f.name}: $it" }
        }
        assertTrue("Android or app code in :domain: $bad", bad.isEmpty())
    }
}
