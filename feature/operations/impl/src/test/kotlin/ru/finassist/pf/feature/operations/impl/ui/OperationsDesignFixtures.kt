package ru.finassist.pf.feature.operations.impl.ui

import ru.finassist.pf.core.api.model.Account
import ru.finassist.pf.core.api.model.Category
import ru.finassist.pf.core.api.model.CategoryKind
import ru.finassist.pf.core.api.model.OperationDetails
import ru.finassist.pf.core.api.model.OperationKind
import ru.finassist.pf.core.api.model.OperationStatus
import ru.finassist.pf.core.api.model.SystemCategories
import ru.finassist.pf.core.common.money.Money
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Mockup data shared by the operations design-check tests (artboards Detail*, CategoryPicker*, Search*). */
internal object OperationsDesignFixtures {
    val zone: ZoneId = ZoneId.of("Europe/Moscow")
    val today: LocalDate = LocalDate.of(2026, 9, 28)

    fun at(month: Int, day: Int, hour: Int, minute: Int = 0): OffsetDateTime =
        OffsetDateTime.of(2026, month, day, hour, minute, 0, 0, ZoneOffset.ofHours(3))

    private val current = Account("Текущий", "··4821", spoken = "текущий счёт, последние цифры 4821")
    private val savings = Account("Накопительный", "··0734", spoken = "накопительный счёт, последние цифры 0734")

    private val expense = listOf(CategoryKind.EXPENSE)
    private val income = listOf(CategoryKind.INCOME)
    private val both = listOf(CategoryKind.EXPENSE, CategoryKind.INCOME)

    private fun cat(id: String, name: String, icon: String, kinds: List<CategoryKind> = expense) =
        Category(id = id, name = name, icon = icon, isSystem = false, kinds = kinds, assignable = true)

    /**
     * The picker lists of the mockup: «Расходы» (21 rows with the system one) and «Доходы» (6 rows). Order is
     * chosen so both groups come out in the mockup's order.
     */
    val categories = listOf(
        Category(SystemCategories.OWN_TRANSFER, "Между своими счетами", "transfer", isSystem = true, kinds = both, assignable = true, note = "не в тратах"),
        Category(SystemCategories.REFUND, "Возврат", "repeat", isSystem = true, kinds = income, assignable = true, note = "уменьшает расходы на покупку"),
        cat("cat_supermarkets", "Супермаркеты", "cart"),
        cat("cat_restaurants", "Рестораны", "coffee"),
        cat("cat_fastfood", "Фастфуд", "bike"),
        cat("cat_finance", "Финансы", "piggy-bank", income),
        cat("cat_transfers", "Переводы", "users", both),
        cat("cat_dividends", "Дивиденды и купоны", "trending-up", income),
        cat("cat_goods", "Различные товары", "package"),
        cat("cat_fuel", "Заправки", "car"),
        cat("cat_pharmacy", "Аптеки", "receipt"),
        cat("cat_digital", "Цифровые товары", "download"),
        cat("cat_clothes", "Одежда и обувь", "basket"),
        cat("cat_service", "Сервис", "sliders"),
        cat("cat_bank_fees", "Услуги банка", "percent", both),
        cat("cat_toll_roads", "Платные дороги", "car"),
        cat("cat_cash", "Наличные", "credit-card"),
        cat("cat_mobile", "Мобильная связь", "phone"),
        cat("cat_utilities", "ЖКХ", "file-text"),
        cat("cat_telecom", "Связь", "smartphone"),
        cat("cat_gov", "Госуслуги", "landmark"),
        cat("cat_services", "Различные услуги", "list"),
        cat("cat_charity", "Благотворительность", "circle"),
        cat("cat_pets", "Животные", "circle"),
    )

    private const val SOURCE = "Выписка Т-Банка"

    val purchase = OperationDetails(
        id = "op_pyaterochka", kind = OperationKind.EXPENSE, occurredAt = at(9, 25, 19, 11), amount = Money(2_340_00),
        currency = "RUB", title = "Пятёрочка", categoryId = "cat_supermarkets", categoryName = "Супермаркеты",
        categoryIcon = "cart", isCategoryManual = false, status = OperationStatus.POSTED, account = current,
        bankCategory = "Супермаркеты", sourceName = SOURCE,
    )

    val refund = OperationDetails(
        id = "op_ozon_refund", kind = OperationKind.INCOME, occurredAt = at(9, 21, 12, 40), amount = Money(6_990_00),
        currency = "RUB", title = "Ozon", categoryId = SystemCategories.REFUND, categoryName = "Возврат",
        categoryIcon = "repeat", isCategoryManual = false, note = "возврат · Различные товары",
        status = OperationStatus.POSTED, account = current,
        noteDetails = "Уменьшает расходы «Различные товары» в сентябре", bankCategory = "Различные товары", sourceName = SOURCE,
    )

    val transfer = OperationDetails(
        id = "op_transfer", kind = OperationKind.OWN_TRANSFER, occurredAt = at(9, 18, 9, 30), amount = Money(20_000_00),
        currency = "RUB", title = "Перевод между счетами", categoryId = SystemCategories.OWN_TRANSFER,
        categoryName = "Между своими счетами", categoryIcon = "transfer", isCategoryManual = false, note = "не в тратах",
        status = OperationStatus.POSTED, fromAccount = savings, toAccount = current,
        noteDetails = "Не считаем тратой и доходом", sourceName = SOURCE,
    )

    fun detailState(op: OperationDetails) = DetailUiState(loading = false, operation = op, categories = categories)
}
