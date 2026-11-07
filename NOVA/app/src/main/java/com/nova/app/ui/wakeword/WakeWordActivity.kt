package com.nova.app.ui.wakeword

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nova.app.R
import com.nova.app.engine.wake.NovaWakeWordEngine

class WakeWordActivity : AppCompatActivity() {

    private lateinit var wakeEngine:     NovaWakeWordEngine
    private var recordCount  = 0
    private val MAX_SAMPLES  = NovaWakeWordEngine.MIN_SAMPLES   // 3

    private lateinit var etWakeWord:     EditText
    private lateinit var tvRecordStatus: TextView
    private lateinit var progressRecord: ProgressBar
    private lateinit var btnRecord:      Button
    private lateinit var btnTrain:       Button
    private lateinit var tvTrainStatus:  TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wake_word)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Set Wake Word"

        etWakeWord     = findViewById(R.id.etWakeWord)
        tvRecordStatus = findViewById(R.id.tvRecordStatus)
        progressRecord = findViewById(R.id.progressRecord)
        btnRecord      = findViewById(R.id.btnRecord)
        btnTrain       = findViewById(R.id.btnTrain)
        tvTrainStatus  = findViewById(R.id.tvTrainStatus)

        wakeEngine = NovaWakeWordEngine(this) {}
        wakeEngine.init()

        btnRecord.setOnClickListener { recordSample() }
        btnTrain.setOnClickListener  { trainModel()   }
    }

    private fun recordSample() {
        val word = etWakeWord.text.toString().trim()
        if (word.isEmpty()) {
            Toast.makeText(this, "Type your wake word first", Toast.LENGTH_SHORT).show()
            return
        }
        btnRecord.isEnabled = false
        btnRecord.text      = "Listening..."

        wakeEngine.recordTrainingSample(true, recordCount, 1500) { success ->
            runOnUiThread {
                if (success) {
                    recordCount++
                    tvRecordStatus.text  = "$recordCount / $MAX_SAMPLES"
                    progressRecord.progress = recordCount

                    if (recordCount >= MAX_SAMPLES) {
                        btnRecord.isEnabled  = false
                        btnRecord.text       = "Done ✓"
                        btnTrain.isEnabled   = true
                        tvTrainStatus.text   = "All recordings done. Tap Train NOVA."
                    } else {
                        btnRecord.isEnabled  = true
                        btnRecord.text       = "🎙 Record ${recordCount + 1} of $MAX_SAMPLES"
                    }
                } else {
                    btnRecord.isEnabled = true
                    btnRecord.text      = "🎙 Record"
                    Toast.makeText(this, "Recording failed — check microphone permission", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun trainModel() {
        btnTrain.isEnabled  = false
        btnTrain.text       = "Training..."
        tvTrainStatus.text  = "Generating training samples from your voice..."

        wakeEngine.trainOnDevice(60, 0.001f, { epoch, loss ->
            runOnUiThread {
                tvTrainStatus.text = "Training: $epoch / 60  (loss ${"%.3f".format(loss)})"
            }
        }, { success ->
            runOnUiThread {
                if (success) {
                    tvTrainStatus.text = "✓ Done! Go back and say \"${etWakeWord.text}\" to activate NOVA."
                    btnTrain.text = "Trained ✓"
                    Toast.makeText(this, "Wake word set! Say it to activate NOVA.", Toast.LENGTH_LONG).show()
                } else {
                    tvTrainStatus.text  = "Training failed. Try recording again."
                    btnTrain.isEnabled  = true
                    btnTrain.text       = "Train NOVA"
                }
            }
        })
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}