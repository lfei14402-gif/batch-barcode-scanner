package com.example.batchbarcodescanner

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val barcodes = mutableListOf<BarcodeItem>()
    private lateinit var adapter: BarcodeAdapter
    private lateinit var statusText: TextView
    private lateinit var emptyView: TextView

    private var pendingPhotoFile: File? = null
    private var pendingPhotoUri: Uri? = null

    private val scanner: BarcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_PDF417,
                    Barcode.FORMAT_AZTEC
                )
                .build()
        )
    }

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = pendingPhotoUri
            val file = pendingPhotoFile
            pendingPhotoUri = null
            pendingPhotoFile = null

            if (result.resultCode == Activity.RESULT_OK && uri != null) {
                processImages(listOf(uri)) {
                    file?.delete()
                }
            } else {
                statusText.text = "已取消拍照"
                file?.delete()
            }
        }

    private val pickImagesLauncher =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
            if (uris.isNotEmpty()) {
                processImages(uris) { }
            } else {
                statusText.text = "未选择图片"
            }
        }

    private val createCsvLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) writeCsv(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        emptyView = findViewById(R.id.emptyView)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        adapter = BarcodeAdapter(barcodes) { position ->
            showDeleteDialog(position)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<Button>(R.id.takePhotoButton).setOnClickListener {
            openCamera()
        }

        findViewById<Button>(R.id.pickImagesButton).setOnClickListener {
            pickImagesLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        findViewById<Button>(R.id.exportButton).setOnClickListener {
            if (barcodes.isEmpty()) {
                Toast.makeText(this, "没有可导出的数据", Toast.LENGTH_SHORT).show()
            } else {
                createCsvLauncher.launch("barcodes_${fileTimestamp()}.csv")
            }
        }

        findViewById<Button>(R.id.clearButton).setOnClickListener {
            if (barcodes.isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("清空全部记录？")
                .setMessage("将删除 ${barcodes.size} 条记录，不可恢复。")
                .setPositiveButton("清空") { _, _ ->
                    barcodes.clear()
                    adapter.notifyDataSetChanged()
                    updateEmptyState()
                    statusText.text = "已清空"
                }
                .setNegativeButton("取消", null)
                .show()
        }

        updateEmptyState()
    }

    override fun onDestroy() {
        scanner.close()
        super.onDestroy()
    }

    private fun openCamera() {
        try {
            val dir = File(cacheDir, "images").apply { mkdirs() }
            val file = File.createTempFile("barcode_", ".jpg", dir)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

            pendingPhotoFile = file
            pendingPhotoUri = uri

            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            takePictureLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "没有找到可用相机应用", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "打开相机失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showDeleteDialog(position: Int) {
        if (position < 0 || position >= barcodes.size) return
        val item = barcodes[position]
        AlertDialog.Builder(this)
            .setTitle("删除这条记录？")
            .setMessage(item.content)
            .setPositiveButton("删除") { _, _ ->
                barcodes.removeAt(position)
                adapter.notifyItemRemoved(position)
                adapter.notifyItemRangeChanged(position, barcodes.size - position)
                updateEmptyState()
                statusText.text = "已删除 1 条，剩余 ${barcodes.size} 条"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun processImages(uris: List<Uri>, onAllDone: () -> Unit) {
        if (uris.isEmpty()) {
            onAllDone()
            return
        }
        val queue = uris.toMutableList()
        fun processNext() {
            if (queue.isEmpty()) {
                onAllDone()
                return
            }
            val uri = queue.removeAt(0)
            processSingleImage(uri) { processNext() }
        }
        processNext()
    }

    private fun processSingleImage(uri: Uri, onDone: () -> Unit) {
        statusText.text = "正在识别图片中的条形码…"
        Thread {
            try {
                val bitmap = decodeSampledBitmap(uri, 1600)
                if (bitmap == null) {
                    runOnUiThread { statusText.text = "读取图片失败" }
                    onDone()
                    return@Thread
                }

                val rotated = rotateBitmapByExif(bitmap, uri)
                val image = InputImage.fromBitmap(rotated, 0)

                scanner.process(image)
                    .addOnSuccessListener { detected ->
                        handleDetected(detected)
                    }
                    .addOnFailureListener { e ->
                        statusText.text = "识别失败：${e.message}"
                    }
                    .addOnCompleteListener {
                        if (rotated !== bitmap) rotated.recycle()
                        bitmap.recycle()
                        onDone()
                    }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "读取图片失败：${e.message}" }
                onDone()
            }
        }.start()
    }

    private fun handleDetected(detected: List<Barcode>) {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        var total = 0
        var added = 0
        var duplicate = 0

        detected.forEach { barcode ->
            val value = barcode.rawValue ?: return@forEach
            total++
            if (barcodes.any { it.content == value }) {
                duplicate++
            } else {
                barcodes.add(BarcodeItem(value, formatName(barcode.format), now))
                added++
            }
        }

        if (total == 0) {
            statusText.text = "未识别到条形码，请靠近、对焦后重拍"
            return
        }

        adapter.notifyDataSetChanged()
        updateEmptyState()
        val msg = buildString {
            append("本次识别 $total 个")
            if (added > 0) append("，新增 $added 个")
            if (duplicate > 0) append("，跳过重复 $duplicate 个")
            append("，累计 ${barcodes.size} 条")
        }
        statusText.text = msg
    }

    private fun decodeSampledBitmap(uri: Uri, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension ||
            bounds.outHeight / sampleSize > maxDimension
        ) {
            sampleSize *= 2
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    private fun rotateBitmapByExif(bitmap: Bitmap, uri: Uri): Bitmap {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return bitmap
            val exif = ExifInterface(input)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            input.close()

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                else -> return bitmap
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            bitmap
        }
    }

    private fun updateEmptyState() {
        emptyView.visibility = if (barcodes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun writeCsv(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)
                ?.bufferedWriter(Charsets.UTF_8)
                ?.use { writer ->
                    writer.write("\uFEFF")
                    writer.write("序号,条码内容,条码格式,识别时间\n")

                    barcodes.forEachIndexed { index, item ->
                        writer.write(
                            "${index + 1}," +
                                    "${csvEscape(item.content)}," +
                                    "${csvEscape(item.format)}," +
                                    "${csvEscape(item.time)}\n"
                        )
                    }
                }

            Toast.makeText(this, "已导出，可用 Excel/WPS 打开", Toast.LENGTH_LONG).show()
            statusText.text = "已导出 ${barcodes.size} 条记录"
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun csvEscape(value: String): String {
        val needsQuote = value.contains(',') || value.contains('"') ||
                value.contains('\n') || value.contains('\r')
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuote) "\"$escaped\"" else escaped
    }

    private fun fileTimestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    private fun formatName(format: Int): String = when (format) {
        Barcode.FORMAT_CODE_128 -> "CODE_128"
        Barcode.FORMAT_CODE_39 -> "CODE_39"
        Barcode.FORMAT_CODE_93 -> "CODE_93"
        Barcode.FORMAT_CODABAR -> "CODABAR"
        Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
        Barcode.FORMAT_EAN_13 -> "EAN_13"
        Barcode.FORMAT_EAN_8 -> "EAN_8"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_QR_CODE -> "QR_CODE"
        Barcode.FORMAT_UPC_A -> "UPC_A"
        Barcode.FORMAT_UPC_E -> "UPC_E"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_AZTEC -> "AZTEC"
        else -> "UNKNOWN"
    }
}

data class BarcodeItem(
    val content: String,
    val format: String,
    val time: String
)

class BarcodeAdapter(
    private val items: MutableList<BarcodeItem>,
    private val onLongClick: (Int) -> Unit
) : RecyclerView.Adapter<BarcodeAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val indexText: TextView = view.findViewById(R.id.indexText)
        val contentText: TextView = view.findViewById(R.id.contentText)
        val formatText: TextView = view.findViewById(R.id.formatText)
        val timeText: TextView = view.findViewById(R.id.timeText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_barcode, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.indexText.text = (position + 1).toString()
        holder.contentText.text = item.content
        holder.formatText.text = item.format
        holder.timeText.text = item.time

        holder.itemView.setOnLongClickListener {
            onLongClick(position)
            true
        }
    }

    override fun getItemCount(): Int = items.size
}
