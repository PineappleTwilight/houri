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
    val icon: String = "🏆",
    val unlockedAt: Long? = null,
) {
    val isUnlocked: Boolean get() = unlockedAt != null
    val displayTitle: String get() = if (isSecret && unlockedAt == null) "???" else title
    val displayDescription: String get() = if (isSecret && unlockedAt == null) "Secret achievement — keep exploring" else description
    val displayIcon: String get() = if (isSecret && unlockedAt == null) "❓" else icon
}

enum class AchievementTier { BRONZE, SILVER, GOLD, PLATINUM, LEGENDARY, MYTHIC }

enum class AchievementCategory { READING, LIBRARY, TRACKER, TRANSLATION, SOCIAL, EXPLORATION }

@Serializable
data class AchievementStats(
    val organicChaptersRead: Long = 0,
    val mangaFinished: Long = 0,
    val libraryCount: Long = 0,
    val totalAchievements: Int = 0,
    val unlockedCount: Int = 0,
    val secretUnlocked: Int = 0,
) {
    val rank: String get() = when {
        unlockedCount >= 120 && organicChaptersRead >= 2000 && mangaFinished >= 25 -> "Mythic"
        unlockedCount >= 90 && organicChaptersRead >= 1000 && mangaFinished >= 15 -> "Legend"
        unlockedCount >= 60 && organicChaptersRead >= 500 && mangaFinished >= 8 -> "Master"
        unlockedCount >= 35 && organicChaptersRead >= 250 -> "Veteran"
        unlockedCount >= 18 -> "Explorer"
        unlockedCount >= 8 -> "Apprentice"
        else -> "Novice"
    }

    val rankTier: AchievementTier get() = when (rank) {
        "Mythic" -> AchievementTier.MYTHIC
        "Legend" -> AchievementTier.LEGENDARY
        "Master" -> AchievementTier.PLATINUM
        "Veteran" -> AchievementTier.GOLD
        "Explorer" -> AchievementTier.SILVER
        else -> AchievementTier.BRONZE
    }
}

