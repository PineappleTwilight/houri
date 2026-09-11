package tachiyomi.domain.achievement.model

import kotlinx.serialization.Serializable

@Serializable
data class Achievement(
    val id: String,
    val title: String,
    val description: String,
    val tier: AchievementTier,
    val category: AchievementCategory = AchievementCategory.READING,
    val isSecret: Boolean = false,
    val isNegative: Boolean = false,
    val isRotating: Boolean = false,
    val icon: String = "🏆",
    val unlockedAt: Long? = null,
) {
    val isUnlocked: Boolean get() = unlockedAt != null
    // Negative achievements do not count toward progress/rank.
    val countsTowardsProgress: Boolean get() = !isNegative
    val displayTitle: String get() = if (isSecret && unlockedAt == null) "???" else title
    val displayDescription: String get() = if (isSecret && unlockedAt == null) "Secret achievement — keep exploring" else description
    val displayIcon: String get() = if (isSecret && unlockedAt == null) "❓" else icon
}

enum class AchievementTier { BRONZE, SILVER, GOLD, PLATINUM, LEGENDARY, MYTHIC, ULTIMATE }

enum class AchievementCategory { READING, LIBRARY, TRACKER, TRANSLATION, SOCIAL, EXPLORATION, ROTATING }

@Serializable
data class AchievementStats(
    val organicChaptersRead: Long = 0,
    val mangaFinished: Long = 0,
    val mangaCaughtUp: Long = 0,
    val libraryCount: Long = 0,
    val totalAchievements: Int = 0,
    val unlockedCount: Int = 0,
    val secretUnlocked: Int = 0,
    val readingTimeMinutes: Long = 0,
    val backlogCount: Long = 0,
    val negativeUnlocked: Int = 0,
) {
    val rank: String get() = when {
        unlockedCount >= 150 && organicChaptersRead >= 5000 && mangaFinished >= 50 -> "Ultimate"
        unlockedCount >= 120 && organicChaptersRead >= 2000 && mangaFinished >= 25 -> "Mythic"
        unlockedCount >= 90 && organicChaptersRead >= 1000 && mangaFinished >= 15 -> "Legend"
        unlockedCount >= 60 && organicChaptersRead >= 500 && mangaFinished >= 8 -> "Master"
        unlockedCount >= 35 && organicChaptersRead >= 250 -> "Veteran"
        unlockedCount >= 18 -> "Explorer"
        unlockedCount >= 8 -> "Apprentice"
        else -> "Novice"
    }

    val rankTier: AchievementTier get() = when (rank) {
        "Ultimate" -> AchievementTier.ULTIMATE
        "Mythic" -> AchievementTier.MYTHIC
        "Legend" -> AchievementTier.LEGENDARY
        "Master" -> AchievementTier.PLATINUM
        "Veteran" -> AchievementTier.GOLD
        "Explorer" -> AchievementTier.SILVER
        else -> AchievementTier.BRONZE
    }
}

object Achievements {
    private fun a(
        id: String,
        title: String,
        description: String,
        tier: AchievementTier,
        category: AchievementCategory = AchievementCategory.READING,
        isSecret: Boolean = false,
        isNegative: Boolean = false,
        isRotating: Boolean = false,
        icon: String = "🏆",
    ) = Achievement(id, title, description, tier, category, isSecret, isNegative, isRotating, icon)

