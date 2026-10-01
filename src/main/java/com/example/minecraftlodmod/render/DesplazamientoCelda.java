package com.example.minecraftlodmod.render;

/** Fija el desplazamiento de la celda que se va a dibujar (relativo a la cámara) en el programa puesto. */
interface DesplazamientoCelda {
    void poner(float x, float y, float z);
}
