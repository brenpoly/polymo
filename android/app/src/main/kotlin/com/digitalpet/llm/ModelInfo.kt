package com.digitalpet.llm

/**
 * Describes a locally-available GGUF model file.
 *
 * Instances are produced by scanning the device's download directory and the
 * app's external files directory for `.gguf` files.
 *
 * @property path             absolute path to the `.gguf` file on disk.
 * @property fileName         human-readable file name (e.g. "tinyllama-1.1b-q4_k_m.gguf").
 * @property parameterCount   approximate parameter count label (e.g. "3B", "7B").
 * @property quantization     quantisation scheme identifier (e.g. "Q4_K_M", "Q5_1").
 * @property architecture     model architecture name (e.g. "llama", "phi").
 * @property fileSizeBytes    size of the model file in bytes.
 */
data class ModelInfo(
    val path: String,
    val fileName: String,
    val parameterCount: String,
    val quantization: String,
    val architecture: String,
    val fileSizeBytes: Long
)
