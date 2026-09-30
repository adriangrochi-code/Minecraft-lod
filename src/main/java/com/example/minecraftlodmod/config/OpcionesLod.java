package com.example.minecraftlodmod.config;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Opciones de {@link PantallaLod} (estilo Sodium): cada una guarda un valor
 * PENDIENTE que recién se escribe en {@link ConfigLod} con "Aplicar" o
 * "Hecho", y sabe dibujar su control a la derecha de la fila.
 * Solo cliente.
 */
final class OpcionesLod {

    private OpcionesLod() {
    }

    /** Cuánto pesa una opción en el rendimiento (línea al pie de la descripción). */
    enum Impacto { NINGUNO, BAJO, MEDIO, ALTO, VARIABLE }

    static final int BLANCO = 0xFFFFFFFF, GRIS = 0xFF8F8F8F, ACENTO = 0xFF94E4D3, MODIFICADO = 0xFFFFE08A;

    abstract static class Opcion {
        final Component nombre;
        private Supplier<Component> descripcion;
        final Impacto impacto;
        private BooleanSupplier habilitada = () -> true;

        Opcion(Component nombre, Component descripcion, Impacto impacto) {
            this.nombre = nombre;
            this.descripcion = () -> descripcion;
            this.impacto = impacto;
        }

        /** Descripción que cambia según el estado (Vulkan, calibración). */
        Opcion conDescripcion(Supplier<Component> descripcion) {
            this.descripcion = descripcion;
            return this;
        }

        Component descripcion() {
            return descripcion.get();
        }

        Opcion siempreQue(BooleanSupplier condicion) {
            this.habilitada = condicion;
            return this;
        }

        boolean habilitada() {
            return habilitada.getAsBoolean();
        }

        /** true si el valor pendiente difiere del guardado. */
        abstract boolean modificada();

        /** Escribe el pendiente en la config (sin guardar el archivo). */
        abstract void aplicar();

        /** Vuelve el pendiente al valor guardado. */
        abstract void deshacer();

        /** Click en la fila (x relativo al borde izquierdo del control, ancho del control). */
        abstract void click(double x, int anchoControl, int boton);

        /** Arrastre con el botón apretado (solo los deslizadores lo usan). */
        void arrastrar(double x, int anchoControl) {
        }

        abstract void dibujarControl(GuiGraphics g, Font fuente, int x, int y, int ancho, int alto, boolean activa);

        /** Ancho que ocupa el control desde el borde derecho de la fila (el nombre no lo pisa). */
        abstract int anchoControl(Font fuente);
    }

    /** Sí / No, con una casilla como Sodium. */
    static final class Interruptor extends Opcion {
        private final ModConfigSpec.BooleanValue valor;
        private boolean pendiente;

        Interruptor(Component nombre, Component descripcion, Impacto impacto, ModConfigSpec.BooleanValue valor) {
            super(nombre, descripcion, impacto);
            this.valor = valor;
            this.pendiente = valor.get();
        }

        boolean pendiente() {
            return pendiente;
        }

        @Override
        boolean modificada() {
            return pendiente != valor.get();
        }

        @Override
        void aplicar() {
            valor.set(pendiente);
        }

        @Override
        void deshacer() {
            pendiente = valor.get();
        }

        @Override
        void click(double x, int anchoControl, int boton) {
            pendiente = !pendiente;
        }

        @Override
        void dibujarControl(GuiGraphics g, Font fuente, int x, int y, int ancho, int alto, boolean activa) {
            int lado = 10;
            int cx = x + ancho - lado - 6, cy = y + (alto - lado) / 2;
            int color = activa ? BLANCO : GRIS;
            g.fill(cx, cy, cx + lado, cy + 1, color);
            g.fill(cx, cy + lado - 1, cx + lado, cy + lado, color);
            g.fill(cx, cy, cx + 1, cy + lado, color);
            g.fill(cx + lado - 1, cy, cx + lado, cy + lado, color);
            if (pendiente) {
                g.fill(cx + 2, cy + 2, cx + lado - 2, cy + lado - 2, activa ? ACENTO : GRIS);
            }
        }

        @Override
        int anchoControl(Font fuente) {
            return 22;
        }
    }

    /** Uno de varios valores: click izquierdo avanza, derecho retrocede. */
    static final class Ciclo<T> extends Opcion {
        private final Supplier<List<T>> valores;
        private final Supplier<T> leer;
        private final Consumer<T> escribir;
        private final Function<T, Component> texto;
        private T pendiente;

        Ciclo(Component nombre, Component descripcion, Impacto impacto, Supplier<List<T>> valores,
              Supplier<T> leer, Consumer<T> escribir, Function<T, Component> texto) {
            super(nombre, descripcion, impacto);
            this.valores = valores;
            this.leer = leer;
            this.escribir = escribir;
            this.texto = texto;
            this.pendiente = leer.get();
        }

        T pendiente() {
            return pendiente;
        }

        @Override
        boolean modificada() {
            return !Objects.equals(pendiente, leer.get());
        }

        @Override
        void aplicar() {
            escribir.accept(pendiente);
        }

        @Override
        void deshacer() {
            pendiente = leer.get();
        }

