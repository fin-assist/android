package ru.finassist.pf.mock.domain

import ru.finassist.pf.core.api.model.Category
import ru.finassist.pf.core.api.model.CategoryKind
import ru.finassist.pf.core.api.model.SystemCategories

/**
 * Category directory: T-Bank categories as they come in `MEMO`, plus the three system ones. Ids are stable
 * slugs (`cat_supermarkets`); unknown bank categories get a transliterated slug on first sight, which is what
 * the real `bank-statement-data` will do too ("список открытый — новые категории принимаем как есть").
 */
class Catalog(
    /**
     * Known categories in order, system ones included, instead of the built-in list. The calculation fixtures test
     * passes the registry of the fixtures (`fixtures/calc/categories.json` of fin-assist/contracts, FIN-108).
     */
    known: List<Category>? = null,
) {
    private val byName = LinkedHashMap<String, Category>()

    init {
        if (known != null) known.forEach { byName[it.name] = it } else builtIn()
    }

    private fun builtIn() {
        byName["Между своими счетами"] = Category(
            id = SystemCategories.OWN_TRANSFER, name = "Между своими счетами", icon = "transfer", isSystem = true,
            kinds = listOf(CategoryKind.EXPENSE, CategoryKind.INCOME), assignable = true, note = "не в тратах",
        )
        byName["Возврат"] = Category(
            id = SystemCategories.REFUND, name = "Возврат", icon = "repeat", isSystem = true,
            kinds = listOf(CategoryKind.INCOME), assignable = true, note = "уменьшает расходы на покупку",
        )
        byName[UNCATEGORIZED_NAME] = Category(
            id = SystemCategories.UNCATEGORIZED, name = UNCATEGORIZED_NAME, icon = "tag", isSystem = true,
            kinds = listOf(CategoryKind.EXPENSE, CategoryKind.INCOME), assignable = false, note = "в выписке нет категории",
        )
        KNOWN.forEach { (name, slug, icon) -> byName[name] = category(name, slug, icon) }
    }

    val categories: List<Category> get() = byName.values.toList()

    fun byId(id: String): Category? = byName.values.firstOrNull { it.id == id }

    /**
     * A category by name; a bank category seen for the first time is added with both directions, a `cat_<транслит>`
     * id and the `tag` icon (docs/calc.md, 3.1).
     */
    fun byName(name: String): Category = byName.getOrPut(name) {
        var id = "cat_" + slugOf(name)
        var n = 2
        while (byId(id) != null) id = "cat_${slugOf(name)}_${n++}" // another name with the same slug
        Category(id = id, name = name, icon = "tag", isSystem = false, kinds = listOf(CategoryKind.EXPENSE, CategoryKind.INCOME), assignable = true)
    }

    /**
     * Bank category of a `MEMO` (docs/calc.md, 3.1): trimmed and cut to 64 characters; empty, literal «Без категории»
     * or «Возврат» — the system «Без категории» (a credit with `MEMO` «Возврат» is a refund, 3.3).
     */
    fun bankCategory(memo: String): Category {
        val name = memo.trim().take(NAME_LIMIT)
        return if (name.isEmpty() || name == UNCATEGORIZED_NAME || name == REFUND_NAME) uncategorized else byName(name)
    }

    /** An expense bank category: a credit in it is a refund (3.1, 3.3). */
    fun isExpense(category: Category) = category.kinds == listOf(CategoryKind.EXPENSE)

    val transfers: Category get() = byName("Переводы")
    val ownTransfer: Category get() = byName.getValue("Между своими счетами")
    val refund: Category get() = byName.getValue("Возврат")
    val uncategorized: Category get() = byName.getValue(UNCATEGORIZED_NAME)
    val bankFees: Category get() = byName("Услуги банка")

    private fun category(name: String, slug: String, icon: String): Category {
        val kinds = when {
            name in INCOME_ONLY -> listOf(CategoryKind.INCOME)
            name in BOTH_KINDS -> listOf(CategoryKind.EXPENSE, CategoryKind.INCOME)
            else -> listOf(CategoryKind.EXPENSE)
        }
        return Category(id = "cat_$slug", name = name, icon = icon, isSystem = false, kinds = kinds, assignable = true)
    }

    companion object {
        const val UNCATEGORIZED_NAME = "Без категории"
        const val REFUND_NAME = "Возврат"
        /** Length of a bank category name (docs/calc.md, 3.1). */
        const val NAME_LIMIT = 64
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
