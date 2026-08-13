package com.lecturelistener.detect

/** 文本相似度工具（用于去重：老师重复提问、回声片段等） */
object TextSimilarity {

    fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun bigrams(s: String): Set<String> {
        if (s.length < 2) return setOf(s)
        val set = HashSet<String>(s.length - 1)
        for (i in 0 until s.length - 1) set.add(s.substring(i, i + 2))
        return set
    }

    /** 字符二元组 Jaccard 相似度，0.0 ~ 1.0 */
    fun similarity(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        val ba = bigrams(na)
        val bb = bigrams(nb)
        val inter = ba.intersect(bb).size
        val union = ba.size + bb.size - inter
        if (union <= 0) return 0.0
        return inter.toDouble() / union
    }
}
