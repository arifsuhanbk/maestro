/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package ios.idb

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrThrow
import com.github.michaelbull.result.runCatching
import com.google.gson.Gson
import com.google.protobuf.ByteString
import idb.CompanionServiceGrpc
import idb.HIDEventKt
import idb.Idb
import idb.Idb.HIDEvent.HIDButtonType
import idb.Idb.RecordResponse
import idb.PushRequestKt.inner
import idb.accessibilityInfoRequest
import idb.clearKeychainRequest
import idb.fileContainer
import idb.hIDEvent
import idb.installRequest
import idb.launchRequest
import idb.location
import idb.mkdirRequest
import idb.openUrlRequest
import idb.payload
import idb.point
import idb.pullRequest
import idb.pushRequest
import idb.recordRequest
import idb.rmRequest
import idb.screenshotRequest
import idb.setLocationRequest
import idb.targetDescriptionRequest
import idb.terminateRequest
import idb.uninstallRequest
import io.grpc.ManagedChannel
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import ios.IOSDevice
import ios.IOSScreenRecording
import ios.device.AccessibilityNode
import ios.device.DeviceInfo
import ios.grpc.BlockingStreamObserver
import ios.hierarchy.XCUIElement
import ios.logger.IOSDriverLogger
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.Sink
import okio.buffer
import okio.source
import java.io.File
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.GZIPInputStream

