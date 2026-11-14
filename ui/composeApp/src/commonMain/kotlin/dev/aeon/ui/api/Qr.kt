package dev.aeon.ui.api

/**
 * QR encoder (byte mode, level M, versions 1-10), a port of src/core/qr.ts. For the same text it must produce the
 * same matrix: QrTest checks the vectors that test/qr.test.ts also checks, and those were compared with an
 * independent encoder.
 */
class QrCode(val version: Int, val mask: Int, val size: Int, val modules: Array<BooleanArray>) {
    /** One line per row, `#` dark and `.` light (the form the shared vectors are hashed in). */
    fun toText(): String = modules.joinToString("\n") { row -> row.joinToString("") { if (it) "#" else "." } }
}

object Qr {
    const val MAX_BYTES = 213
    private val ECC_PER_BLOCK_M = intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
    private val BLOCKS_M = intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

    private fun rawModules(ver: Int): Int {
        var r = (16 * ver + 128) * ver + 64
        if (ver >= 2) {
            val n = ver / 7 + 2
            r -= (25 * n - 10) * n - 55
            if (ver >= 7) r -= 36
        }
        return r
    }

    private fun dataCodewords(ver: Int) = rawModules(ver) / 8 - ECC_PER_BLOCK_M[ver] * BLOCKS_M[ver]

