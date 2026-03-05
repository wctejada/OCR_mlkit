package com.example.ocr_mlkit;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class HomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        // Botón Live Preview
        findViewById(R.id.btnIrLive).setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra(MainActivity.EXTRA_MODO, MainActivity.MODO_LIVE);
            startActivity(intent);
        });

        // Botón Galería
        findViewById(R.id.btnIrGaleria).setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra(MainActivity.EXTRA_MODO, MainActivity.MODO_GALERIA);
            startActivity(intent);
        });
    }
}
