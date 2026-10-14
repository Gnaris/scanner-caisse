package com.belijolie.scanner.util

/**
 * Dictionnaire FR→EN interne pour la bijouterie / accessoires. Utilisé pour
 * pré-remplir le champ "Nom anglais" lors de la création d'une catégorie.
 * L'utilisateur peut toujours corriger / écraser la suggestion à la main.
 *
 * Comparaisons insensibles à la casse et aux accents. Les clés multi-mots
 * sont testées en premier (recherche greedy mot le plus long d'abord).
 */
object CategoryTranslator {

    private val dictionary: Map<String, String> = linkedMapOf(
        // Bijoux
        "boucle d'oreille" to "Earring",
        "boucles d'oreille" to "Earrings",
        "boucle oreille" to "Earring",
        "boucles oreille" to "Earrings",
        "boucle" to "Earring",
        "boucles" to "Earrings",
        "bracelet" to "Bracelet",
        "bracelets" to "Bracelets",
        "bague" to "Ring",
        "bagues" to "Rings",
        "collier" to "Necklace",
        "colliers" to "Necklaces",
        "chaine" to "Chain",
        "chaîne" to "Chain",
        "chaines" to "Chains",
        "chaînes" to "Chains",
        "pendentif" to "Pendant",
        "pendentifs" to "Pendants",
        "broche" to "Brooch",
        "broches" to "Brooches",
        "medaille" to "Medal",
        "médaille" to "Medal",
        "medaillon" to "Locket",
        "médaillon" to "Locket",
        "parure" to "Jewelry set",
        "parures" to "Jewelry sets",
        "anneau" to "Band ring",
        "anneaux" to "Band rings",
        "piercing" to "Piercing",
        "piercings" to "Piercings",
        "alliance" to "Wedding band",
        "alliances" to "Wedding bands",
        "diademe" to "Tiara",
        "diadème" to "Tiara",
        "sautoir" to "Long necklace",
        "sautoirs" to "Long necklaces",
        "gourmette" to "Chain bracelet",
        "gourmettes" to "Chain bracelets",
        "jonc" to "Bangle",
        "joncs" to "Bangles",
        "manchette" to "Cuff",
        "manchettes" to "Cuffs",
        "creole" to "Hoop earring",
        "créole" to "Hoop earring",
        "creoles" to "Hoop earrings",
        "créoles" to "Hoop earrings",
        "puce" to "Stud earring",
        "puces" to "Stud earrings",
        "clou" to "Stud",
        "clous" to "Studs",
        // Accessoires
        "montre" to "Watch",
        "montres" to "Watches",
        "foulard" to "Scarf",
        "foulards" to "Scarves",
        "echarpe" to "Scarf",
        "écharpe" to "Scarf",
        "echarpes" to "Scarves",
        "écharpes" to "Scarves",
        "sac" to "Bag",
        "sacs" to "Bags",
        "pochette" to "Clutch",
        "pochettes" to "Clutches",
        "ceinture" to "Belt",
        "ceintures" to "Belts",
        "chapeau" to "Hat",
        "chapeaux" to "Hats",
        "casquette" to "Cap",
        "casquettes" to "Caps",
        "bonnet" to "Beanie",
        "bonnets" to "Beanies",
        "gant" to "Glove",
        "gants" to "Gloves",
        "lunette" to "Glasses",
        "lunettes" to "Glasses",
        "porte-cle" to "Keychain",
        "porte-clé" to "Keychain",
        "porte-cles" to "Keychains",
        "porte-clés" to "Keychains",
        "porte-monnaie" to "Coin purse",
        "portefeuille" to "Wallet",
        "portefeuilles" to "Wallets",
        // Divers boutique
        "presentoir" to "Display",
        "présentoir" to "Display",
        "presentoirs" to "Displays",
        "présentoirs" to "Displays",
        "boite" to "Box",
        "boîte" to "Box",
        "boites" to "Boxes",
        "boîtes" to "Boxes",
        "ecrin" to "Jewel case",
        "écrin" to "Jewel case",
        "pochon" to "Pouch",
        "sachet" to "Bag",
        "etiquette" to "Tag",
        "étiquette" to "Tag",
        "divers" to "Misc",
        "autre" to "Other",
        "autres" to "Other",
        "cadeau" to "Gift",
        "cadeaux" to "Gifts",
        "promo" to "Promo",
        "solde" to "Sale",
        "soldes" to "Sale",
    )

    /**
     * Retourne la traduction anglaise suggérée pour un nom de catégorie FR.
     * - Match "entier" prioritaire (ex. "Boucles d'oreille" → "Earrings").
     * - Sinon match mot-à-mot : chaque mot est traduit s'il est au dictionnaire,
     *   le reste est capitalisé tel quel. Permet "Bracelet enfant" → "Bracelet enfant"
     *   (fallback : mot inconnu passé tel quel).
     * - Retourne null si rien de reconnaissable (laisser le champ vide).
     */
    fun suggestEnglishName(fr: String): String? {
        val norm = normalize(fr)
        if (norm.isBlank()) return null

        // 1. Match entier
        dictionary[norm]?.let { return it }

        // 2. Match mot-à-mot. On retourne une suggestion seulement si au moins
        //    un mot a été traduit — sinon on renvoie null pour laisser vide.
        val words = norm.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        var anyTranslated = false
        val parts = words.map { w ->
            dictionary[w]?.also { anyTranslated = true } ?: w.replaceFirstChar { it.uppercase() }
        }
        return if (anyTranslated) parts.joinToString(" ") else null
    }

    private fun normalize(s: String): String {
        return s.trim().lowercase()
            .replace("œ", "oe")
            .replace("æ", "ae")
    }
}
