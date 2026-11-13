package com.example.batchbarcodescanner

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = pendingPhotoUri
            val file = pendingPhotoFile
            pendingPhotoUri = null
            pendingPhotoFile = null

            if (result.resultCode == Activity.RESULT_OK && uri != null) {
                processImage(uri, file)
            } else {
                statusText.text = "已取消拍照"
                file?.delete()
            }
        }

    private val createCsvLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) {
                writeCsv(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        emptyView = findViewById(R.id.emptyView)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        adapter = BarcodeAdapter(barcodes)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<Button>(R.id.takePhotoButton).setOnClickListener {
            openCamera()
        }

        findViewById<Button>(R.id.exportButton).setOnClickListener {
            if (barcodes.isEmpty()) {
                Toast.makeText(this, "没有可导出的数据", Toast.LENGTH_SHORT).show()
            } else {
                createCsvLauncher.launch("barcodes_${fileTimestamp()}.csv")
            }
        }

        findViewById<Button>(R.id.clearButton).setOnClickListener {
            barcodes.clear()
            adapter.notifyDataSetChanged()
            updateEmptyState()
            statusText.text = "已清空"
        }

        updateEmptyState()
    }

    private fun openCamera() {
        try {
            val dir = File(cacheDir, "images").apply { mkdirs() }
            val file = File.createTempFile("barcode_", ".jpg", dir)
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )

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

    private fun processImage(uri: Uri, sourceFile: File?) {
        statusText.text = "正在识别图片中的条形码…"

        val scanner = BarcodeScanning.getClient()

        try {
            val image = InputImage.fromFilePath(this, uri)

            scanner.process(image)
                .addOnSuccessListener { detected ->
                    val now = SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        Locale.getDefault()
                    ).format(Date())

                    val newItems = detected.mapNotNull { barcode ->
                        val value = barcode.rawValue ?: return@mapNotNull null
                        BarcodeItem(
                            content = value,
                            format = formatName(barcode.format),
                            time = now
                        )
                    }

                    if (newItems.isEmpty()) {
                        statusText.text = "未识别到条形码，请靠近、对焦后重拍"
                    } else {
                        barcodes.addAll(newItems)
                        adapter.notifyDataSetChanged()
                        updateEmptyState()
                        statusText.text = "本次识别到 ${newItems.size} 个，共 ${barcodes.size} 条"
                    }
                }
                .addOnFailureListener { e ->
                    statusText.text = "识别失败：${e.message}"
                }
                .addOnCompleteListener {
                    scanner.close()
                    sourceFile?.delete()
                }
        } catch (e: Exception) {
            statusText.text = "读取图片失败：${e.message}"
            sourceFile?.delete()
            scanner.close()
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
        val needsQuote = value.contains(',') ||
                value.contains('"') ||
                value.contains('\n') ||
                value.contains('\r')

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
    private val items: List<BarcodeItem>
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
    }

    override fun getItemCount(): Int = items.size
}