    private fun gfMul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11d)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z and 0xff
    }

    private fun rsDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in result.indices) {
                result[j] = gfMul(result[j], root)
                if (j + 1 < result.size) result[j] = result[j] xor result[j + 1]
            }
            root = gfMul(root, 0x02)
        }
        return result
    }

    private fun rsRemainder(data: List<Int>, divisor: IntArray): List<Int> {
        val result = ArrayDeque<Int>(divisor.size)
        repeat(divisor.size) { result.addLast(0) }
        for (b in data) {
            val factor = b xor result.removeFirst()
            result.addLast(0)
            for (i in divisor.indices) result[i] = result[i] xor gfMul(divisor[i], factor)
        }
        return result.toList()
    }

    private fun bit(x: Int, i: Int) = ((x ushr i) and 1) != 0

    private fun alignmentPositions(ver: Int): List<Int> {
        if (ver == 1) return emptyList()
        val n = ver / 7 + 2
        val size = ver * 4 + 17
        val step = ((ver * 4 + 4) + (n * 2 - 2) - 1) / (n * 2 - 2) * 2
        val result = mutableListOf(6)
        var pos = size - 7
        while (result.size < n) {
            result.add(1, pos)
            pos -= step
        }
        return result
    }

    private fun codewords(bytes: ByteArray, ver: Int): List<Int> {
        val bits = ArrayList<Int>()
        fun put(value: Int, len: Int) {
            for (i in len - 1 downTo 0) bits.add((value ushr i) and 1)
        }
        put(0b0100, 4)
        put(bytes.size, if (ver <= 9) 8 else 16)
        for (b in bytes) put(b.toInt() and 0xff, 8)
        val capacity = dataCodewords(ver) * 8
        put(0, minOf(4, capacity - bits.size))
        put(0, (8 - bits.size % 8) % 8)
        val data = ArrayList<Int>()
        var i = 0
        while (i < bits.size) {
            var v = 0
            for (k in 0 until 8) v = (v shl 1) or bits[i + k]
            data.add(v)
            i += 8
        }
        var pad = 0xec
        while (data.size < dataCodewords(ver)) {
            data.add(pad)
            pad = pad xor (0xec xor 0x11)
        }
        val nBlocks = BLOCKS_M[ver]
        val eccLen = ECC_PER_BLOCK_M[ver]
        val raw = rawModules(ver) / 8
        val nShort = nBlocks - raw % nBlocks
        val shortLen = raw / nBlocks
        val divisor = rsDivisor(eccLen)
        val blocks = ArrayList<List<Int>>()
        var k = 0
        for (b in 0 until nBlocks) {
            val dat = ArrayList(data.subList(k, k + shortLen - eccLen + if (b < nShort) 0 else 1))
            k += dat.size
            val ecc = rsRemainder(dat, divisor)
            if (b < nShort) dat.add(0)
            blocks.add(dat + ecc)
        }
        val out = ArrayList<Int>()
        for (col in blocks[0].indices) {
            for (j in blocks.indices) if (col != shortLen - eccLen || j >= nShort) out.add(blocks[j][col])
        }
        return out
    }

    private class Grid(val ver: Int) {
        val size = ver * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val fixed = Array(size) { BooleanArray(size) }

        fun setFn(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            fixed[y][x] = true
        }

        fun drawFunctionPatterns() {
            for (i in 0 until size) {
                setFn(6, i, i % 2 == 0)
                setFn(i, 6, i % 2 == 0)
            }
            for ((cx, cy) in listOf(3 to 3, size - 4 to 3, 3 to size - 4)) {
                for (dy in -4..4) for (dx in -4..4) {
                    val x = cx + dx
                    val y = cy + dy
                    val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    if (x in 0 until size && y in 0 until size) setFn(x, y, dist != 2 && dist != 4)
                }
            }
            val pos = alignmentPositions(ver)
            val n = pos.size
            for (i in 0 until n) for (j in 0 until n) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) setFn(pos[i] + dx, pos[j] + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
            }
            drawFormat(0)
            if (ver >= 7) {
                var rem = ver
                for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1f25)
                val bits = (ver shl 12) or rem
                for (i in 0 until 18) {
                    val a = size - 11 + i % 3
                    val b = i / 3
                    setFn(a, b, bit(bits, i))
                    setFn(b, a, bit(bits, i))
                }
            }
        }

        fun drawFormat(mask: Int) {
            val data = mask // level M = 0b00
            var rem = data
            for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
            val bits = ((data shl 10) or rem) xor 0x5412
            for (i in 0..5) setFn(8, i, bit(bits, i))
            setFn(8, 7, bit(bits, 6))
            setFn(8, 8, bit(bits, 7))
            setFn(7, 8, bit(bits, 8))
            for (i in 9 until 15) setFn(14 - i, 8, bit(bits, i))
            for (i in 0 until 8) setFn(size - 1 - i, 8, bit(bits, i))
            for (i in 8 until 15) setFn(8, size - 15 + i, bit(bits, i))
            setFn(8, size - 8, true)
        }

        fun placeData(words: List<Int>) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0 until 2) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!fixed[y][x] && i < words.size * 8) {
                            modules[y][x] = bit(words[i ushr 3], 7 - (i and 7))
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> (x * y) % 2 + (x * y) % 3 == 0
                    6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
                    else -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
                }
                if (!fixed[y][x] && invert) modules[y][x] = !modules[y][x]
            }
        }
    }

    private fun penalty(m: Array<BooleanArray>): Int {
        val n = m.size
        var score = 0
        for (axis in 0..1) {
            for (a in 0 until n) {
                fun at(i: Int) = if (axis == 0) m[a][i] else m[i][a]
                var run = 1
                for (i in 1..n) {
                    if (i < n && at(i) == at(i - 1)) run++
                    else {
                        if (run >= 5) score += 3 + (run - 5)
                        run = 1
                    }
                }
                for (i in 0..n - 11) {
                    val w = buildString { for (k in 0 until 11) append(if (at(i + k)) '1' else '0') }
                    if (w == "10111010000" || w == "00001011101") score += 40
                }
            }
        }
        for (y in 0 until n - 1) for (x in 0 until n - 1) {
            if (m[y][x] == m[y][x + 1] && m[y][x] == m[y + 1][x] && m[y][x] == m[y + 1][x + 1]) score += 3
        }
        var dark = 0
        for (row in m) for (v in row) if (v) dark++
        val total = n * n
        val diff = kotlin.math.abs(dark * 20 - total * 10)
        score += ((diff + total - 1) / total - 1) * 10
        return score
    }

    /** Throws [IllegalArgumentException] when the text needs more than [MAX_BYTES] bytes. */
    fun encode(text: String, forceMask: Int? = null): QrCode {
        val bytes = text.encodeToByteArray()
        var ver = 1
        while (ver <= 10 && bytes.size * 8 + 4 + (if (ver <= 9) 8 else 16) > dataCodewords(ver) * 8) ver++
        require(ver <= 10) { "QR text is too long (${bytes.size} bytes, at most $MAX_BYTES)" }
        val grid = Grid(ver)
        grid.drawFunctionPatterns()
        grid.placeData(codewords(bytes, ver))
        var best = forceMask ?: 0
        if (forceMask == null) {
            var bestScore = Int.MAX_VALUE
            for (mask in 0 until 8) {
                grid.applyMask(mask)
                grid.drawFormat(mask)
                val s = penalty(grid.modules)
                if (s < bestScore) {
                    bestScore = s
                    best = mask
                }
                grid.applyMask(mask)
            }
        }
        grid.applyMask(best)
        grid.drawFormat(best)
        return QrCode(ver, best, grid.size, grid.modules)
    }
}
