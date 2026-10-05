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
        c("cat_restaurants", "Рестораны", "utensils", expense),
        c("cat_fastfood", "Фастфуд", "coffee", expense),
        c("cat_fuel", "Заправки", "car", expense),
        c("cat_taxi", "Такси", "car", expense),
        c("cat_transport", "Местный транспорт", "bus", expense),
        c("cat_marketplaces", "Маркетплейсы", "package", expense),
        c("cat_goods", "Различные товары", "package", expense),
        c("cat_services", "Различные услуги", "wrench", expense),
        c("cat_pharmacy", "Аптеки", "pill", expense),
        c("cat_medicine", "Медицина", "heart", expense),
        c("cat_clothes", "Одежда и обувь", "shirt", expense),
        c("cat_beauty", "Красота", "sparkles", expense),
        c("cat_housing", "ЖКХ", "home", expense),
        c("cat_repair", "Ремонт и мебель", "hammer", expense),
        c("cat_mobile", "Мобильная связь", "smartphone", expense),
        c("cat_telecom", "Связь", "smartphone", expense),
        c("cat_internet", "Интернет", "wifi", expense),
        c("cat_digital", "Цифровые товары", "monitor", expense),
        c("cat_entertainment", "Развлечения", "film", expense),
        c("cat_cinema", "Онлайн-кинотеатры", "film", expense),
        c("cat_art", "Искусство", "palette", expense),
        c("cat_flowers", "Цветы", "flower", expense),
        c("cat_gifts", "Подарки и творчество", "gift", expense),
        c("cat_pets", "Животные", "paw", expense),
        c("cat_sport", "Тренировки", "dumbbell", expense),
        c("cat_travel", "Турагентства", "plane", expense),
        c("cat_hotels", "Отели", "bed", expense),
        c("cat_flights", "Авиабилеты", "plane", expense),
        c("cat_rail", "Ж/д билеты", "train", expense),
        c("cat_tolls", "Платные дороги", "road", expense),
        c("cat_auto_services", "Автоуслуги", "wrench", expense),
        c("cat_car_dealers", "Автосалоны", "car", expense),
        c("cat_fines", "Штрафы", "alert-triangle", expense),
        c("cat_gov", "Госуслуги", "landmark", expense),
        c("cat_charity", "Благотворительность", "heart", expense),
        c("cat_nko", "НКО", "heart", expense),
        c("cat_photo", "Фото и копицентры", "camera", expense),
        c("cat_yandex", "Экосистема Яндекс", "sparkles", expense),
        c("cat_service", "Сервис", "wrench", expense),
        c(BANK_FEES, "Услуги банка", "landmark", expense),
        c("cat_cash", "Наличные", "banknote", both),
        c(TRANSFERS, "Переводы", "users", both),
        c("cat_finance", "Финансы", "landmark", both),
        c("cat_dividends", "Дивиденды и купоны", "trending-up", both),
        c("cat_interest", "Проценты", "percent", income),
        c("cat_bonus", "Бонусы", "star", income),
        c("cat_salary", "Зарплата", "briefcase", income),
        c("cat_deposits", "Вклады", "piggy-bank", both),
        c("cat_topup", "Пополнения", "plus-circle", income),
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
