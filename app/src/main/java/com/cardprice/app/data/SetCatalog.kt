package com.cardprice.app.data

import com.cardprice.app.data.Series.MEGA_EVOLUTION
import com.cardprice.app.data.Series.SCARLET_VIOLET
import com.cardprice.app.data.Series.SUN_MOON
import com.cardprice.app.data.Series.SWORD_SHIELD
import com.cardprice.app.data.Series.XY

/**
 * English Pokémon TCG expansions, newest first within each series. Newer sets can be added in-app.
 * The last argument is TCGplayer's set slug, used to look up sealed product prices.
 */
object SetCatalog {
    val sets: List<PokemonSet> = listOf(
        PokemonSet("me6", "Delta Reign", MEGA_EVOLUTION, 2026, tcgSlug = "me06-delta-reign"),
        PokemonSet("me30", "30th Celebration", MEGA_EVOLUTION, 2026, special = true, tcgSlug = "me-30th-celebration"),
        PokemonSet("me5", "Pitch Black", MEGA_EVOLUTION, 2026, tcgSlug = "me05-pitch-black"),
        PokemonSet("me4", "Chaos Rising", MEGA_EVOLUTION, 2026, tcgSlug = "me04-chaos-rising"),
        PokemonSet("me3", "Perfect Order", MEGA_EVOLUTION, 2026, tcgSlug = "me03-perfect-order"),
        PokemonSet("me2_5", "Ascended Heroes", MEGA_EVOLUTION, 2026, special = true, tcgSlug = "me-ascended-heroes"),
        PokemonSet("me2", "Phantasmal Flames", MEGA_EVOLUTION, 2025, tcgSlug = "me02-phantasmal-flames"),
        PokemonSet("me1", "Mega Evolution", MEGA_EVOLUTION, 2025, tcgSlug = "me01-mega-evolution"),

        PokemonSet("rsv10pt5", "White Flare", SCARLET_VIOLET, 2025, special = true, tcgSlug = "sv-white-flare"),
        PokemonSet("zsv10pt5", "Black Bolt", SCARLET_VIOLET, 2025, special = true, tcgSlug = "sv-black-bolt"),
        PokemonSet("sv10", "Destined Rivals", SCARLET_VIOLET, 2025, tcgSlug = "sv10-destined-rivals"),
        PokemonSet("sv9", "Journey Together", SCARLET_VIOLET, 2025, tcgSlug = "sv09-journey-together"),
        PokemonSet("sv8pt5", "Prismatic Evolutions", SCARLET_VIOLET, 2025, special = true, tcgSlug = "sv-prismatic-evolutions"),
        PokemonSet("sv8", "Surging Sparks", SCARLET_VIOLET, 2024, tcgSlug = "sv08-surging-sparks"),
        PokemonSet("sv7", "Stellar Crown", SCARLET_VIOLET, 2024, tcgSlug = "sv07-stellar-crown"),
        PokemonSet("sv6pt5", "Shrouded Fable", SCARLET_VIOLET, 2024, special = true, tcgSlug = "sv-shrouded-fable"),
        PokemonSet("sv6", "Twilight Masquerade", SCARLET_VIOLET, 2024, tcgSlug = "sv06-twilight-masquerade"),
        PokemonSet("sv5", "Temporal Forces", SCARLET_VIOLET, 2024, tcgSlug = "sv05-temporal-forces"),
        PokemonSet("sv4pt5", "Paldean Fates", SCARLET_VIOLET, 2024, special = true, tcgSlug = "sv-paldean-fates"),
        PokemonSet("sv4", "Paradox Rift", SCARLET_VIOLET, 2023, tcgSlug = "sv04-paradox-rift"),
        PokemonSet("sv3pt5", "151", SCARLET_VIOLET, 2023, special = true, tcgSlug = "sv-scarlet-and-violet-151"),
        PokemonSet("sv3", "Obsidian Flames", SCARLET_VIOLET, 2023, tcgSlug = "sv03-obsidian-flames"),
        PokemonSet("sv2", "Paldea Evolved", SCARLET_VIOLET, 2023, tcgSlug = "sv02-paldea-evolved"),
        PokemonSet("sv1", "Scarlet & Violet", SCARLET_VIOLET, 2023, tcgSlug = "sv01-scarlet-and-violet-base-set"),

        PokemonSet("swsh12pt5", "Crown Zenith", SWORD_SHIELD, 2023, special = true, tcgSlug = "swsh-crown-zenith"),
        PokemonSet("swsh12", "Silver Tempest", SWORD_SHIELD, 2022, tcgSlug = "swsh12-silver-tempest"),
        PokemonSet("swsh11", "Lost Origin", SWORD_SHIELD, 2022, tcgSlug = "swsh11-lost-origin"),
        PokemonSet("pgo", "Pokémon GO", SWORD_SHIELD, 2022, special = true, tcgSlug = "pokemon-go"),
        PokemonSet("swsh10", "Astral Radiance", SWORD_SHIELD, 2022, tcgSlug = "swsh10-astral-radiance"),
        PokemonSet("swsh9", "Brilliant Stars", SWORD_SHIELD, 2022, tcgSlug = "swsh09-brilliant-stars"),
        PokemonSet("swsh8", "Fusion Strike", SWORD_SHIELD, 2021, tcgSlug = "swsh08-fusion-strike"),
        PokemonSet("cel25", "Celebrations", SWORD_SHIELD, 2021, cardsPerPack = 4, special = true, tcgSlug = "celebrations"),
        PokemonSet("swsh7", "Evolving Skies", SWORD_SHIELD, 2021, tcgSlug = "swsh07-evolving-skies"),
        PokemonSet("swsh6", "Chilling Reign", SWORD_SHIELD, 2021, tcgSlug = "swsh06-chilling-reign"),
        PokemonSet("swsh5", "Battle Styles", SWORD_SHIELD, 2021, tcgSlug = "swsh05-battle-styles"),
        PokemonSet("swsh45", "Shining Fates", SWORD_SHIELD, 2021, special = true, tcgSlug = "shining-fates"),
        PokemonSet("swsh4", "Vivid Voltage", SWORD_SHIELD, 2020, tcgSlug = "swsh04-vivid-voltage"),
        PokemonSet("swsh35", "Champion's Path", SWORD_SHIELD, 2020, special = true, tcgSlug = "champions-path"),
        PokemonSet("swsh3", "Darkness Ablaze", SWORD_SHIELD, 2020, tcgSlug = "swsh03-darkness-ablaze"),
        PokemonSet("swsh2", "Rebel Clash", SWORD_SHIELD, 2020, tcgSlug = "swsh02-rebel-clash"),
        PokemonSet("swsh1", "Sword & Shield", SWORD_SHIELD, 2020, tcgSlug = "swsh01-sword-and-shield-base-set"),

        PokemonSet("sm12", "Cosmic Eclipse", SUN_MOON, 2019, tcgSlug = "sm-cosmic-eclipse"),
        PokemonSet("sm115", "Hidden Fates", SUN_MOON, 2019, special = true, tcgSlug = "hidden-fates"),
        PokemonSet("sm11", "Unified Minds", SUN_MOON, 2019, tcgSlug = "sm-unified-minds"),
        PokemonSet("sm10", "Unbroken Bonds", SUN_MOON, 2019, tcgSlug = "sm-unbroken-bonds"),
        PokemonSet("det1", "Detective Pikachu", SUN_MOON, 2019, cardsPerPack = 4, special = true, tcgSlug = "detective-pikachu"),
        PokemonSet("sm9", "Team Up", SUN_MOON, 2019, tcgSlug = "sm-team-up"),
        PokemonSet("sm8", "Lost Thunder", SUN_MOON, 2018, tcgSlug = "sm-lost-thunder"),
        PokemonSet("sm75", "Dragon Majesty", SUN_MOON, 2018, special = true, tcgSlug = "dragon-majesty"),
        PokemonSet("sm7", "Celestial Storm", SUN_MOON, 2018, tcgSlug = "sm-celestial-storm"),
        PokemonSet("sm6", "Forbidden Light", SUN_MOON, 2018, tcgSlug = "sm-forbidden-light"),
        PokemonSet("sm5", "Ultra Prism", SUN_MOON, 2018, tcgSlug = "sm-ultra-prism"),
        PokemonSet("sm4", "Crimson Invasion", SUN_MOON, 2017, tcgSlug = "sm-crimson-invasion"),
        PokemonSet("sm35", "Shining Legends", SUN_MOON, 2017, special = true, tcgSlug = "shining-legends"),
        PokemonSet("sm3", "Burning Shadows", SUN_MOON, 2017, tcgSlug = "sm-burning-shadows"),
        PokemonSet("sm2", "Guardians Rising", SUN_MOON, 2017, tcgSlug = "sm-guardians-rising"),
        PokemonSet("sm1", "Sun & Moon", SUN_MOON, 2017, tcgSlug = "sm-base-set"),

        PokemonSet("xy12", "Evolutions", XY, 2016, tcgSlug = "xy-evolutions"),
        PokemonSet("xy11", "Steam Siege", XY, 2016, tcgSlug = "xy-steam-siege"),
        PokemonSet("xy10", "Fates Collide", XY, 2016, tcgSlug = "xy-fates-collide"),
        PokemonSet("g1", "Generations", XY, 2016, special = true, tcgSlug = "generations"),
        PokemonSet("xy9", "BREAKpoint", XY, 2016, tcgSlug = "xy-breakpoint"),
        PokemonSet("xy8", "BREAKthrough", XY, 2015, tcgSlug = "xy-breakthrough"),
        PokemonSet("xy7", "Ancient Origins", XY, 2015, tcgSlug = "xy-ancient-origins"),
        PokemonSet("xy6", "Roaring Skies", XY, 2015, tcgSlug = "xy-roaring-skies"),
        PokemonSet("xy5", "Primal Clash", XY, 2015, tcgSlug = "xy-primal-clash"),
        PokemonSet("xy4", "Phantom Forces", XY, 2014, tcgSlug = "xy-phantom-forces"),
        PokemonSet("xy3", "Furious Fists", XY, 2014, tcgSlug = "xy-furious-fists"),
        PokemonSet("xy2", "Flashfire", XY, 2014, tcgSlug = "xy-flashfire"),
        PokemonSet("xy1", "XY", XY, 2014, tcgSlug = "xy-base-set"),
    )
}
