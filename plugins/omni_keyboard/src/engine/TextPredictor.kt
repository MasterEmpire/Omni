package com.omni.plugin.keyboard.engine

import android.util.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.PriorityQueue
import kotlin.math.abs

data class Prediction(
    val text: String,
    val shouldAutoCorrect: Boolean
)

class TextPredictor(
    private val dataDir: File,
    private val persistentDictDir: File,
    private val logger: (String) -> Unit
) {

    private val trieRoot = TrieNode()
    private val bigramMap = HashMap<String, List<String>>()
    
    private val userBigrams = HashMap<String, HashMap<String, Int>>()
    private val userTrigrams = HashMap<String, HashMap<String, Int>>()
    
    @Volatile private var isLoaded = false
    @Volatile private var isDirty = false
    
    private val blockedWords = HashSet<String>()
    private val candidateWords = HashMap<String, Int>()
    
    private val userDictFile = File(dataDir, "user_learning.txt")
    private val bigramFile = File(dataDir, "user_bigrams.txt")
    private val trigramFile = File(dataDir, "user_trigrams.txt")
    private val blockedFile = File(dataDir, "blocked.txt")
    private val candidatesFile = File(dataDir, "candidates.txt")
    private val typoStatsFile = File(dataDir, "typo_stats.txt")
    private val usageStatsFile = File(dataDir, "usage_stats.txt")

    private val typoStats = HashMap<String, HashMap<String, Int>>()
    private val usageStats = HashMap<String, Long>()
    private val fileMutex = Mutex()

    private val neighbors = HashMap<Char, String>().apply {
        put('q', "wsa12"); put('w', "qeasd23"); put('e', "wrsdf34"); put('r', "etdfg45"); put('t', "ryfgh56")
        put('y', "tughj67"); put('u', "yijhk78"); put('i', "uojkl89"); put('o', "ipkl90"); put('p', "ol0")
        put('a', "qwsz"); put('s', "weadzxc"); put('d', "ersfxcv"); put('f', "rtdgcvb"); put('g', "tyfhvbn")
        put('h', "yugjbnm"); put('j', "uihknm"); put('k', "iojlm"); put('l', "opk")
        put('z', "asx"); put('x', "sdzc"); put('c', "dfxv"); put('v', "fgcb"); put('b', "ghvn"); put('n', "hjbm"); put('m', "jkn")
        loadAmharicNeighbors(this)
    }

    private fun loadAmharicNeighbors(map: HashMap<Char, String>) {
        fun link(chars: String) {
            for (c1 in chars) {
                val sb = StringBuilder(map[c1] ?: "")
                for (c2 in chars) {
                    if (c1 != c2 && !sb.contains(c2)) sb.append(c2)
                }
                map[c1] = sb.toString()
            }
        }

        link("ሀሐኀኸ"); link("ሁሑኁኹ"); link("ሂሒኂኺ"); link("ሃሓኃኻ"); link("ሄሔኄኼ"); link("ህሕኅኽ"); link("ሆሖኆኾ")
        link("ሰሠ"); link("ሱሡ"); link("ሲሢ"); link("ሳሣ"); link("ሴሤ"); link("ስሥ"); link("ሶሦ")
        link("አዐ"); link("ኡዑ"); link("ኢዒ"); link("ኣዓ"); link("ኤዔ"); link("እዕ"); link("ኦዖ")
        link("ጸፀ"); link("ጹፁ"); link("ጺፂ"); link("ጻፃ"); link("ጼፄ"); link("ጽፅ"); link("ጾፆ")
        link("ሰደዘአ"); link("ሀጀገበ"); link("ቀወአ"); link("ረተደፈ"); link("ተየፈገ"); link("ከኢኦጀ"); link("ነበሀጀመ")
    }

    fun loadDictionary() {
        if (isLoaded) return
        Thread {
            try {
                loadUnigrams()
                loadCommonContractions()
                loadAmharicJson()
                loadBigrams()
                loadPersistence()
                isLoaded = true
                startAutoSave()
                logger("Prediction engine ready. Persistent dictionary sync verified.")
            } catch (e: Exception) {
                logger("Error loading dictionary: ${e.message}")
            }
        }.start()
    }

    private fun startAutoSave() {
        Thread {
            while (true) {
                try {
                    Thread.sleep(30_000)
                    if (isDirty) saveData()
                } catch (_: InterruptedException) { break }
            }
        }.start()
    }

    fun flush() {
        if (isDirty) {
            Thread { saveData() }.start()
        }
    }

    private fun saveData() {
        try {
            dataDir.mkdirs()
            val bigramStr = StringBuilder()
            synchronized(userBigrams) {
                userBigrams.forEach { entry -> 
                    val k = entry.key
                    entry.value.forEach { subEntry -> bigramStr.append("$k ${subEntry.key} ${subEntry.value}\n") }
                }
            }
            bigramFile.writeText(bigramStr.toString(), Charsets.UTF_8)

            val trigramStr = StringBuilder()
            synchronized(userTrigrams) {
                userTrigrams.forEach { entry -> 
                    val k = entry.key
                    entry.value.forEach { subEntry -> trigramStr.append("$k ${subEntry.key} ${subEntry.value}\n") }
                }
            }
            trigramFile.writeText(trigramStr.toString(), Charsets.UTF_8)

            val usageStr = StringBuilder()
            synchronized(usageStats) {
                usageStats.forEach { entry -> usageStr.append("${entry.key} ${entry.value}\n") }
            }
            usageStatsFile.writeText(usageStr.toString(), Charsets.UTF_8)

            isDirty = false
        } catch (_: Exception) {}
    }

    private fun loadPersistence() {
        if (bigramFile.exists()) {
            bigramFile.forEachLine {
                val p = it.split(" ")
                if (p.size >= 3) {
                    val key = p[0]; val word = p[1]; val count = p[2].toIntOrNull() ?: 1
                    userBigrams.getOrPut(key) { HashMap() }[word] = count
                }
            }
        }
        if (trigramFile.exists()) {
            trigramFile.forEachLine {
                val p = it.split(" ")
                if (p.size >= 3) {
                    val key = p[0]; val word = p[1]; val count = p[2].toIntOrNull() ?: 1
                    userTrigrams.getOrPut(key) { HashMap() }[word] = count
                }
            }
        }
        if (usageStatsFile.exists()) {
            usageStatsFile.forEachLine {
                val parts = it.split(" ")
                if (parts.size >= 2) {
                    val word = parts[0]
                    val count = parts[1].toLongOrNull() ?: 0L
                    usageStats[word] = count
                    findTrieNode(word)?.apply { 
                        frequency += (count * 1000)
                        updatePathMaxFreq(word, frequency)
                    }
                }
            }
        }

        if (blockedFile.exists()) blockedFile.forEachLine { blockedWords.add(it.trim().lowercase()) }
        if (userDictFile.exists()) {
            userDictFile.forEachLine {
                val word = it.trim()
                if (word.isNotEmpty()) insertTrie(word, Long.MAX_VALUE)
            }
        }
        if (candidatesFile.exists()) {
            candidatesFile.forEachLine {
                val parts = it.split(" ")
                if (parts.size >= 2) candidateWords[parts[0]] = parts[1].toIntOrNull() ?: 1
            }
        }
        if (typoStatsFile.exists()) {
            typoStatsFile.forEachLine {
                val parts = it.split(" ")
                if (parts.size >= 3) {
                    val typo = parts[0]
                    val correct = parts[1]
                    val count = parts[2].toIntOrNull() ?: 0
                    typoStats.getOrPut(typo) { HashMap() }[correct] = count
                }
            }
        }
    }

    fun learnWord(rawWord: String, prev: String?, prevPrev: String?, isExplicit: Boolean) {
        val word = rawWord.trim()
        if (word.isBlank() || blockedWords.contains(word.lowercase())) return

        val node = findTrieNode(word)
        if (node != null && node.isEndOfWord) {
            boostWordStats(word, node, prev, prevPrev)
            return
        }

        if (!isValidLearningCandidate(word)) return

        val threshold = if (isExplicit) 1 else 5
        val count = candidateWords.getOrDefault(word, 0) + 1
        if (count >= threshold) {
            insertTrie(word, Long.MAX_VALUE)
            Thread {
                try { userDictFile.appendText("$word\n") } catch (_: Exception) {}
            }.start()
            candidateWords.remove(word)
            val insertedNode = findTrieNode(word)
            if (insertedNode != null) {
                boostWordStats(word, insertedNode, prev, prevPrev)
            }
        } else {
            candidateWords[word] = count
            isDirty = true
        }
    }

    private fun boostWordStats(word: String, node: TrieNode, prev: String?, prevPrev: String?) {
        synchronized(usageStats) {
            val newCount = usageStats.getOrDefault(word, 0L) + 1
            usageStats[word] = newCount
            node.frequency += 1000
            updatePathMaxFreq(word, node.frequency)
        }

        if (prev != null) {
            val k = prev.lowercase()
            synchronized(userBigrams) {
                userBigrams.getOrPut(k) { HashMap() }[word] = (userBigrams[k]?.get(word) ?: 0) + 1
            }
        }

        if (prev != null && prevPrev != null) {
            val k = "${prevPrev.lowercase()}_${prev.lowercase()}"
            synchronized(userTrigrams) {
                userTrigrams.getOrPut(k) { HashMap() }[word] = (userTrigrams[k]?.get(word) ?: 0) + 1
            }
        }
        isDirty = true
    }

    private fun isValidLearningCandidate(word: String): Boolean {
        val isAmharic = word.any { it.code in 0x1200..0x137F }
        if (!isAmharic && word.length < 2 && word != "I" && word != "a") return false
        val hasVowel = word.contains(Regex("[aeiouyAEIOUY]"))
        if (!isAmharic && !hasVowel) return false

        val firstLower = word[0].isLowerCase()
        val hasUpperLater = word.substring(1).any { it.isUpperCase() }
        if (firstLower && hasUpperLater) return false

        return word.all { it.isLetter() || it == '\'' }
    }

    suspend fun removeWord(word: String) = withContext(Dispatchers.IO) {
        blockedWords.add(word.lowercase())
        fileMutex.withLock {
            try { blockedFile.writeText(blockedWords.joinToString("\n")) } catch (_: Exception) {}
        }
    }

    suspend fun learnCorrection(typo: String, correct: String) = withContext(Dispatchers.IO) {
        if (typo.isBlank() || correct.isBlank() || typo.equals(correct, true)) return@withContext
        val t = typo.lowercase()
        val map = typoStats.getOrPut(t) { HashMap() }
        map[correct] = (map[correct] ?: 0) + 1
        saveTypoStats()
    }

    private suspend fun saveTypoStats() {
        fileMutex.withLock {
            try {
                val sb = StringBuilder()
                typoStats.forEach { (t, map) ->
                    map.forEach { entry -> sb.append("$t ${entry.key} ${entry.value}\n") }
                }
                typoStatsFile.writeText(sb.toString(), Charsets.UTF_8)
            } catch (_: Exception) {}
        }
    }

    suspend fun predict(currentInput: String, prev: String, prevPrev: String, isAmharic: Boolean): List<Prediction> {
        if (!isLoaded) return emptyList()
        
        return withContext(Dispatchers.Default) {
            if (currentInput.isBlank()) {
                val candidates = ArrayList<String>()
                val triKey = "${prevPrev.lowercase()}_${prev.lowercase()}"
                userTrigrams[triKey]?.entries?.sortedByDescending { it.value }?.take(2)?.forEach { candidates.add(it.key) }
                userBigrams[prev.lowercase()]?.entries?.sortedByDescending { it.value }?.take(2)?.forEach { candidates.add(it.key) }
                
                val static = bigramMap[prev.lowercase()] ?: emptyList()
                candidates.addAll(static.take(3))
                
                if (candidates.isEmpty()) {
                    if (isAmharic) {
                        candidates.addAll(listOf("ሰላም", "ነው", "እንዴት"))
                    } else {
                        candidates.addAll(listOf("The", "I", "Hello"))
                    }
                }
                return@withContext candidates.distinct().take(3).map { Prediction(it, false) }
            }

            val term = currentInput.lowercase()
            val candidates = ArrayList<SearchResult>()

            if (term.length == 1 && "uiojkl".contains(term)) {
                candidates.add(SearchResult("I", 0.0, Long.MAX_VALUE, true))
            }

            candidates.addAll(searchPrefix(term))
            val exactMatchNode = findTrieNode(term)
            val isInputValidWord = exactMatchNode != null && exactMatchNode.isEndOfWord
            
            if (candidates.size < 5) {
                val penaltyMultiplier = if (isInputValidWord) 2.0 else 1.0
                candidates.addAll(searchFuzzy(term, penaltyMultiplier))
            }

            val whitelist = setOf("I", "a", "A", "&")
            val ranked = candidates.map { candidate ->
                var finalScore = candidate.score
                val inputHasApostrophe = term.contains("'")
                val candHasApostrophe = candidate.word.contains("'")
                if (!inputHasApostrophe && candHasApostrophe) {
                    val normalizedCand = candidate.word.replace("'", "").lowercase()
                    val normalizedInput = term.replace("'", "").lowercase()
                    if (normalizedCand == normalizedInput) {
                        finalScore -= 3.0
                    }
                }
                if (candidate.frequency > 0) {
                    val freqBoost = kotlin.math.log10(candidate.frequency.toDouble()) * 0.2
                    finalScore -= freqBoost
                }
                candidate.copy(score = finalScore)
            }
            .sortedWith(Comparator { a, b ->
                if (abs(a.score - b.score) > 0.01) a.score.compareTo(b.score)
                else b.frequency.compareTo(a.frequency)
            })
            .distinctBy { it.word }
            .filter { !blockedWords.contains(it.word.lowercase()) }
            .filter { it.word.length > 1 || whitelist.contains(it.word) }
            .take(3)

            ranked.mapIndexed { index, result ->
                val isTop = index == 0
                var confident = false
                
                if (isTop) {
                    val lengthRatio = result.word.length.toFloat() / term.length.toFloat()
                    val isLengthSafe = lengthRatio >= 0.5f
                    val learnedCount = typoStats[term]?.get(result.word) ?: 0
                    val isLearnedRule = learnedCount >= 3
                    
                    if (isLearnedRule) {
                        confident = true
                    } else if (isLengthSafe) {
                        if (result.isPrefix) {
                            confident = true
                        } else {
                            val maxAllowedCost = term.length * 0.3
                            if (result.score <= maxAllowedCost) {
                                confident = true
                            }
                        }
                    }
                }
                Prediction(result.word, confident)
            }
        }
    }

    data class SearchResult(val word: String, val score: Double, val frequency: Long, val isPrefix: Boolean)

    class TrieNode {
        val children = HashMap<Char, TrieNode>(4)
        var isEndOfWord = false
        var frequency: Long = 0
        var maxSubtreeFreq: Long = 0
        var word: String? = null
    }

    private fun findTrieNode(word: String): TrieNode? {
        var node = trieRoot
        for (c in word.lowercase()) node = node.children[c] ?: return null
        return node
    }

    private fun updatePathMaxFreq(word: String, newFreq: Long) {
        val lower = word.lowercase()
        var curr = trieRoot
        if (newFreq > curr.maxSubtreeFreq) curr.maxSubtreeFreq = newFreq
        for (c in lower) {
            curr = curr.children[c] ?: break
            if (newFreq > curr.maxSubtreeFreq) curr.maxSubtreeFreq = newFreq
        }
    }

    private fun insertTrie(word: String, freq: Long) {
        val lower = word.lowercase()
        var node = trieRoot
        if (freq > node.maxSubtreeFreq) node.maxSubtreeFreq = freq
        for (c in lower) {
            node = node.children.getOrPut(c) { TrieNode() }
            if (freq > node.maxSubtreeFreq) node.maxSubtreeFreq = freq
        }
        node.isEndOfWord = true
        node.word = word
        if (freq > node.frequency) node.frequency = freq
    }

    fun isValidWord(rawWord: String): Boolean {
        if (rawWord.isBlank()) return false
        val lower = rawWord.lowercase()
        if (blockedWords.contains(lower)) return false
        val node = findTrieNode(rawWord)
        return node != null && node.isEndOfWord
    }

    private fun searchPrefix(prefix: String): List<SearchResult> {
        var node = trieRoot
        for (c in prefix) node = node.children[c] ?: return emptyList()
        val results = ArrayList<SearchResult>()
        collectDescendants(node, results)
        return results
    }

    private fun collectDescendants(node: TrieNode, results: MutableList<SearchResult>) {
        if (results.size >= 5) return
        if (node.isEndOfWord && node.word != null) {
            results.add(SearchResult(node.word!!, 0.0, node.frequency, true))
        }
        node.children.values.sortedByDescending { it.maxSubtreeFreq }.forEach {
            collectDescendants(it, results)
        }
    }

    private fun searchFuzzy(target: String, costMultiplier: Double): List<SearchResult> {
        val results = ArrayList<SearchResult>()
        val maxCost = if (target.length < 4) 1.5 else 3.0
        recursiveWalk(trieRoot, target, 0, 0.0, maxCost, results, costMultiplier)
        return results
    }

    private fun recursiveWalk(node: TrieNode, target: String, index: Int, cost: Double, maxCost: Double, results: MutableList<SearchResult>, multiplier: Double) {
        if (cost > maxCost) return
        
        if (node.isEndOfWord && node.word != null) {
            val remaining = target.length - index
            val finalCost = cost + (remaining * 1.0)
            if (finalCost <= maxCost) {
                results.add(SearchResult(node.word!!, finalCost, node.frequency, false))
            }
        }

        if (cost + 0.1 > maxCost) return

        if (index < target.length - 1) {
            val char1 = target[index]
            val char2 = target[index + 1]
            val child2 = node.children[char2]
            if (child2 != null) {
                val grandChild = child2.children[char1]
                if (grandChild != null) {
                    recursiveWalk(grandChild, target, index + 2, cost + (0.75 * multiplier), maxCost, results, multiplier)
                }
            }
        }

        node.children.forEach { (trieChar, childNode) ->
            val baseInsCost = if (trieChar == '\'') 0.1 else 1.25
            recursiveWalk(childNode, target, index, cost + (baseInsCost * multiplier), maxCost, results, multiplier)
        }

        if (index >= target.length) return
        val inputChar = target[index]

        node.children.forEach { (trieChar, childNode) ->
            var newCost = cost
            if (trieChar != inputChar) {
                val nearby = neighbors[inputChar]?.contains(trieChar) == true
                val subCost = if (nearby) 0.35 else 2.0
                newCost += (subCost * multiplier)
            }
            recursiveWalk(childNode, target, index + 1, newCost, maxCost, results, multiplier)
        }

        var baseDelCost = 1.25
        if (index > 0 && inputChar == target[index - 1]) baseDelCost = 0.2
        recursiveWalk(node, target, index + 1, cost + (baseDelCost * multiplier), maxCost, results, multiplier)
    }

    private fun loadAmharicJson() {
        var count = 0
        val file = File(persistentDictDir, "amharic_dict.json")
        if (!file.exists()) {
            logger("No amharic_dict.json found in ${persistentDictDir.absolutePath}")
            return
        }
        try {
            file.inputStream().use { inputStream ->
                val reader = JsonReader(inputStream.bufferedReader())
                reader.isLenient = true
                var isRootArray = false
                try {
                    reader.beginArray()
                    isRootArray = true
                } catch (_: Exception) {
                    reader.beginObject()
                }

                while (reader.hasNext()) {
                    if (!isRootArray) {
                        val name = reader.nextName()
                        if (name == "words") {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                parseEntry(reader)
                                count++
                            }
                            reader.endArray()
                        } else {
                            reader.skipValue()
                        }
                    } else {
                        parseEntry(reader)
                        count++
                    }
                }

                if (!isRootArray) reader.endObject() else reader.endArray()
                logger("Amharic Dictionary Loaded: $count words from storage")
            }
        } catch (e: Exception) {
            logger("Amharic JSON Parse Fail: ${e.message}")
        }
    }

    private fun parseEntry(reader: JsonReader) {
        reader.beginObject()
        var word = ""
        var freq = 5000L
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "word" -> word = reader.nextString()
                "freq", "frequency" -> freq = reader.nextLong()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (word.isNotEmpty()) insertTrie(word, freq)
    }

    private fun loadCommonContractions() {
        val contractions = listOf(
            "don't", "can't", "won't", "it's", "I'm", "I'll", "I've", "I'd",
            "that's", "didn't", "doesn't", "isn't", "aren't", "wasn't", "weren't",
            "hasn't", "haven't", "hadn't", "wouldn't", "shouldn't", "couldn't",
            "we're", "we've", "we'll", "they're", "they've", "they'll",
            "you're", "you've", "you'll", "let's", "what's", "here's", "there's",
            "who's", "how's", "where's", "when's", "why's",
            "ain't", "y'all", "ma'am", "o'clock", "mustn't", "shan't", "needn't",
            "daren't", "oughtn't", "mightn't", "'tis", "'twas", "ol'", "ne'er",
            "o'er", "e'er", "it'll", "he's", "he'll", "she's", "she'll",
            "could've", "should've", "would've", "might've", "must've",
            "who'd", "what'd", "that'll", "there'll"
        )
        contractions.forEach { insertTrie(it, 2_000_000L) }
    }

    private fun loadUnigrams() {
        val file = File(persistentDictDir, "dictionary.txt")
        if (!file.exists()) {
            logger("No dictionary.txt found in ${persistentDictDir.absolutePath}")
            return
        }
        file.forEachLine { line ->
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.isNotEmpty()) insertTrie(parts[0], if (parts.size >= 2) parts[1].toLongOrNull() ?: 100L else 100L)
        }
    }

    private fun loadBigrams() {
        val file = File(persistentDictDir, "bigrams.txt")
        if (!file.exists()) return
        val tempMap = HashMap<String, PriorityQueue<Pair<Long, String>>>()
        file.forEachLine { line ->
            val parts = line.trim().split(" ")
            if (parts.size >= 3 && (parts[2].toLongOrNull() ?: 0L) > 1000) {
                val heap = tempMap.getOrPut(parts[0].lowercase()) { PriorityQueue { a, b -> a.first.compareTo(b.first) } }
                heap.add((parts[2].toLongOrNull() ?: 0L) to parts[1])
                if (heap.size > 3) heap.poll()
            }
        }
        tempMap.forEach { entry -> bigramMap[entry.key] = entry.value.sortedByDescending { it.first }.map { it.second } }
    }
}