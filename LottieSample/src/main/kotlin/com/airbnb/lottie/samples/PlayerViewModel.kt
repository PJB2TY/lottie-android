package com.airbnb.lottie.samples

import android.annotation.SuppressLint
import android.app.Application
import android.arch.lifecycle.AndroidViewModel
import android.arch.lifecycle.MutableLiveData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.samples.model.CompositionArgs
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import okhttp3.CacheControl
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class CompositionData(var composition: LottieComposition? = null) {
    val images = HashMap<String, Bitmap>()
}

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    val composition = MutableLiveData<CompositionData>()
    val error = MutableLiveData<Throwable>()

    fun fetchAnimation(args: CompositionArgs) {
        val url = args.url ?: "https://www.lottiefiles.com/download/${args.animationData?.id}"
        fetchAnimationByUrl(url)
    }

    private fun fetchAnimationByUrl(url: String) {
        val request: Request
        try {
            request = Request.Builder()
                    .cacheControl(CacheControl.Builder()
                            .maxAge(Int.MAX_VALUE, TimeUnit.DAYS)
                            .build())
                    .url(url)
                    .build()
        } catch (e: IllegalArgumentException) {
            error.value = e
            return
        }
        getApplication<LottieApplication>().okHttpClient
                .newCall(request)
                ?.enqueue(OkHttpCallback(
                        onFailure = { _, e -> error.value = e },
                        onResponse = { _, response ->
                            if (!response.isSuccessful) {
                                error.value = IllegalStateException("Response was unsuccessful.")
                            } else {
                                if (response.body()?.contentType() == MediaType.parse("application/zip")) {
                                    handleZipResponse(response.body()!!)
                                } else {
                                    val string = response.body()?.string()
                                    if (string == null) {
                                        error.value = IllegalStateException("Response body was null")
                                        return@OkHttpCallback
                                    }
                                    handleJsonResponse(string)
                                }


                            }
                        }))
    }

    private fun handleJsonResponse(jsonString: String) {
        try {
            LottieComposition.Factory.fromJsonString(jsonString, {
                if (it == null) {
                    error.value = IllegalArgumentException("Unable to parse composition")
                } else {
                    composition.value = CompositionData(it)
                }
            })
        } catch (e: RuntimeException) {
            error.value = e
        }
    }

    @SuppressLint("CheckResult")
    private fun handleZipResponse(body: ResponseBody) {
        Observable.just(body.byteStream())
                .map {
                    val compositionData = CompositionData()
                    val zis: ZipInputStream
                    try {
                        zis = ZipInputStream(body.byteStream())

                        var zipEntry = zis.nextEntry
                        while (zipEntry != null) {
                            if (zipEntry.name.contains("__MACOSX")) {
                                zis.closeEntry()
                            } else if (zipEntry.name.contains(".json")) {
                                val composition = LottieComposition.Factory.fromInputStreamSync(zis, false)
                                if (composition == null) {
                                    throw IllegalArgumentException("Unable to parse composition")
                                } else {
                                    compositionData.composition = composition
                                }
                            } else if (zipEntry.name.contains(".png")) {
                                val name = zipEntry.name.split("/").last()
                                compositionData.images[name] = BitmapFactory.decodeStream(zis)
                            } else {
                                zis.closeEntry()
                            }
                            zipEntry = zis.nextEntry
                        }

                        zis.close()
                        compositionData
                    } catch (e: IOException) {
                        throw e
                    }
                }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({
                    composition.value = it
                }, {
                    error.value = it
                })

    }
}