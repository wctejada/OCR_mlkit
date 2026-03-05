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
    private boolean esCapturaManual = false;

    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;
    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;

    private List<DetectedLine> lineasDetectadas = new ArrayList<>();
    private int ultimoScannerWidthPx = 1;
    private int ultimoScannerHeightPx = 1;

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
        solicitarPermisoYArrancar();
    }

    private void crearTranslator() {
        if (translator != null) translator.close();
        translator = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang).setTargetLanguage(targetLang).build());
        modeloListo = false;
    }

    private void verificarYPrepararModelo() {
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().requireWifi().build())
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
        reanudarLive();
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
                        binding.contentMain.imgCapturada.setImageBitmap(bmp);
                        binding.contentMain.imgCapturada.setVisibility(View.VISIBLE);
                        modoActual = Modo.CONGELADO;
                        esCapturaManual = false;
                    }
                }
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
        clipboard.setPrimaryClip(ClipData.newPlainText("Traducción", texto));
        Toast.makeText(this, "Texto copiado", Toast.LENGTH_SHORT).show();
    }

    private void actualizarUI() {
        runOnUiThread(() -> {
            boolean esLive = modoActual == Modo.LIVE, esCongelado = modoActual == Modo.CONGELADO, esGaleria = modoActual == Modo.GALERIA;
            binding.contentMain.btnCapturar.setVisibility(esLive && !bloqueadoPorTraduccion ? View.VISIBLE : View.GONE);
            binding.contentMain.btnReanudar.setVisibility(esCongelado || esGaleria || bloqueadoPorTraduccion ? View.VISIBLE : View.GONE);
            binding.contentMain.btnGaleria.setVisibility((MODO_LIVE.equals(modoInicio) && esLive && !bloqueadoPorTraduccion) ? View.VISIBLE : View.GONE);
            
            // Handle de ajuste siempre visible excepto cuando la traducción está bloqueando la pantalla
            binding.contentMain.resizeHandle.setVisibility(!bloqueadoPorTraduccion ? View.VISIBLE : View.GONE);

            if (!lineasDetectadas.isEmpty()) {
                binding.contentMain.overlayContainer.setVisibility(View.VISIBLE);
                binding.contentMain.btnTraducir.setVisibility(ultimoTextoTraducido.isEmpty() ? View.VISIBLE : View.GONE);
                binding.contentMain.btnVerOriginal.setVisibility(ultimoTextoTraducido.isEmpty() ? View.GONE : View.VISIBLE);
            } else {
                binding.contentMain.overlayContainer.setVisibility(View.GONE);
            }

            binding.contentMain.badgeModo.setText(esLive ? "● EN VIVO" : "● ESTÁTICO");
            binding.contentMain.imgCapturada.setVisibility(esLive ? View.GONE : View.VISIBLE);
            if (esGaleria) binding.contentMain.imgCapturada.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            else if (esCongelado) binding.contentMain.imgCapturada.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        });
    }

    private void configurarMarcoAjustable() {
        binding.contentMain.scannerFrame.setOnTouchListener(new View.OnTouchListener() {
            private float dX, dY;
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (bloqueadoPorTraduccion) return false;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = view.getX() - event.getRawX();
                        dY = view.getY() - event.getRawY();
                        view.performClick();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        view.setX(event.getRawX() + dX);
                        view.setY(event.getRawY() + dY);
                        break;
                }
                return true;
            }
        });

        binding.contentMain.resizeHandle.setOnTouchListener(new View.OnTouchListener() {
            private float lastX, lastY;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (bloqueadoPorTraduccion) return false;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = event.getRawX();
                        lastY = event.getRawY();
                        v.performClick();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float deltaX = event.getRawX() - lastX;
                        float deltaY = event.getRawY() - lastY;
                        ViewGroup.LayoutParams params = binding.contentMain.scannerFrame.getLayoutParams();
                        params.width = Math.max(dpToPx(100), params.width + (int)deltaX);
                        params.height = Math.max(dpToPx(80), params.height + (int)deltaY);
                        binding.contentMain.scannerFrame.setLayoutParams(params);
                        lastX = event.getRawX();
                        lastY = event.getRawY();
                        break;
                }
                return true;
            }
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
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.contentMain.previewView.getSurfaceProvider());
                imageAnalysis = new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                imageAnalysis.setAnalyzer(cameraExecutor, this::procesarFrame);
                imageCapture = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build();
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis, imageCapture);
                actualizarUI();
            } catch (Exception e) { Log.e("CAM", "Error", e); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void pausarAnalisis() { if (imageAnalysis != null) imageAnalysis.clearAnalyzer(); }

    private void reanudarLive() {
        bloqueadoPorTraduccion = false; ultimoTextoTraducido = ""; esCapturaManual = false;
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
                    esCapturaManual = true;
                    binding.contentMain.imgCapturada.setImageBitmap(b);
                    procesarOCRBitmap(b);
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
        InputImage image = InputImage.fromMediaImage(ip.getImage(), rot);

        recognizer.process(image).addOnSuccessListener(vt -> {
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
            float cvW = binding.contentMain.overlayContainer.getWidth(), cvH = binding.contentMain.overlayContainer.getHeight();
            if (cvW == 0) cvW = dpToPx(300); if (cvH == 0) cvH = dpToPx(200);
            float scaleX = cvW / (float) ultimoScannerWidthPx, scaleY = cvH / (float) ultimoScannerHeightPx;

            for (DetectedLine line : lineasDetectadas) {
                if (mostrandoOriginalEnOverlay && modoActual == Modo.LIVE && !bloqueadoPorTraduccion) {
                    View v = new View(this); v.setBackgroundColor(Color.parseColor("#4000B0FF"));
                    v.setX(line.boundingBox.left * scaleX); v.setY(line.boundingBox.top * scaleY);
                    binding.contentMain.overlayContainer.addView(v, new FrameLayout.LayoutParams((int)(line.boundingBox.width()*scaleX), (int)(line.boundingBox.height()*scaleY)));
                } else {
                    TextView tv = new TextView(this); tv.setTextColor(Color.WHITE); tv.setPadding(8, 2, 8, 2);
                    tv.setText(mostrandoOriginalEnOverlay ? line.originalText : (line.translatedText != null ? line.translatedText : "..."));
                    tv.setBackgroundColor(mostrandoOriginalEnOverlay ? Color.parseColor("#CC444444") : Color.BLACK);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, line.rawHeight * scaleY * 0.8f);
                    tv.setX(line.boundingBox.left * scaleX); tv.setY(line.boundingBox.top * scaleY);
                    tv.setMaxWidth((int)(cvW - tv.getX()));
                    binding.contentMain.overlayContainer.addView(tv, new FrameLayout.LayoutParams(-2, -2));
                }
            }
            binding.contentMain.overlayContainer.setVisibility(View.VISIBLE);
        });
    }

    private void traducirLineas() {
        if (lineasDetectadas.isEmpty()) return;
        runOnUiThread(() -> binding.contentMain.txtTraducido.setText("Traduciendo..."));
        
        StringBuilder sb = new StringBuilder();
        for (DetectedLine l : lineasDetectadas) sb.append(l.originalText).append("\n");
        
        translator.translate(sb.toString().trim())
                .addOnSuccessListener(t -> {
                    String[] lines = t.split("\n");
                    for (int i = 0; i < lineasDetectadas.size(); i++) {
                        lineasDetectadas.get(i).translatedText = (i < lines.length) ? lines[i] : "";
                    }
                    finalizeTraduccion();
                })
                .addOnFailureListener(e -> {
                    for (DetectedLine l : lineasDetectadas) l.translatedText = l.originalText;
                    finalizeTraduccion();
                });
    }

    private void finalizeTraduccion() {
        runOnUiThread(() -> {
            StringBuilder sb = new StringBuilder();
            for (DetectedLine l : lineasDetectadas) sb.append(l.translatedText).append("\n");
            ultimoTextoTraducido = sb.toString().trim();
            binding.contentMain.txtTraducido.setText(ultimoTextoTraducido);
            mostrandoOriginalEnOverlay = false; dibujarOverlay(); actualizarUI();
            if (esCapturaManual) {
                binding.contentMain.cameraFrame.postDelayed(() -> {
                    saveBitmapToGallery(captureView(binding.contentMain.cameraFrame));
                    esCapturaManual = false;
                }, 500);
            }
        });
    }

    private Rect obtenerRectScanner(int imgW, int imgH) {
        float viewW = binding.contentMain.cameraFrame.getWidth(), viewH = binding.contentMain.cameraFrame.getHeight();
        if (viewW == 0 || viewH == 0) return new Rect((int)(imgW*0.2), (int)(imgH*0.3), (int)(imgW*0.8), (int)(imgH*0.7));
        
        float boxW = binding.contentMain.scannerFrame.getWidth(), boxH = binding.contentMain.scannerFrame.getHeight();
        float boxX = binding.contentMain.scannerFrame.getX(), boxY = binding.contentMain.scannerFrame.getY();

        float scale = (modoActual == Modo.GALERIA) ? Math.min(viewW/imgW, viewH/imgH) : Math.max(viewW/imgW, viewH/imgH);
        float dImgW = imgW * scale, dImgH = imgH * scale;
        float offX = (viewW - dImgW)/2f;
        float offY = (viewH - dImgH)/2f;
        
        int iL = (int)((boxX - offX)/scale);
        int iT = (int)((boxY - offY)/scale);
        int iR = (int)((boxX + boxW - offX)/scale);
        int iB = (int)((boxY + boxH - offY)/scale);

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
                if (cameraProvider != null) cameraProvider.unbindAll(); procesarOCRBitmap(b);
            } catch (Exception e) { reanudarLive(); }
        } else if (MODO_GALERIA.equals(modoInicio)) finish(); else reanudarLive();
    }

    private void procesarOCRBitmap(Bitmap b) {
        recognizer.process(InputImage.fromBitmap(b, 0)).addOnSuccessListener(vt -> {
            Rect rect = obtenerRectScanner(b.getWidth(), b.getHeight());
            ultimoScannerWidthPx = Math.max(1, rect.width()); ultimoScannerHeightPx = Math.max(1, rect.height());
            List<DetectedLine> nuevas = new ArrayList<>();
            for (Text.TextBlock block : vt.getTextBlocks()) {
                for (Text.Line l : block.getLines()) {
                    if (Rect.intersects(rect, l.getBoundingBox())) {
                        Rect r = new Rect(l.getBoundingBox()); r.offset(-rect.left, -rect.top);
                        nuevas.add(new DetectedLine(l.getText(), r, l.getBoundingBox().height()));
                    }
                }
            }
            lineasDetectadas = nuevas; ultimoTextoDetectado = TextFormatter.formatearLive(vt, rect);
            runOnUiThread(() -> { binding.contentMain.imgCapturada.setImageBitmap(b); binding.contentMain.txtOriginal.setText(ultimoTextoDetectado);
                mostrandoOriginalEnOverlay = true; dibujarOverlay(); actualizarUI();
            });
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