        @Override
        void click(double x, int anchoControl, int boton) {
            List<T> lista = valores.get();
            if (lista.isEmpty()) {
                return;
            }
            int i = lista.indexOf(pendiente);
            int paso = boton == 1 ? -1 : 1;
            pendiente = lista.get(Math.floorMod((i < 0 ? 0 : i) + paso, lista.size()));
        }

        @Override
        void dibujarControl(GuiGraphics g, Font fuente, int x, int y, int ancho, int alto, boolean activa) {
            Component valor = texto.apply(pendiente);
            g.drawString(fuente, valor, x + ancho - fuente.width(valor) - 6, y + (alto - 8) / 2,
                    activa ? BLANCO : GRIS);
        }

        @Override
        int anchoControl(Font fuente) {
            return fuente.width(texto.apply(pendiente)) + 12;
        }
    }

    /**
     * Número en un rango, con barra como Sodium. Trabaja en enteros: para
     * decimales se pasa una escala (ej. 100 para centésimas).
     */
    static final class Deslizador extends Opcion {
        static final int ANCHO_BARRA = 60;
        private final int minimo, maximo, paso;
        private final Supplier<Integer> leer;
        private final Consumer<Integer> escribir;
        private final Function<Integer, Component> texto;
        private int pendiente;

        Deslizador(Component nombre, Component descripcion, Impacto impacto, int minimo, int maximo, int paso,
                   Supplier<Integer> leer, Consumer<Integer> escribir, Function<Integer, Component> texto) {
            super(nombre, descripcion, impacto);
            this.minimo = minimo;
            this.maximo = Math.max(minimo, maximo);
            this.paso = Math.max(1, paso);
            this.leer = leer;
            this.escribir = escribir;
            this.texto = texto;
            this.pendiente = leer.get(); // tal cual: redondearlo al paso lo marcaría como cambiado sin tocarlo
        }

        private int limitar(int v) {
            int conPaso = minimo + Math.round((v - minimo) / (float) paso) * paso;
            return Math.max(minimo, Math.min(maximo, conPaso));
        }

        @Override
        boolean modificada() {
            return pendiente != leer.get();
        }

        @Override
        void aplicar() {
            escribir.accept(pendiente);
        }

        @Override
        void deshacer() {
            pendiente = leer.get();
        }

        @Override
        void click(double x, int anchoControl, int boton) {
            arrastrar(x, anchoControl);
        }

        @Override
        void arrastrar(double x, int anchoControl) {
            double inicioBarra = anchoControl - ANCHO_BARRA - 6;
            double t = Math.max(0, Math.min(1, (x - inicioBarra) / ANCHO_BARRA));
            pendiente = limitar((int) Math.round(minimo + t * (maximo - minimo)));
        }

        @Override
        void dibujarControl(GuiGraphics g, Font fuente, int x, int y, int ancho, int alto, boolean activa) {
            int bx = x + ancho - ANCHO_BARRA - 6, by = y + alto / 2;
            int color = activa ? BLANCO : GRIS;
            g.fill(bx, by, bx + ANCHO_BARRA, by + 1, color);
            double t = maximo == minimo ? 0
                    : Math.max(0, Math.min(1, (pendiente - minimo) / (double) (maximo - minimo)));
            int cx = bx + (int) Math.round(t * (ANCHO_BARRA - 3));
            g.fill(cx, by - 4, cx + 3, by + 5, activa ? ACENTO : GRIS);
            Component valor = texto.apply(pendiente);
            g.drawString(fuente, valor, bx - fuente.width(valor) - 6, y + (alto - 8) / 2, color);
        }

        @Override
        int anchoControl(Font fuente) {
            return ANCHO_BARRA + 6 + fuente.width(texto.apply(pendiente)) + 12;
        }
    }

    /** Acción inmediata (calibrar, Vulkan): no entra en Aplicar/Deshacer. */
    static final class Accion extends Opcion {
        private final Supplier<Component> etiqueta;
        private final Runnable accion;

        Accion(Component nombre, Component descripcion, Impacto impacto, Supplier<Component> etiqueta,
               Runnable accion) {
            super(nombre, descripcion, impacto);
            this.etiqueta = etiqueta;
            this.accion = accion;
        }

        @Override
        boolean modificada() {
            return false;
        }

        @Override
        void aplicar() {
        }

        @Override
        void deshacer() {
        }

        @Override
        void click(double x, int anchoControl, int boton) {
            accion.run();
        }

        @Override
        void dibujarControl(GuiGraphics g, Font fuente, int x, int y, int ancho, int alto, boolean activa) {
            Component texto = etiqueta.get();
            int w = fuente.width(texto) + 12;
            int bx = x + ancho - w - 3;
            g.fill(bx, y + 2, x + ancho - 3, y + alto - 2, activa ? 0x40FFFFFF : 0x20FFFFFF);
            g.drawString(fuente, texto, bx + 6, y + (alto - 8) / 2, activa ? ACENTO : GRIS);
        }

        @Override
        int anchoControl(Font fuente) {
            return fuente.width(etiqueta.get()) + 12 + 3 + 6;
        }
    }
}
