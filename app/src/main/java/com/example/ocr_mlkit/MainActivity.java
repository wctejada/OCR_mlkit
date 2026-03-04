package com.example.ocr_mlkit;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.example.ocr_mlkit.databinding.ActivityMainBinding;
import com.google.android.material.snackbar.Snackbar;
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

import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;

import androidx.camera.core.ImageProxy;

import android.media.Image;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private PreviewView previewView;


    private String directorioImagen;
    private Bitmap imagenSeleccionada;


    private TextRecognizer recognizer;

    private Translator translator;
    private boolean modeloListo = false;
    private String ultimoTextoDetectado = "";

    private ProcessCameraProvider cameraProvider;

    private ExecutorService cameraExecutor;


    private static final int REQUEST_IMAGE_CAPTURE = 100;
    private static final int REQUEST_PICK_IMAGE = 101;
    private static final int REQUEST_PERMISSIONS = 102;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        // Inicializar OCR
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        // Traduccion
        TranslatorOptions options =
                new TranslatorOptions.Builder()
                        .setSourceLanguage(TranslateLanguage.ENGLISH)
                        .setTargetLanguage(TranslateLanguage.SPANISH)
                        .build();
        translator = Translation.getClient(options);

        // Descargar modelo
        DownloadConditions conditions = new DownloadConditions.Builder().build();

        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> modeloListo = true)
                .addOnFailureListener(e -> Log.e("TRAD", "Error modelo", e));



        // Inicializar Live Preview
        previewView = binding.contentMain.previewView;
        cameraExecutor = Executors.newSingleThreadExecutor();


        // Botón camara live preview
        binding.fabCam.setOnClickListener(view -> {
            if (checkAndRequestPermissions()) {
                iniciarLivePreview();
            } else {
                Snackbar.make(binding.getRoot(),
                        "Favor otorgar permisos",
                        Snackbar.LENGTH_LONG).show();
            }
        });
        //Tomar foto
        binding.contentMain.btnEjecutarCamara.setOnClickListener(view -> {
            if (checkAndRequestPermissions()) {
                abrirCamara();
            } else {
                Snackbar.make(binding.getRoot(),
                        "Favor otorgar permisos",
                        Snackbar.LENGTH_LONG).show();
            }
        });

        // Botón galería
        binding.contentMain.btnSeleccionarGaleria.setOnClickListener(v -> abrirGaleria());

        // Texto inicial
        binding.contentMain.txtOriginal.setText(
                "Selecciona una foto o usa Live Preview con el botón flotante."
        );
        binding.contentMain.txtTraducido.setText("");
    }


    // MÉTODOS DE SELECCIÓN DE IMAGEN

    private void abrirGaleria() {
        detenerLivePreview();
        if (checkAndRequestPermissions()) {
            Intent galeriaIntent = new Intent(Intent.ACTION_GET_CONTENT);
            galeriaIntent.setType("image/*");

            Intent selectorIntent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            selectorIntent.setType("image/*");

            Intent menuSelection = Intent.createChooser(galeriaIntent, "Seleccione una Imagen");
            menuSelection.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{selectorIntent});

            startActivityForResult(menuSelection, REQUEST_PICK_IMAGE);
        }
    }

    private void abrirCamara() {
        detenerLivePreview();
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            File archivoImagen = null;
            try {
                archivoImagen = createImageFile();
            } catch (Exception error) {
                error.printStackTrace();
                Log.d("IMAGEN_CAMARA", "Error al generar archivo de imagen");
            }

            if (archivoImagen != null) {
                Uri fotoUri = FileProvider.getUriForFile(this,
                        "com.example.ocr_mlkit.fileprovider", archivoImagen);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, fotoUri);
            }

            startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
        } else {
            Snackbar.make(binding.getRoot(), "No se encontró app para manejo de cámara", Snackbar.LENGTH_LONG).show();
        }
    }

    // LIVE PREVIEW
    private void iniciarLivePreview() {

        binding.contentMain.previewView.setVisibility(View.VISIBLE);
        binding.contentMain.imgBarcode.setVisibility(View.GONE);

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {

                cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis =
                        new ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build();

                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    procesarFrame(imageProxy);
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageAnalysis
                );

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }

        }, ContextCompat.getMainExecutor(this));
    }
    private void detenerLivePreview() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }
    private void procesarFrame(ImageProxy imageProxy) {

        @androidx.annotation.Nullable
        android.media.Image mediaImage = imageProxy.getImage();

        if (mediaImage != null) {

            InputImage image = InputImage.fromMediaImage(
                    mediaImage,
                    imageProxy.getImageInfo().getRotationDegrees()
            );

            recognizer.process(image)
                    .addOnSuccessListener(text -> {

                        String detectedText = text.getText();

                        runOnUiThread(() -> {
                            binding.contentMain.txtOriginal.setText(
                                    detectedText.isEmpty() ? "Escaneando..." : detectedText
                            );
                        });
                        // traducir si hay texto y modelo listo
                        if (!detectedText.isEmpty()
                                && modeloListo
                                && !detectedText.equals(ultimoTextoDetectado)) {

                            ultimoTextoDetectado = detectedText;
                            traducirEnVivo(detectedText);
                        }

                    })
                    .addOnFailureListener(e -> Log.e("LIVE_OCR", "Error", e))
                    .addOnCompleteListener(task -> {
                        if (imageProxy != null) {
                            imageProxy.close();
                        }
                    });

        } else {
            imageProxy.close();
        }
    }
