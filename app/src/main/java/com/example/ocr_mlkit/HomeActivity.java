package com.example.ocr_mlkit;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.ocr_mlkit.databinding.ActivityHomeBinding;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

public class HomeActivity extends AppCompatActivity {

    private ActivityHomeBinding binding;
    private Translator translator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityHomeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        configurarBotones();
        verificarYPrepararModelo();
    }

    private void configurarBotones() {
        binding.btnIrLive.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra(MainActivity.EXTRA_MODO, MainActivity.MODO_LIVE);
            startActivity(intent);
        });

        binding.btnIrGaleria.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra(MainActivity.EXTRA_MODO, MainActivity.MODO_GALERIA);
            startActivity(intent);
        });

        binding.btnAjustes.setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        });
    }

    private void verificarYPrepararModelo() {
        binding.layoutDescarga.setVisibility(View.VISIBLE);
        binding.layoutBotones.setVisibility(View.GONE);

        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.SPANISH)
                .build();
        translator = Translation.getClient(options);

        DownloadConditions conditions = new DownloadConditions.Builder()
                .requireWifi()
                .build();

        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> {
                    binding.layoutDescarga.setVisibility(View.GONE);
                    binding.layoutBotones.setVisibility(View.VISIBLE);
                })
                .addOnFailureListener(e -> {
                    binding.txtEstadoModelo.setText("Error al descargar modelo");
                    binding.progressDescarga.setIndeterminate(false);
                    binding.progressDescarga.setProgress(0);
                    Toast.makeText(this, "Asegúrate de tener conexión a internet", Toast.LENGTH_LONG).show();
                });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (translator != null) {
            translator.close();
        }
    }
}
