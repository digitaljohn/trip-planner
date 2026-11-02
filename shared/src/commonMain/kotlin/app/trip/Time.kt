package app.trip

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// Trips (MyTrip.kt) and the engine are written with java.time's names. These map them onto kotlin.time
// and kotlinx-datetime, which exist on iOS too; on Android they're backed by java.time itself.
// Instant.now() can't be mapped (kotlin.time.Instant has a deprecated member of that name, and members
// beat extensions), so the engine calls Clock.System.now(). Companions can't be reached through a
// typealias either, hence the full names on the Companion extensions.

typealias Instant = kotlin.time.Instant
typealias Duration = kotlin.time.Duration
typealias LocalDate = kotlinx.datetime.LocalDate
typealias ZoneId = kotlinx.datetime.TimeZone

fun Instant.toEpochMilli(): Long = toEpochMilliseconds()
fun Instant.isBefore(other: Instant) = this < other
fun Instant.isAfter(other: Instant) = this > other
fun Instant.plusSeconds(s: Long): Instant = this + s.seconds
fun Instant.minusSeconds(s: Long): Instant = this - s.seconds
fun Instant.plusMillis(ms: Long): Instant = this + ms.milliseconds
fun Instant.atZone(zone: ZoneId): LocalDateTime = toLocalDateTime(zone)
fun LocalDateTime.toLocalDate() = date
fun LocalDateTime.toLocalTime() = time

/** [a] to [b]; negative when [b] is earlier. Whole-unit conversions truncate towards zero, as java.time's do. */
fun kotlin.time.Duration.Companion.between(a: Instant, b: Instant): Duration = b - a
fun kotlin.time.Duration.Companion.ofDays(n: Long): Duration = n.days
fun kotlin.time.Duration.Companion.ofHours(n: Long): Duration = n.hours
fun kotlin.time.Duration.Companion.ofMinutes(n: Long): Duration = n.minutes
fun Duration.toMinutes() = inWholeMinutes
fun Duration.toHours() = inWholeHours
fun Duration.toDays() = inWholeDays
fun Duration.toMillis() = inWholeMilliseconds

fun TimeZone.Companion.systemDefault(): ZoneId = TimeZone.currentSystemDefault()

fun kotlinx.datetime.LocalDate.Companion.of(year: Int, month: Int, day: Int) = LocalDate(year, month, day)
fun kotlinx.datetime.LocalDate.Companion.now(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
fun LocalDate.plusDays(n: Long): LocalDate = plus(n, DateTimeUnit.DAY)
fun LocalDate.minusDays(n: Long): LocalDate = minus(n, DateTimeUnit.DAY)
fun LocalDate.isBefore(other: LocalDate) = this < other
fun LocalDate.isAfter(other: LocalDate) = this > other

/** "2026-11", as java.time's YearMonth.now() prints it. */
fun yearMonth(): String = LocalDate.now().let { "${it.year}-${(it.month.ordinal + 1).toString().padStart(2, '0')}" }
