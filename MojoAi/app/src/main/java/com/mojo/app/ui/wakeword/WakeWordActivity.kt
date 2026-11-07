package com.mojo.app.ui.wakeword

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mojo.app.R
import com.mojo.app.core.MojoStatus
import com.mojo.app.engine.MojoEngine
import com.mojo.app.engine.wake.MojoWakeWordEngine

class WakeWordActivity : AppCompatActivity() {

    private lateinit var wakeEngine:     MojoWakeWordEngine
    private var recordCount  = 0
    private val MAX_SAMPLES  = MojoWakeWordEngine.MIN_SAMPLES   // 3
    private val TRAIN_EPOCHS = 30
    private var trained      = false

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

        wakeEngine = MojoWakeWordEngine(this) {}
        // The background engine holds the mic while it listens — free it while we record.
        MojoEngine.pauseWake(this)

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
        btnRecord.text      = "Listening... say \"$word\""
        if (recordCount == 0) wakeEngine.clearSamples()   // start a fresh set

        wakeEngine.recordTrainingSample(true, recordCount, MojoWakeWordEngine.RECORD_MS) { success ->
            runOnUiThread {
                if (success) {
                    recordCount++
                    tvRecordStatus.text  = "$recordCount / $MAX_SAMPLES"
                    progressRecord.progress = recordCount

                    if (recordCount >= MAX_SAMPLES) {
                        btnRecord.isEnabled  = false
                        btnRecord.text       = "Done ✓"
                        btnTrain.isEnabled   = true
                        tvTrainStatus.text   = "All recordings done. Tap Train MOJO."
                    } else {
                        btnRecord.isEnabled  = true
                        btnRecord.text       = "🎙 Record ${recordCount + 1} of $MAX_SAMPLES"
                    }
                } else {
                    btnRecord.isEnabled = true
                    btnRecord.text      = "🎙 Record"
                    Toast.makeText(this, "I didn't hear anything. Speak a little louder and try again.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun trainModel() {
        btnTrain.isEnabled  = false
        btnTrain.text       = "Training..."
        tvTrainStatus.text  = "Generating training samples from your voice..."

        wakeEngine.trainOnDevice(TRAIN_EPOCHS, 0.001f, { epoch, loss ->
            runOnUiThread {
                tvTrainStatus.text = "Training: $epoch / $TRAIN_EPOCHS  (loss ${"%.3f".format(loss)})"
            }
        }, { success ->
            runOnUiThread {
                if (success) {
                    tvTrainStatus.text = "✓ Done! Go back and say \"${etWakeWord.text}\" to activate MOJO."
                    btnTrain.text = "Trained ✓"
                    trained = true
                    // Start the engine (or make a running one reload the new model)
                    MojoEngine.start(this)
                    Toast.makeText(this, "Wake word set! Say it to activate MOJO.", Toast.LENGTH_LONG).show()
                } else {
                    tvTrainStatus.text  = "Training failed. Try recording again."
                    btnTrain.isEnabled  = true
                    btnTrain.text       = "Train MOJO"
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        // Give the mic back to the background engine if we paused it
        if (!trained && MojoStatus.running.value) MojoEngine.start(this)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}