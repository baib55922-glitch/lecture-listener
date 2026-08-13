package com.lecturelistener.asr

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import java.io.File

/**
 * sherpa-onnx 离线识别薄封装。
 * 模型：Paraformer 双语流式（中文优化，支持中英混说）。
 */
object SherpaEngine {

    const val SAMPLE_RATE = 16000

    const val FILE_ENCODER = "encoder.int8.onnx"
    const val FILE_DECODER = "decoder.int8.onnx"
    const val FILE_TOKENS = "tokens.txt"

    /**
     * 创建流式识别器。
     * @param modelDir 模型目录（含 encoder/decoder/tokens 三个文件）
     * @param silenceMs 断句静音阈值：映射到 sherpa 端点规则（rule2）
     */
    fun createRecognizer(modelDir: File, silenceMs: Int, numThreads: Int = 2): OnlineRecognizer {
        val silenceSec = (silenceMs / 1000f).coerceIn(0.5f, 3.0f)
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                paraformer = OnlineParaformerModelConfig(
                    encoder = File(modelDir, FILE_ENCODER).absolutePath,
                    decoder = File(modelDir, FILE_DECODER).absolutePath,
                ),
                tokens = File(modelDir, FILE_TOKENS).absolutePath,
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
                modelType = "",
            ),
            endpointConfig = EndpointConfig(
                // rule1: 长静音端点（无语音时）
                rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = silenceSec + 1.0f, minUtteranceLength = 0f),
                // rule2: 说话后的静音端点（主断句规则，跟随用户设置）
                rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = silenceSec, minUtteranceLength = 0f),
                // rule3: 超长句强制端点
                rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0f, minUtteranceLength = 20f),
            ),
            enableEndpoint = true,
        )
        return OnlineRecognizer(assetManager = null, config = config)
    }
}
