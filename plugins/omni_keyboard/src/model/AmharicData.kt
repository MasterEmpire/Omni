package com.omni.plugin.keyboard.model

object AmharicData {
    val map = mapOf(
        // 1. h-family (ha -> ሀ)
        "he" to "ሀ", "hu" to "ሁ", "hi" to "ሂ", "ha" to "ሃ", "hee" to "ሄ", "h" to "ህ", "ho" to "ሆ",
        "haa" to "ሃ",

        // 2. l-family (le -> ለ)
        "le" to "ለ", "lu" to "ሉ", "li" to "ሊ", "la" to "ላ", "lee" to "ሌ", "l" to "ል", "lo" to "ሎ", "lua" to "ሏ",

        // 3. m-family (me -> መ)
        "me" to "መ", "mu" to "ሙ", "mi" to "ሚ", "ma" to "ማ", "mee" to "ሜ", "m" to "ም", "mo" to "ሞ", "mua" to "ሟ",

        // 4. S-family (Se -> ሠ) [Capitalized]
        "Se" to "ሠ", "Su" to "ሡ", "Si" to "ሢ", "Sa" to "ሣ", "See" to "ሤ", "S" to "ሥ", "So" to "ሦ", "Sua" to "ሷ",

        // 5. r-family (re -> ረ)
        "re" to "ረ", "ru" to "ሩ", "ri" to "ሪ", "ra" to "ራ", "ree" to "ሬ", "r" to "ር", "ro" to "ሮ", "rua" to "ሯ",

        // 6. s-family (se -> ሰ)
        "se" to "ሰ", "su" to "ሱ", "si" to "ሲ", "sa" to "ሳ", "see" to "ሴ", "s" to "ስ", "so" to "ሶ", "sua" to "ሷ",

        // 7. sh-family (she -> ሸ)
        "she" to "ሸ", "shu" to "ሹ", "shi" to "ሺ", "sha" to "ሻ", "shee" to "ሼ", "sh" to "ሽ", "sho" to "ሾ", "shua" to "ሿ",

        // 8. q-family (qe -> ቀ)
        "qe" to "ቀ", "qu" to "ቁ", "qi" to "ቂ", "qa" to "ቃ", "qee" to "ቄ", "q" to "ቅ", "qo" to "ቆ", "qua" to "ቋ",

        // 10. b-family (be -> በ)
        "be" to "በ", "bu" to "ቡ", "bi" to "ቢ", "ba" to "ባ", "bee" to "ቤ", "b" to "ብ", "bo" to "ቦ", "bua" to "ቧ",

        // 11. t-family (te -> ተ)
        "te" to "ተ", "tu" to "ቱ", "ti" to "ቲ", "ta" to "ታ", "tee" to "ቴ", "t" to "ት", "to" to "ቶ", "tua" to "ቷ",

        // 12. ch-family (che -> ቸ)
        "che" to "ቸ", "chu" to "ቹ", "chi" to "ቺ", "cha" to "ቻ", "chee" to "ቼ", "ch" to "ች", "cho" to "ቾ", "chua" to "ቿ",

        // 13. H-family (He -> ኀ) [Capitalized]
        "He" to "ኀ", "Hu" to "ኁ", "Hi" to "ኂ", "Ha" to "ኃ", "Hee" to "ኄ", "H" to "ኅ", "Ho" to "ኆ",

        // 14. n-family (ne -> ነ)
        "ne" to "ነ", "nu" to "ኑ", "ni" to "ኒ", "na" to "ና", "nee" to "ኔ", "n" to "ን", "no" to "ኖ", "nua" to "ኗ",

        // 15. gn-family (gne -> ኘ)
        "gne" to "ኘ", "gnu" to "ኙ", "gni" to "ኚ", "gna" to "ኛ", "gnee" to "ኜ", "gn" to "ኝ", "gno" to "ኞ", "gnua" to "ኟ",

        // 16. A-family (Vowels)
        "A" to "አ", "Au" to "ኡ", "Ai" to "ኢ", "Aa" to "ኣ", "E" to "ኤ", "e" to "እ", "O" to "ኦ",
        "u" to "ኡ", "i" to "ኢ", "a" to "አ",

        // 17. k-family (ke -> ከ)
        "ke" to "ከ", "ku" to "ኩ", "ki" to "ኪ", "ka" to "ካ", "kee" to "ኬ", "k" to "ክ", "ko" to "ኮ", "kua" to "ኳ",

        // 18. x-family (x -> ኸ)
        "xe" to "ኸ", "xu" to "ኹ", "xi" to "ኺ", "xa" to "ኻ", "xee" to "ኼ", "x" to "ኽ", "xo" to "ኾ",

        // 19. w-family (we -> ወ)
        "we" to "ወ", "wu" to "ዉ", "wi" to "ዊ", "wa" to "ዋ", "wee" to "ዌ", "w" to "ው", "wo" to "ዎ",

        // 20. a-family (Ayin: a -> ዐ)
        "au" to "ዑ", "ai" to "ዒ", "aee" to "ዔ", "ae" to "ዕ", "ao" to "ዖ", "aa" to "ዓ",

        // 21. z-family (ze -> ዘ)
        "ze" to "ዘ", "zu" to "ዙ", "zi" to "ዚ", "za" to "ዛ", "zee" to "ዜ", "z" to "ዝ", "zo" to "ዞ", "zua" to "ዟ",

        // 22. zh-family (zhe -> ዠ)
        "zhe" to "ዠ", "zhu" to "ዡ", "zhi" to "ዢ", "zha" to "ዣ", "zhee" to "ዤ", "zh" to "ዥ", "zho" to "ዦ", "zhua" to "ዧ",

        // 22(b). y-family (ye -> የ)
        "ye" to "የ", "yu" to "ዩ", "yi" to "ዪ", "ya" to "ያ", "yee" to "ዬ", "y" to "ይ", "yo" to "ዮ",

        // 23. d-family (de -> ደ)
        "de" to "ደ", "du" to "ዱ", "di" to "ዲ", "da" to "ዳ", "dee" to "ዴ", "d" to "ድ", "do" to "ዶ", "dua" to "ዷ",

        // 25. j-family (je -> ጀ)
        "je" to "ጀ", "ju" to "ጁ", "ji" to "ጂ", "ja" to "ጃ", "jee" to "ጄ", "j" to "ጅ", "jo" to "ጆ", "jua" to "ጇ",

        // 26. g-family (ge -> ገ)
        "ge" to "ገ", "gu" to "ጉ", "gi" to "ጊ", "ga" to "ጋ", "gee" to "ጌ", "g" to "ግ", "go" to "ጎ", "gua" to "ጓ",

        // 28. T-family (Te -> ጠ) [Capital T]
        "Te" to "ጠ", "Tu" to "ጡ", "Ti" to "ጢ", "Ta" to "ጣ", "Tee" to "ጤ", "T" to "ጥ", "To" to "ጦ", "Tua" to "ጧ",

        // 29. c-family (ce -> ጨ)
        "ce" to "ጨ", "cu" to "ጩ", "ci" to "ጪ", "ca" to "ጫ", "cee" to "ጬ", "c" to "ጭ", "co" to "ጮ", "cua" to "ቿ",

        // 30. ts-family (User: tse -> ጰ) [Pait]
        "tse" to "ጰ", "tsu" to "ጱ", "tsi" to "ጲ", "tsa" to "ጳ", "tsee" to "ጴ", "ts" to "ጵ", "tso" to "ጶ", "tsua" to "ጷ",
        "pe" to "ጰ", "pu" to "ጱ", "pi" to "ጲ", "pa" to "ጳ", "pee" to "ጴ", "p" to "ጵ", "po" to "ጶ",

        // 33. f-family (fe -> ፈ)
        "fe" to "ፈ", "fu" to "ፉ", "fi" to "ፊ", "fa" to "ፋ", "fee" to "ፌ", "f" to "ፍ", "fo" to "ፎ", "fua" to "ፏ",

        // 35. p-family (Pe -> ፐ)
        "Pe" to "ፐ", "Pu" to "ፑ", "Pi" to "ፒ", "Pa" to "ፓ", "Pee" to "ፔ", "P" to "ፕ", "Po" to "ፖ", "Pua" to "ፗ",

        // 36. v-family (ve -> ቨ)
        "ve" to "ቨ", "vu" to "ቩ", "vi" to "ቪ", "va" to "ቫ", "vee" to "ቬ", "v" to "ቭ", "vo" to "ቮ", "vua" to "ቯ"
    )
}