package ru.finassist.pf.core.mockbackend.engine

/**
 * Category dictionary: T-Bank categories as they come in `MEMO`, plus the two system categories.
 * Ids are stable slugs (`cat_supermarkets`); unknown bank categories get `cat_x_<hash>` and the default icon,
 * which is what the real backend will do with an open list («новые категории принимаем как есть»).
 */
data class Category(
    val id: String,
    val name: String,
    val icon: String,
    val isSystem: Boolean,
    val kinds: Set<Direction>,
    val assignable: Boolean,
    val note: String? = null,
)

object Categories {
    const val OWN_TRANSFER = "cat_own_transfer"
    const val REFUND = "cat_refund"
    const val TRANSFERS = "cat_transfers"
    const val BANK_FEES = "cat_bank_services"

    private val both = setOf(Direction.Debit, Direction.Credit)
    private val expense = setOf(Direction.Debit)
    private val income = setOf(Direction.Credit)

    /** Bank categories that are never a purchase; a CREDIT in any other category may be a refund (decision-ofx-only.md). */
    val incomeLikeBankCategories = setOf(
        "Переводы", "Финансы", "Дивиденды и купоны", "Проценты", "Бонусы", "Наличные", "Зарплата", "Вклады", "Пополнения", "Другое",
    )

    private val known: List<Category> = listOf(
        Category(OWN_TRANSFER, "Между своими счетами", "transfer", true, both, true, "не в тратах"),
        Category(REFUND, "Возврат", "repeat", true, income, true, "уменьшает расходы на покупку"),
        c("cat_supermarkets", "Супермаркеты", "cart", expense),
        c("cat_restaurants", "Рестораны", "coffee", expense),
        c("cat_fastfood", "Фастфуд", "coffee", expense),
        c("cat_fuel", "Заправки", "car", expense),
        c("cat_taxi", "Такси", "car", expense),
        c("cat_transport", "Местный транспорт", "car", expense),
        c("cat_marketplaces", "Маркетплейсы", "package", expense),
        c("cat_goods", "Различные товары", "package", expense),
        c("cat_services", "Различные услуги", "tag", expense),
        c("cat_pharmacy", "Аптеки", "plus", expense),
        c("cat_medicine", "Медицина", "tag", expense),
        c("cat_clothes", "Одежда и обувь", "tag", expense),
        c("cat_beauty", "Красота", "tag", expense),
        c("cat_housing", "ЖКХ", "landmark", expense),
        c("cat_repair", "Ремонт и мебель", "tag", expense),
        c("cat_mobile", "Мобильная связь", "smartphone", expense),
        c("cat_telecom", "Связь", "smartphone", expense),
        c("cat_internet", "Интернет", "smartphone", expense),
        c("cat_digital", "Цифровые товары", "smartphone", expense),
        c("cat_entertainment", "Развлечения", "tag", expense),
        c("cat_cinema", "Онлайн-кинотеатры", "tag", expense),
        c("cat_art", "Искусство", "tag", expense),
        c("cat_flowers", "Цветы", "tag", expense),
        c("cat_gifts", "Подарки и творчество", "tag", expense),
        c("cat_pets", "Животные", "tag", expense),
        c("cat_sport", "Тренировки", "tag", expense),
        c("cat_travel", "Турагентства", "car", expense),
        c("cat_hotels", "Отели", "landmark", expense),
        c("cat_flights", "Авиабилеты", "car", expense),
        c("cat_rail", "Ж/д билеты", "car", expense),
        c("cat_tolls", "Платные дороги", "car", expense),
        c("cat_auto_services", "Автоуслуги", "tag", expense),
        c("cat_car_dealers", "Автосалоны", "car", expense),
        c("cat_fines", "Штрафы", "alert-triangle", expense),
        c("cat_gov", "Госуслуги", "landmark", expense),
        c("cat_charity", "Благотворительность", "tag", expense),
        c("cat_nko", "НКО", "tag", expense),
        c("cat_photo", "Фото и копицентры", "tag", expense),
        c("cat_yandex", "Экосистема Яндекс", "tag", expense),
        c("cat_service", "Сервис", "tag", expense),
        c(BANK_FEES, "Услуги банка", "landmark", expense),
        c("cat_cash", "Наличные", "landmark", both),
        c(TRANSFERS, "Переводы", "users", both),
        c("cat_finance", "Финансы", "landmark", both),
        c("cat_dividends", "Дивиденды и купоны", "trending-up", both),
        c("cat_interest", "Проценты", "percent", income),
        c("cat_bonus", "Бонусы", "tag", income),
        c("cat_salary", "Зарплата", "landmark", income),
        c("cat_deposits", "Вклады", "piggy-bank", both),
        c("cat_topup", "Пополнения", "plus", income),
        c("cat_other", "Другое", "circle", both),
    )

    private fun c(id: String, name: String, icon: String, kinds: Set<Direction>) = Category(id, name, icon, false, kinds, true)

    private val byName = known.associateBy { it.name }
    private val byId = known.associateBy { it.id }.toMutableMap()

    /** Dictionary in display order: system first, then expense, then income (as the picker groups them). */
    val all: List<Category> get() = byId.values.toList()

    fun byId(id: String): Category? = byId[id]

    /** Category for a bank `MEMO`; registers an unknown one on first sight. */
    fun forBankCategory(bankCategory: String, direction: Direction): Category {
        byName[bankCategory]?.let { return it }
        val slug = "cat_x_" + Integer.toHexString(bankCategory.lowercase().hashCode())
        return byId.getOrPut(slug) {
            Category(slug, bankCategory.ifBlank { "Без категории" }, "circle", false, setOf(direction), true)
        }
    }

    fun isExpenseBankCategory(bankCategory: String): Boolean = bankCategory !in incomeLikeBankCategories
}
