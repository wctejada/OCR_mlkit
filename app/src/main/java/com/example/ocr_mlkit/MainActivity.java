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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.example.ocr_mlkit.databinding.ActivityMainBinding;
import com.google.android.material.snackbar.Snackbar;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private String directorioImagen;
    private Bitmap imagenSeleccionada;
    private TextRecognizer recognizer;

    private static final int REQUEST_IMAGE_CAPTURE = 100;
    private static final int REQUEST_PICK_IMAGE = 101;
    private static final int REQUEST_PERMISSIONS = 102;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Inicializar OCR
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        // Botón cámara
        binding.fabCam.setOnClickListener(view -> {
            if (checkAndRequestPermissions()) {
                abrirCamara();
            } else {
                Snackbar.make(binding.getRoot(), "Favor otorgar permisos", Snackbar.LENGTH_LONG).show();
            }
        });
        binding.contentMain.btnEjecutarCamara.setOnClickListener(view -> {
            if (checkAndRequestPermissions()) {
                abrirCamara();
            } else {
                Snackbar.make(binding.getRoot(), "Favor otorgar permisos", Snackbar.LENGTH_LONG).show();
            }
        });
        // Botón galería
        binding.contentMain.btnSeleccionarGaleria.setOnClickListener(v -> abrirGaleria());

        // Inicializar TextView
        binding.contentMain.txtResultado.setText("Selecciona una foto de la galería o toma una con la cámara para iniciar.");
    }

    // -----------------------------
    // MÉTODOS DE SELECCIÓN DE IMAGEN
    // -----------------------------
    private void abrirGaleria() {
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
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            File archivoImagen = null;
            try {
                archivoImagen = createImageFile();
            } catch (Exception error) {
                error.printStackTrace();
                Log.d("IMAGEN_CAMARA","Error al generar archivo de imagen");
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

    // -----------------------------
    // RESULTADO DE IMAGEN
    // -----------------------------
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

                    binding.contentMain.imgBarcode.setImageBitmap(imagenSeleccionada);
                    binding.contentMain.imgBarcode.setVisibility(View.VISIBLE);

                    binding.contentMain.txtResultado.setText("Reconociendo texto...");
                    procesarOCR(imagenSeleccionada);
                }
            }

            if (requestCode == REQUEST_PICK_IMAGE && data != null && data.getData() != null) {
                Uri imagenUri = data.getData();
                InputImage image = InputImage.fromFilePath(this, imagenUri);
                imagenSeleccionada = image.getBitmapInternal();

                binding.contentMain.imgBarcode.setImageBitmap(imagenSeleccionada);
                binding.contentMain.imgBarcode.setVisibility(View.VISIBLE);

                binding.contentMain.txtResultado.setText("Reconociendo texto...");
                procesarOCR(imagenSeleccionada);
            }

        } catch (IOException e) {
            e.printStackTrace();
            Snackbar.make(binding.getRoot(), "Error al procesar la imagen", Snackbar.LENGTH_LONG).show();
        }
    }

    // -----------------------------
    // OCR CON ML KIT
    // -----------------------------
    private void procesarOCR(Bitmap bitmap) {
        if (bitmap == null) {
            binding.contentMain.txtResultado.setText("No hay imagen para OCR");
            return;
        }

        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);

            recognizer.process(image)
                    .addOnSuccessListener(visionText -> {
                        StringBuilder resultado = new StringBuilder();

                        // Bloques y líneas de texto (para posible traducción posterior)
                        for (Text.TextBlock block : visionText.getTextBlocks()) {
                            for (Text.Line line : block.getLines()) {
                                resultado.append(line.getText()).append("\n");
                            }
                        }

                        if (resultado.length() == 0) {
                            binding.contentMain.txtResultado.setText("No se detectó texto");
                        } else {
                            binding.contentMain.txtResultado.setText(resultado.toString());
                        }

                    })
                    .addOnFailureListener(e -> {
                        e.printStackTrace();
                        binding.contentMain.txtResultado.setText("Error en OCR");
                    });

        } catch (Exception e) {
            e.printStackTrace();
            binding.contentMain.txtResultado.setText("Error procesando imagen");
        }
    }

    // -----------------------------
    // PERMISOS
    // -----------------------------
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

    // -----------------------------
    // CREAR ARCHIVO TEMPORAL DE IMAGEN
    // -----------------------------
    private File createImageFile() throws IOException {
        String fechaHoy = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String nombreArchivo = "JPEG_" + fechaHoy + "_";
        File directorio = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File imagen = File.createTempFile(nombreArchivo, ".jpg", directorio);
        directorioImagen = imagen.getAbsolutePath();
        return imagen;
    }

    // -----------------------------
    // MENÚ
    // -----------------------------
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) return true;
        return super.onOptionsItemSelected(item);
    }
}