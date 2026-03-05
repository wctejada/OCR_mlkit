package com.example.ocr_mlkit;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.ocr_mlkit.databinding.ActivityMainBinding;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_MODO   = "extra_modo";
    public static final String MODO_LIVE    = "LIVE";
    public static final String MODO_GALERIA = "GALERIA";

    private ActivityMainBinding binding;
    private PreviewView previewView;

    private TextRecognizer recognizer;
    private Translator translator;

    private boolean modeloListo = false;
    private String ultimoTextoDetectado = "";

    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;
    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;

    private enum Modo { LIVE, CONGELADO, GALERIA }
    private Modo modoActual = Modo.LIVE;

    private static final int REQUEST_PICK_IMAGE  = 101;
    private static final int REQUEST_PERMISSIONS = 102;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        // Crear el translator primero, luego verificar/descargar modelo
        crearTranslator();
        verificarYPrepararModelo();

        previewView = binding.contentMain.previewView;
        cameraExecutor = Executors.newSingleThreadExecutor();

        configurarBotones();

        String modoInicio = getIntent().getStringExtra(EXTRA_MODO);
        if (MODO_GALERIA.equals(modoInicio)) {
            solicitarPermisoYArrancar();
            abrirGaleria();
        } else {
            solicitarPermisoYArrancar();
        }
    }

    // ── Traductor ─────────────────────────────────────────────────────────────

    /** Crea la instancia del traductor (no descarga nada aquí). */
    private void crearTranslator() {
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.SPANISH)
                .build();
        translator = Translation.getClient(options);
    }

    /**
     * Verifica si los modelos EN y ES ya están descargados localmente.
     * - Si SÍ están → modeloListo = true de inmediato, sin descargar nada.
     * - Si NO están → descarga y muestra progreso.
     */
    private void verificarYPrepararModelo() {
        mostrarEstadoTraduccion("Verificando modelo...");

        RemoteModelManager manager = RemoteModelManager.getInstance();
        TranslateRemoteModel modeloES =
                new TranslateRemoteModel.Builder(TranslateLanguage.SPANISH).build();

        // Comprobar si el modelo español (el crítico) ya está descargado
        manager.isModelDownloaded(modeloES)
                .addOnSuccessListener(descargado -> {
                    if (descargado) {
                        // Ya existe localmente → listo de inmediato
                        modeloListo = true;
                        Log.d("TRAD", "Modelo ya descargado, listo para traducir");
                        mostrarEstadoTraduccion("");

                        // Si hay texto pendiente, traducirlo ya
                        if (!ultimoTextoDetectado.isEmpty()) {
                            traducir(ultimoTextoDetectado, false);
                        }
                    } else {
                        // No existe → descargar ahora
                        descargarModelo();
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e("TRAD", "Error verificando modelo, intentando descargar", e);
                    descargarModelo();
                });
    }

    /** Descarga el modelo solo cuando no está disponible localmente. */
    private void descargarModelo() {
        mostrarEstadoTraduccion("Descargando modelo de traducción...");
        Log.d("TRAD", "Iniciando descarga del modelo");

        // Sin restricción de WiFi para facilitar la descarga
        DownloadConditions conditions = new DownloadConditions.Builder().build();

        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> {
                    modeloListo = true;
                    Log.d("TRAD", "Modelo descargado exitosamente");
                    mostrarEstadoTraduccion("");

                    if (!ultimoTextoDetectado.isEmpty()) {
                        traducir(ultimoTextoDetectado, false);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e("TRAD", "Fallo en descarga del modelo", e);
                    mostrarEstadoTraduccion("⚠ Sin modelo. Verifica tu conexión.");
                });
    }

    /** Muestra un mensaje en el campo de traducción solo si no hay traducción activa. */
    private void mostrarEstadoTraduccion(String mensaje) {
        runOnUiThread(() -> {
            // Solo actualizar si el texto actual es un mensaje de estado (no una traducción real)
            String actual = binding.contentMain.txtTraducido.getText().toString();
            boolean esEstado = actual.isEmpty()
                    || actual.startsWith("Verificando")
                    || actual.startsWith("Descargando")
                    || actual.startsWith("⚠")
                    || actual.equals("Traduciendo...");

            if (esEstado) {
                binding.contentMain.txtTraducido.setText(mensaje);
            }
        });
    }

    // ── Permisos ──────────────────────────────────────────────────────────────

    private void solicitarPermisoYArrancar() {
        if (tienePermisosCamara()) {
            iniciarCamara();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.CAMERA,
                            Manifest.permission.READ_EXTERNAL_STORAGE
                    }, REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_PERMISSIONS
                && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            iniciarCamara();
        } else {
            Toast.makeText(this,
                    "Se necesita permiso de cámara para usar la app.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean tienePermisosCamara() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ── Botones ───────────────────────────────────────────────────────────────

    private void configurarBotones() {
        binding.contentMain.btnCapturar.setOnClickListener(v -> capturarFoto());
        binding.contentMain.btnReanudar.setOnClickListener(v -> reanudarLive());
        binding.contentMain.btnGaleria.setOnClickListener(v -> abrirGaleria());
        actualizarUI();
    }

    // ── CameraX ───────────────────────────────────────────────────────────────

    private void iniciarCamara() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Log.e("CAM", "Error iniciando cámara", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysis.setAnalyzer(cameraExecutor, this::procesarFrame);

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build();

        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis,
                imageCapture
        );

        modoActual = Modo.LIVE;
        actualizarUI();
    }

    private void pausarAnalisis() {
        if (imageAnalysis != null) imageAnalysis.clearAnalyzer();
    }

    private void reanudarLive() {
        binding.contentMain.imgCapturada.setVisibility(View.GONE);
        binding.contentMain.previewView.setVisibility(View.VISIBLE);

        if (imageAnalysis != null)
            imageAnalysis.setAnalyzer(cameraExecutor, this::procesarFrame);

        ultimoTextoDetectado = "";
        binding.contentMain.txtOriginal.setText("Apunta la cámara al texto...");
        binding.contentMain.txtTraducido.setText("");

        modoActual = Modo.LIVE;
        actualizarUI();
    }

    // ── Captura ───────────────────────────────────────────────────────────────

    private void capturarFoto() {
        if (imageCapture == null) return;

        pausarAnalisis();
        modoActual = Modo.CONGELADO;
        actualizarUI();

        binding.contentMain.txtOriginal.setText("Procesando captura...");
        binding.contentMain.txtTraducido.setText("");

        imageCapture.takePicture(cameraExecutor,
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(ImageProxy imageProxy) {
                        Bitmap bitmap = imageProxyToBitmap(imageProxy);
                        imageProxy.close();

                        if (bitmap == null) {
                            runOnUiThread(() ->
                                    binding.contentMain.txtOriginal.setText("Error al capturar imagen"));
                            return;
                        }

                        runOnUiThread(() -> {
                            binding.contentMain.imgCapturada.setImageBitmap(bitmap);
                            binding.contentMain.imgCapturada.setVisibility(View.VISIBLE);
                        });

                        procesarOCRBitmap(bitmap);
                    }

                    @Override
                    public void onError(ImageCaptureException exception) {
                        Log.e("CAPTURE", "Error capturando", exception);
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this,
                                    "Error al capturar", Toast.LENGTH_SHORT).show();
                            reanudarLive();
                        });
                    }
                });
    }

    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        try {
            android.media.Image mediaImage = imageProxy.getImage();
            if (mediaImage == null) return null;
            InputImage inputImage = InputImage.fromMediaImage(
                    mediaImage, imageProxy.getImageInfo().getRotationDegrees());
            return inputImage.getBitmapInternal();
        } catch (Exception e) {
            Log.e("BITMAP", "Error convirtiendo imagen", e);
            return null;
        }
    }

    // ── Live OCR ──────────────────────────────────────────────────────────────

    private void procesarFrame(ImageProxy imageProxy) {
        android.media.Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }

        InputImage image = InputImage.fromMediaImage(
                mediaImage, imageProxy.getImageInfo().getRotationDegrees());

        recognizer.process(image)
                .addOnSuccessListener(text -> {
                    String detectedText = text.getText();
                    runOnUiThread(() ->
                            binding.contentMain.txtOriginal.setText(
                                    detectedText.isEmpty()
                                            ? "Apunta la cámara al texto..."
                                            : detectedText)
                    );
                    if (!detectedText.isEmpty()
                            && modeloListo
                            && !detectedText.equals(ultimoTextoDetectado)) {
                        ultimoTextoDetectado = detectedText;
                        traducir(detectedText, false);
                    }
                })
                .addOnFailureListener(e -> Log.e("LIVE_OCR", "Error", e))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    // ── OCR Bitmap ────────────────────────────────────────────────────────────

    private void procesarOCRBitmap(Bitmap bitmap) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);

        recognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    StringBuilder sb = new StringBuilder();
                    for (Text.TextBlock block : visionText.getTextBlocks())
                        for (Text.Line line : block.getLines())
                            sb.append(line.getText()).append("\n");

                    String texto = sb.toString().trim();

                    runOnUiThread(() -> {
                        if (texto.isEmpty()) {
                            binding.contentMain.txtOriginal.setText("No se detectó texto.");
                            binding.contentMain.txtTraducido.setText("");
                        } else {
                            binding.contentMain.txtOriginal.setText(texto);
                            if (modeloListo) {
                                traducir(texto, true);
                            } else {
                                binding.contentMain.txtTraducido.setText(
                                        "Modelo descargando, intente en un momento.");
                            }
                        }
                    });
                })
                .addOnFailureListener(e -> {
                    Log.e("OCR_BITMAP", "Error", e);
                    runOnUiThread(() ->
                            binding.contentMain.txtOriginal.setText("Error al reconocer texto."));
                });
    }

    // ── Traducción ────────────────────────────────────────────────────────────

    private void traducir(String texto, boolean mostrarCargando) {
        if (mostrarCargando) {
            runOnUiThread(() ->
                    binding.contentMain.txtTraducido.setText("Traduciendo..."));
        }
        translator.translate(texto)
                .addOnSuccessListener(traduccion ->
                        runOnUiThread(() ->
                                binding.contentMain.txtTraducido.setText(traduccion)))
                .addOnFailureListener(e -> {
                    Log.e("TRAD", "Error traduciendo", e);
                    // Si falla la traducción, puede que el modelo se corrompió: reintentar descarga
                    modeloListo = false;
                    verificarYPrepararModelo();
                });
    }

    // ── Galería ───────────────────────────────────────────────────────────────

    private void abrirGaleria() {
        pausarAnalisis();
        modoActual = Modo.GALERIA;
        actualizarUI();

        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        startActivityForResult(
                Intent.createChooser(intent, "Seleccionar imagen"),
                REQUEST_PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != RESULT_OK || requestCode != REQUEST_PICK_IMAGE
                || data == null || data.getData() == null) {
            reanudarLive();
            return;
        }

        try {
            Uri imagenUri = data.getData();
            InputImage input = InputImage.fromFilePath(this, imagenUri);
            Bitmap bitmap = input.getBitmapInternal();

            if (bitmap != null) {
                binding.contentMain.imgCapturada.setImageBitmap(bitmap);
                binding.contentMain.imgCapturada.setVisibility(View.VISIBLE);
                binding.contentMain.previewView.setVisibility(View.GONE);
                binding.contentMain.txtOriginal.setText("Reconociendo texto...");
                binding.contentMain.txtTraducido.setText("");
                procesarOCRBitmap(bitmap);
            }
        } catch (IOException e) {
            Log.e("GALLERY", "Error cargando imagen", e);
            Toast.makeText(this, "Error al cargar imagen", Toast.LENGTH_SHORT).show();
            reanudarLive();
        }
    }

    // ── UI dinámica ───────────────────────────────────────────────────────────

    private void actualizarUI() {
        runOnUiThread(() -> {
            boolean enLive = modoActual == Modo.LIVE;
            binding.contentMain.btnCapturar.setVisibility(enLive ? View.VISIBLE : View.GONE);
            binding.contentMain.btnReanudar.setVisibility(enLive ? View.GONE : View.VISIBLE);
            if (modoActual != Modo.GALERIA)
                binding.contentMain.previewView.setVisibility(View.VISIBLE);
        });
    }

    // ── Menú y ciclo de vida ──────────────────────────────────────────────────

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recognizer != null) recognizer.close();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (translator != null) translator.close();
    }
}