object Achievements {
    val all = listOf(
        // === READING — milestones (organic only) ===
        Achievement("first_chapter", "First Steps", "Read your first chapter organically", AchievementTier.BRONZE, AchievementCategory.READING, icon = "🌱"),
        Achievement("ten_chapters", "Getting Started", "Read 10 chapters organically", AchievementTier.BRONZE, AchievementCategory.READING, icon = "🌿"),
        Achievement("twenty_five", "Quarter Century", "Read 25 chapters", AchievementTier.BRONZE, AchievementCategory.READING, icon = "📗"),
        Achievement("fifty_chapters", "Half Century", "Read 50 chapters organically", AchievementTier.SILVER, AchievementCategory.READING, icon = "📘"),
        Achievement("hundred_chapters", "Century", "Read 100 chapters organically", AchievementTier.SILVER, AchievementCategory.READING, icon = "💯"),
        Achievement("two_fifty", "Quarter K", "Read 250 chapters", AchievementTier.GOLD, AchievementCategory.READING, icon = "📚"),
        Achievement("five_hundred", "Marathon", "Read 500 chapters organically", AchievementTier.GOLD, AchievementCategory.READING, icon = "🏃"),
        Achievement("thousand", "Legend", "Read 1000 chapters organically", AchievementTier.PLATINUM, AchievementCategory.READING, icon = "👑"),
        Achievement("two_thousand", "Mythic", "Read 2000 chapters", AchievementTier.LEGENDARY, AchievementCategory.READING, icon = "🐉"),
        Achievement("five_thousand", "Houri Legend", "Read 5000 chapters organically", AchievementTier.LEGENDARY, AchievementCategory.READING, icon = "🌟"),
        Achievement("ten_thousand", "Ink Drinker", "Read 10,000 chapters", AchievementTier.MYTHIC, AchievementCategory.READING, icon = "🩸"),
        // reading streaks & tempo
        Achievement("streak_3", "Three Day Run", "Read 3 days in a row", AchievementTier.BRONZE, AchievementCategory.READING, icon = "🔥"),
        Achievement("streak_7", "Weekly Habit", "Read 7 days in a row", AchievementTier.SILVER, AchievementCategory.READING, icon = "📅"),
        Achievement("streak_30", "Monthly Devotee", "Read 30 days in a row", AchievementTier.GOLD, AchievementCategory.READING, icon = "🗓️"),
        Achievement("streak_100", "Unbreakable", "Read 100 days in a row", AchievementTier.PLATINUM, AchievementCategory.READING, icon = "⛓️"),
        Achievement("binge_10", "Binge Reader", "Read 10 chapters in one day", AchievementTier.SILVER, AchievementCategory.READING, icon = "⚡"),
        Achievement("binge_50", "Binge Lord", "Read 50 chapters in one day", AchievementTier.GOLD, AchievementCategory.READING, icon = "💥"),
        Achievement("binge_100", "Ascended Binger", "Read 100 chapters in one day", AchievementTier.PLATINUM, AchievementCategory.READING, icon = "🌪️"),
        Achievement("weekend_warrior", "Weekend Warrior", "Read 20 chapters over a weekend", AchievementTier.SILVER, AchievementCategory.READING, icon = "🛡️"),
        Achievement("early_bird", "Early Bird", "Read a chapter before 6 AM", AchievementTier.BRONZE, AchievementCategory.READING, icon = "🐦"),
        Achievement("night_owl", "Night Owl", "Read 10 chapters after midnight", AchievementTier.BRONZE, AchievementCategory.SOCIAL, icon = "🦉"),
        Achievement("lunch_break", "Lunch Break", "Read a chapter at noon (11:30–13:30)", AchievementTier.BRONZE, AchievementCategory.READING, icon = "🍱"),
        // manga completion
        Achievement("first_manga_finished", "Finisher", "Complete a manga", AchievementTier.SILVER, AchievementCategory.READING, icon = "✅"),
        Achievement("five_manga_finished", "Collector", "Complete 5 manga", AchievementTier.GOLD, AchievementCategory.READING, icon = "🗃️"),
        Achievement("ten_manga_finished", "Completionist", "Complete 10 manga", AchievementTier.PLATINUM, AchievementCategory.READING, icon = "🏆"),
        Achievement("twenty_manga_finished", "Grand Completionist", "Complete 20 manga", AchievementTier.PLATINUM, AchievementCategory.READING, icon = "🎖️"),
        Achievement("fifty_manga_finished", "Wholesome Finisher", "Complete 50 manga", AchievementTier.LEGENDARY, AchievementCategory.READING, icon = "👑"),
        Achievement("one_shot", "One-Shot Wonder", "Complete a single-chapter manga", AchievementTier.BRONZE, AchievementCategory.READING, icon = "🎯"),
        Achievement("long_runner", "Long Runner", "Complete a 200+ chapter manga", AchievementTier.GOLD, AchievementCategory.READING, icon = "🏔️"),
        Achievement("ultra_long", "Ultra Marathon", "Complete a 500+ chapter series", AchievementTier.MYTHIC, AchievementCategory.READING, icon = "🗻"),
        // rereading
        Achievement("rereader", "Rereader", "Reread a manga", AchievementTier.SILVER, AchievementCategory.READING, icon = "🔁"),
        Achievement("reread_five", "Nostalgic", "Reread 5 manga", AchievementTier.GOLD, AchievementCategory.READING, icon = "💭"),
        Achievement("reread_twenty", "Eternal Return", "Reread 20 manga", AchievementTier.PLATINUM, AchievementCategory.READING, icon = "♾️"),
        Achievement("reread_hundred", "Living Library", "Reread 100 times total", AchievementTier.LEGENDARY, AchievementCategory.READING, icon = "📜"),
        // reading modes & preferences
        Achievement("webtoon_pager_both", "Versatile Reader", "Finish a manga in both pager and webtoon mode", AchievementTier.SILVER, AchievementCategory.READING, icon = "🔀"),
        Achievement("double_page", "Panorama Lover", "Read a chapter in double-page mode", AchievementTier.BRONZE, AchievementCategory.READING, icon = "🖼️"),
        Achievement("rtl_reader", "Right to Left", "Complete a manga in RTL mode", AchievementTier.BRONZE, AchievementCategory.READING, icon = "⬅️"),
        Achievement("vertical_reader", "Vertical Scroller", "Complete a manga in vertical/webtoon mode", AchievementTier.BRONZE, AchievementCategory.READING, icon = "⬇️"),
        Achievement("cutout_enthusiast", "Cutout Connoisseur", "Read with cutout enabled", AchievementTier.BRONZE, AchievementCategory.READING, icon = "✂️"),
        Achievement("incognito_reader", "Shadow Reader", "Read 10 chapters in incognito", AchievementTier.BRONZE, AchievementCategory.READING, icon = "🥷"),
        // === LIBRARY ===
        Achievement("library_1", "First Shelf", "Add your first manga to library", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "📥"),
        Achievement("library_5", "Shelf Starter", "Add 5 manga to library", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "📦"),
        Achievement("library_10", "Librarian", "Add 10 manga to library", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "📚"),
        Achievement("library_25", "Stacker", "Add 25 manga to library", AchievementTier.SILVER, AchievementCategory.LIBRARY, icon = "📚"),
        Achievement("library_50", "Curator", "Add 50 manga to library", AchievementTier.SILVER, AchievementCategory.LIBRARY, icon = "🗄️"),
        Achievement("library_100", "Archivist", "Add 100 manga to library", AchievementTier.GOLD, AchievementCategory.LIBRARY, icon = "🏛️"),
        Achievement("library_250", "Hoarder", "Add 250 manga to library", AchievementTier.PLATINUM, AchievementCategory.LIBRARY, icon = "🏚️"),
        Achievement("library_500", "Infinite Shelves", "Add 500 manga to library", AchievementTier.LEGENDARY, AchievementCategory.LIBRARY, icon = "♾️"),
        Achievement("library_1000", "Houri Akashic", "Add 1000 manga to library", AchievementTier.MYTHIC, AchievementCategory.LIBRARY, icon = "🌌"),
        Achievement("category_master", "Organizer", "Create 5 categories", AchievementTier.SILVER, AchievementCategory.EXPLORATION, icon = "🗂️"),
        Achievement("category_ten", "Mega Organizer", "Create 10 categories", AchievementTier.GOLD, AchievementCategory.LIBRARY, icon = "🗃️"),
        Achievement("subcategory_creator", "Nester", "Create a subcategory", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "📁"),
        Achievement("subcategory_five", "Folder Master", "Create 5 subcategories", AchievementTier.SILVER, AchievementCategory.LIBRARY, icon = "🗂️"),
        Achievement("subcategory_twenty", "Taxonomist", "Create 20 subcategories", AchievementTier.GOLD, AchievementCategory.LIBRARY, icon = "🧬"),
        Achievement("cover_custom", "Cover Artist", "Set a custom cover", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "🎨"),
        Achievement("cover_cropped", "Crop Master", "Crop a custom cover", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "✂️"),
        Achievement("cover_ten", "Gallery Curator", "Set 10 custom covers", AchievementTier.SILVER, AchievementCategory.LIBRARY, icon = "🖌️"),
        Achievement("censor_toggle", "Modesty Panel", "Enable censor lewd manga", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "🫣"),
        Achievement("staggered_grid", "Stagger Stunner", "Enable staggered library grid", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "🧱"),
        Achievement("feed_user", "Feed Me", "Enable the Feed and add a saved search", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "📰"),
        Achievement("smart_filter", "Smart Filterer", "Enable smart scanlator filter", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "🧠"),
        Achievement("merge_fan", "Merger", "Merge two manga entries", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "🔗"),
        Achievement("full_library_backup", "Hoarder Backup", "Create a library backup", AchievementTier.BRONZE, AchievementCategory.LIBRARY, icon = "💾"),
        // === TRACKER ===
        Achievement("tracker_connected", "Connected", "Connect a tracker", AchievementTier.BRONZE, AchievementCategory.TRACKER, icon = "🔌"),
        Achievement("tracker_two", "Double Linked", "Connect 2 trackers", AchievementTier.BRONZE, AchievementCategory.TRACKER, icon = "🔗"),
        Achievement("tracker_three", "Tracker Trio", "Connect 3 trackers", AchievementTier.SILVER, AchievementCategory.TRACKER, icon = "⛓️"),
        Achievement("tracker_five", "Tracker Legion", "Connect 5 trackers", AchievementTier.GOLD, AchievementCategory.TRACKER, icon = "🕸️"),
        Achievement("tracker_all", "Omni-Tracker", "Connect 8+ trackers", AchievementTier.PLATINUM, AchievementCategory.TRACKER, icon = "🌐"),
        Achievement("track_score", "Critic", "Score a manga on a tracker", AchievementTier.BRONZE, AchievementCategory.TRACKER, icon = "⭐"),
        Achievement("track_status", "Status Updater", "Update tracker status 10 times", AchievementTier.SILVER, AchievementCategory.TRACKER, icon = "📝"),
        Achievement("fill_metadata", "Info Broker", "Fill manga metadata from tracker", AchievementTier.BRONZE, AchievementCategory.TRACKER, icon = "📋"),
        Achievement("fill_metadata_tags", "Tag Dealer", "Fill tags + status from tracker", AchievementTier.SILVER, AchievementCategory.TRACKER, icon = "🏷️"),
        Achievement("definitive_tracker", "Definitive Source", "Set a definitive tracker source for a manga/category", AchievementTier.SILVER, AchievementCategory.TRACKER, icon = "📌"),
        Achievement("tracker_mangaupdates", "MangaUpdates Maven", "Use MangaUpdates tracker", AchievementTier.BRONZE, AchievementCategory.TRACKER, icon = "🦉"),
        Achievement("tracker_anilist", "AniLyst", "Use AniList tracker", AchievementTier.BRONZE, AchievementCategory.TRACKER, icon = "🌸"),
        // === TRANSLATION / MTL (blanket AI) ===
        Achievement("translator", "Polyglot", "Translate a chapter", AchievementTier.SILVER, AchievementCategory.TRANSLATION, icon = "🌐"),
        Achievement("translator_five", "Interpreter", "Translate 5 chapters", AchievementTier.SILVER, AchievementCategory.TRANSLATION, icon = "🗣️"),
        Achievement("translator_ten", "Bridge Builder", "Translate 10 chapters", AchievementTier.GOLD, AchievementCategory.TRANSLATION, icon = "🌉"),
        Achievement("translator_fifty", "United Nations", "Translate 50 chapters", AchievementTier.PLATINUM, AchievementCategory.TRANSLATION, icon = "🏛️"),
        Achievement("translator_hundred", "Babel Fish", "Translate 100 chapters", AchievementTier.LEGENDARY, AchievementCategory.TRANSLATION, icon = "🐟"),
        Achievement("mtl_offline", "Offline Polyglot", "Translate with Gemini Nano offline fallback", AchievementTier.SILVER, AchievementCategory.TRANSLATION, icon = "📴"),
        Achievement("mtl_local", "Local LLM Whisperer", "Translate with a local GGUF model", AchievementTier.GOLD, AchievementCategory.TRANSLATION, icon = "🤖"),
        Achievement("mtl_vision", "Eagle Eye", "Translate with vision-aware local model (mmproj)", AchievementTier.GOLD, AchievementCategory.TRANSLATION, icon = "🦅"),
        Achievement("mtl_glossary", "Glossarian", "Create a translation glossary", AchievementTier.BRONZE, AchievementCategory.TRANSLATION, icon = "📖"),
        Achievement("mtl_info", "Info Translator", "Translate manga title/description", AchievementTier.BRONZE, AchievementCategory.TRANSLATION, icon = "🏷️"),
        Achievement("mtl_auto_download", "Auto Translator", "Enable auto-translate on download", AchievementTier.BRONZE, AchievementCategory.TRANSLATION, icon = "🤹"),
        Achievement("upscale_first", "Sharp Eye", "Upscale a page (MTL-gated)", AchievementTier.BRONZE, AchievementCategory.TRANSLATION, icon = "🔍"),
        Achievement("upscale_ten", "HD Fan", "Upscale 10 pages", AchievementTier.SILVER, AchievementCategory.TRANSLATION, icon = "🖥️"),
        Achievement("upscale_hundred", "Pixel Purist", "Upscale 100 pages", AchievementTier.GOLD, AchievementCategory.TRANSLATION, icon = "🎞️"),
        Achievement("upscale_vulkan", "Vulkan Forged", "Upscale with Vulkan backend", AchievementTier.SILVER, AchievementCategory.TRANSLATION, icon = "🌋"),
        Achievement("upscale_npu", "Neural Boost", "Upscale with NPU backend", AchievementTier.GOLD, AchievementCategory.TRANSLATION, icon = "🧠"),
        // === EXPLORATION / SYSTEM ===
        Achievement("download_one", "First Download", "Download a chapter", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, icon = "⬇️"),
        Achievement("download_ten", "Offline Ready", "Download 10 chapters", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, icon = "📥"),
        Achievement("download_hundred", "Doomsday Prepper", "Download 100 chapters", AchievementTier.SILVER, AchievementCategory.EXPLORATION, icon = "🏕️"),
        Achievement("download_thousand", "Local Archivist", "Download 1000 chapters", AchievementTier.GOLD, AchievementCategory.EXPLORATION, icon = "🗄️"),
        Achievement("sources_five", "Source Explorer", "Add 5 different sources", AchievementTier.SILVER, AchievementCategory.EXPLORATION, icon = "🧭"),
        Achievement("sources_ten", "Source Conqueror", "Add 10 sources", AchievementTier.GOLD, AchievementCategory.EXPLORATION, icon = "🚩"),
        Achievement("eh_enabled", "Forbidden Library", "Enable E-Hentai/ExHentai", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, icon = "🔞"),
        Achievement("decensored", "Uncensored Eye", "Find a decensored/uncensored manga", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, icon = "👁️"),
        Achievement("mihon_port", "Mihon Pilgrim", "Use a Mihon-backported feature", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, icon = "⛩️"),
        Achievement("webgpu", "GPU Reader", "Read with WebGPU viewer", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, icon = "🎮"),
        Achievement("data_saver", "Data Saver", "Enable Data Saver for a source", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, icon = "📉"),
        Achievement("search_ast", "AST Searcher", "Use advanced search (AST) with filters", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, icon = "🔎"),
        Achievement("backup_created", "Safety Net", "Create a backup", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, icon = "💾"),
        Achievement("backup_restored", "Phoenix", "Restore a backup", AchievementTier.SILVER, AchievementCategory.EXPLORATION, icon = "🦅"),
        // === SOCIAL / MISC ===
        Achievement("discord_rpc", "Discord Famous", "Enable Discord RPC", AchievementTier.BRONZE, AchievementCategory.SOCIAL, icon = "🎧"),
        Achievement("webhook", "Webhook Wizard", "Configure a webhook", AchievementTier.BRONZE, AchievementCategory.SOCIAL, icon = "🪝"),
        Achievement("moan_enabled", "Audible Joy", "Enable chapter completion moan", AchievementTier.BRONZE, AchievementCategory.SOCIAL, icon = "🔊"),
        Achievement("moan_legendary", "Legendary Moan", "Hear a legendary (10%) moan", AchievementTier.GOLD, AchievementCategory.SOCIAL, icon = "💎"),
        Achievement("mango_easter", "Mango Found", "Find the mango easter egg", AchievementTier.SILVER, AchievementCategory.SOCIAL, isSecret = true, icon = "🥭"),
        // === SECRET ACHIEVEMENTS ===
        Achievement("secret_houri", "Houri Hour", "Open the app at 03:33", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, isSecret = true, icon = "🕒"),
        Achievement("secret_konami", "Konami Scholar", "Enter the Konami code in settings", AchievementTier.SILVER, AchievementCategory.EXPLORATION, isSecret = true, icon = "🎮"),
        Achievement("secret_uwu", "UwU", "Set the app language to something cursed 10 times", AchievementTier.BRONZE, AchievementCategory.SOCIAL, isSecret = true, icon = "😳"),
        Achievement("secret_pillow", "Pillow Talk", "Trigger the moan 50 times", AchievementTier.GOLD, AchievementCategory.SOCIAL, isSecret = true, icon = "🛏️"),
        Achievement("secret_pineapple", "Pineapple King", "Read a manga with 'pineapple' in the search", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, isSecret = true, icon = "🍍"),
        Achievement("secret_404", "404 Not Found", "Search for a manga that returns zero results 5 times", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, isSecret = true, icon = "❌"),
        Achievement("secret_speedrun", "Speed Reader", "Finish a 20+ chapter manga in under an hour", AchievementTier.GOLD, AchievementCategory.READING, isSecret = true, icon = "💨"),
        Achievement("secret_midnight_100", "Midnight Marathon", "Read 100 chapters between 00:00-04:00", AchievementTier.PLATINUM, AchievementCategory.READING, isSecret = true, icon = "🌙"),
        Achievement("secret_no_sleep", "No Sleep", "Keep the reader open for 3 hours straight", AchievementTier.SILVER, AchievementCategory.READING, isSecret = true, icon = "😵"),
        Achievement("secret_herobrine", "Herobrine", "Try to remove Herobrine again", AchievementTier.MYTHIC, AchievementCategory.EXPLORATION, isSecret = true, icon = "👁️‍🗨️"),
        Achievement("secret_alt_f4", "Alt F4", "Spam tap a button until the UI flickers", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, isSecret = true, icon = "⌨️"),
        Achievement("secret_wasm", "WASM Whisperer", "Trigger a WASM keygen for a source", AchievementTier.GOLD, AchievementCategory.TRANSLATION, isSecret = true, icon = "🧩"),
        Achievement("secret_upscale_4k", "4K Ascension", "Upscale the same page 5 times", AchievementTier.SILVER, AchievementCategory.TRANSLATION, isSecret = true, icon = "✨"),
        Achievement("secret_flip_phone", "Flip Phone", "Switch reader direction 20 times in a row", AchievementTier.BRONZE, AchievementCategory.READING, isSecret = true, icon = "📱"),
        Achievement("secret_ghost_category", "Ghost Category", "Create and immediately delete a category", AchievementTier.BRONZE, AchievementCategory.LIBRARY, isSecret = true, icon = "👻"),
        Achievement("secret_perfect_week", "Perfect Week", "Complete 7 manga in 7 days", AchievementTier.GOLD, AchievementCategory.READING, isSecret = true, icon = "📆"),
        Achievement("secret_lovense", "Lovense?", "Look for Lovense support", AchievementTier.MYTHIC, AchievementCategory.SOCIAL, isSecret = true, icon = "💝"),
        Achievement("secret_mango_double", "Double Mango", "Find the mango easter egg twice", AchievementTier.GOLD, AchievementCategory.SOCIAL, isSecret = true, icon = "🥭"),
        Achievement("secret_jxl", "JXL Pioneer", "Open a JPG-XL image", AchievementTier.SILVER, AchievementCategory.EXPLORATION, isSecret = true, icon = "🖼️"),
        Achievement("secret_webgpu_rescue", "GPU Survivor", "Recover from a WebGPU black-screen preload", AchievementTier.BRONZE, AchievementCategory.EXPLORATION, isSecret = true, icon = "🚑"),
        Achievement("secret_anki", "Anki Overlord", "Export 100 vocabulary words from translations", AchievementTier.GOLD, AchievementCategory.TRANSLATION, isSecret = true, icon = "🧠"),
        Achievement("secret_all_secret", "Secret Hunter", "Unlock 10 secret achievements", AchievementTier.PLATINUM, AchievementCategory.SOCIAL, isSecret = true, icon = "🕵️"),
        Achievement("secret_platinum_club", "Platinum Club", "Unlock every PLATINUM achievement", AchievementTier.LEGENDARY, AchievementCategory.SOCIAL, isSecret = true, icon = "🏆"),
        Achievement("secret_100_percent", "Houri 100%", "Unlock all non-secret achievements", AchievementTier.MYTHIC, AchievementCategory.SOCIAL, isSecret = true, icon = "🌟"),
        Achievement("secret_mythic_hoard", "Mythic Hoard", "Unlock 5 MYTHIC achievements", AchievementTier.MYTHIC, AchievementCategory.SOCIAL, isSecret = true, icon = "🐲"),
    )

    fun forId(id: String) = all.find { it.id == id }
    val secrets get() = all.filter { it.isSecret }
    val visible get() = all.filter { !it.isSecret }
}
