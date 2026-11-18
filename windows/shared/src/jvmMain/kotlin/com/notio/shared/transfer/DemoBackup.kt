package com.notio.shared.transfer

import com.notio.shared.domain.ListDocument
import com.notio.shared.domain.ListEntry
import com.notio.shared.domain.RichAlign
import com.notio.shared.domain.RichDocument
import com.notio.shared.domain.RichList
import com.notio.shared.domain.RichPara
import com.notio.shared.domain.RichRun
import com.notio.shared.domain.RichStyle
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import javax.imageio.ImageIO

/**
 * A demo backup in the Android format, built from the fixtures of the Android tests (`TransferTestSupport.budget`: Food / Bread /
 * Rent; the default settings of `FakeSettings`) plus one note of every kind the desktop can already show. It goes through the
 * real import, so loading it checks the same path a phone's backup takes.
 */
object DemoBackup {

    fun write(out: OutputStream, now: Long = System.currentTimeMillis()) {
        BackupCodec.write(out, file(now), mapOf(BackupCodec.attachmentPath("demo-drawing", "drawing.png") to drawingPng()))
    }

    fun file(now: Long): BackupFile {
        val day = 24L * 60 * 60 * 1000
        fun folder(id: String, name: String, color: String, order: Int, comment: String? = null) = FolderRecord(
            id = id, name = name, colorHex = color, styleId = "SOFT", sortOrder = order,
            createdAt = now - 30 * day, updatedAt = now - 30 * day, comment = comment,
        )

        fun note(id: String, folder: String, title: String, type: String, text: String?, age: Int, extras: String? = null) = NoteRecord(
            id = id, folderId = folder, title = title, type = type, contentText = text, extras = extras,
            createdAt = now - age * day, updatedAt = now - age * day / 2,
        )

        val folders = listOf(
            folder("demo-personal", "Личное", "#3366FF", 0, "Всё для дома"),
            folder("demo-money", "Финансы", "#2E7D32", 1),
            folder("demo-work", "Работа", "#E65100", 2),
        )
        val notes = listOf(
            note(
                "demo-ideas", "demo-personal", "Идеи на выходные", "TEXT",
                "# Идеи\n\n**Суббота:** парк и кино\n*Воскресенье:* дача\n\n- купить семена\n- позвонить маме\n\n[Сайт кинотеатра](https://example.com)",
                5,
            ).copy(isPinned = true, pinnedAt = now - day, tags = listOf("семья")),
            shoppingList(now).copy(colorHex = "#FFF59D", tags = listOf("семья", "магазин")),
            note(
                "demo-schedule", "demo-personal", "Распорядок дня", "SCHEDULE",
                scheduleDoc().fallbackText({ m -> "${m / 60} ч ${m % 60} мин" }, "?"), 10, extras = scheduleDoc().encode(),
            ),
            note("demo-budget", "demo-money", "Бюджет на месяц", "BUDGET", null, 12, extras = """{"currency":"RUB","currencyLabel":"₽"}""")
                .copy(tags = listOf("деньги"), comment = "Проверить в конце месяца"),
            wordNote(now),
            note("demo-drawing", "demo-work", "Схема офиса", "DRAWING", null, 3),
            note("demo-meeting", "demo-work", "Встреча с клиентом", "TEXT", "Обсудить:\n1. сроки\n2. бюджет\n3. следующий шаг", 1)
                .copy(isTitleBold = true),
            note("demo-trashed", "demo-work", "Старый черновик", "TEXT", "Эта заметка лежит в корзине.", 40)
                .copy(deletedAt = now - 2 * day),
        )
        val folderWithFeatured = folders.map { if (it.id == "demo-money") it.copy(featuredNoteId = "demo-budget", featuredMetricsMask = 7) else it }

        // The tree of TransferTestSupport.budget(): a group "Food" holding "Bread" (10.00 / 25.00) and a leaf "Rent" (500.00 / 400.00).
        fun node(id: String, parent: String?, title: String, kind: String, sum: Long = 0, goal: Long = 0, order: Int = 0, quantity: Int = 1) =
            BudgetNodeRecord(
                id = id, noteId = "demo-budget", parentId = parent, title = title, kind = kind, manualSumMinor = sum,
                manualGoalMinor = goal, sortOrder = order, createdAt = now - 12 * day, updatedAt = now - 12 * day, quantity = quantity,
            )
        val nodes = listOf(
            node("demo-b-root", null, "Бюджет на месяц", "GROUP"),
            node("demo-b-food", "demo-b-root", "Еда", "GROUP", order = 0),
            node("demo-b-bread", "demo-b-food", "Хлеб", "LEAF", 1_000, 2_500, 0),
            node("demo-b-milk", "demo-b-food", "Молоко", "LEAF", 9_000, 10_000, 1, quantity = 2),
            node("demo-b-rent", "demo-b-root", "Аренда", "LEAF", 50_000, 40_000, 1),
        )
        val reminder = ReminderRecord(
            id = "demo-reminder", noteId = "demo-meeting", triggerAt = now + day, createdAt = now, updatedAt = now,
        )
        val log = listOf(
            BudgetLogRecord("demo-log-1", "demo-budget", now - 3 * day, "SUM", "Хлеб", 0, 1_000),
            BudgetLogRecord("demo-log-2", "demo-budget", now - 2 * day, "SUM", "Аренда", 0, 50_000),
        )
        return BackupFile(
            createdAt = now,
            appVersion = "demo",
            folders = folderWithFeatured,
            notes = notes,
            budgetNodes = nodes,
            reminders = listOf(reminder),
            settings = SettingsRecord("ru", "DARK", "TITLE_ONLY", trashRetentionDays = 60),
            budgetLog = log,
        )
    }

