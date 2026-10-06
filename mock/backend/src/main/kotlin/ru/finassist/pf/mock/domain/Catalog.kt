package ru.finassist.pf.mock.domain

import ru.finassist.pf.core.api.model.Category
import ru.finassist.pf.core.api.model.CategoryKind
import ru.finassist.pf.core.api.model.SystemCategories

/**
 * Category directory: T-Bank categories as they come in `MEMO`, plus the two system ones. Ids are stable
 * slugs (`cat_supermarkets`); unknown bank categories get a transliterated slug on first sight, which is what
 * the real `bank-statement-data` will do too ("список открытый — новые категории принимаем как есть").
 */
class Catalog {
    private val byName = LinkedHashMap<String, Category>()

    init {
        byName["Между своими счетами"] = Category(
            id = SystemCategories.OWN_TRANSFER, name = "Между своими счетами", icon = "transfer", isSystem = true,
            kinds = listOf(CategoryKind.EXPENSE, CategoryKind.INCOME), assignable = true, note = "не в тратах",
        )
        byName["Возврат"] = Category(
            id = SystemCategories.REFUND, name = "Возврат", icon = "repeat", isSystem = true,
            kinds = listOf(CategoryKind.INCOME), assignable = true, note = "уменьшает расходы на покупку",
        )
        KNOWN.forEach { (name, slug, icon) -> byName[name] = category(name, slug, icon) }
    }

    val categories: List<Category> get() = byName.values.toList()

    fun byId(id: String): Category? = byName.values.firstOrNull { it.id == id }

    fun byName(name: String): Category = byName.getOrPut(name) { category(name, slugOf(name), "tag") }

    val transfers: Category get() = byName("Переводы")
    val ownTransfer: Category get() = byName.getValue("Между своими счетами")
    val refund: Category get() = byName.getValue("Возврат")
    val bankFees: Category get() = byName("Услуги банка")

    /** Bank categories that are never a purchase — a credit in them is income, not a refund (decision-ofx-only.md). */
    fun isIncomeOnly(name: String) = name in INCOME_ONLY

    private fun category(name: String, slug: String, icon: String): Category {
        val kinds = when {
            name in INCOME_ONLY -> listOf(CategoryKind.INCOME)
            name in BOTH_KINDS -> listOf(CategoryKind.EXPENSE, CategoryKind.INCOME)
            else -> listOf(CategoryKind.EXPENSE)
        }
        return Category(id = "cat_$slug", name = name, icon = icon, isSystem = false, kinds = kinds, assignable = true)
    }

    companion object {
        val INCOME_ONLY = setOf("Зарплата", "Пополнения", "Вклады", "Проценты", "Бонусы", "Дивиденды и купоны", "Другое")
        val BOTH_KINDS = setOf("Переводы", "Финансы", "Наличные")

        /** name → (slug, icon). Icon keys match the design-system icon set. */
        private val KNOWN = listOf(
            Triple("Супермаркеты", "supermarkets", "cart"),
            Triple("Переводы", "transfers", "users"),
            Triple("Фастфуд", "fastfood", "coffee"),
            Triple("Рестораны", "restaurants", "utensils"),
            Triple("Такси", "taxi", "car"),
            Triple("Цифровые товары", "digital", "smartphone"),
            Triple("Финансы", "finance", "landmark"),
            Triple("Местный транспорт", "transport", "bus"),
            Triple("Мобильная связь", "mobile", "phone"),
            Triple("Аптеки", "pharmacy", "pill"),
            Triple("Заправки", "fuel", "car"),
            Triple("Благотворительность", "charity", "heart"),
            Triple("Дивиденды и купоны", "dividends", "trending-up"),
            Triple("Маркетплейсы", "marketplaces", "package"),
            Triple("Бонусы", "bonuses", "gift"),
            Triple("Наличные", "cash", "banknote"),
            Triple("Зарплата", "salary", "briefcase"),
            Triple("Различные услуги", "services", "wrench"),
            Triple("Ж/д билеты", "rail", "train"),
            Triple("Сервис", "service", "wrench"),
            Triple("Связь", "telecom", "wifi"),
            Triple("НКО", "nko", "heart"),
            Triple("Услуги банка", "bank_fees", "landmark"),
            Triple("ЖКХ", "utilities", "home"),
            Triple("Проценты", "interest", "percent"),
            Triple("Животные", "pets", "paw"),
            Triple("Медицина", "medicine", "stethoscope"),
            Triple("Авиабилеты", "flights", "plane"),
            Triple("Онлайн-кинотеатры", "streaming", "film"),
            Triple("Платные дороги", "toll_roads", "road"),
            Triple("Искусство", "art", "palette"),
            Triple("Подарки и творчество", "gifts", "gift"),
            Triple("Пополнения", "top_ups", "plus-circle"),
            Triple("Красота", "beauty", "scissors"),
            Triple("Цветы", "flowers", "flower"),
            Triple("Госуслуги", "gov", "building"),
            Triple("Вклады", "deposits", "piggy-bank"),
            Triple("Турагентства", "travel", "map"),
            Triple("Ремонт и мебель", "repair", "hammer"),
            Triple("Ремонт и мебель", "repair", "hammer"),
            Triple("Другое", "other", "tag"),
            Triple("Различные товары", "goods", "package"),
            Triple("Автоуслуги", "auto_services", "car"),
            Triple("Экосистема Яндекс", "yandex", "layers"),
            Triple("Штрафы", "fines", "alert-triangle"),
            Triple("Одежда и обувь", "clothes", "shirt"),
            Triple("Развлечения", "entertainment", "ticket"),
            Triple("Фото и копицентры", "photo", "camera"),
            Triple("Автосалоны", "car_dealers", "car"),
            Triple("Отели", "hotels", "bed"),
            Triple("Тренировки", "fitness", "dumbbell"),
            Triple("Интернет", "internet", "wifi"),
        )

        private val TRANSLIT = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z",
            'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
            'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch",
            'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        )

        fun slugOf(name: String): String = name.lowercase().map { c ->
            when {
                c.isLetterOrDigit() && c.code < 128 -> c.toString()
                c in TRANSLIT -> TRANSLIT.getValue(c)
                else -> "_"
            }
        }.joinToString("").trim('_').replace(Regex("_+"), "_").ifEmpty { "unknown" }
    }
}
