package com.example.ocr_mlkit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class TextFormatter {

    /**
     * Limpia y formatea el texto crudo del OCR para que sea legible.
     *
     * Problemas típicos del OCR que se corrigen:
     *  - Líneas partidas en medio de una oración
     *  - Espacios duplicados o irregulares
     *  - Guiones de corte de palabra al final de línea (ej: "trans-\nlate")
     *  - Caracteres basura (símbolos raros producto del ruido en la imagen)
     *  - Líneas muy cortas que son fragmentos sueltos
     *  - Párrafos separados correctamente
     */
    public static String formatear(String textoRaw) {
        if (textoRaw == null || textoRaw.trim().isEmpty()) return "";

        // 1. Normalizar saltos de línea
        String texto = textoRaw.replace("\r\n", "\n").replace("\r", "\n");

        // 2. Unir palabras cortadas con guion al final de línea
        //    Ej: "trans-\nlate" → "translate"
        texto = texto.replaceAll("-\\n([a-záéíóúüña-z])", "$1");

        // 3. Separar el texto en líneas para procesarlas
        String[] lineas = texto.split("\n");

        List<String> parrafos = new ArrayList<>();
        StringBuilder parrafoActual = new StringBuilder();

        for (int i = 0; i < lineas.length; i++) {
            String linea = lineas[i].trim();

            // Ignorar líneas vacías o de un solo carácter (basura OCR)
            if (linea.length() <= 1) {
                // Línea vacía → fin de párrafo
                if (parrafoActual.length() > 0) {
                    parrafos.add(parrafoActual.toString().trim());
                    parrafoActual = new StringBuilder();
                }
                continue;
            }

            // Ignorar líneas que parecen ruido (más del 40% son símbolos raros)
            if (esBasura(linea)) continue;

            // Decidir si esta línea continúa el párrafo anterior o empieza uno nuevo
            if (parrafoActual.length() == 0) {
                // Inicio de párrafo nuevo
                parrafoActual.append(linea);
            } else {
                String ultimaLinea = obtenerUltimaLinea(parrafoActual.toString());
                boolean terminaEnPunto = ultimaLinea.matches(".*[.!?:;]\\s*$");
                boolean siguienteEsMayuscula = linea.length() > 0
                        && Character.isUpperCase(linea.charAt(0));
                boolean lineaCorta = linea.split("\\s+").length <= 3;

                if (terminaEnPunto && siguienteEsMayuscula) {
                    // La línea anterior terminó oración → nuevo párrafo
                    parrafos.add(parrafoActual.toString().trim());
                    parrafoActual = new StringBuilder(linea);
                } else if (lineaCorta && siguienteEsMayuscula) {
                    // Línea muy corta seguida de mayúscula → probablemente título o sección
                    parrafos.add(parrafoActual.toString().trim());
                    parrafoActual = new StringBuilder(linea);
                } else {
                    // Misma oración, unir con espacio
                    parrafoActual.append(" ").append(linea);
                }
            }
        }

        // Agregar el último párrafo si quedó pendiente
        if (parrafoActual.length() > 0) {
            parrafos.add(parrafoActual.toString().trim());
        }

        // 4. Limpiar cada párrafo
        List<String> parrafosLimpios = new ArrayList<>();
        for (String p : parrafos) {
            String limpio = limpiarParrafo(p);
            if (!limpio.isEmpty()) {
                parrafosLimpios.add(limpio);
            }
        }

        // 5. Unir párrafos con doble salto de línea
        return String.join("\n\n", parrafosLimpios);
    }

    /**
     * Limpieza interna de un párrafo:
     * - Espacios múltiples → uno solo
     * - Espacios antes de puntuación
     * - Capitalizar primera letra
     */
    private static String limpiarParrafo(String parrafo) {
        if (parrafo.isEmpty()) return "";

        // Espacios múltiples → uno
        String s = parrafo.replaceAll("\\s{2,}", " ").trim();

        // Quitar espacio antes de puntuación (ej: "hola ." → "hola.")
        s = s.replaceAll("\\s+([.!?,;:)])", "$1");

        // Quitar espacio después de apertura de paréntesis
        s = s.replaceAll("([(\\[{])\\s+", "$1");

        // Capitalizar primera letra del párrafo si no lo está
        if (s.length() > 0 && Character.isLowerCase(s.charAt(0))) {
            s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }

        return s;
    }

    /**
     * Detecta si una línea es "basura" del OCR:
     * más del 40% de sus caracteres son no-alfanuméricos ni espacios.
     */
    private static boolean esBasura(String linea) {
        if (linea.length() < 3) return true;
        int raros = 0;
        for (char c : linea.toCharArray()) {
            if (!Character.isLetterOrDigit(c) && c != ' ' && c != '.' && c != ','
                    && c != '!' && c != '?' && c != ':' && c != ';'
                    && c != '-' && c != '\'' && c != '"' && c != '('
                    && c != ')' && c != '%' && c != '$' && c != '@') {
                raros++;
            }
        }
        return (double) raros / linea.length() > 0.4;
    }

    private static String obtenerUltimaLinea(String texto) {
        int ultimo = texto.lastIndexOf('\n');
        return ultimo >= 0 ? texto.substring(ultimo + 1) : texto;
    }

    /**
     * Versión para live preview: limpieza rápida sin reorganizar párrafos,
     * solo elimina ruido y espacios extra para no afectar el rendimiento.
     */
    public static String formatearLive(String textoRaw) {
        if (textoRaw == null || textoRaw.trim().isEmpty()) return "";

        String[] lineas = textoRaw.split("\n");
        List<String> resultado = new ArrayList<>();

        for (String linea : lineas) {
            linea = linea.trim();
            if (linea.length() > 1 && !esBasura(linea)) {
                // Limpiar espacios múltiples
                linea = linea.replaceAll("\\s{2,}", " ");
                resultado.add(linea);
            }
        }

        return String.join("\n", resultado);
    }
}