    val all: List<Achievement> by lazy {
        listOf(
            // === READING — milestones (organic only) ===
            a("first_chapter", "First Steps", "Read your first chapter organically", AchievementTier.BRONZE, icon = "🌱"),
            a("ten_chapters", "Getting Started", "Read 10 chapters organically", AchievementTier.BRONZE, AchievementCategory.READING, icon = "🌿"),
            a("twenty_five", "Quarter Century", "Read 25 chapters", AchievementTier.BRONZE, AchievementCategory.READING, icon = "📗"),
            a("fifty_chapters", "Half Century", "Read 50 chapters organically", AchievementTier.SILVER, AchievementCategory.READING, icon = "📘"),
            a("hundred_chapters", "Century", "Read 100 chapters organically", AchievementTier.SILVER, AchievementCategory.READING, icon = "💯"),
            a("two_fifty", "Quarter K", "Read 250 chapters", AchievementTier.GOLD, AchievementCategory.READING, icon = "📚"),
            a("five_hundred", "Marathon", "Read 500 chapters organically", AchievementTier.GOLD, AchievementCategory.READING, icon = "🏃"),
            a("thousand", "Legend", "Read 1000 chapters organically", AchievementTier.PLATINUM, AchievementCategory.READING, icon = "👑"),
            a("two_thousand", "Mythic", "Read 2000 chapters", AchievementTier.LEGENDARY, AchievementCategory.READING, icon = "🐉"),
            a("five_thousand", "Houri Legend", "Read 5000 chapters organically", AchievementTier.LEGENDARY, AchievementCategory.READING, icon = "🌟"),
            a("ten_thousand", "Ink Drinker", "Read 10,000 chapters", AchievementTier.MYTHIC, AchievementCategory.READING, icon = "🩸"),
            // reading streaks & tempo
            a("streak_3", "Three Day Run", "Read 3 days in a row", AchievementTier.BRONZE, AchievementCategory.READING, icon = "🔥"),
            a("streak_7", "Weekly Habit", "Read 7 days in a row", AchievementTier.SILVER, AchievementCategory.READING, icon = "📅"),
            a("streak_30", "Monthly Devotee", "Read 30 days in a row", AchievementTier.GOLD, AchievementCategory.READING, icon = "🗓️"),
            a("streak_100", "Unbreakable", "Read 100 days in a row", AchievementTier.PLATINUM, AchievementCategory.READING, icon = "⛓️"),
            a("binge_10", "Binge Reader", "Read 10 chapters in one day", AchievementTier.SILVER, AchievementCategory.READING, icon = "⚡"),
            a("binge_50", "Binge Lord", "Read 50 chapters in one day", AchievementTier.GOLD, AchievementCategory.READING, icon = "💥"),
            a("binge_100", "Ascended Binger", "Read 100 chapters in one day", AchievementTier.PLATINUM, AchievementCategory.READING, icon = "🌪️"),
            a("weekend_warrior", "Weekend Warrior", "Read 20 chapters over a weekend", AchievementTier.SILVER, AchievementCategory.READING, icon = "🛡️"),
            a("early_bird", "Early Bird", "Read a chapter before 6 AM", AchievementTier.BRONZE, AchievementCategory.READING, icon = "🐦"),
            a("night_owl", "Night Owl", "Read 10 chapters after midnight", AchievementTier.BRONZE, AchievementCategory.SOCIAL, icon = "🦉"),
            a("lunch_break", "Lunch Break", "Read a chapter at noon (11:30–13:30)", AchievementTier.BRONZE, AchievementCategory.READING, icon = "🍱"),
            // manga completion
            a("first_manga_finished", "Finisher", "Complete a manga", AchievementTier.SILVER, AchievementCategory.READING, icon = "✅"),
            a("five_manga_finished", "Collector", "Complete 5 manga", AchievementTier.GOLD, AchievementCategory.READING, icon = "🗃️"),
            a("ten_manga_finished", "Completionist", "Complete 10 manga", AchievementTier.PLATINUM, AchievementCategory.READING, icon = "🏆"),
            a("twenty_manga_finished", "Grand Completionist", "Complete 20 manga", AchievementTier.PLATINUM, AchievementCategory.READING, icon = "🎖️"),
            a("fifty_manga_finished", "Wholesome Finisher", "Complete 50 manga", AchievementTier.LEGENDARY, AchievementCategory.READING, icon = "👑"),
            a("first_manga_caught_up", "Up To Date", "Catch up to an ongoing manga", AchievementTier.SILVER, AchievementCategory.READING, icon = "📖"),
            a("five_manga_caught_up", "Avid Follower", "Catch up to 5 ongoing manga", AchievementTier.GOLD, AchievementCategory.READING, icon = "📚"),
            a("ten_manga_caught_up", "Dedicated Follower", "Catch up to 10 ongoing manga", AchievementTier.PLATINUM, AchievementCategory.READING, icon = "🔖"),
            a("twenty_manga_caught_up", "Series Tracker", "Catch up to 20 ongoing manga", AchievementTier.PLATINUM, AchievementCategory.READING, icon = "📌"),
            a("fifty_manga_caught_up", "Completionist Lite", "Catch up to 50 ongoing manga", AchievementTier.LEGENDARY, AchievementCategory.READING, icon = "🗂️"),
            a("one_shot", "One-Shot Wonder", "Complete a single-chapter manga", AchievementTier.BRONZE, AchievementCategory.READING, icon = "🎯"),
            a("long_runner", "Long Runner", "Complete a 200+ chapter manga", AchievementTier.GOLD, AchievementCategory.READING, icon = "🏔️"),
            a("ultra_long", "Ultra Marathon", "Complete a 500+ chapter series", AchievementTier.MYTHIC, AchievementCategory.READING, icon = "🗻"),
            // rereading
            a("rereader", "Rereader", "Reread a manga", AchievementTier.SILVER, AchievementCategory.READING, icon = "🔁"),
            a("reread_five", "Nostalgic", "Reread 5 manga", AchievementTier.GOLD, AchievementCategory.READING, icon = "💭"),
            a("reread_twenty", "Eternal Return", "Reread 20 manga", AchievementTier.PLATINUM, AchievementCategory.READING, icon = "♾️"),
            a("reread_hundred", "Living Library", "Reread 100 times total", AchievementTier.LEGENDARY, AchievementCategory.READING, icon = "📜"),
            // reading modes & preferences
            a("webtoon_pager_both", "Versatile Reader", "Finish a manga in both pager and webtoon mode", AchievementTier.SILVER, AchievementCategory.READING, icon = "🔀"),
            a("double_page", "Panorama Lover", "Read a chapter in double-page mode", AchievementTier.BRONZE, AchievementCategory.READING, icon = "🖼️"),
            a("rtl_reader", "Right to Left", "Complete a manga in RTL mode", AchievementTier.BRONZE, AchievementCategory.READING, icon = "⬅️"),
            a("vertical_reader", "Vertical Scroller", "Complete a manga in vertical/webtoon mode", AchievementTier.BRONZE, AchievementCategory.READING, icon = "⬇️"),
            a("cutout_enthusiast", "Cutout Connoisseur", "Read with cutout enabled", AchievementTier.BRONZE, AchievementCategory.READING, icon = "✂️"),
            a("incognito_reader", "Shadow Reader", "Read 10 chapters in incognito", AchievementTier.BRONZE, AchievementCategory.READING, icon = "🥷"),
            // === LIBRARY ===
            a("library_1", "First Shelf", "Add your first manga to library", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "📥"),
            a("library_5", "Shelf Starter", "Add 5 manga to library", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "📦"),
            a("library_10", "Librarian", "Add 10 manga to library", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "📚"),
            a("library_25", "Stacker", "Add 25 manga to library", AchievementTier.SILVER, AchievementCategory.LIBRARY, icon = "📚"),
            a("library_50", "Curator", "Add 50 manga to library", AchievementTier.SILVER, AchievementCategory.LIBRARY, icon = "🗄️"),
            a("library_100", "Archivist", "Add 100 manga to library", AchievementTier.GOLD, AchievementCategory.LIBRARY, icon = "🏛️"),
            a("library_250", "Hoarder", "Add 250 manga to library", AchievementTier.PLATINUM, AchievementCategory.LIBRARY, icon = "🏚️"),
            a("library_500", "Infinite Shelves", "Add 500 manga to library", AchievementTier.LEGENDARY, AchievementCategory.LIBRARY, icon = "♾️"),
            a("library_1000", "Houri Akashic", "Add 1000 manga to library", AchievementTier.MYTHIC, AchievementCategory.LIBRARY, icon = "🌌"),
            a("category_master", "Organizer", "Create 5 categories", AchievementTier.SILVER, AchievementCategory.EXPLORATION, icon = "🗂️"),
            a("category_ten", "Mega Organizer", "Create 10 categories", AchievementTier.GOLD, AchievementCategory.LIBRARY, icon = "🗃️"),
            a("subcategory_creator", "Nester", "Create a subcategory", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "📁"),
            a("subcategory_five", "Folder Master", "Create 5 subcategories", AchievementTier.SILVER, AchievementCategory.LIBRARY, icon = "🗂️"),
            a("subcategory_twenty", "Taxonomist", "Create 20 subcategories", AchievementTier.GOLD, AchievementCategory.LIBRARY, icon = "🧬"),
            a("cover_custom", "Cover Artist", "Set a custom cover", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "🎨"),
            a("cover_cropped", "Crop Master", "Crop a custom cover", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "✂️"),
            a("cover_ten", "Gallery Curator", "Set 10 custom covers", AchievementTier.SILVER, AchievementCategory.LIBRARY, icon = "🖌️"),
            a("censor_toggle", "Modesty Panel", "Enable censor lewd manga", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "🫣"),
            a("staggered_grid", "Stagger Stunner", "Enable staggered library grid", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "🧱"),
            a("feed_user", "Feed Me", "Enable the Feed and add a saved search", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "📰"),
            a("smart_filter", "Smart Filterer", "Enable smart scanlator filter", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "🧠"),
            a("merge_fan", "Merger", "Merge two manga entries", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "🔗"),
            a("full_library_backup", "Hoarder Backup", "Create a library backup", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "💾"),
            // === TRACKER ===
            a("tracker_connected", "Connected", "Connect a tracker", AchievementTier.BRONZE, AchievementCategory.TRACKER, icon = "🔌"),
            a("tracker_two", "Double Linked", "Connect 2 trackers", AchievementTier.BRONZE, AchievementCategory.TRACKER, icon = "🔗"),
            a("tracker_three", "Tracker Trio", "Connect 3 trackers", AchievementTier.SILVER, AchievementCategory.TRACKER, icon = "⛓️"),
            a("tracker_five", "Tracker Legion", "Connect 5 trackers", AchievementTier.GOLD, AchievementCategory.TRACKER, icon = "🕸️"),
            a("tracker_all", "Omni-Tracker", "Connect 8+ trackers", AchievementTier.PLATINUM, AchievementCategory.TRACKER, icon = "🌐"),
            a("track_score", "Critic", "Score a manga on a tracker", AchievementTier.BRONZE, AchievementCategory.TRACKER, icon = "⭐"),
            a("track_status", "Status Updater", "Update tracker status 10 times", AchievementTier.SILVER, AchievementCategory.TRACKER, icon = "📝"),
            a("fill_metadata", "Info Broker", "Fill manga metadata from tracker", AchievementTier.BRONZE, AchievementCategory.TRACKER, icon = "📋"),
            a("fill_metadata_tags", "Tag Dealer", "Fill tags + status from tracker", AchievementTier.SILVER, AchievementCategory.TRACKER, icon = "🏷️"),
            a("definitive_tracker", "Definitive Source", "Set a definitive tracker source for a manga/category", AchievementTier.SILVER, AchievementCategory.TRACKER, icon = "📌"),
            a("tracker_mangaupdates", "MangaUpdates Maven", "Use MangaUpdates tracker", AchievementTier.BRONZE, AchievementCategory.TRACKER, icon = "🦉"),
            a("tracker_anilist", "AniLyst", "Use AniList tracker", AchievementTier.BRONZE, AchievementCategory.TRACKER, icon = "🌸"),
            // === TRANSLATION / MTL (blanket AI) ===
            a("translator", "Polyglot", "Translate a chapter", AchievementTier.SILVER, AchievementCategory.TRANSLATION, icon = "🌐"),
            a("translator_five", "Interpreter", "Translate 5 chapters", AchievementTier.SILVER, AchievementCategory.TRANSLATION, icon = "🗣️"),
            a("translator_ten", "Bridge Builder", "Translate 10 chapters", AchievementTier.GOLD, AchievementCategory.TRANSLATION, icon = "🌉"),
            a("translator_fifty", "United Nations", "Translate 50 chapters", AchievementTier.PLATINUM, AchievementCategory.TRANSLATION, icon = "🏛️"),
            a("translator_hundred", "Babel Fish", "Translate 100 chapters", AchievementTier.LEGENDARY, AchievementCategory.TRANSLATION, icon = "🐟"),
            a("mtl_offline", "Offline Polyglot", "Translate with Gemini Nano offline fallback", AchievementTier.SILVER, AchievementCategory.TRANSLATION, icon = "📴"),
            a("mtl_local", "Local LLM Whisperer", "Translate with a local GGUF model", AchievementTier.GOLD, AchievementCategory.TRANSLATION, icon = "🤖"),
            a("mtl_vision", "Eagle Eye", "Translate with vision-aware local model (mmproj)", AchievementTier.GOLD, AchievementCategory.TRANSLATION, icon = "🦅"),
            a("mtl_glossary", "Glossarian", "Create a translation glossary", AchievementTier.BRONZE, AchievementCategory.TRANSLATION, icon = "📖"),
            a("mtl_info", "Info Translator", "Translate manga title/description", AchievementTier.BRONZE, AchievementCategory.TRANSLATION, icon = "🏷️"),
            a("mtl_auto_download", "Auto Translator", "Enable auto-translate on download", AchievementTier.BRONZE, AchievementCategory.TRANSLATION, icon = "🤹"),
            a("upscale_first", "Sharp Eye", "Upscale a page (MTL-gated)", AchievementTier.BRONZE, AchievementCategory.TRANSLATION, icon = "🔍"),
            a("upscale_ten", "HD Fan", "Upscale 10 pages", AchievementTier.SILVER, AchievementCategory.TRANSLATION, icon = "🖥️"),
            a("upscale_hundred", "Pixel Purist", "Upscale 100 pages", AchievementTier.GOLD, AchievementCategory.TRANSLATION, icon = "🎞️"),
            a("upscale_vulkan", "Vulkan Forged", "Upscale with Vulkan backend", AchievementTier.SILVER, AchievementCategory.TRANSLATION, icon = "🌋"),
            a("upscale_npu", "Neural Boost", "Upscale with NPU backend", AchievementTier.GOLD, AchievementCategory.TRANSLATION, icon = "🧠"),
            // === EXPLORATION / SYSTEM ===
            a("download_one", "First Download", "Download a chapter", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, icon = "⬇️"),
            a("download_ten", "Offline Ready", "Download 10 chapters", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, icon = "📥"),
            a("download_hundred", "Doomsday Prepper", "Download 100 chapters", AchievementTier.SILVER, AchievementCategory.EXPLORATION, icon = "🏕️"),
            a("download_thousand", "Local Archivist", "Download 1000 chapters", AchievementTier.GOLD, AchievementCategory.EXPLORATION, icon = "🗄️"),
            a("sources_five", "Source Explorer", "Add 5 different sources", AchievementTier.SILVER, AchievementCategory.EXPLORATION, icon = "🧭"),
            a("sources_ten", "Source Conqueror", "Add 10 sources", AchievementTier.GOLD, AchievementCategory.EXPLORATION, icon = "🚩"),
            a("eh_enabled", "Forbidden Library", "Enable E-Hentai/ExHentai", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, icon = "🔞"),
            a("decensored", "Uncensored Eye", "Find a decensored/uncensored manga", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, icon = "👁️"),
            a("mihon_port", "Mihon Pilgrim", "Use a Mihon-backported feature", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, icon = "⛩️"),
            a("webgpu", "GPU Reader", "Read with WebGPU viewer", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, icon = "🎮"),
            a("data_saver", "Data Saver", "Enable Data Saver for a source", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, icon = "📉"),
            a("search_ast", "AST Searcher", "Use advanced search (AST) with filters", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, icon = "🔎"),
            a("backup_created", "Safety Net", "Create a backup", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, icon = "💾"),
            a("backup_restored", "Phoenix", "Restore a backup", AchievementTier.SILVER, AchievementCategory.EXPLORATION, icon = "🦅"),
            a("ltr_reader", "Left to Right", "Complete a manga in LTR mode", AchievementTier.BRONZE, AchievementCategory.READING, icon = "➡️"),
            a("reading_time_1h", "First Hour", "Accumulate 1 hour of reading", AchievementTier.BRONZE, AchievementCategory.READING, icon = "⏰"),
            a("reading_time_10h", "Time Well Spent", "Accumulate 10 hours of reading", AchievementTier.SILVER, AchievementCategory.READING, icon = "⏳"),
            a("reading_time_50h", "Dedicated Reader", "Accumulate 50 hours of reading", AchievementTier.GOLD, AchievementCategory.READING, icon = "🕰️"),
            a("reading_time_100h", "Century Hours", "Accumulate 100 hours of reading", AchievementTier.PLATINUM, AchievementCategory.READING, icon = "⌛"),
            a("reading_time_500h", "Time Master", "Accumulate 500 hours of reading", AchievementTier.LEGENDARY, AchievementCategory.READING, icon = "⏱️"),
            a("reading_time_1000h", "Chronos", "Accumulate 1000 hours of reading", AchievementTier.MYTHIC, AchievementCategory.READING, icon = "🌌"),
            a("backlog_10", "Growing Pile", "Have 10 unread manga in backlog (library minus finished)", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "📑"),
            a("backlog_25", "Backlog Builder", "Have 25 unread manga in backlog", AchievementTier.SILVER, AchievementCategory.LIBRARY, icon = "📚"),
            a("backlog_50", "Pile of Shame", "Have 50 unread manga in backlog", AchievementTier.GOLD, AchievementCategory.LIBRARY, icon = "🗻"),
            a("backlog_100", "Infinite Backlog", "Have 100 unread manga in backlog", AchievementTier.PLATINUM, AchievementCategory.LIBRARY, icon = "🏔️"),
            a("backlog_250", "Hoarder's Guilt", "Have 250 unread manga in backlog", AchievementTier.LEGENDARY, AchievementCategory.LIBRARY, icon = "🌋"),
            a("backlog_cleared_10", "Backlog Slayer", "Clear 10 manga from backlog (finish backlog items)", AchievementTier.SILVER, AchievementCategory.LIBRARY, icon = "⚔️"),
            a("backlog_cleared_100", "Backlog Annihilator", "Clear 100 manga from backlog", AchievementTier.MYTHIC, AchievementCategory.LIBRARY, icon = "🔥"),
            a("negative_binge_guilt", "Binge Guilt", "Read 100 chapters in a day then not read for 3 days", AchievementTier.BRONZE, AchievementCategory.READING, isNegative = true, icon = "😓"),
            a("negative_abandoned", "Abandoned", "Add 50 manga to library but finish none", AchievementTier.BRONZE, AchievementCategory.LIBRARY, isNegative = true, icon = "💔"),
            a("negative_midnight_oil", "Burnt Out", "Read past 3 AM 5 times", AchievementTier.SILVER, AchievementCategory.SOCIAL, isNegative = true, icon = "🥱"),
            a("negative_spoiled", "Spoiler Alert", "Skip to last chapter without reading middle", AchievementTier.BRONZE, AchievementCategory.READING, isNegative = true, icon = "🤦"),
            a("negative_hoarder_shame", "Hoarder's Shame", "Library 500 but 0 finished", AchievementTier.SILVER, AchievementCategory.LIBRARY, isNegative = true, icon = "🫣"),
            a("negative_rage_quit", "Rage Quit", "Drop 10 manga without finishing", AchievementTier.BRONZE, AchievementCategory.READING, isNegative = true, icon = "😡"),
            a("negative_do_not_disturb", "Do Not Disturb", "Ignore 20 update notifications", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, isNegative = true, icon = "🔕"),
            a("ultimate_ink_god", "Ink God", "Read 20,000 chapters", AchievementTier.ULTIMATE, AchievementCategory.READING, icon = "👁️"),
            a("ultimate_eternal_library", "Eternal Library", "Maintain library of 2000 manga", AchievementTier.ULTIMATE, AchievementCategory.LIBRARY, icon = "🏛️"),
            a("ultimate_time_dilation", "Time Dilation", "Accumulate 2000 hours reading", AchievementTier.ULTIMATE, AchievementCategory.READING, icon = "🌀"),
            a("ultimate_perfection", "Absolute Perfection", "Unlock 200 non-negative achievements", AchievementTier.ULTIMATE, AchievementCategory.SOCIAL, icon = "💫"),
            a("ultimate_secret_hunter_ultimate", "Ultimate Hunter", "Unlock 20 secret achievements", AchievementTier.ULTIMATE, AchievementCategory.SOCIAL, isSecret = true, icon = "🕶️"),
            // === SOCIAL / MISC ===
            a("discord_rpc", "Discord Famous", "Enable Discord RPC", AchievementTier.BRONZE, AchievementCategory.SOCIAL, icon = "🎧"),
            a("webhook", "Webhook Wizard", "Configure a webhook", AchievementTier.BRONZE, AchievementCategory.SOCIAL, icon = "🪝"),
            a("moan_enabled", "Audible Joy", "Enable chapter completion moan", AchievementTier.BRONZE, AchievementCategory.SOCIAL, icon = "🔊"),
            a("moan_legendary", "Legendary Moan", "Hear a legendary (10%) moan", AchievementTier.GOLD, AchievementCategory.SOCIAL, icon = "💎"),
            a("mango_easter", "Mango Found", "Find the mango easter egg", AchievementTier.SILVER, AchievementCategory.SOCIAL, isSecret = true, icon = "🥭"),
            a("eh_browsed", "Forbidden Browsing", "Browse E-Hentai/ExHentai for the first time", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, icon = "👀"),
            a("rotating_daily_read_5", "Daily Sprint", "Read 5 chapters today", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "🏃"),
            a("rotating_daily_read_15", "Daily Marathon", "Read 15 chapters today", AchievementTier.SILVER, AchievementCategory.ROTATING, isRotating = true, icon = "🏅"),
            a("rotating_daily_library_add_3", "Daily Collector", "Add 3 manga to library today", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "📥"),
            a("rotating_daily_finish_1", "Daily Finisher", "Finish a manga today", AchievementTier.SILVER, AchievementCategory.ROTATING, isRotating = true, icon = "✅"),
            a("rotating_daily_tracker_update_3", "Daily Tracker", "Update tracker 3 times today", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "📝"),
            a("rotating_daily_translate_2", "Daily Polyglot", "Translate 2 chapters today", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "🌐"),
            a("rotating_daily_streak_bonus", "Streak Keeper", "Read 2 days in a row this week", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "🔥"),
            a("rotating_daily_morning_read", "Morning Pages", "Read before 8 AM today", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "🌅"),
            a("rotating_daily_midnight_read", "Midnight Pages", "Read after 11 PM today", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "🌙"),
            a("rotating_daily_search_5", "Daily Explorer", "Search 5 times today", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "🔍"),
            a("rotating_weekly_read_30", "Weekly Grind", "Read 30 chapters this week", AchievementTier.SILVER, AchievementCategory.ROTATING, isRotating = true, icon = "📚"),
            a("rotating_weekly_read_75", "Weekly Binge", "Read 75 chapters this week", AchievementTier.GOLD, AchievementCategory.ROTATING, isRotating = true, icon = "💥"),
            a("rotating_weekly_library_10", "Weekly Curator", "Add 10 manga this week", AchievementTier.SILVER, AchievementCategory.ROTATING, isRotating = true, icon = "🗂️"),
            a("rotating_weekly_finish_3", "Weekly Completionist", "Finish 3 manga this week", AchievementTier.GOLD, AchievementCategory.ROTATING, isRotating = true, icon = "🏆"),
            a("rotating_weekly_translate_10", "Weekly Translator", "Translate 10 chapters this week", AchievementTier.GOLD, AchievementCategory.ROTATING, isRotating = true, icon = "🗣️"),
            a("rotating_weekly_reread_2", "Weekly Nostalgia", "Reread 2 manga this week", AchievementTier.SILVER, AchievementCategory.ROTATING, isRotating = true, icon = "🔁"),
            a("rotating_weekly_upload_cover_3", "Weekly Artist", "Set 3 custom covers this week", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "🎨"),
            a("rotating_weekly_category_2", "Weekly Organizer", "Create 2 categories this week", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "🗃️"),
            a("rotating_weekly_tracker_5", "Weekly Tracker Pro", "Update tracker status 5 times this week", AchievementTier.SILVER, AchievementCategory.ROTATING, isRotating = true, icon = "⭐"),
            a("rotating_weekly_backup", "Weekly Safety", "Create a backup this week", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "💾"),
            a("rotating_daily_genre_explore", "Genre Hopper", "Browse 3 different sources today", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "🧭"),
            a("rotating_weekly_upscale_20", "Weekly Sharpener", "Upscale 20 pages this week", AchievementTier.SILVER, AchievementCategory.ROTATING, isRotating = true, icon = "🔍"),
            a("rotating_daily_webtoon_5", "Webtoon Daily", "Read 5 webtoon chapters today", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "⬇️"),
            a("rotating_weekly_night_owl", "Owl Week", "Read after midnight 3 nights this week", AchievementTier.SILVER, AchievementCategory.ROTATING, isRotating = true, icon = "🦉"),
            a("rotating_daily_backlog_clear_1", "Backlog Chip", "Clear 1 backlog item today", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "⚔️"),
            a("rotating_weekly_ltr_3", "LTR Week", "Complete 3 manga in LTR mode this week", AchievementTier.SILVER, AchievementCategory.ROTATING, isRotating = true, icon = "➡️"),
            a("rotating_daily_data_saver_5", "Saver Daily", "Read 5 chapters with Data Saver today", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "📉"),
            a("rotating_weekly_data_saver_20", "Saver Weekly", "Read 20 chapters with Data Saver this week", AchievementTier.SILVER, AchievementCategory.ROTATING, isRotating = true, icon = "📊"),
            a("rotating_daily_extra_1", "Quick Read", "Read a one-shot today", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "🎯"),
            a("rotating_daily_extra_2", "Tag Explorer", "Search by tag today", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "🏷️"),
            a("rotating_daily_extra_3", "Feed Check", "Open Feed today", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "📰"),
            a("rotating_daily_extra_4", "Incognito Dash", "Read 2 chapters incognito today", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "🥷"),
            a("rotating_daily_extra_5", "Double Page Day", "Read a spread in double-page mode today", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "🖼️"),
            a("rotating_weekly_extra_1", "Source Taster", "Try a new source this week", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "🆕"),
            a("rotating_weekly_extra_2", "Merge Master Weekly", "Merge a manga this week", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "🔗"),
            a("rotating_weekly_extra_3", "Translation Sprint", "Translate a 10+ chapter manga this week", AchievementTier.GOLD, AchievementCategory.ROTATING, isRotating = true, icon = "📖"),
            a("rotating_weekly_extra_4", "Backlog Buster Weekly", "Clear 5 backlog items this week", AchievementTier.GOLD, AchievementCategory.ROTATING, isRotating = true, icon = "💣"),
            a("rotating_daily_extra_6", "Stagger Day", "Browse library in staggered grid today", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "🧱"),
            a("rotating_weekly_extra_5", "Ultimate Weekly", "Read every day this week", AchievementTier.PLATINUM, AchievementCategory.ROTATING, isRotating = true, icon = "🗓️"),
            a("rotating_daily_extra_7", "Cover Swap", "Update a cover today", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "🖌️"),
            a("rotating_weekly_extra_6", "Tracker Streak Weekly", "Score 2 trackers this week", AchievementTier.SILVER, AchievementCategory.ROTATING, isRotating = true, icon = "🌸"),
            a("rotating_daily_extra_8", "Webhook Today", "Trigger a webhook today", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "🪝"),
            a("rotating_daily_extra_9", "EH Daily", "Browse EH today", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "👁️"),
            a("rotating_weekly_extra_7", "Reading Time Weekly", "Accumulate 5h reading this week", AchievementTier.SILVER, AchievementCategory.ROTATING, isRotating = true, icon = "⏰"),
            a("rotating_weekly_extra_8", "Library Growth Weekly", "Grow library by 15 this week", AchievementTier.GOLD, AchievementCategory.ROTATING, isRotating = true, icon = "🌱"),
            a("rotating_daily_extra_10", "Perfect Day", "Read 10 chapters without skipping", AchievementTier.SILVER, AchievementCategory.ROTATING, isRotating = true, icon = "💯"),
            a("rotating_weekly_extra_9", "Perfect Week Challenges", "Complete all weekly dailies at least once", AchievementTier.LEGENDARY, AchievementCategory.ROTATING, isRotating = true, icon = "🌟"),
            a("rotating_daily_extra_11", "Lunch Break Daily", "Read at lunch today", AchievementTier.BRONZE, AchievementCategory.ROTATING, isRotating = true, icon = "🍱"),
            a("rotating_weekly_extra_10", "Manga Marathon Weekly", "Read a 50+ chapter series this week", AchievementTier.GOLD, AchievementCategory.ROTATING, isRotating = true, icon = "🏔️"),
            // === SECRET ACHIEVEMENTS ===
            a("secret_houri", "Houri Hour", "Open the app at 03:33", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, isSecret = true, icon = "🕒"),
            a("secret_konami", "Konami Scholar", "Enter the Konami code in settings", AchievementTier.SILVER, AchievementCategory.EXPLORATION, isSecret = true, icon = "🎮"),
            a("secret_uwu", "UwU", "Set the app language to something cursed 10 times", AchievementTier.BRONZE, AchievementCategory.SOCIAL, isSecret = true, icon = "😳"),
            a("secret_pillow", "Pillow Talk", "Trigger the moan 50 times", AchievementTier.GOLD, AchievementCategory.SOCIAL, isSecret = true, icon = "🛏️"),
            a("secret_pineapple", "Pineapple King", "Read a manga with 'pineapple' in the search", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, isSecret = true, icon = "🍍"),
            a("secret_404", "404 Not Found", "Search for a manga that returns zero results 5 times", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, isSecret = true, icon = "❌"),
            a("secret_speedrun", "Speed Reader", "Finish a 20+ chapter manga in under an hour", AchievementTier.GOLD, AchievementCategory.READING, isSecret = true, icon = "💨"),
            a("secret_midnight_100", "Midnight Marathon", "Read 100 chapters between 00:00-04:00", AchievementTier.PLATINUM, AchievementCategory.READING, isSecret = true, icon = "🌙"),
            a("secret_no_sleep", "No Sleep", "Keep the reader open for 3 hours straight", AchievementTier.SILVER, AchievementCategory.READING, isSecret = true, icon = "😵"),
            a("secret_herobrine", "Herobrine", "Try to remove Herobrine again", AchievementTier.MYTHIC, AchievementCategory.EXPLORATION, isSecret = true, icon = "👁️‍🗨️"),
            a("secret_alt_f4", "Alt F4", "Spam tap a button until the UI flickers", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, isSecret = true, icon = "⌨️"),
            a("secret_wasm", "WASM Whisperer", "Trigger a WASM keygen for a source", AchievementTier.GOLD, AchievementCategory.TRANSLATION, isSecret = true, icon = "🧩"),
            a("secret_upscale_4k", "4K Ascension", "Upscale the same page 5 times", AchievementTier.SILVER, AchievementCategory.TRANSLATION, isSecret = true, icon = "✨"),
            a("secret_flip_phone", "Flip Phone", "Switch reader direction 20 times in a row", AchievementTier.BRONZE, AchievementCategory.READING, isSecret = true, icon = "📱"),
            a("secret_ghost_category", "Ghost Category", "Create and immediately delete a category", AchievementTier.BRONZE, AchievementCategory.LIBRARY, isSecret = true, icon = "👻"),
            a("secret_perfect_week", "Perfect Week", "Complete 7 manga in 7 days", AchievementTier.GOLD, AchievementCategory.READING, isSecret = true, icon = "📆"),
            a("secret_lovense", "Lovense?", "Look for Lovense support", AchievementTier.MYTHIC, AchievementCategory.SOCIAL, isSecret = true, icon = "💝"),
            a("secret_mango_double", "Double Mango", "Find the mango easter egg twice", AchievementTier.GOLD, AchievementCategory.SOCIAL, isSecret = true, icon = "🥭"),
            a("secret_jxl", "JXL Pioneer", "Open a JPG-XL image", AchievementTier.SILVER, AchievementCategory.EXPLORATION, isSecret = true, icon = "🖼️"),
            a("secret_webgpu_rescue", "GPU Survivor", "Recover from a WebGPU black-screen preload", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, isSecret = true, icon = "🚑"),
            a("secret_anki", "Anki Overlord", "Export 100 vocabulary words from translations", AchievementTier.GOLD, AchievementCategory.TRANSLATION, isSecret = true, icon = "🧠"),
            a("secret_all_secret", "Secret Hunter", "Unlock 10 secret achievements", AchievementTier.PLATINUM, AchievementCategory.SOCIAL, isSecret = true, icon = "🕵️"),
            a("secret_platinum_club", "Platinum Club", "Unlock every PLATINUM achievement", AchievementTier.LEGENDARY, AchievementCategory.SOCIAL, isSecret = true, icon = "🏆"),
            a("secret_100_percent", "Houri 100%", "Unlock all non-secret achievements", AchievementTier.MYTHIC, AchievementCategory.SOCIAL, isSecret = true, icon = "🌟"),
            a("secret_mythic_hoard", "Mythic Hoard", "Unlock 5 MYTHIC achievements", AchievementTier.MYTHIC, AchievementCategory.SOCIAL, isSecret = true, icon = "🐲"),
        )
    }

    fun forId(id: String) = all.find { it.id == id }
    val secrets get() = all.filter { it.isSecret }
    val visible get() = all.filter { !it.isSecret }
    val negatives get() = all.filter { it.isNegative }
    val rotating get() = all.filter { it.isRotating }
    val nonNegative get() = all.filter { !it.isNegative }
    val countable get() = all.filter { it.countsTowardsProgress }
}