class IdbIOSDevice(
    private val channel: ManagedChannel,
    override val deviceId: String?,
) : IOSDevice {

    private val blockingStub = CompanionServiceGrpc.newBlockingStub(channel)
    private val asyncStub = CompanionServiceGrpc.newStub(channel)
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun deviceInfo(): Result<DeviceInfo, Throwable> {
        return runCatching {
            val response = blockingStub.describe(targetDescriptionRequest {})
            val screenDimensions = response.targetDescription.screenDimensions

            DeviceInfo(
                widthPixels = screenDimensions.width.toInt(),
                heightPixels = screenDimensions.height.toInt()
            )
        }
    }

    override fun contentDescriptor(appId: String): Result<XCUIElement, Throwable> {
        return runCatching {
            val httpUrl = HttpUrl.Builder()
                .scheme("http")
                .host("localhost")
                .addPathSegment("subTree")
                .port(9080)
                .addQueryParameter("appId", appId)
                .build()
            val request = Request.Builder().get().url(httpUrl).build()
            val response = okHttpClient.newCall(request).execute()
            val xcUiElement = if (response.isSuccessful) {
                response.body?.let {
                    GSON.fromJson(String(it.bytes()), XCUIElement::class.java)
                } ?: throw IllegalStateException("View Hierarchy not available, response body is null")
            } else {
                IOSDriverLogger.dumpDeviceLogs(deviceId)
                throw IllegalArgumentException("Maestro was not able to capture view hierarchy. Run maestro bugreport command and submit " +
                    "new github issue on https://github.com/mobile-dev-inc/maestro/issues/new with the bugreport created.")
            }
            xcUiElement
        }
    }

    override fun describePoint(x: Int, y: Int): Result<List<AccessibilityNode>, Throwable> {
        return runCatching {
            val response = blockingStub.accessibilityInfo(accessibilityInfoRequest {
                point = point {
                    this.x = x.toDouble()
                    this.y = y.toDouble()
                }
                format = Idb.AccessibilityInfoRequest.Format.NESTED
            })

            listOf(
                GSON.fromJson(response.json, AccessibilityNode::class.java)
            )
        }
    }

    override fun tap(x: Int, y: Int): Result<Unit, Throwable> {
        return press(x, y, holdDelay = 50)
    }

    override fun longPress(x: Int, y: Int): Result<Unit, Throwable> {
        return press(x, y, holdDelay = 3000)
    }

    override fun pressKey(code: Int): Result<Unit, Throwable> {
        return runCatching {
            val responseObserver = BlockingStreamObserver<Idb.HIDResponse>()
            val stream = asyncStub.hid(responseObserver)

            TextInputUtil.keyPressToEvents(code.toLong())
                .forEach {
                    stream.onNext(it)
                }
            stream.onCompleted()
            responseObserver.awaitResult()
        }
    }

    override fun pressButton(code: Int): Result<Unit, Throwable> {
        return runCatching {
            val responseObserver = BlockingStreamObserver<Idb.HIDResponse>()
            val stream = asyncStub.hid(responseObserver)

            TextInputUtil.pressWithDuration(
                HIDEventKt.hIDPressAction {
                    this.button = HIDEventKt.hIDButton {
                        this.button = HIDButtonType.forNumber(code)
                    }
                }
            ).forEach { stream.onNext(it) }

            stream.onCompleted()
        }
    }

    private fun press(
        x: Int,
        y: Int,
        holdDelay: Long
    ): Result<Unit, Throwable> {
        return runCatching {
            val responseObserver = BlockingStreamObserver<Idb.HIDResponse>()
            val stream = asyncStub.hid(responseObserver)

            val pressAction = HIDEventKt.hIDPressAction {
                touch = HIDEventKt.hIDTouch {
                    point = point {
                        this.x = x.toDouble()
                        this.y = y.toDouble()
                    }
                }
            }

            stream.onNext(
                hIDEvent {
                    press = HIDEventKt.hIDPress {
                        action = pressAction
                        direction = Idb.HIDEvent.HIDDirection.DOWN
                    }
                }
            )
            Thread.sleep(holdDelay)
            stream.onNext(
                hIDEvent {
                    press = HIDEventKt.hIDPress {
                        action = pressAction
                        direction = Idb.HIDEvent.HIDDirection.UP
                    }
                }
            )
            stream.onCompleted()
        }
    }

    override fun scroll(xStart: Int, yStart: Int, xEnd: Int, yEnd: Int, durationMs: Long, scrollType: ScrollType): Result<Unit, Throwable> {
        val deviceInfo = deviceInfo().getOrThrow { throw IllegalStateException("Device info not available") }
        return runCatching {
            val responseObserver = BlockingStreamObserver<Idb.HIDResponse>()
            val stream = asyncStub.hid(responseObserver)

            val durationS = (durationMs.toDouble() / 1000)
            val swipeAction = HIDEventKt.hIDSwipe {
                this.start = point {
                    this.x = xStart.toDouble()
                    this.y = yStart.toDouble()
                }.toBuilder().build()
                this.end = point {
                    this.x = xEnd.toDouble()
                    this.y = yEnd.toDouble()
                }
                this.duration = if (durationS >= 1.0) DEFAULT_SWIPE_DURATION_MILLIS.toDouble() / 1000 else durationS
                this.delta = if (scrollType == ScrollType.SWIPE) SCROLL_FACTOR * deviceInfo.widthPixels else SCROLL_FACTOR * deviceInfo.heightPixels
            }

            stream.onNext(
                hIDEvent {
                    swipe = swipeAction
                }
            )
            stream.onCompleted()
        }
    }

    override fun input(text: String): Result<Unit, Throwable> {
        return runCatching {
            val responseObserver = BlockingStreamObserver<Idb.HIDResponse>()
            val stream = asyncStub.hid(responseObserver)

            TextInputUtil.textToListOfEvents(text)
                .forEach {
                    stream.onNext(it)
                    Thread.sleep(75)
                }
            stream.onCompleted()
        }
    }

    override fun install(stream: InputStream): Result<Unit, Throwable> {
        return runCatching {
            val responseObserver = BlockingStreamObserver<Idb.InstallResponse>()
            val requestStream = asyncStub.install(responseObserver)

            requestStream.onNext(
                installRequest {
                    destination = Idb.InstallRequest.Destination.APP
                    payload = payload {
                        compression = Idb.Payload.Compression.GZIP
                    }
                }
            )

            stream.buffered()
                .iterator()
                .asSequence()
                .chunked(CHUNK_SIZE)
                .forEach {
                    requestStream.onNext(
                        installRequest {
                            payload = payload {
                                data = ByteString.copyFrom(it.toByteArray())
                            }
                        }
                    )
                }
            requestStream.onCompleted()

            responseObserver.awaitResult()
        }
    }

    override fun uninstall(id: String): Result<Unit, Throwable> {
        return runCatching {
            try {
                blockingStub.uninstall(
                    uninstallRequest {
                        bundleId = id
                    }
                )
            } catch (e: StatusRuntimeException) {
                if (e.status.description != "$id is not installed") {
                    throw e
                }
            }
        }
    }

    override fun pullAppState(id: String, file: File): Result<Idb.PullResponse, Throwable> {
        return runCatching {
            val observer = BlockingStreamObserver<Idb.PullResponse>()
            asyncStub.pull(pullRequest {
                container = fileContainer {
                    kind = Idb.FileContainer.Kind.APPLICATION
                    bundleId = id
                }
                srcPath = "/"
                dstPath = file.absolutePath
            }, observer)
            observer.awaitResult()
        }
    }

    override fun pushAppState(id: String, file: File): Result<Unit, Throwable> {
        return runCatching {
            val observer = BlockingStreamObserver<Idb.PushResponse>()
            val stream = asyncStub.push(observer)

            stream.onNext(pushRequest {
                inner = inner {
                    container = fileContainer {
                        kind = Idb.FileContainer.Kind.APPLICATION
                        bundleId = id
                    }
                    dstPath = "/"
                }
            })

            if (file.isDirectory) {
                file.listFiles()?.map { it.absolutePath }?.forEach {
                    stream.onNext(pushRequest {
                        payload = payload {
                            filePath = it
                        }
                    })
                }
            } else {
                stream.onNext(pushRequest {
                    payload = payload {
                        filePath = file.absolutePath
                    }
                })
            }

            stream.onCompleted()
            observer.awaitResult()
        }
    }

    override fun clearAppState(id: String): Result<Idb.RmResponse, Throwable> {

        // Stop the app before clearing the file system
        // This prevents the app from saving its state after it has been cleared
        stop(id)
        
        // Wait for the app to be stopped, unfortunately idb's stop()
        // does not wait for the process to finish
        Thread.sleep(1500)

        // deletes app data, including container folder
        val result = runCatching {
            blockingStub.rm(rmRequest {
                container = fileContainer {
                    kind = Idb.FileContainer.Kind.APPLICATION
                    bundleId = id
                }
                paths.add("/")
            })
        }

        // forces app container folder to be re-created
        runCatching {
            blockingStub.mkdir(mkdirRequest {
                container = fileContainer {
                    kind = Idb.FileContainer.Kind.APPLICATION
                    bundleId = id
                }
                path = "tmp"
            })
        }
        return result
    }

    override fun clearKeychain(): Result<Unit, Throwable> {
        return runCatching {
            blockingStub.clearKeychain(clearKeychainRequest { })
        }
    }

    override fun launch(id: String): Result<Unit, Throwable> {
        return runCatching {
            val responseObserver = BlockingStreamObserver<Idb.LaunchResponse>()
            val stream = asyncStub.launch(responseObserver)
            stream.onNext(
                launchRequest {
                    start = idb.LaunchRequestKt.start {
                        bundleId = id
                    }
                }
            )

            stream.onCompleted()
            responseObserver.awaitResult()
        }
    }

    override fun stop(id: String): Result<Unit, Throwable> {
        return runCatching {
            val responseObserver = BlockingStreamObserver<Idb.TerminateResponse>()
            asyncStub.terminate(
                terminateRequest {
                    bundleId = id
                },
                responseObserver
            )

            responseObserver.awaitResult()
        }
    }

    override fun openLink(link: String): Result<Unit, Throwable> {
        return runCatching {
            blockingStub.openUrl(openUrlRequest {
                url = link
            })
        }
    }

    override fun takeScreenshot(out: Sink): Result<Unit, Throwable> {
        return runCatching {
            val response = blockingStub.screenshot(screenshotRequest {})

            out
                .buffer()
                .use {
                    it.write(response.imageData.toByteArray())
                }
        }
    }

    // Warning: This method reads all bytes into memory. This can probably be optimized if necessary.
    override fun startScreenRecording(out: Sink): Result<IOSScreenRecording, Throwable> {
        val future = CompletableFuture<Void>()
        val bufferedOut = out.buffer()
        val compressedData = Buffer()
        return runCatching {
            val request = asyncStub.record(object : StreamObserver<RecordResponse> {
                override fun onNext(value: RecordResponse) {
                    if (value.payload.compression == Idb.Payload.Compression.GZIP) {
                        compressedData.write(value.payload.data.toByteArray())
                    } else {
                        bufferedOut.write(value.payload.data.toByteArray())
                    }
                }

                override fun onError(t: Throwable) {
                    try {
                        finish()
                    } finally {
                        future.completeExceptionally(t)
                    }
                }

                override fun onCompleted() {
                    try {
                        finish()
                    } finally {
                        future.complete(null)
                    }
                }

                private fun finish() {
                    if (compressedData.size > 0) {
                        val gzippedInputStream = GZIPInputStream(compressedData.inputStream())
                        gzippedInputStream.source().buffer().use { source ->
                            source.readAll(out)
                        }
                    } else {
                        bufferedOut.flush()
                    }
                }
            })
            request.onNext(recordRequest {
                start = Idb.RecordRequest.Start.newBuilder().build()
            })

            object : IOSScreenRecording {

                override fun close() {
                    request.onNext(recordRequest {
                        stop = Idb.RecordRequest.Stop.newBuilder().build()
                    })
                    future.get()
                }
            }
        }
    }

    override fun setLocation(latitude: Double, longitude: Double): Result<Unit, Throwable> {
        return runCatching {
            blockingStub.setLocation(setLocationRequest {
                location = location { this.latitude = latitude; this.longitude = longitude }
            })
        }
    }

    override fun close() {
        channel.shutdown()

        if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
            throw TimeoutException("Couldn't close Maestro iOS driver due to gRPC timeout")
        }
    }

    companion object {
        // 4Mb, the default max read for gRPC
        private const val CHUNK_SIZE = 1024 * 1024 * 3
        private val GSON = Gson()
        private const val SCROLL_FACTOR = 0.07
        const val DEFAULT_SWIPE_DURATION_MILLIS = 1000L
    }

    enum class ScrollType {
        SCROLL,
        SWIPE
    }
}
