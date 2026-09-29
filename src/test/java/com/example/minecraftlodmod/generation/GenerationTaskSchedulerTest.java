package com.example.minecraftlodmod.generation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GenerationTaskSchedulerTest {

    @Test
    void ejecutaUnaTareaSimpleYDevuelveElResultado() throws Exception {
        GenerationTaskScheduler scheduler = new GenerationTaskScheduler(2);
        try {
            int resultado = scheduler.enviarYEsperar(() -> 40 + 2);
            assertEquals(42, resultado);
        } finally {
            scheduler.apagar();
        }
    }

    @Test
    @Timeout(10)
    void reparteMuchasTareasEntreVariosHilos() throws InterruptedException {
        // Muchas tareas cortas: con work-stealing, distintos hilos deberían
        // terminar ejecutando tareas (no un solo hilo haciendo todo el trabajo).
        GenerationTaskScheduler scheduler = new GenerationTaskScheduler(4);
        try {
            int cantidadTareas = 200;
            List<Future<Long>> futuros = new ArrayList<>();
            // Sin esta barrera el test era no determinista: con tareas casi
            // instantáneas, a veces un solo hilo las terminaba todas antes de
            // que otro llegara a robar. Cada tarea espera a que haya al menos
            // dos corriendo a la vez, lo que obliga a repartir.
            CountDownLatch dosEnParalelo = new CountDownLatch(2);

            for (int i = 0; i < cantidadTareas; i++) {
                futuros.add(scheduler.enviar(() -> {
                    dosEnParalelo.countDown();
                    dosEnParalelo.await(5, TimeUnit.SECONDS);
                    return Thread.currentThread().threadId();
                }));
            }

            var hilosDistintos = new java.util.HashSet<Long>();
            for (Future<Long> f : futuros) {
                try {
                    hilosDistintos.add(f.get());
                } catch (ExecutionException e) {
                    fail(e);
                }
            }

            assertTrue(hilosDistintos.size() > 1,
                    "Con 4 hilos disponibles y 200 tareas, debería haber usado más de un hilo");
        } finally {
            scheduler.apagar();
        }
    }

    @Test
    @Timeout(10)
    void elLimiteDeConcurrenciaNuncaSeExcede() throws InterruptedException {
        int limite = 2;
        GenerationTaskScheduler scheduler = new GenerationTaskScheduler(4); // pool más grande que el límite
        scheduler.ajustarLimiteConcurrencia(limite);

        try {
            AtomicInteger enEjecucion = new AtomicInteger(0);
            AtomicInteger maximoObservado = new AtomicInteger(0);
            CountDownLatch todasTerminaron = new CountDownLatch(10);

            for (int i = 0; i < 10; i++) {
                scheduler.enviar(() -> {
                    int actual = enEjecucion.incrementAndGet();
                    maximoObservado.updateAndGet(max -> Math.max(max, actual));
                    Thread.sleep(20); // simula trabajo
                    enEjecucion.decrementAndGet();
                    todasTerminaron.countDown();
                    return null;
                });
            }

            assertTrue(todasTerminaron.await(5, TimeUnit.SECONDS));
            assertTrue(maximoObservado.get() <= limite,
                    "Nunca deberían correr más de " + limite + " tareas a la vez, se observó " + maximoObservado.get());
        } finally {
            scheduler.apagar();
        }
    }

    @Test
    void ajustarLimiteConcurrenciaRechazaValoresMenoresAUno() {
        GenerationTaskScheduler scheduler = new GenerationTaskScheduler(2);
        try {
            assertThrows(IllegalArgumentException.class, () -> scheduler.ajustarLimiteConcurrencia(0));
        } finally {
            scheduler.apagar();
        }
    }

    @Test
    void hilosEnElPoolDevuelveElValorConfigurado() {
        GenerationTaskScheduler scheduler = new GenerationTaskScheduler(3);
        try {
            assertEquals(3, scheduler.hilosEnElPool());
        } finally {
            scheduler.apagar();
        }
    }

    @Test
    @Timeout(10)
    void elBackpressureBloqueaCuandoLaColaEstaLlena() throws Exception {
        // Tope de cola muy chico (2) para forzar backpressure fácilmente.
        GenerationTaskScheduler scheduler = new GenerationTaskScheduler(1, 2);
        try {
            CountDownLatch dejarTerminar = new CountDownLatch(1);

            // Llenar la cola con tareas que no terminan hasta que se les avise.
            scheduler.enviar(() -> {
                dejarTerminar.await();
                return null;
            });
            scheduler.enviar(() -> {
                dejarTerminar.await();
                return null;
            });

            // Enviar en otro hilo: debería bloquear porque la cola ya está llena.
            AtomicInteger llegoAEnviar = new AtomicInteger(0);
            Thread hiloQueEnvia = new Thread(() -> {
                scheduler.enviar(() -> "listo");
                llegoAEnviar.set(1);
            });
            hiloQueEnvia.start();

            Thread.sleep(200); // darle tiempo a intentar y quedar bloqueado
            assertEquals(0, llegoAEnviar.get(), "Debería seguir bloqueado con la cola llena");

            dejarTerminar.countDown(); // libera espacio en la cola
            hiloQueEnvia.join(5000);
            assertEquals(1, llegoAEnviar.get(), "Debería desbloquearse al liberarse espacio");
        } finally {
            scheduler.apagar();
        }
    }
}
