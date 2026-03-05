package com.example.ocr_mlkit;

import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;

import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {

    private LinearLayout container;
    private RemoteModelManager modelManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // FIX: configurar toolbar con botón de regreso
        Toolbar toolbar = findViewById(R.id.toolbarSettings);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Modelos descargados");
        }

        container = findViewById(R.id.containerModelos);
        modelManager = RemoteModelManager.getInstance();

        listarModelos();
    }

    // FIX: botón "atrás" de la toolbar
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void listarModelos() {
        modelManager.getDownloadedModels(TranslateRemoteModel.class)
                .addOnSuccessListener(models -> {
                    container.removeAllViews();

                    if (models.isEmpty()) {
                        TextView empty = new TextView(this);
                        empty.setText("No hay modelos descargados.");
                        empty.setPadding(8, 8, 8, 8);
                        container.addView(empty);
                        return;
                    }

                    for (TranslateRemoteModel model : models) {
                        agregarModeloUI(model.getLanguage());
                    }
                })
                // FIX: manejar fallo en la consulta de modelos
                .addOnFailureListener(e -> {
                    Log.e("SETTINGS", "Error listando modelos", e);
                    TextView errorView = new TextView(this);
                    errorView.setText("Error al obtener modelos: " + e.getMessage());
                    container.addView(errorView);
                });
    }

    private void agregarModeloUI(String languageCode) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 8, 0, 8);

        TextView textView = new TextView(this);
        Locale locale = new Locale(languageCode);
        String nombre = locale.getDisplayLanguage(Locale.getDefault());

        textView.setText("Modelo: " + nombre + " (" + languageCode + ")");
        textView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button btnEliminar = new Button(this);
        btnEliminar.setText("Eliminar");
        btnEliminar.setOnClickListener(v -> eliminarModelo(languageCode));

        row.addView(textView);
        row.addView(btnEliminar);

        container.addView(row);
    }

    private void eliminarModelo(String languageCode) {
        TranslateRemoteModel model =
                new TranslateRemoteModel.Builder(languageCode).build();

        modelManager.deleteDownloadedModel(model)
                .addOnSuccessListener(unused -> {
                    Toast.makeText(this,
                            "Modelo eliminado: " + languageCode,
                            Toast.LENGTH_SHORT).show();
                    listarModelos();
                })
                .addOnFailureListener(e -> {
                    Log.e("MODEL_DELETE", "Error eliminando modelo", e);
                    Toast.makeText(this,
                            "Error al eliminar el modelo",
                            Toast.LENGTH_SHORT).show();
                });
    }
}
