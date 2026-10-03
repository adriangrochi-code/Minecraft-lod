package com.example.minecraftlodmod.generation;

/**
 * Recorrido de chunks del centro hacia afuera, por anillos cuadrados
 * (distancia de Chebyshev 0, 1, 2, ...), descartando los que quedan fuera
 * del círculo de radio dado. Lo usa {@link PregeneradorChunks} para que el
 * LOD se complete desde el jugador hacia el horizonte, como Chunky.
 *
 * Lógica pura: solo desplazamientos relativos al centro.
 */
public final class EspiralChunks {

    private final int radio;
    private final long radio2;
    private int anillo;
    /** Posición dentro del perímetro del anillo actual (0 .. 8*anillo - 1). */
    private int paso;
    private int dx, dz;
    private boolean terminado;

    public EspiralChunks(int radio) {
        if (radio < 0) {
            throw new IllegalArgumentException("radio negativo: " + radio);
        }
        this.radio = radio;
        this.radio2 = (long) radio * radio;
        this.paso = -1;
    }

    /** Avanza al próximo chunk dentro del círculo; false cuando ya no quedan. */
    public boolean siguiente() {
        while (!terminado) {
            avanzar();
            if (!terminado && (long) dx * dx + (long) dz * dz <= radio2) {
                return true;
            }
        }
        return false;
    }

    private void avanzar() {
        if (anillo == 0) {
            if (paso < 0) {
                paso = 0;
                dx = dz = 0;
                return;
            }
            anillo = 1;
            paso = 0;
        } else if (++paso >= 8 * anillo) {
            anillo++;
            paso = 0;
        }
        if (anillo > radio) {
            terminado = true;
            return;
        }
        // Perímetro del cuadrado de lado 2*anillo, en 4 tramos de 2*anillo pasos.
        int lado = 2 * anillo;
        int tramo = paso / lado, en = paso % lado;
        switch (tramo) {
            case 0 -> { dx = -anillo + en; dz = -anillo; }
            case 1 -> { dx = anillo; dz = -anillo + en; }
            case 2 -> { dx = anillo - en; dz = anillo; }
            default -> { dx = -anillo; dz = anillo - en; }
        }
    }

    public int dx() {
        return dx;
    }

    public int dz() {
        return dz;
    }

    /** Anillo actual (distancia de Chebyshev al centro): sirve para mostrar el avance. */
    public int anillo() {
        return anillo;
    }

    public int radio() {
        return radio;
    }
}
