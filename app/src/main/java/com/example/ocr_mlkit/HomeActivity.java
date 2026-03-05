package com.example.ocr_mlkit;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

public class HomeActivity extends AppCompatActivity {

    private View layoutDescarga;
    private View layoutBotones;
    private ProgressBar progressDescarga;
    private TextView txtEstadoModelo;
    private TextView txtAyudaDescarga;
    private ImageView iconEstado;
    private CardView btnIrLive;
    private CardView btnIrGaleria;

    // Estados posibles del modelo
    private enum EstadoModelo { VERIFICANDO, DESCARGANDO, LISTO, ERROR }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        bindViews();
        configurarBotones();

        // Al abrir la app, verificar estado del modelo
        verificarModelo();
    }

    private void bindViews() {
        layoutDescarga   = findViewById(R.id.layoutDescarga);
        layoutBotones    = findViewById(R.id.layoutBotones);
        progressDescarga = findViewById(R.id.progressDescarga);
        txtEstadoModelo  = findViewById(R.id.txtEstadoModelo);
        txtAyudaDescarga = findViewById(R.id.txtAyudaDescarga);
        iconEstado       = findViewById(R.id.iconEstado);
        btnIrLive        = findViewById(R.id.btnIrLive);
        btnIrGaleria     = findViewById(R.id.btnIrGaleria);
    }

    private void configurarBotones() {
        btnIrLive.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra(MainActivity.EXTRA_MODO, MainActivity.MODO_LIVE);
            startActivity(intent);
        });

        btnIrGaleria.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra(MainActivity.EXTRA_MODO, MainActivity.MODO_GALERIA);
            startActivity(intent);
        });
    }

    // ── Lógica de verificación y descarga ────────────────────────────────────

    private void verificarModelo() {
        actualizarEstado(EstadoModelo.VERIFICANDO);

        RemoteModelManager manager = RemoteModelManager.getInstance();
        TranslateRemoteModel modeloES =
                new TranslateRemoteModel.Builder(TranslateLanguage.SPANISH).build();

        manager.isModelDownloaded(modeloES)
                .addOnSuccessListener(descargado -> {
                    if (descargado) {
                        // Modelo ya listo → mostrar botones directamente
                        actualizarEstado(EstadoModelo.LISTO);
                    } else {
                        // Falta el modelo → descargar
                        descargarModelo();
                    }
                })
                .addOnFailureListener(e -> {
                    // No se pudo verificar → intentar descargar de todas formas
                    descargarModelo();
                });
    }

    private void descargarModelo() {
        actualizarEstado(EstadoModelo.DESCARGANDO);

        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.SPANISH)
                .build();
        Translator translator = Translation.getClient(options);

        DownloadConditions conditions = new DownloadConditions.Builder().build();

        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> {
                    translator.close();
                    actualizarEstado(EstadoModelo.LISTO);
                })
                .addOnFailureListener(e -> {
                    translator.close();
                    actualizarEstado(EstadoModelo.ERROR);
                });
    }

    // ── UI según estado ───────────────────────────────────────────────────────

    private void actualizarEstado(EstadoModelo estado) {
        runOnUiThread(() -> {
            switch (estado) {

                case VERIFICANDO:
                    // Mostrar bloque de descarga, deshabilitar botones
                    layoutDescarga.setVisibility(View.VISIBLE);
                    progressDescarga.setVisibility(View.VISIBLE);
                    iconEstado.setImageResource(android.R.drawable.stat_sys_download);
                    iconEstado.setColorFilter(
                            getResources().getColor(android.R.color.holo_blue_light));
                    txtEstadoModelo.setText("Verificando modelo de traducción...");
                    txtAyudaDescarga.setText("Un momento...");
                    deshabilitarBotones();
                    break;

                case DESCARGANDO:
                    layoutDescarga.setVisibility(View.VISIBLE);
                    progressDescarga.setVisibility(View.VISIBLE);
                    iconEstado.setImageResource(android.R.drawable.stat_sys_download_done);
                    iconEstado.setColorFilter(
                            getResources().getColor(android.R.color.holo_blue_light));
                    txtEstadoModelo.setText("Descargando modelo de traducción...");
                    txtAyudaDescarga.setText("Esto solo ocurre la primera vez");
                    deshabilitarBotones();
                    break;

                case LISTO:
                    // Animar: ocultar bloque de descarga → mostrar botones habilitados
                    mostrarModeloListo();
                    break;

                case ERROR:
                    layoutDescarga.setVisibility(View.VISIBLE);
                    progressDescarga.setVisibility(View.GONE);
                    iconEstado.setImageResource(android.R.drawable.ic_dialog_alert);
                    iconEstado.setColorFilter(
                            getResources().getColor(android.R.color.holo_orange_light));
                    txtEstadoModelo.setText("No se pudo descargar el modelo");
                    txtAyudaDescarga.setText(
                            "Verifica tu conexión. La traducción no estará disponible.");
                    // Habilitar botones igual, el usuario puede usar OCR sin traducción
                    habilitarBotones();
                    break;
            }
        });
    }

    private void mostrarModeloListo() {
        // Cambiar ícono a check verde brevemente antes de ocultar
        progressDescarga.setVisibility(View.GONE);
        iconEstado.setImageResource(android.R.drawable.checkbox_on_background);
        iconEstado.setColorFilter(
                getResources().getColor(android.R.color.holo_green_light));
        txtEstadoModelo.setText("¡Modelo listo!");
        txtAyudaDescarga.setText("");

        // Esperar 800ms mostrando el check, luego animar transición
        layoutDescarga.postDelayed(() -> {
            // Fade out del bloque de descarga
            layoutDescarga.animate()
                    .alpha(0f)
                    .setDuration(400)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            layoutDescarga.setVisibility(View.GONE);
                            layoutDescarga.setAlpha(1f);

                            // Fade in de los botones
                            layoutBotones.setAlpha(0f);
                            layoutBotones.setVisibility(View.VISIBLE);
                            layoutBotones.animate()
                                    .alpha(1f)
                                    .setDuration(400)
                                    .setListener(null)
                                    .start();

                            habilitarBotones();
                        }
                    })
                    .start();
        }, 800);
    }

    private void deshabilitarBotones() {
        // Si ya están visibles (segunda apertura), solo bloquear clics
        btnIrLive.setClickable(false);
        btnIrGaleria.setClickable(false);
        btnIrLive.setAlpha(0.4f);
        btnIrGaleria.setAlpha(0.4f);

        // Ocultar botones si aún no se han mostrado antes
        if (layoutBotones.getVisibility() != View.VISIBLE) {
            layoutBotones.setVisibility(View.GONE);
        }
    }

    private void habilitarBotones() {
        btnIrLive.setClickable(true);
        btnIrGaleria.setClickable(true);
        btnIrLive.setAlpha(1f);
        btnIrGaleria.setAlpha(1f);
    }

    // ── Al volver a la pantalla, re-verificar ─────────────────────────────────
    @Override
    protected void onResume() {
        super.onResume();
        // Si los botones ya están visibles y clickeables, no re-verificar
        if (btnIrLive.isClickable()) return;
        verificarModelo();
    }
}