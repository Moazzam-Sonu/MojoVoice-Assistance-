package com.nova.app.engine.math

class ModelWeights {
    private val layers = mutableMapOf<String, Matrix>()
    fun set(name: String, matrix: Matrix) { layers[name] = matrix }
    fun get(name: String): Matrix = layers[name] ?: error("Layer not found: $name")
    fun has(name: String) = layers.containsKey(name)
    fun layerNames() = layers.keys.toList()

    fun saveTo(path: String) {
        java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(path))).use { out ->
            out.write("NOVA".toByteArray())
            out.writeInt(1)
            out.writeInt(layers.size)
            for ((name, matrix) in layers) {
                val nb = name.toByteArray(Charsets.UTF_8)
                out.writeInt(nb.size); out.write(nb)
                out.writeInt(matrix.rows); out.writeInt(matrix.cols)
                matrix.data.forEach { out.writeFloat(it) }
            }
        }
    }

    companion object {
        fun loadFrom(path: String): ModelWeights {
            val w = ModelWeights()
            java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(path))).use { inp ->
                inp.readFully(ByteArray(4)); inp.readInt()
                val n = inp.readInt()
                repeat(n) {
                    val nameLen = inp.readInt()
                    val name = ByteArray(nameLen).also { inp.readFully(it) }.toString(Charsets.UTF_8)
                    val rows = inp.readInt(); val cols = inp.readInt()
                    val data = FloatArray(rows * cols) { inp.readFloat() }
                    w.layers[name] = Matrix.from(rows, cols, data)
                }
            }
            return w
        }
    }
}