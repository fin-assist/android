package ru.finassist.pf.feature.operations.impl.ui

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.model.FeedState
import ru.finassist.pf.core.api.model.MonthSummary
import ru.finassist.pf.core.api.model.OperationItem
import ru.finassist.pf.core.api.model.OperationKind
import ru.finassist.pf.core.api.model.OperationStatus
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.screenshot.DesignCheck
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** Artboards `Main` / `MainDark` / `MainStale` / `MainEmpty`: the same operations and totals as drawn there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedDesignCheckTest {

    private val zone = ZoneId.of("Europe/Moscow")
    // Not the day after the last operation: the mockup labels groups by date, not «Сегодня» / «Вчера».
    private val today = LocalDate.of(2026, 9, 28)

    private fun at(day: Int, hour: Int) = OffsetDateTime.of(2026, 9, day, hour, 0, 0, 0, java.time.ZoneOffset.ofHours(3))

    private fun op(id: String, kind: OperationKind, day: Int, hour: Int, rub: Long, title: String, category: String, icon: String, note: String? = null) =
        OperationItem(
            id = id, kind = kind, occurredAt = at(day, hour), amount = Money(rub * 100), currency = "RUB", title = title,
            categoryId = "c_$id", categoryName = category, categoryIcon = icon, isCategoryManual = false, note = note,
            status = OperationStatus.POSTED,
        )

    private val state = FeedUiState(
        loading = false,
        items = listOf(
            op("1", OperationKind.EXPENSE, 25, 18, 2_340, "Пятёрочка", "Супермаркеты", "cart"),
            op("2", OperationKind.EXPENSE, 25, 12, 2_500, "Лукойл", "Заправки", "car"),
            op("3", OperationKind.INCOME, 21, 15, 6_990, "Ozon", "Возврат", "repeat", note = "возврат · Различные товары"),
            op("4", OperationKind.EXPENSE, 18, 20, 1_716, "Алкотека", "Супермаркеты", "cart"),
            op("5", OperationKind.EXPENSE, 18, 16, 6_990, "Ozon", "Различные товары", "package"),
            op("6", OperationKind.INCOME, 18, 12, 1_250, "Выплата дивидендов", "Дивиденды и купоны", "trending-up"),
            op("7", OperationKind.OWN_TRANSFER, 18, 10, 20_000, "Перевод между счетами", "Между своими счетами", "transfer", note = "не в тратах"),
        ),
        summary = listOf(
            MonthSummary(
                month = "2026-09",
                range = DateRange(at(1, 0), at(26, 0)),
                expense = Money(84_320_00), income = Money(156_900_00), dataTo = at(25, 23),
            ),
        ),
        state = FeedState(stale = false),
        nextBefore = at(1, 0),
    )

    private fun capture(name: String, dark: Boolean = false, state: FeedUiState = this.state) = DesignCheck.capture(name, dark = dark, tab = 0) {
        FeedContent(state, today, zone, onOpenSearch = {}, onOpenOperation = {}, onUpload = {}, onRetry = {}, onLoadMore = {})
    }

    @Test fun main() = capture("Main", dark = false)

    @Test fun mainDark() = capture("MainDark", dark = true)

    /** Last operation on 25 September, more than two weeks ago: the info notice instead of the caption. */
    @Test fun mainStale() = capture("MainStale", state = state.copy(state = FeedState(stale = true, lastOperationAt = at(25, 18))))

    @Test fun mainEmpty() = capture(
        "MainEmpty",
        state = FeedUiState(loading = false, items = emptyList(), state = FeedState(stale = false), nextBefore = null),
    )
}
