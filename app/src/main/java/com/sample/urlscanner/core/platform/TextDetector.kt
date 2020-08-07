package com.sample.urlscanner.core.platform

import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import kotlinx.coroutines.tasks.await

object TextDetector {

    private val recognizer = FirebaseVision.getInstance().onDeviceTextRecognizer

    suspend fun processImage(image: FirebaseVisionImage) = recognizer.processImage(image).await()
}