    /** A day's schedule (D155): four times and a calculator of the time from getting up to lunch. */
    private fun scheduleDoc() = com.notio.shared.domain.ScheduleDocument(
        entries = listOf(
            com.notio.shared.domain.ScheduleEntry("s1", text = "Подъём", minutes = 7 * 60),
            com.notio.shared.domain.ScheduleEntry("s2", text = "Работа", minutes = 8 * 60 + 30),
            com.notio.shared.domain.ScheduleEntry("s3", text = "Обед", minutes = 13 * 60),
            com.notio.shared.domain.ScheduleEntry("s4", text = "Спортзал", minutes = 18 * 60, comment = "Не забыть форму"),
            com.notio.shared.domain.ScheduleEntry("s5", kind = com.notio.shared.domain.ScheduleEntry.CALC, text = "До обеда", fromId = "s1", toId = "s3"),
        ),
    )

    /** A list in the price mode (D92, D101): items with quantities, units and prices, and a total by unit, as the phone stores it. */
    private fun shoppingList(now: Long): NoteRecord {
        fun item(id: String, text: String, q: Long?, unit: String?, price: Long?, done: Boolean = false) = ListEntry(
            id = id, text = text, done = done, quantityMilli = q, unit = unit, priceMilli = price, priceUnit = if (price != null) "rub" else null, createdAt = now,
        )
        val doc = ListDocument(
            entries = listOf(
                item("l1", "Хлеб", 1_000, "pcs", 45_000, done = true),
                item("l2", "Молоко", 2_000, "l", 89_000),
                item("l3", "Яйца", 10_000, "pcs", 12_000),
                item("l4", "Сыр", 300, "kg", 950_000),
                ListEntry(id = "l5", kind = ListEntry.TOTAL, text = "Штук всего", unit = "pcs", createdAt = now),
            ),
            mode = ListDocument.MODE_PRICE,
        )
        val labels = mapOf("pcs" to "шт", "kg" to "кг", "l" to "л", "rub" to "₽")
        val encoded = doc.encode({ labels[it] ?: it }, emptyMap())
        val day = 24L * 60 * 60 * 1000
        return NoteRecord(
            id = "demo-shopping", folderId = "demo-personal", title = "Покупки", type = "LIST", contentText = encoded.contentText,
            extras = encoded.extras, createdAt = now - 2 * day, updatedAt = now - day,
        )
    }

    /** A formatted note (D214): a big bold centred heading, a list with a coloured word and a check box, stored as the phone stores it. */
    private fun wordNote(now: Long): NoteRecord {
        val text = "Договор аренды квартиры\nСрок: 12 месяцев\nОплата: до 5 числа\nЗалог: один месяц\nПроверить счётчики"
        val doc = RichDocument(
            text,
            runs = listOf(
                RichRun(0, 23, RichStyle(bold = true, size = 1.5f)),
                RichRun(24, 29, RichStyle(bold = true)),
                RichRun(49, 59, RichStyle(color = 0xFFD32F2F.toInt())),
            ),
            paras = listOf(
                RichPara(align = RichAlign.CENTER),
                RichPara(list = RichList.BULLET),
                RichPara(list = RichList.BULLET),
                RichPara(list = RichList.BULLET),
                RichPara(list = RichList.CHECK),
            ),
        ).normalized()
        val day = 24L * 60 * 60 * 1000
        return NoteRecord(
            id = "demo-word", folderId = "demo-money", title = "Договор аренды", type = "WORD", contentText = text,
            extras = doc.encode(), createdAt = now - 20 * day, updatedAt = now - 10 * day,
        )
    }

    /** A small office plan, drawn here so the demo has a real picture to show. */
    fun drawingPng(): ByteArray {
        val img = BufferedImage(640, 400, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, 640, 400)
        g.color = Color(0x33, 0x66, 0xFF)
        g.stroke = BasicStroke(6f)
        g.drawRect(40, 40, 560, 320)
        g.drawLine(320, 40, 320, 240)
        g.drawLine(40, 240, 600, 240)
        g.color = Color(0xE6, 0x51, 0x00)
        g.fillOval(150, 120, 60, 60)
        g.fillOval(430, 120, 60, 60)
        g.color = Color.DARK_GRAY
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 22)
        g.drawString("Кабинет 1", 120, 220)
        g.drawString("Кабинет 2", 400, 220)
        g.drawString("Коридор", 270, 310)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }
}
