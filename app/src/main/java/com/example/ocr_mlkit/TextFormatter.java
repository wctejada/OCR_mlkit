package com.example.ocr_mlkit;

import android.graphics.Rect;
import com.google.mlkit.vision.text.Text;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class TextFormatter {

    /**
     * Procesa el objeto Text de ML Kit para capturas estáticas.
     */
    public static String formatear(Text visionText) {
        if (visionText == null || visionText.getTextBlocks().isEmpty()) return "";
        return formatearConFiltro(visionText, null);
    }

    /**
     * Formateo para LIVE, filtrando por una zona rectangular si se proporciona.
     */
    public static String formatearLive(Text visionText, Rect zonaFiltro) {
        if (visionText == null || visionText.getTextBlocks().isEmpty()) return "";

        List<Text.Line> lineasFiltradas = obtenerLineasOrdenadas(visionText, zonaFiltro);
        List<String> textos = new ArrayList<>();

        for (Text.Line line : lineasFiltradas) {
            String t = line.getText().trim();
            if (t.length() > 2 && !esBasura(t)) {
                textos.add(t);
            }
        }

        if (textos.isEmpty()) return "";
        return textos.size() <= 3 ? String.join("\n", textos) : String.join(" ", textos);
    }

    private static String formatearConFiltro(Text visionText, Rect zonaFiltro) {
        List<Text.Line> lineas = obtenerLineasOrdenadas(visionText, zonaFiltro);
        StringBuilder sb = new StringBuilder();
        int lastY = -1;

        for (Text.Line line : lineas) {
            int currentY = line.getBoundingBox().top;
            if (lastY != -1 && Math.abs(currentY - lastY) > 50) {
                sb.append("\n\n");
            } else if (lastY != -1) {
                sb.append(" ");
            }
            sb.append(line.getText());
            lastY = currentY;
        }

        return limpiarTextoFinal(sb.toString());
    }

    private static List<Text.Line> obtenerLineasOrdenadas(Text visionText, Rect zonaFiltro) {
        List<Text.Line> lineas = new ArrayList<>();
        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                if (zonaFiltro == null || Rect.intersects(zonaFiltro, line.getBoundingBox())) {
                    lineas.add(line);
                }
            }
        }

        Collections.sort(lineas, new Comparator<Text.Line>() {
            @Override
            public int compare(Text.Line l1, Text.Line l2) {
                int y1 = l1.getBoundingBox().top;
                int y2 = l2.getBoundingBox().top;
                if (Math.abs(y1 - y2) < 30) {
                    return Integer.compare(l1.getBoundingBox().left, l2.getBoundingBox().left);
                }
                return Integer.compare(y1, y2);
            }
        });
        return lineas;
    }

    private static String limpiarTextoFinal(String textoRaw) {
        if (textoRaw == null || textoRaw.trim().isEmpty()) return "";
        String texto = textoRaw.replace("\r\n", "\n").replace("\r", "\n")
                .replaceAll("-\\n([a-záéíóúüña-z])", "$1")
                .replaceAll("\\s{2,}", " ");

        String[] lineas = texto.split("\n");
        List<String> parrafos = new ArrayList<>();
        StringBuilder parrafoActual = new StringBuilder();

        for (String linea : lineas) {
            linea = linea.trim();
            if (linea.isEmpty()) {
                if (parrafoActual.length() > 0) {
                    parrafos.add(parrafoActual.toString().trim());
                    parrafoActual = new StringBuilder();
                }
                continue;
            }
            if (esBasura(linea)) continue;

            if (parrafoActual.length() == 0) {
                parrafoActual.append(linea);
            } else {
                if (parrafoActual.toString().matches(".*[.!?:;]\\s*$") && Character.isUpperCase(linea.charAt(0))) {
                    parrafos.add(parrafoActual.toString().trim());
                    parrafoActual = new StringBuilder(linea);
                } else {
                    parrafoActual.append(" ").append(linea);
                }
            }
        }
        if (parrafoActual.length() > 0) parrafos.add(parrafoActual.toString().trim());

        List<String> finales = new ArrayList<>();
        for (String p : parrafos) {
            String s = p.replaceAll("\\s+([.!?,;:)])", "$1");
            if (s.length() > 0) {
                s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
                finales.add(s);
            }
        }
        return String.join("\n\n", finales);
    }

    private static boolean esBasura(String linea) {
        if (linea.length() < 2) return true;
        int raros = 0;
        for (char c : linea.toCharArray()) {
            if (!Character.isLetterOrDigit(c) && !Character.isWhitespace(c) 
                && ".,!?:;-_'\"()%@$".indexOf(c) == -1) {
                raros++;
            }
        }
        return (double) raros / linea.length() > 0.4;
    }
}
