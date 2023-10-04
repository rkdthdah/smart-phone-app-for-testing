package com.example.myapplication

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.video.VideoRecordEvent.Finalize
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.myapplication.databinding.ActivityMainBinding
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

typealias LumaListener = (luma: Double) -> Unit

@ExperimentalCamera2Interop class MainActivity : ComponentActivity(), CoroutineScope by MainScope(),  MessageClient.OnMessageReceivedListener {

    private var activityContext: Context? = null
    private lateinit var viewBinding: ActivityMainBinding

    private var json: JSONObject? = null


    private val wearableAppCheckPayload = "AppOpenWearable"
    private val wearableAppCheckPayloadReturnACK = "AppOpenWearableACK"
    private var wearableDeviceConnected: Boolean = false

    private var currentAckFromWearForAppOpenCheck: String? = null
    private val APP_OPEN_WEARABLE_PAYLOAD_PATH = "/APP_OPEN_WEARABLE_PAYLOAD"
    private val MESSAGE_ITEM_RECEIVED_PATH: String = "/message-item-received"

    private val TAG_GET_NODES: String = "getnodes1"
    private val TAG_MESSAGE_RECEIVED: String = "receive1"

    private var messageEvent: MessageEvent? = null
    private var wearableNodeUri: String? = null


    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var name: String

    private lateinit var cameraExecutor: ExecutorService

    private lateinit var viewFinder: View

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var checkButton: Button
    private lateinit var sign: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        activityContext = this
        wearableDeviceConnected = false

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        viewFinder = findViewById(R.id.view_finder)

        checkButton = findViewById(R.id.check)
        checkButton.isEnabled = true
        startButton = findViewById(R.id.start)
        startButton.isEnabled = false
        stopButton = findViewById(R.id.stop)
        stopButton.isEnabled = false

        sign = findViewById(R.id.sign)

        checkButton.setOnClickListener {
            if (!wearableDeviceConnected) {
                val tempAct: Activity = activityContext as MainActivity
                //Couroutine
                initialiseDevicePairing(tempAct)
            } else {
                Toast.makeText(activityContext, "이미 연결되어 있습니다", Toast.LENGTH_SHORT).show()
                startButton.isEnabled = true
            }
        }

        startButton.setOnClickListener {

            startButton.isEnabled = false
            stopButton.isEnabled = true

            startRecording()

            captureVideo()
        }