//Traducir en vivo
private void traducirEnVivo(String texto) {

    translator.translate(texto)
            .addOnSuccessListener(traduccion -> {
                runOnUiThread(() -> {
                    binding.contentMain.txtTraducido.setText(traduccion);
                });
            })
            .addOnFailureListener(e -> {
                Log.e("TRAD", "Error traducción", e);
            });
}
    // RESULTADO DE IMAGEN

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != RESULT_OK) return;

        try {
            if (requestCode == REQUEST_IMAGE_CAPTURE) {
                File imgFile = new File(directorioImagen);
                if (imgFile.exists()) {

                    InputImage image = InputImage.fromFilePath(this, Uri.fromFile(imgFile));
                    imagenSeleccionada = image.getBitmapInternal();

                    binding.contentMain.previewView.setVisibility(View.GONE); // ocultar live
                    binding.contentMain.imgBarcode.setImageBitmap(imagenSeleccionada);
                    binding.contentMain.imgBarcode.setVisibility(View.VISIBLE);

                    binding.contentMain.txtOriginal.setText("Reconociendo texto...");
                    binding.contentMain.txtTraducido.setText("");


                    procesarOCR(imagenSeleccionada);
                }
            }

            if (requestCode == REQUEST_PICK_IMAGE && data != null && data.getData() != null) {
                Uri imagenUri = data.getData();
                InputImage image = InputImage.fromFilePath(this, imagenUri);
                imagenSeleccionada = image.getBitmapInternal();

                binding.contentMain.imgBarcode.setImageBitmap(imagenSeleccionada);
                binding.contentMain.imgBarcode.setVisibility(View.VISIBLE);
                binding.contentMain.previewView.setVisibility(View.GONE);
                binding.contentMain.txtOriginal.setText("Reconociendo texto...");
                binding.contentMain.txtTraducido.setText("");
                procesarOCR(imagenSeleccionada);
            }

        } catch (IOException e) {
            e.printStackTrace();
            Snackbar.make(binding.getRoot(), "Error al procesar la imagen", Snackbar.LENGTH_LONG).show();
        }
    }


    // OCR

    private void procesarOCR(Bitmap bitmap) {

        if (bitmap == null) {
            binding.contentMain.txtOriginal.setText("No hay imagen para OCR");
            binding.contentMain.txtTraducido.setText("");
            return;
        }

        try {

            InputImage image = InputImage.fromBitmap(bitmap, 0);

            recognizer.process(image)
                    .addOnSuccessListener(visionText -> {

                        StringBuilder resultado = new StringBuilder();

                        for (Text.TextBlock block : visionText.getTextBlocks()) {
                            for (Text.Line line : block.getLines()) {
                                resultado.append(line.getText()).append("\n");
                            }
                        }

                        String textoDetectado = resultado.toString().trim();

                        if (textoDetectado.isEmpty()) {

                            binding.contentMain.txtOriginal.setText("No se detectó texto");
                            binding.contentMain.txtTraducido.setText("");

                        } else {

                            // Mostrar texto original
                            binding.contentMain.txtOriginal.setText(textoDetectado);

                            // Traducir si modelo listo
                            if (modeloListo) {
                                traducirFoto(textoDetectado);
                            } else {
                                binding.contentMain.txtTraducido.setText("Modelo no disponible");
                            }
                        }

                    })
                    .addOnFailureListener(e -> {
                        e.printStackTrace();
                        binding.contentMain.txtOriginal.setText("Error en OCR");
                        binding.contentMain.txtTraducido.setText("");
                    });

        } catch (Exception e) {
            e.printStackTrace();
            binding.contentMain.txtOriginal.setText("Error procesando imagen");
            binding.contentMain.txtTraducido.setText("");
        }
    }

    private void traducirFoto(String texto) {

        binding.contentMain.txtTraducido.setText("Traduciendo...");

        translator.translate(texto)
                .addOnSuccessListener(traduccion -> {
                    binding.contentMain.txtTraducido.setText(traduccion);
                })
                .addOnFailureListener(e -> {
                    Log.e("TRAD_FOTO", "Error traducción", e);
                    binding.contentMain.txtTraducido.setText("Error en traducción");
                });
    }
    // PERMISOS

    private boolean checkAndRequestPermissions() {
        int cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        if (cameraPermission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.CAMERA,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE
                    }, REQUEST_PERMISSIONS);
            return false;
        }
        return true;
    }

    // CREAR ARCHIVO TEMPORAL DE IMAGEN
    private File createImageFile() throws IOException {
        String fechaHoy = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String nombreArchivo = "JPEG_" + fechaHoy + "_";
        File directorio = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File imagen = File.createTempFile(nombreArchivo, ".jpg", directorio);
        directorioImagen = imagen.getAbsolutePath();
        return imagen;
    }


    // MENÚ

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (recognizer != null) recognizer.close();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (translator != null) translator.close();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) return true;
        return super.onOptionsItemSelected(item);
    }
}