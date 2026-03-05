package com.example.ocr_mlkit;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
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
    private String modoInicio = MODO_LIVE;

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

        modoInicio = getIntent().getStringExtra(EXTRA_MODO);
        if (modoInicio == null) modoInicio = MODO_LIVE;

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        crearTranslator();
        verificarYPrepararModelo();

        previewView = binding.contentMain.previewView;
        cameraExecutor = Executors.newSingleThreadExecutor();

        configurarBotones();
        solicitarPermisoYArrancar();
    }

    // ── Botones ───────────────────────────────────────────────────────────────

    private void configurarBotones() {
        binding.contentMain.btnCapturar.setOnClickListener(v -> capturarFoto());
        binding.contentMain.btnReanudar.setOnClickListener(v -> reanudarLive());
        binding.contentMain.btnGaleria.setOnClickListener(v -> abrirGaleria());

        // FAB: compartir texto traducido
        binding.fabCompartir.setOnClickListener(v -> compartirTexto());

        actualizarUI();
    }

    private void actualizarUI() {
        runOnUiThread(() -> {
            boolean esLive      = modoActual == Modo.LIVE;
            boolean esCongelado = modoActual == Modo.CONGELADO;

            binding.contentMain.btnCapturar.setVisibility(
                    esLive ? View.VISIBLE : View.GONE);
            binding.contentMain.btnReanudar.setVisibility(
                    esCongelado ? View.VISIBLE : View.GONE);
            // Botón galería: solo si se abrió en modo LIVE y estamos en live
            binding.contentMain.btnGaleria.setVisibility(
                    (MODO_LIVE.equals(modoInicio) && esLive) ? View.VISIBLE : View.GONE);

            if (modoActual != Modo.GALERIA)
                binding.contentMain.previewView.setVisibility(View.VISIBLE);
        });
    }

    // ── Permisos ──────────────────────────────────────────────────────────────

    private void solicitarPermisoYArrancar() {
        if (tienePermisosCamara()) {
            iniciarCamara();
            if (MODO_GALERIA.equals(modoInicio)) abrirGaleria();
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
            if (MODO_GALERIA.equals(modoInicio)) abrirGaleria();
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
                this, CameraSelector.DEFAULT_BACK_CAMERA,
                preview, imageAnalysis, imageCapture);

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

    // ── Captura de foto ───────────────────────────────────────────────────────

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
                    String formateado = TextFormatter.formatearLive(text.getText());
                    runOnUiThread(() ->
                            binding.contentMain.txtOriginal.setText(
                                    formateado.isEmpty()
                                            ? "Apunta la cámara al texto..."
                                            : formateado)
                    );
                    if (!formateado.isEmpty()
                            && modeloListo
                            && !formateado.equals(ultimoTextoDetectado)) {
                        ultimoTextoDetectado = formateado;
                        traducir(formateado, false);
                    }
                })
                .addOnFailureListener(e -> Log.e("LIVE_OCR", "Error", e))
                .addOnCompleteListener(task -> imageProxy.close());
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
            if (MODO_GALERIA.equals(modoInicio)) finish();
            else reanudarLive();
            return;
        }

        Uri imagenUri = data.getData();

        try {
            // FIX 1: Cargar bitmap con ContentResolver (funciona con cualquier URI de galería)
            // getBitmapInternal() es API interna de ML Kit y retorna null para URIs de galería
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imagenUri);

            // FIX 2: Detener completamente el preview para que no se vea encima de la imagen
            if (cameraProvider != null) cameraProvider.unbindAll();
            binding.contentMain.previewView.setVisibility(View.GONE);

            binding.contentMain.imgCapturada.setImageBitmap(bitmap);
            binding.contentMain.imgCapturada.setVisibility(View.VISIBLE);
            binding.contentMain.txtOriginal.setText("Reconociendo texto...");
            binding.contentMain.txtTraducido.setText("");

            // FIX 3: OCR desde URI directamente (respeta rotación EXIF de la foto)
            procesarOCRUri(imagenUri, bitmap);

        } catch (IOException e) {
            Log.e("GALLERY", "Error cargando imagen", e);
            Toast.makeText(this, "Error al cargar imagen", Toast.LENGTH_SHORT).show();
            if (MODO_GALERIA.equals(modoInicio)) finish();
            else reanudarLive();
        }
    }

    /**
     * OCR desde URI usando InputImage.fromFilePath — más fiable que fromBitmap
     * para galería porque respeta la rotación EXIF automáticamente.
     * El bitmap solo se usa para mostrar en pantalla.
     */
    private void procesarOCRUri(Uri uri, Bitmap bitmapParaMostrar) {
        try {
            InputImage image = InputImage.fromFilePath(this, uri);

            recognizer.process(image)
                    .addOnSuccessListener(visionText -> {
                        StringBuilder sb = new StringBuilder();
                        for (Text.TextBlock block : visionText.getTextBlocks()) {
                            for (Text.Line line : block.getLines()) {
                                sb.append(line.getText()).append("\n");
                            }
                            sb.append("\n");
                        }
                        String textoFormateado = TextFormatter.formatear(sb.toString());

                        runOnUiThread(() -> {
                            if (textoFormateado.isEmpty()) {
                                binding.contentMain.txtOriginal.setText("No se detectó texto.");
                                binding.contentMain.txtTraducido.setText("");
                            } else {
                                binding.contentMain.txtOriginal.setText(textoFormateado);
                                if (modeloListo) {
                                    traducir(textoFormateado, true);
                                } else {
                                    binding.contentMain.txtTraducido.setText(
                                            "Modelo descargando, intente en un momento.");
                                }
                            }
                        });
                    })
                    .addOnFailureListener(e -> {
                        Log.e("OCR_URI", "Error", e);
                        // Fallback: intentar desde bitmap si fromFilePath falla
                        if (bitmapParaMostrar != null) procesarOCRBitmap(bitmapParaMostrar);
                    });

        } catch (IOException e) {
            Log.e("OCR_URI", "Error creando InputImage desde URI", e);
            if (bitmapParaMostrar != null) procesarOCRBitmap(bitmapParaMostrar);
        }
    }

    // ── OCR Bitmap (captura de cámara y fallback) ─────────────────────────────

    private void procesarOCRBitmap(Bitmap bitmap) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);

        recognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    StringBuilder sb = new StringBuilder();
                    for (Text.TextBlock block : visionText.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            sb.append(line.getText()).append("\n");
                        }
                        sb.append("\n");
                    }
                    String textoFormateado = TextFormatter.formatear(sb.toString());

                    runOnUiThread(() -> {
                        if (textoFormateado.isEmpty()) {
                            binding.contentMain.txtOriginal.setText("No se detectó texto.");
                            binding.contentMain.txtTraducido.setText("");
                        } else {
                            binding.contentMain.txtOriginal.setText(textoFormateado);
                            if (modeloListo) {
                                traducir(textoFormateado, true);
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
        if (mostrarCargando)
            runOnUiThread(() ->
                    binding.contentMain.txtTraducido.setText("Traduciendo..."));

        translator.translate(texto)
                .addOnSuccessListener(traduccion -> {
                    String traduccionFormateada = TextFormatter.formatear(traduccion);
                    runOnUiThread(() ->
                            binding.contentMain.txtTraducido.setText(
                                    traduccionFormateada.isEmpty() ? traduccion : traduccionFormateada));
                })
                .addOnFailureListener(e -> {
                    Log.e("TRAD", "Error traduciendo", e);
                    modeloListo = false;
                    verificarYPrepararModelo();
                });
    }

    private void crearTranslator() {
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.SPANISH)
                .build();
        translator = Translation.getClient(options);
    }

    private void verificarYPrepararModelo() {
        mostrarEstadoTraduccion("Verificando modelo...");

        RemoteModelManager manager = RemoteModelManager.getInstance();
        TranslateRemoteModel modeloES =
                new TranslateRemoteModel.Builder(TranslateLanguage.SPANISH).build();

        manager.isModelDownloaded(modeloES)
                .addOnSuccessListener(descargado -> {
                    if (descargado) {
                        modeloListo = true;
                        mostrarEstadoTraduccion("");
                        if (!ultimoTextoDetectado.isEmpty())
                            traducir(ultimoTextoDetectado, false);
                    } else {
                        descargarModelo();
                    }
                })
                .addOnFailureListener(e -> descargarModelo());
    }

    private void descargarModelo() {
        mostrarEstadoTraduccion("Descargando modelo de traducción...");
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(unused -> {
                    modeloListo = true;
                    mostrarEstadoTraduccion("");
                    if (!ultimoTextoDetectado.isEmpty())
                        traducir(ultimoTextoDetectado, false);
                })
                .addOnFailureListener(e -> {
                    Log.e("TRAD", "Fallo en descarga del modelo", e);
                    mostrarEstadoTraduccion("⚠ Sin modelo. Verifica tu conexión.");
                });
    }

    private void mostrarEstadoTraduccion(String mensaje) {
        runOnUiThread(() -> {
            String actual = binding.contentMain.txtTraducido.getText().toString();
            boolean esEstado = actual.isEmpty()
                    || actual.startsWith("Verificando")
                    || actual.startsWith("Descargando")
                    || actual.startsWith("⚠")
                    || actual.equals("Traduciendo...");
            if (esEstado) binding.contentMain.txtTraducido.setText(mensaje);
        });
    }

    // ── Ciclo de vida ─────────────────────────────────────────────────────────


    // ── Compartir ─────────────────────────────────────────────────────────────

    private void compartirTexto() {
        String traducido = binding.contentMain.txtTraducido.getText().toString().trim();
        String original  = binding.contentMain.txtOriginal.getText().toString().trim();

        // No compartir si no hay contenido real
        if (traducido.isEmpty() || traducido.startsWith("Traduciendo")
                || traducido.startsWith("Verificando") || traducido.startsWith("Descargando")
                || traducido.startsWith("⚠")) {
            Toast.makeText(this, "No hay traducción para compartir", Toast.LENGTH_SHORT).show();
            return;
        }

        String textoCompartir =
                "--- Texto original ---\n" + original +
                        "\n\n--- Traducción ---\n" + traducido;

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, textoCompartir);

        startActivity(Intent.createChooser(shareIntent, "Compartir traducción"));
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
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