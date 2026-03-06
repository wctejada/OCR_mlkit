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
import android.view.MotionEvent;
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
import java.util.Collections;
import java.util.List;
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
    private boolean esCapturaManual = false;

    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private ExecutorService cameraExecutor;
    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;

    private List<DetectedLine> lineasDetectadas = new ArrayList<>();
    private int ultimoScannerWidthPx = 1;
    private int ultimoScannerHeightPx = 1;

    // Caché para el flujo de Galería
    private Text cachedFullVisionText = null;
    private Bitmap currentBitmap = null;

    // Variables para movimiento y redimensionado del marco
    private float dX, dY;
    private float lastX, lastY;

    private static class DetectedLine {
        String originalText;
        String translatedText;
        Rect boundingBox;
        float rawHeight;
        
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

        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        modoInicio = getIntent().getStringExtra(EXTRA_MODO);
        if (modoInicio == null) modoInicio = MODO_LIVE;

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        crearTranslator();
        verificarYPrepararModelo();

        cameraExecutor = Executors.newSingleThreadExecutor();
        configurarBotones();
        configurarMarcoAjustable();
        configurarTapToFocus();
        solicitarPermisoYArrancar();
    }

    private void crearTranslator() {
        if (translator != null) translator.close();
        translator = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang).setTargetLanguage(targetLang).build());
        modeloListo = false;
    }

    private void verificarYPrepararModelo() {
        DownloadConditions conditions = new DownloadConditions.Builder().requireWifi().build();
        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> {
                    modeloListo = true;
                    runOnUiThread(() -> Toast.makeText(this, "Traductor listo", Toast.LENGTH_SHORT).show());
                });
    }

    private void cambiarIdioma() {
        if (sourceLang.equals(TranslateLanguage.ENGLISH)) {
            sourceLang = TranslateLanguage.SPANISH; targetLang = TranslateLanguage.ENGLISH;
            binding.contentMain.btnCambiarIdioma.setText("Español → Inglés");
        } else {
            sourceLang = TranslateLanguage.ENGLISH; targetLang = TranslateLanguage.SPANISH;
            binding.contentMain.btnCambiarIdioma.setText("Inglés → Español");
        }
        crearTranslator();
        verificarYPrepararModelo();
        
        // Si hay una imagen cargada, forzar re-detección con el nuevo idioma si es necesario
        if (cachedFullVisionText != null) {
            bloqueadoPorTraduccion = false;
            ultimoTextoTraducido = "";
            mostrandoOriginalEnOverlay = true;
            actualizarSeleccionPorMarco();
            actualizarUI();
        } else {
            reanudarLive();
        }
    }

    private void configurarBotones() {
        binding.contentMain.btnCapturar.setOnClickListener(v -> capturarFoto());
        binding.contentMain.btnReanudar.setOnClickListener(v -> reanudarLive());
        binding.contentMain.btnGaleria.setOnClickListener(v -> abrirGaleria());
        binding.contentMain.btnCambiarIdioma.setOnClickListener(v -> cambiarIdioma());

        binding.contentMain.btnTraducir.setOnClickListener(v -> {
            if (!lineasDetectadas.isEmpty() && modeloListo) {
                if (modoActual == Modo.LIVE) {
                    Bitmap bmp = binding.contentMain.previewView.getBitmap();
                    if (bmp != null) {
                        currentBitmap = bmp;
                        binding.contentMain.imgCapturada.setImageBitmap(bmp);
                        binding.contentMain.imgCapturada.setVisibility(View.VISIBLE);
                        modoActual = Modo.CONGELADO;
                        esCapturaManual = false;
                        // Procesar OCR completo para permitir ajuste posterior
                        recognizer.process(InputImage.fromBitmap(bmp, 0)).addOnSuccessListener(vt -> {
                            cachedFullVisionText = vt;
                            actualizarSeleccionPorMarco();
                            realizarTraduccionFinal();
                        });
                    }
                } else {
                    realizarTraduccionFinal();
                }
            }
        });

        binding.contentMain.btnVerOriginal.setOnClickListener(v -> {
            mostrandoOriginalEnOverlay = !mostrandoOriginalEnOverlay;
            dibujarOverlay();
            actualizarUI(); // Refresca el texto del botón y el estado de la UI
        });

        binding.fabCompartir.setOnClickListener(v -> compartirTexto());
        actualizarUI();
    }

    private void realizarTraduccionFinal() {
        pausarAnalisis();
        bloqueadoPorTraduccion = true;
        traducirLineas();
        actualizarUI();
    }

    private void configurarMarcoAjustable() {
        binding.contentMain.scannerFrame.setOnTouchListener((view, event) -> {
            if (bloqueadoPorTraduccion) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dX = view.getX() - event.getRawX();
                    dY = view.getY() - event.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    view.setX(event.getRawX() + dX);
                    view.setY(event.getRawY() + dY);
                    if (modoActual != Modo.LIVE) resetTraduccionAlMover();
                    actualizarSeleccionPorMarco();
                    break;
            }
            return true;
        });

        binding.contentMain.resizeHandle.setOnTouchListener((v, event) -> {
            if (bloqueadoPorTraduccion) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float deltaX = event.getRawX() - lastX;
                    float deltaY = event.getRawY() - lastY;
                    ViewGroup.LayoutParams params = binding.contentMain.scannerFrame.getLayoutParams();
                    params.width = Math.max(dpToPx(80), params.width + (int)deltaX);
                    params.height = Math.max(dpToPx(60), params.height + (int)deltaY);
                    binding.contentMain.scannerFrame.setLayoutParams(params);
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    if (modoActual != Modo.LIVE) resetTraduccionAlMover();
                    actualizarSeleccionPorMarco();
                    break;
            }
            return true;
        });
    }

    private void resetTraduccionAlMover() {
        if (!ultimoTextoTraducido.isEmpty()) {
            ultimoTextoTraducido = "";
            bloqueadoPorTraduccion = false;
            mostrandoOriginalEnOverlay = true;
            runOnUiThread(() -> {
                binding.contentMain.txtTraducido.setText("");
                actualizarUI();
            });
        }
    }

    private void actualizarSeleccionPorMarco() {
        if (modoActual == Modo.LIVE) return; // En vivo el frame analyzer lo hace solo
        if (cachedFullVisionText == null || currentBitmap == null) return;

        Rect rect = obtenerRectScanner(currentBitmap.getWidth(), currentBitmap.getHeight());
        ultimoScannerWidthPx = Math.max(1, rect.width());
        ultimoScannerHeightPx = Math.max(1, rect.height());

        List<DetectedLine> nuevas = new ArrayList<>();
        for (Text.TextBlock block : cachedFullVisionText.getTextBlocks()) {
            for (Text.Line l : block.getLines()) {
                if (Rect.intersects(rect, l.getBoundingBox())) {
                    Rect r = new Rect(l.getBoundingBox());
                    r.offset(-rect.left, -rect.top);
                    nuevas.add(new DetectedLine(l.getText(), r, l.getBoundingBox().height()));
                }
            }
        }

        Collections.sort(nuevas, (l1, l2) -> {
            int diff = l1.boundingBox.top - l2.boundingBox.top;
            return Math.abs(diff) < 20 ? l1.boundingBox.left - l2.boundingBox.left : diff;
        });

        lineasDetectadas = nuevas;
        String txt = TextFormatter.formatearLive(cachedFullVisionText, rect);
        runOnUiThread(() -> {
            ultimoTextoDetectado = txt;
            binding.contentMain.txtOriginal.setText(txt.isEmpty() ? "Encuadra el texto que deseas leer..." : txt);
            dibujarOverlay();
        });
    }

    private void configurarTapToFocus() {
        binding.contentMain.previewView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP && camera != null) {
                MeteringPoint point = binding.contentMain.previewView.getMeteringPointFactory().createPoint(event.getX(), event.getY());
                camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(point).build());
                return true;
            }
            return false;
        });
    }

    private void actualizarUI() {
        runOnUiThread(() -> {
            boolean esLive = modoActual == Modo.LIVE, esCongelado = modoActual == Modo.CONGELADO, esGaleria = modoActual == Modo.GALERIA;
            binding.contentMain.btnCapturar.setVisibility(esLive && !bloqueadoPorTraduccion ? View.VISIBLE : View.GONE);
            binding.contentMain.btnReanudar.setVisibility(esCongelado || esGaleria || bloqueadoPorTraduccion ? View.VISIBLE : View.GONE);
            binding.contentMain.btnGaleria.setVisibility((MODO_LIVE.equals(modoInicio) && esLive && !bloqueadoPorTraduccion) ? View.VISIBLE : View.GONE);
            binding.contentMain.resizeHandle.setVisibility(!bloqueadoPorTraduccion ? View.VISIBLE : View.GONE);

            if (!lineasDetectadas.isEmpty()) {
                binding.contentMain.overlayContainer.setVisibility(View.VISIBLE);
                binding.contentMain.btnTraducir.setVisibility(ultimoTextoTraducido.isEmpty() ? View.VISIBLE : View.GONE);
                binding.contentMain.btnVerOriginal.setVisibility(ultimoTextoTraducido.isEmpty() ? View.GONE : View.VISIBLE);
                
                // Actualiza el texto del botón según lo que se muestra actualmente en el overlay
                binding.contentMain.btnVerOriginal.setText(mostrandoOriginalEnOverlay ? "Ver Traducción" : "Ver Original");
            } else {
                binding.contentMain.overlayContainer.setVisibility(View.GONE);
            }

            binding.contentMain.badgeModo.setText(esLive ? "● EN VIVO" : "● ESTÁTICO");
            binding.contentMain.imgCapturada.setVisibility(esLive ? View.GONE : View.VISIBLE);
        });
    }

    private void solicitarPermisoYArrancar() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            iniciarCamara(); if (MODO_GALERIA.equals(modoInicio)) abrirGaleria();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_PERMISSIONS);
        }
    }

    private void iniciarCamara() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (Exception e) { Log.e("CAM", "Error", e); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(binding.contentMain.previewView.getSurfaceProvider());
        imageAnalysis = new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
        imageAnalysis.setAnalyzer(cameraExecutor, this::procesarFrame);
        imageCapture = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build();
        cameraProvider.unbindAll();
        camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis, imageCapture);
        actualizarUI();
    }

    private void pausarAnalisis() { if (imageAnalysis != null) imageAnalysis.clearAnalyzer(); }

    private void reanudarLive() {
        bloqueadoPorTraduccion = false; ultimoTextoTraducido = ""; esCapturaManual = false;
        cachedFullVisionText = null; currentBitmap = null;
        if (imageAnalysis != null) imageAnalysis.setAnalyzer(cameraExecutor, this::procesarFrame);
        lineasDetectadas.clear(); mostrandoOriginalEnOverlay = true;
        binding.contentMain.txtOriginal.setText("Buscando texto...");
        binding.contentMain.txtTraducido.setText("");
        modoActual = Modo.LIVE; actualizarUI();
    }

    private void capturarFoto() {
        if (imageCapture == null) return; pausarAnalisis(); modoActual = Modo.CONGELADO; actualizarUI();
        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override @OptIn(markerClass = ExperimentalGetImage.class)
            public void onCaptureSuccess(ImageProxy ip) {
                Bitmap b = imageProxyToBitmap(ip); ip.close();
                if (b != null) runOnUiThread(() -> {
                    esCapturaManual = true; currentBitmap = b;
                    binding.contentMain.imgCapturada.setImageBitmap(b);
                    procesarOCRImagenCompleta(b);
                });
            }
            @Override public void onError(ImageCaptureException e) { runOnUiThread(() -> reanudarLive()); }
        });
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void procesarFrame(ImageProxy ip) {
        if (bloqueadoPorTraduccion) { ip.close(); return; }
        int rot = ip.getImageInfo().getRotationDegrees();
        int w = (rot == 90 || rot == 270) ? ip.getHeight() : ip.getWidth();
        int h = (rot == 90 || rot == 270) ? ip.getWidth() : ip.getHeight();
        recognizer.process(InputImage.fromMediaImage(ip.getImage(), rot)).addOnSuccessListener(vt -> {
            if (bloqueadoPorTraduccion) return;
            Rect rect = obtenerRectScanner(w, h);
            ultimoScannerWidthPx = Math.max(1, rect.width());
            ultimoScannerHeightPx = Math.max(1, rect.height());
            List<DetectedLine> nuevas = new ArrayList<>();
            for (Text.TextBlock b : vt.getTextBlocks()) {
                for (Text.Line l : b.getLines()) {
                    if (Rect.intersects(rect, l.getBoundingBox())) {
                        Rect r = new Rect(l.getBoundingBox()); r.offset(-rect.left, -rect.top);
                        nuevas.add(new DetectedLine(l.getText(), r, l.getBoundingBox().height()));
                    }
                }
            }
            lineasDetectadas = nuevas;
            String txt = TextFormatter.formatearLive(vt, rect);
            runOnUiThread(() -> {
                if (!txt.equals(ultimoTextoDetectado)) {
                    ultimoTextoDetectado = txt; binding.contentMain.txtOriginal.setText(txt.isEmpty() ? "Buscando texto..." : txt);
                }
                dibujarOverlay(); actualizarUI();
            });
        }).addOnCompleteListener(t -> ip.close());
    }

    private void dibujarOverlay() {
        binding.contentMain.overlayContainer.post(() -> {
            binding.contentMain.overlayContainer.removeAllViews();
            if (lineasDetectadas.isEmpty()) return;
            
            // Si estamos en vivo y no estamos en proceso de traducción bloqueada, 
            // no dibujamos nada para mantener la preview limpia.
            if (modoActual == Modo.LIVE && !bloqueadoPorTraduccion) return;

            float cvW = binding.contentMain.overlayContainer.getWidth(), cvH = binding.contentMain.overlayContainer.getHeight();
            if (cvW == 0) cvW = dpToPx(300); if (cvH == 0) cvH = dpToPx(200);
            float scaleX = cvW / (float) ultimoScannerWidthPx, scaleY = cvH / (float) ultimoScannerHeightPx;

            for (DetectedLine line : lineasDetectadas) {
                TextView tv = new TextView(this); tv.setTextColor(Color.WHITE); 
                tv.setPadding(2, 1, 2, 1);
                tv.setIncludeFontPadding(false);
                float xPos = line.boundingBox.left * scaleX;
                float yPos = line.boundingBox.top * scaleY;
                
                if (!mostrandoOriginalEnOverlay) {
                    // Mostramos la traducción en el mismo lugar que el original
                    tv.setText(line.translatedText != null ? line.translatedText : "...");
                    tv.setBackgroundColor(Color.parseColor("#80000000")); // Fondo oscuro para legibilidad
                } else {
                    // Mostramos texto original
                    tv.setText(line.originalText);
                    tv.setBackgroundColor(Color.parseColor("#40444444"));
                }
                
                tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, line.rawHeight * scaleY * 0.25f);
                tv.setX(xPos); tv.setY(yPos);
                tv.setMaxWidth((int)(cvW - tv.getX()));
                binding.contentMain.overlayContainer.addView(tv, new FrameLayout.LayoutParams(-2, -2));
            }
            binding.contentMain.overlayContainer.setVisibility(View.VISIBLE);
        });
    }

    private void traducirLineas() {
        if (lineasDetectadas.isEmpty()) return;
        runOnUiThread(() -> binding.contentMain.txtTraducido.setText("Traduciendo..."));
        StringBuilder sb = new StringBuilder();
        for (DetectedLine l : lineasDetectadas) sb.append(l.originalText).append("\n");
        translator.translate(sb.toString().trim()).addOnSuccessListener(t -> {
            String[] lines = t.split("\n");
            for (int i = 0; i < lineasDetectadas.size(); i++) lineasDetectadas.get(i).translatedText = (i < lines.length) ? lines[i] : "";
            runOnUiThread(() -> {
                ultimoTextoTraducido = t;
                binding.contentMain.txtTraducido.setText(t);
                mostrandoOriginalEnOverlay = false; // Al terminar de traducir, mostramos la traducción por defecto
                dibujarOverlay(); 
                actualizarUI();
                if (esCapturaManual) binding.contentMain.cameraFrame.postDelayed(() -> saveBitmapToGallery(captureView(binding.contentMain.cameraFrame)), 500);
            });
        });
    }

    private Rect obtenerRectScanner(int imgW, int imgH) {
        float viewW = binding.contentMain.cameraFrame.getWidth(), viewH = binding.contentMain.cameraFrame.getHeight();
        if (viewW == 0 || viewH == 0) return new Rect((int)(imgW*0.2), (int)(imgH*0.3), (int)(imgW*0.8), (int)(imgH*0.7));
        float boxW = binding.contentMain.scannerFrame.getWidth(), boxH = binding.contentMain.scannerFrame.getHeight();
        float boxX = binding.contentMain.scannerFrame.getX(), boxY = binding.contentMain.scannerFrame.getY();
        float scale = (modoActual == Modo.GALERIA) ? Math.min(viewW/imgW, viewH/imgH) : Math.max(viewW/imgW, viewH/imgH);
        float dImgW = imgW * scale, dImgH = imgH * scale;
        float offX = (viewW - dImgW)/2f, offY = (viewH - dImgH)/2f;
        int iL = (int)((boxX - offX)/scale), iT = (int)((boxY - offY)/scale);
        int iR = (int)((boxX + boxW - offX)/scale), iB = (int)((boxY + boxH - offY)/scale);
        return new Rect(Math.max(0, iL), Math.max(0, iT), Math.min(imgW, iR), Math.min(imgH, iB));
    }

    private void abrirGaleria() {
        pausarAnalisis(); modoActual = Modo.GALERIA; esCapturaManual = false; actualizarUI();
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
        startActivityForResult(Intent.createChooser(intent, "Seleccionar imagen"), REQUEST_PICK_IMAGE);
    }

    @Override protected void onActivityResult(int rc, int res, @Nullable Intent d) {
        super.onActivityResult(rc, res, d);
        if (res == RESULT_OK && rc == REQUEST_PICK_IMAGE && d != null) {
            try { Bitmap b = MediaStore.Images.Media.getBitmap(getContentResolver(), d.getData());
                if (cameraProvider != null) cameraProvider.unbindAll();
                currentBitmap = b;
                binding.contentMain.imgCapturada.setImageBitmap(b);
                procesarOCRImagenCompleta(b);
            } catch (Exception e) { reanudarLive(); }
        } else if (MODO_GALERIA.equals(modoInicio)) finish(); else reanudarLive();
    }

    private void procesarOCRImagenCompleta(Bitmap b) {
        recognizer.process(InputImage.fromBitmap(b, 0)).addOnSuccessListener(vt -> {
            cachedFullVisionText = vt;
            actualizarSeleccionPorMarco();
            actualizarUI();
        });
    }

    private void saveBitmapToGallery(Bitmap b) {
        ContentValues v = new ContentValues(); v.put(MediaStore.Images.Media.DISPLAY_NAME, "OCR_" + System.currentTimeMillis() + ".jpg");
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OCR_App");
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri != null) { try (OutputStream o = getContentResolver().openOutputStream(uri)) { b.compress(Bitmap.CompressFormat.JPEG, 90, o);
            runOnUiThread(() -> Toast.makeText(this, "Guardado en Galería", Toast.LENGTH_SHORT).show()); } catch (Exception e) {} }
    }

    private Bitmap captureView(View v) {
        Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        v.draw(new android.graphics.Canvas(b)); return b;
    }

    private int dpToPx(int dp) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics()); }
    @OptIn(markerClass = ExperimentalGetImage.class)
    private Bitmap imageProxyToBitmap(ImageProxy ip) { return InputImage.fromMediaImage(ip.getImage(), ip.getImageInfo().getRotationDegrees()).getBitmapInternal(); }
    @Override public boolean onOptionsItemSelected(android.view.MenuItem item) { if (item.getItemId() == android.R.id.home) { finish(); return true; } return super.onOptionsItemSelected(item); }
    @Override protected void onDestroy() { super.onDestroy(); if (recognizer != null) recognizer.close(); if (cameraExecutor != null) cameraExecutor.shutdown(); if (translator != null) translator.close(); }
    private void compartirTexto() {
        if (ultimoTextoTraducido.isEmpty()) return;
        Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain");
        s.putExtra(Intent.EXTRA_TEXT, "Original: " + ultimoTextoDetectado + "\n\nTraducción: " + ultimoTextoTraducido);
        startActivity(Intent.createChooser(s, "Compartir traducción"));
    }
}