        stopButton.setOnClickListener {
            startButton.isEnabled = true
            stopButton.isEnabled = false

            stopRecording()

            val curRecording = recording
            if (curRecording != null) {
                curRecording.stop()
                recording = null
            }
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return
        val curRecording = recording
        if (curRecording != null) {
            curRecording.stop()
            recording = null
            return
        }

        name = "Video_data_" + LocalDateTime.now().toString() + ".mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Wanted-Video")
            }
        }
        val mediaStoreOutput = MediaStoreOutputOptions
            .Builder(this.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutput)
            .start(ContextCompat.getMainExecutor(this)) { captureListener ->
                when(captureListener) {
                    is VideoRecordEvent.Start -> {
                        sign.visibility = View.VISIBLE
                    }
                    is VideoRecordEvent.Pause -> {

                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!captureListener.hasError()) {
                            sign.visibility = View.GONE

                            Log.d("video1", "Save successfully")
                            Toast.makeText(activityContext, "저장 완료", Toast.LENGTH_SHORT).show()
                        }
                        else {
                            Log.d("video1", "recording Error")
                            recording?.close()
                            recording = null
                        }
                    }
                }
                val recordingStats = captureListener.recordingStats
            }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            // video setting
            val recorder = Recorder.Builder()
                .setExecutor(cameraExecutor)
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture)

            } catch(exc: Exception) {
                Log.e("Camera1", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    override fun onPause() {
        super.onPause()
        try {
            Wearable.getMessageClient(activityContext!!).removeListener(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            Wearable.getMessageClient(activityContext!!).addListener(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        cameraExecutor.shutdown()
    }

    private fun startRecording() {
        sendMessageToWatch("start")
    }

    private fun stopRecording() {
        sendMessageToWatch("stop")
    }

    private fun initialiseDevicePairing(tempAct: Activity) {
        //Coroutine
        launch(Dispatchers.Default) {
            var getNodesResBool: BooleanArray? = null

            try {
                getNodesResBool =
                    getNodes(tempAct.applicationContext)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            //UI Thread
            withContext(Dispatchers.Main) {
                if (getNodesResBool!![0]) {
                    //if message Acknowlegement Received
                    if (getNodesResBool[1]) {
                        Toast.makeText(activityContext, "기기 준비 완료!", Toast.LENGTH_LONG).show()
                        wearableDeviceConnected = true
                        startButton.isEnabled = true
                    } else {
                        Toast.makeText(activityContext, "연결되었지만, 준비되지 않았습니다.", Toast.LENGTH_LONG).show()

                        wearableDeviceConnected = false
                        startButton.isEnabled = false
                        stopButton.isEnabled = false
                    }
                } else {
                    Toast.makeText(activityContext, "연결된 기기가 없습니다.", Toast.LENGTH_LONG).show()

                    wearableDeviceConnected = false
                    startButton.isEnabled = false
                    stopButton.isEnabled = false
                }
            }
        }
    }

    private fun getNodes(context: Context): BooleanArray {
        val nodeResults = HashSet<String>()
        val resBool = BooleanArray(2)
        resBool[0] = false //nodePresent
        resBool[1] = false //wearableReturnAckReceived
        val nodeListTask =
            Wearable.getNodeClient(context).connectedNodes
        try {
            // Block on a task and get the result synchronously (because this is on a background thread).
            val nodes =
                Tasks.await(
                    nodeListTask
                )
            Log.e(TAG_GET_NODES, "Task fetched nodes")
            for (node in nodes) {
                Log.e(TAG_GET_NODES, "inside loop")
                nodeResults.add(node.id)
                try {
                    val nodeId = node.id
                    // Set the data of the message to be the bytes of the Uri.
                    val payload: ByteArray = wearableAppCheckPayload.toByteArray()
                    // Send the rpc
                    // Instantiates clients without member variables, as clients are inexpensive to
                    // create. (They are cached and shared between GoogleApi instances.)
                    val sendMessageTask =
                        Wearable.getMessageClient(context)
                            .sendMessage(nodeId, APP_OPEN_WEARABLE_PAYLOAD_PATH, payload)
                    try {
                        // Block on a task and get the result synchronously (because this is on a background thread).
                        val result = Tasks.await(sendMessageTask)
                        Log.d(TAG_GET_NODES, "send message result : $result")
                        resBool[0] = true

                        //Wait for 700 ms/0.7 sec for the acknowledgement message
                        //Wait 1
                        if (currentAckFromWearForAppOpenCheck != wearableAppCheckPayloadReturnACK) {
                            Thread.sleep(100)
                            Log.d(TAG_GET_NODES, "ACK thread sleep 1")
                        }
                        if (currentAckFromWearForAppOpenCheck == wearableAppCheckPayloadReturnACK) {
                            resBool[1] = true
                            return resBool
                        }
                        //Wait 2
                        if (currentAckFromWearForAppOpenCheck != wearableAppCheckPayloadReturnACK) {
                            Thread.sleep(250)
                            Log.d(TAG_GET_NODES, "ACK thread sleep 2")
                        }
                        if (currentAckFromWearForAppOpenCheck == wearableAppCheckPayloadReturnACK) {
                            resBool[1] = true
                            return resBool
                        }
                        //Wait 3
                        if (currentAckFromWearForAppOpenCheck != wearableAppCheckPayloadReturnACK) {
                            Thread.sleep(350)
                            Log.d(TAG_GET_NODES, "ACK thread sleep 5")
                        }
                        if (currentAckFromWearForAppOpenCheck == wearableAppCheckPayloadReturnACK) {
                            resBool[1] = true
                            return resBool
                        }
                        resBool[1] = false
                        Log.d(
                            TAG_GET_NODES,
                            "ACK thread timeout, no message received from the wearable "
                        )
                    } catch (exception: Exception) {
                        exception.printStackTrace()
                    }
                } catch (e1: Exception) {
                    Log.d(TAG_GET_NODES, "Send message exception")
                    e1.printStackTrace()
                }
            } //end of for loop
        } catch (exception: Exception) {
            Log.e(TAG_GET_NODES, "Task failed: $exception")
            exception.printStackTrace()
        }
        return resBool
    }

    override fun onMessageReceived(p0: MessageEvent) {

        try {
            val s = String(p0.data)
            val path: String = p0.path

            Log.d(TAG_MESSAGE_RECEIVED, "Message received: $path")

            if (path == APP_OPEN_WEARABLE_PAYLOAD_PATH) {
                currentAckFromWearForAppOpenCheck = s
                Log.d(TAG_MESSAGE_RECEIVED, "Received ACK")

                messageEvent = p0
                wearableNodeUri = p0.sourceNodeId
            }
            else if (path == MESSAGE_ITEM_RECEIVED_PATH) {

                try {
                    json = JSONObject(s)

                    Log.d("res", json.toString())

                    try {
                        val fileName = "Sensor_data_" + LocalDateTime.now().toString() + ".json"

                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                        intent.addCategory(Intent.CATEGORY_OPENABLE)
                        intent.type = "application/json"
                        intent.putExtra(Intent.EXTRA_TITLE, fileName)

                        startActivityForResult(intent, 43)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }

                } catch (e: JSONException) {
                    Log.d("json", "jsonException")
                    e.printStackTrace()
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Log.d("receive1", "handled")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 43 && resultCode == Activity.RESULT_OK) {
            val uri: Uri = data!!.data!!

            try {
                val pfd = this.contentResolver.openFileDescriptor(uri, "w")
                val fileOutputStream = FileOutputStream(pfd!!.fileDescriptor)
                fileOutputStream.write(json.toString().toByteArray())

                Toast.makeText(applicationContext, "저장되었습니다.", Toast.LENGTH_LONG).show();
                fileOutputStream.close();
                pfd.close();

            } catch (e: FileNotFoundException) {
                e.printStackTrace()
            }
        }
    }

    private fun sendMessageToWatch(request: String) {

        if (wearableDeviceConnected) {
            val nodeId: String = messageEvent?.sourceNodeId!!
            // Set the data of the message to be the bytes of the Uri.
            val payload: ByteArray = request.toByteArray()

            // Send the rpc
            // Instantiates clients without member variables, as clients are inexpensive to
            // create. (They are cached and shared between GoogleApi instances.)
            val sendMessageTask =
                Wearable.getMessageClient(activityContext!!)
                    .sendMessage(nodeId, MESSAGE_ITEM_RECEIVED_PATH, payload)

            sendMessageTask.addOnCompleteListener {
                if (it.isSuccessful) {
                    Log.d("send1", "Message sent successfully")
                    Toast.makeText(activityContext, "$request 전송 성공", Toast.LENGTH_LONG).show()
                } else {
                    Log.d("send1", "Message failed.")
                    Toast.makeText(activityContext, "$request 전송 실패", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}