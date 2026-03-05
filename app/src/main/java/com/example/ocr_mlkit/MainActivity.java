package com.example.ocr_mlkit;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.ocr_mlkit.databinding.ActivityMainBinding;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_MODO   = "extra_modo";
    public static final String MODO_LIVE    = "LIVE";
    public static final String MODO_GALERIA = "GALERIA";

    private ActivityMainBinding binding;

    private TextRecognizer recognizer;
    private Translator translator;

    private boolean modeloListo = false;
    private String ultimoTextoDetectado = "";
    private String ultimoTextoTraducido = "";
    private boolean mostrandoOriginalEnOverlay = true;
    private String modoInicio = MODO_LIVE;
    
    private String sourceLang = TranslateLanguage.ENGLISH;
    private String targetLang = TranslateLanguage.SPANISH;
    private boolean bloqueadoPorTraduccion = false;

    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;
    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;

    private List<DetectedLine> lineasDetectadas = new ArrayList<>();
    private int ultimoScannerWidthPx = 0;
    private int ultimoScannerHeightPx = 0;

    private static class DetectedLine {
        String originalText;
        String translatedText;
        Rect boundingBox; // Relativo al scanner rect
        float rawHeight;  // Altura original en pixels
        
        DetectedLine(String text, Rect rect, float height) {
            this.originalText = text;
            this.boundingBox = rect;
            this.rawHeight = height;
        }
    }

    private enum Modo { LIVE, CONGELADO, GALERIA }
    private Modo modoActual = Modo.LIVE;

    private static final int REQUEST_PICK_IMAGE  = 101;
    private static final int REQUEST_PERMISSIONS = 102;

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

        cameraExecutor = Executors.newSingleThreadExecutor();

        configurarBotones();
        solicitarPermisoYArrancar();
    }

    private void crearTranslator() {
        if (translator != null) {
            translator.close();
        }
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(targetLang)
                .build();
        translator = Translation.getClient(options);
        modeloListo = false;
    }

    private void verificarYPrepararModelo() {
        DownloadConditions conditions = new DownloadConditions.Builder()
                .requireWifi()
                .build();
        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> {
                    modeloListo = true;
                    Log.d("ML_KIT", "Modelo de traducción listo.");
                    runOnUiThread(() -> Toast.makeText(this, "Traductor listo", Toast.LENGTH_SHORT).show());
                })
                .addOnFailureListener(e -> {
                    modeloListo = false;
                    Log.e("ML_KIT", "Error descargando modelo", e);
                    runOnUiThread(() -> Toast.makeText(this, "Error al descargar el modelo de traducción", Toast.LENGTH_SHORT).show());
                });
    }

    private void cambiarIdioma() {
        if (sourceLang.equals(TranslateLanguage.ENGLISH)) {
            sourceLang = TranslateLanguage.SPANISH;
            targetLang = TranslateLanguage.ENGLISH;
            binding.contentMain.btnCambiarIdioma.setText("ES → EN");
        } else {
            sourceLang = TranslateLanguage.ENGLISH;
            targetLang = TranslateLanguage.SPANISH;
            binding.contentMain.btnCambiarIdioma.setText("EN → ES");
        }
        crearTranslator();
        verificarYPrepararModelo();
        reanudarLive(); // Reiniciar escaneo con el nuevo idioma
    }

    private void configurarBotones() {
        binding.contentMain.btnCapturar.setOnClickListener(v -> capturarFoto());
        binding.contentMain.btnReanudar.setOnClickListener(v -> reanudarLive());
        binding.contentMain.btnGaleria.setOnClickListener(v -> abrirGaleria());

        binding.contentMain.btnCambiarIdioma.setOnClickListener(v -> cambiarIdioma());

        binding.contentMain.btnTraducir.setOnClickListener(v -> {
            if (!lineasDetectadas.isEmpty() && modeloListo) {
                // TRADUCCIÓN MANUAL
                pausarAnalisis(); 
                bloqueadoPorTraduccion = true; 
                traducirLineas(); 
                actualizarUI();
            }
        });

        binding.contentMain.btnVerOriginal.setOnClickListener(v -> {
            mostrandoOriginalEnOverlay = !mostrandoOriginalEnOverlay;
            dibujarOverlay();
            binding.contentMain.btnVerOriginal.setText(mostrandoOriginalEnOverlay ? "Ver Traducción" : "Ver Original");
        });

        binding.contentMain.txtTraducido.setOnLongClickListener(v -> {
            copiarAlPortapapeles(binding.contentMain.txtTraducido.getText().toString());
            return true;
        });

        binding.fabCompartir.setOnClickListener(v -> compartirTexto());

        actualizarUI();
    }

    private void copiarAlPortapapeles(String texto) {
        if (texto.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Traducción", texto);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "Texto copiado al portapapeles", Toast.LENGTH_SHORT).show();
    }

    private void actualizarUI() {
        runOnUiThread(() -> {
            boolean esLive      = modoActual == Modo.LIVE;
            boolean esCongelado = modoActual == Modo.CONGELADO;
            boolean esGaleria   = modoActual == Modo.GALERIA;

            binding.contentMain.btnCapturar.setVisibility(esLive && !bloqueadoPorTraduccion ? View.VISIBLE : View.GONE);
            binding.contentMain.btnReanudar.setVisibility(esCongelado || esGaleria || bloqueadoPorTraduccion ? View.VISIBLE : View.GONE);
            binding.contentMain.btnGaleria.setVisibility((MODO_LIVE.equals(modoInicio) && esLive && !bloqueadoPorTraduccion) ? View.VISIBLE : View.GONE);
            
            binding.contentMain.scannerFrame.setVisibility(View.VISIBLE);
            binding.contentMain.scannerOverlay.setVisibility(esGaleria ? View.GONE : View.VISIBLE); // Ocultar overlay oscuro en galería

            if (!lineasDetectadas.isEmpty()) {
                // En modo LIVE no mostramos el overlay automáticamente
                binding.contentMain.overlayContainer.setVisibility(esLive ? View.GONE : View.VISIBLE);
                if (ultimoTextoTraducido.isEmpty()) {
                    binding.contentMain.btnTraducir.setVisibility(View.VISIBLE);
                    binding.contentMain.btnVerOriginal.setVisibility(View.GONE);
                } else {
                    binding.contentMain.btnTraducir.setVisibility(View.GONE);
                    binding.contentMain.btnVerOriginal.setVisibility(View.VISIBLE);
                }
            } else {
                binding.contentMain.overlayContainer.setVisibility(View.GONE);
                binding.contentMain.btnTraducir.setVisibility(View.GONE);
                binding.contentMain.btnVerOriginal.setVisibility(View.GONE);
            }

            binding.contentMain.badgeModo.setText(modoActual == Modo.LIVE ? "● EN VIVO" : "● ESTÁTICO");
            binding.contentMain.badgeModo.setBackgroundResource(modoActual == Modo.LIVE ? R.drawable.badge_background : R.drawable.badge_background_frozen);

            if (modoActual == Modo.GALERIA) {
                binding.contentMain.previewView.setVisibility(View.GONE);
                binding.contentMain.imgCapturada.setVisibility(View.VISIBLE);
                binding.contentMain.imgCapturada.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER); // Mejor para galería
            } else if (modoActual == Modo.CONGELADO) {
                binding.contentMain.previewView.setVisibility(View.VISIBLE);
                binding.contentMain.imgCapturada.setVisibility(View.VISIBLE);
                binding.contentMain.imgCapturada.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            } else {
                binding.contentMain.previewView.setVisibility(View.VISIBLE);
                binding.contentMain.imgCapturada.setVisibility(View.GONE);
            }
        });
    }

    private void solicitarPermisoYArrancar() {
        if (tienePermisosCamara()) {
            iniciarCamara();
            if (MODO_GALERIA.equals(modoInicio)) abrirGaleria();
        } else {
            List<String> permissions = new ArrayList<>();
            permissions.add(Manifest.permission.CAMERA);
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_PERMISSIONS && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            iniciarCamara();
            if (MODO_GALERIA.equals(modoInicio)) abrirGaleria();
        } else {
            Toast.makeText(this, "Se necesita permiso de cámara.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean tienePermisosCamara() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void iniciarCamara() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
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
        preview.setSurfaceProvider(binding.contentMain.previewView.getSurfaceProvider());

        imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysis.setAnalyzer(cameraExecutor, this::procesarFrame);

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build();

        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis, imageCapture);

        modoActual = Modo.LIVE;
        actualizarUI();
    }

    private void pausarAnalisis() {
        if (imageAnalysis != null) imageAnalysis.clearAnalyzer();
    }

    @androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
    private void reanudarLive() {
        bloqueadoPorTraduccion = false;
        ultimoTextoTraducido = "";
        binding.contentMain.imgCapturada.setVisibility(View.GONE);
        binding.contentMain.previewView.setVisibility(View.VISIBLE);

        if (imageAnalysis != null)
            imageAnalysis.setAnalyzer(cameraExecutor, this::procesarFrame);

        ultimoTextoDetectado = "";
        lineasDetectadas.clear();
        mostrandoOriginalEnOverlay = true;
        binding.contentMain.txtOriginal.setText("Apunta la cámara al texto...");
        binding.contentMain.txtTraducido.setText("");
        binding.contentMain.overlayContainer.removeAllViews();
        binding.contentMain.overlayContainer.setVisibility(View.GONE);
        binding.contentMain.btnTraducir.setVisibility(View.GONE);
        binding.contentMain.btnVerOriginal.setVisibility(View.GONE);

        modoActual = Modo.LIVE;
        actualizarUI();
    }

    private void capturarFoto() {
        if (imageCapture == null) return;
        pausarAnalisis();
        modoActual = Modo.CONGELADO;
        actualizarUI();

        binding.contentMain.txtOriginal.setText("Procesando captura...");
        binding.contentMain.txtTraducido.setText("");

        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            @androidx.camera.core.ExperimentalGetImage
            public void onCaptureSuccess(ImageProxy imageProxy) {
                Bitmap bitmap = imageProxyToBitmap(imageProxy);
                imageProxy.close();
                if (bitmap != null) {
                    runOnUiThread(() -> {
                        binding.contentMain.imgCapturada.setImageBitmap(bitmap);
                        binding.contentMain.imgCapturada.setVisibility(View.VISIBLE);
                    });
                    procesarOCRBitmap(bitmap);
                }
            }

            @Override
            @androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
            public void onError(ImageCaptureException exception) {
                runOnUiThread(() -> reanudarLive());
            }
        });
    }

    private Bitmap captureView(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        view.draw(canvas);
        return bitmap;
    }

    private void saveBitmapToGallery(Bitmap bitmap) {
        String filename = "OCR_" + System.currentTimeMillis() + ".jpg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OCR_App");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri != null) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                }
                runOnUiThread(() -> Toast.makeText(this, "Imagen guardada en galería", Toast.LENGTH_SHORT).show());
            } catch (IOException e) {
                Log.e("SAVE_IMG", "Error al guardar imagen", e);
            }
        }
    }

    @androidx.camera.core.ExperimentalGetImage
    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        try {
            android.media.Image mediaImage = imageProxy.getImage();
            if (mediaImage == null) return null;
            InputImage inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
            return inputImage.getBitmapInternal();
        } catch (Exception e) {
            return null;
        }
    }

    @androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
    private void procesarFrame(ImageProxy imageProxy) {
        if (bloqueadoPorTraduccion) {
            imageProxy.close();
            return;
        }

        android.media.Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }

        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        int frameWidth = imageProxy.getWidth();
        int frameHeight = imageProxy.getHeight();
        
        int uprightWidth = (rotation == 90 || rotation == 270) ? frameHeight : frameWidth;
        int uprightHeight = (rotation == 90 || rotation == 270) ? frameWidth : frameHeight;

        InputImage image = InputImage.fromMediaImage(mediaImage, rotation);
        
        recognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    Rect rectScanner = obtenerRectScanner(uprightWidth, uprightHeight);
                    ultimoScannerWidthPx = rectScanner.width();
                    ultimoScannerHeightPx = rectScanner.height();
                    
                    List<DetectedLine> nuevasLineas = new ArrayList<>();
                    for (Text.TextBlock block : visionText.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            if (Rect.intersects(rectScanner, line.getBoundingBox())) {
                                Rect r = new Rect(line.getBoundingBox());
                                r.offset(-rectScanner.left, -rectScanner.top);
                                nuevasLineas.add(new DetectedLine(line.getText(), r, line.getBoundingBox().height()));
                            }
                        }
                    }

                    String formateado = TextFormatter.formatearLive(visionText, rectScanner);
                    
                    if (formateado.isEmpty()) {
                        if (!ultimoTextoDetectado.isEmpty()) {
                            ultimoTextoDetectado = "";
                            lineasDetectadas.clear();
                            ultimoTextoTraducido = ""; // Limpiar traduccion
                            runOnUiThread(() -> {
                                binding.contentMain.txtOriginal.setText("Buscando texto en el recuadro...");
                                binding.contentMain.txtTraducido.setText("");
                                binding.contentMain.overlayContainer.removeAllViews();
                                binding.contentMain.overlayContainer.setVisibility(View.GONE);
                                actualizarUI();
                            });
                        }
                    } else if (!formateado.equals(ultimoTextoDetectado)) {
                        ultimoTextoDetectado = formateado;
                        lineasDetectadas = nuevasLineas;
                        ultimoTextoTraducido = ""; // Limpiar traduccion al cambiar texto
                        mostrandoOriginalEnOverlay = true; // Forzar original al detectar nuevo
                        
                        runOnUiThread(() -> {
                            binding.contentMain.txtOriginal.setText(formateado);
                            binding.contentMain.txtTraducido.setText("");
                            dibujarOverlay();
                            actualizarUI();
                        });
                    }
                })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void dibujarOverlay() {
        binding.contentMain.overlayContainer.post(() -> {
            binding.contentMain.overlayContainer.removeAllViews();
            if (lineasDetectadas.isEmpty()) return;

            float cvW = binding.contentMain.overlayContainer.getWidth();
            float cvH = binding.contentMain.overlayContainer.getHeight();

            // Usar dpToPx para valores de fallback correctos
            if (cvW == 0) cvW = dpToPx(300);
            if (cvH == 0) cvH = dpToPx(200);

            float scaleX = cvW / ultimoScannerWidthPx;
            float scaleY = cvH / ultimoScannerHeightPx;

            for (DetectedLine line : lineasDetectadas) {
                TextView tv = new TextView(this);
                
                if (mostrandoOriginalEnOverlay) {
                    // MODO ESCANEO (OCR ORIGINAL)
                    tv.setText(line.originalText);
                    tv.setTextColor(Color.WHITE);
                    tv.setBackgroundColor(Color.parseColor("#99444444"));
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, line.rawHeight * scaleY * 0.50f);
                    
                    tv.setX(line.boundingBox.left * scaleX);
                    tv.setY(line.boundingBox.top * scaleY);
                } else {
                    // MODO TRADUCCIÓN
                    String texto = (line.translatedText != null ? line.translatedText : "...");
                    tv.setText(texto);
                    tv.setTextColor(Color.WHITE);
                    tv.setBackgroundColor(Color.BLACK); 
                    tv.setElevation(4f); 
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, line.rawHeight * scaleY * 0.45f);
                    
                    tv.setX(line.boundingBox.left * scaleX);
                    tv.setY(line.boundingBox.top * scaleY);
                }

                tv.setPadding(8, 2, 8, 2);

                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );

                tv.setMaxWidth((int) (cvW - tv.getX()));
                binding.contentMain.overlayContainer.addView(tv, params);
            }
            // Solo mostramos el overlay si NO estamos en modo LIVE
            if (modoActual != Modo.LIVE) {
                binding.contentMain.overlayContainer.setVisibility(View.VISIBLE);
            } else {
                binding.contentMain.overlayContainer.setVisibility(View.GONE);
            }
        });
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private void traducirLineas() {
        if (lineasDetectadas.isEmpty()) return;
        
        runOnUiThread(() -> binding.contentMain.txtTraducido.setText("Traduciendo..."));
        
        StringBuilder fullText = new StringBuilder();
        for (DetectedLine line : lineasDetectadas) {
            fullText.append(line.originalText).append("\n");
        }
        
        translator.translate(fullText.toString().trim())
                .addOnSuccessListener(traduccionCompleta -> {
                    ultimoTextoTraducido = traduccionCompleta;
                    String[] lineasTraducidas = traduccionCompleta.split("\n");
                    
                    for (int i = 0; i < lineasDetectadas.size(); i++) {
                        if (i < lineasTraducidas.length) {
                            lineasDetectadas.get(i).translatedText = lineasTraducidas[i];
                        }
                    }
                    
                    runOnUiThread(() -> {
                        binding.contentMain.txtTraducido.setText(traduccionCompleta);
                        mostrandoOriginalEnOverlay = false;
                        dibujarOverlay();
                        actualizarUI();
                        
                        // GUARDAR EN GALERÍA EL RESULTADO FINAL
                        if (modoActual == Modo.CONGELADO || modoActual == Modo.GALERIA) {
                            binding.contentMain.cameraFrame.postDelayed(() -> {
                                Bitmap capture = captureView(binding.contentMain.cameraFrame);
                                saveBitmapToGallery(capture);
                            }, 500);
                        }
                    });
                })
                .addOnFailureListener(e -> {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Error en la traducción", Toast.LENGTH_SHORT).show();
                        bloqueadoPorTraduccion = false;
                        actualizarUI();
                    });
                });
    }

    private Rect obtenerRectScanner(int width, int height) {
        int marginX = (int) (width * 0.2);
        int marginY = (int) (height * 0.3);
        return new Rect(marginX, marginY, width - marginX, height - marginY);
    }

    @androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
    private void abrirGaleria() {
        pausarAnalisis();
        modoActual = Modo.GALERIA;
        actualizarUI();
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        startActivityForResult(Intent.createChooser(intent, "Seleccionar imagen"), REQUEST_PICK_IMAGE);
    }

    @Override
    @androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || requestCode != REQUEST_PICK_IMAGE || data == null || data.getData() == null) {
            if (MODO_GALERIA.equals(modoInicio)) finish();
            else reanudarLive();
            return;
        }
        Uri imagenUri = data.getData();
        try {
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imagenUri);
            if (cameraProvider != null) cameraProvider.unbindAll();
            procesarOCRBitmap(bitmap);
        } catch (IOException e) {
            if (MODO_GALERIA.equals(modoInicio)) finish();
            else reanudarLive();
        }
    }

    private void procesarOCRBitmap(Bitmap bitmap) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        recognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    int w = bitmap.getWidth();
                    int h = bitmap.getHeight();
                    Rect rectScanner = obtenerRectScanner(w, h);
                    ultimoScannerWidthPx = rectScanner.width();
                    ultimoScannerHeightPx = rectScanner.height();
                    
                    List<DetectedLine> nuevasLineas = new ArrayList<>();
                    for (Text.TextBlock block : visionText.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            if (Rect.intersects(rectScanner, line.getBoundingBox())) {
                                Rect r = new Rect(line.getBoundingBox());
                                r.offset(-rectScanner.left, -rectScanner.top);
                                nuevasLineas.add(new DetectedLine(line.getText(), r, line.getBoundingBox().height()));
                            }
                        }
                    }

                    String textoFormateado = TextFormatter.formatearLive(visionText, rectScanner);
                    ultimoTextoDetectado = textoFormateado;
                    ultimoTextoTraducido = "";
                    lineasDetectadas = nuevasLineas;
                    mostrandoOriginalEnOverlay = true;

                    runOnUiThread(() -> {
                        binding.contentMain.imgCapturada.setImageBitmap(bitmap);
                        binding.contentMain.imgCapturada.setVisibility(View.VISIBLE);
                        binding.contentMain.txtOriginal.setText(textoFormateado.isEmpty() ? "No se detectó texto en el recuadro." : textoFormateado);
                        actualizarUI();
                        dibujarOverlay();
                    });
                });
    }

    private void compartirTexto() {
        String traducido = binding.contentMain.txtTraducido.getText().toString().trim();
        String original  = binding.contentMain.txtOriginal.getText().toString().trim();
        if (traducido.isEmpty() || traducido.equals("Traduciendo...")) return;

        String textoCompartir = "Original: " + original + "\n\nTraducción: " + traducido;
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
