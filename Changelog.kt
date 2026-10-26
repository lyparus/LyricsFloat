package io.github.lyricsfloat

/** История версий для экрана «Что нового». Новые версии — сверху. */
object Changelog {
    data class Release(val versionCode: Int, val version: String, val items: List<String>)

    val releases = listOf(
        Release(11, "1.9", listOf(
            "Экран «Что нового» — показывает изменения после обновления",
        )),
        Release(10, "1.8", listOf(
            "Подпись автора перенесена вниз главного экрана",
        )),
        Release(9, "1.7", listOf(
            "Настройки вида в отдельной панели: шрифт, сдвиг текста, прозрачность — с живым предпросмотром",
            "Новый главный экран с карточками",
            "Обучалка по основным функциям",
        )),
        Release(8, "1.6", listOf(
            "Запасной источник текстов — NetEase",
            "Тап по строке перематывает трек",
            "Автопрокрутка для текстов без таймкодов",
        )),
        Release(7, "1.5", listOf(
            "Работа в фоне на vivo и Xiaomi после закрытия из недавних",
        )),
        Release(5, "1.4", listOf(
            "Управление плеером на полном экране: пауза, треки, перемотка",
        )),
        Release(4, "1.3", listOf(
            "Полноэкранный режим с обложкой на фоне",
            "Экран не гаснет, пока открыт текст",
        )),
    )

    /** Версии новее [sinceCode], но не новее установленной [currentCode]. */
    fun newerThan(sinceCode: Int, currentCode: Int) =
        releases.filter { it.versionCode in (sinceCode + 1)..currentCode }
}
