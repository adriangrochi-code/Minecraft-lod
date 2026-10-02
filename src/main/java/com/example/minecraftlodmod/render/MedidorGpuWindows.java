package com.example.minecraftlodmod.render;

import com.mojang.logging.LogUtils;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Uso de GPU y VRAM desde los contadores de rendimiento de Windows (PDH), los
 * mismos del Administrador de tareas: no dependen de cómo dibuja el juego
 * (OpenGL, Vulkan, shaderpacks) ni del fabricante de la placa.
 * <ul>
 * <li>GPU %: {@code \GPU Engine(*)\Utilization Percentage}, sumado entre
 *     procesos por motor y el motor más ocupado (como el Administrador).</li>
 * <li>VRAM usada: {@code \GPU Adapter Memory(*)\Dedicated Usage}, la del
 *     adaptador que más usa; total: {@code HardwareInformation.qwMemorySize}
 *     del registro (la mayor entre los adaptadores).</li>
 * </ul>
 * Lee en un hilo propio una vez por segundo (nunca frena un cuadro). Fuera de
 * Windows, o si algo falla, queda sin dato y el HUD usa sus otras fuentes.
 */
final class MedidorGpuWindows {

    private static final Logger LOG = LogUtils.getLogger();

    static final MedidorGpuWindows INSTANCIA = new MedidorGpuWindows();

    private volatile double gpuPorcentaje = -1;
    private volatile long vramUsadaMb = -1;
    private volatile long vramTotalMb = -1;
    private volatile boolean arrancado;

    private MedidorGpuWindows() {
    }

    /** % de uso de GPU, o -1 sin dato. */
    double gpuPorcentaje() {
        arrancar();
        return gpuPorcentaje;
    }

    /** VRAM dedicada usada (MB), o -1. */
    long vramUsadaMb() {
        arrancar();
        return vramUsadaMb;
    }

    /** VRAM dedicada total (MB), o -1. */
    long vramTotalMb() {
        arrancar();
        return vramTotalMb;
    }

    private void arrancar() {
        if (arrancado) {
            return;
        }
        arrancado = true;
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            return;
        }
        Thread hilo = new Thread(this::bucle, "LOD medidor GPU");
        hilo.setDaemon(true);
        hilo.setPriority(Thread.MIN_PRIORITY);
        hilo.start();
    }

    // ------------------------------------------------------------------ PDH

    /** Las llamadas de pdh.dll que hacen falta (la interfaz de JNA no trae la de arreglos). */
    interface Pdh extends Library {
        int PdhOpenQueryW(WString fuente, Pointer datos, PointerByReference consulta);

        int PdhAddEnglishCounterW(Pointer consulta, WString ruta, Pointer datos, PointerByReference contador);

        int PdhCollectQueryData(Pointer consulta);

        int PdhGetFormattedCounterArrayW(Pointer contador, int formato, IntByReference tamano,
                                         IntByReference cantidad, Pointer items);

        int PdhCloseQuery(Pointer consulta);
    }

    private static final int PDH_FMT_DOUBLE = 0x00000200;
    private static final int PDH_FMT_NOCAP100 = 0x00008000;
    private static final int PDH_MORE_DATA = 0x800007D2;
    private static final int ERROR_SUCCESS = 0;
    /** PDH_FMT_COUNTERVALUE_ITEM_W en 64 bits: nombre (8) + CStatus (4) + relleno (4) + valor (8). */
    private static final int TAMANO_ITEM = 24;

    private void bucle() {
        Pdh pdh;
        Pointer consulta;
        Pointer contadorGpu, contadorVram;
        try {
            pdh = Native.load("pdh", Pdh.class);
            PointerByReference q = new PointerByReference();
            verificar(pdh.PdhOpenQueryW(null, null, q), "PdhOpenQuery");
            consulta = q.getValue();
            PointerByReference c1 = new PointerByReference();
            verificar(pdh.PdhAddEnglishCounterW(consulta, new WString("\\GPU Engine(*)\\Utilization Percentage"),
                    null, c1), "contador de uso de GPU");
            contadorGpu = c1.getValue();
            PointerByReference c2 = new PointerByReference();
            contadorVram = pdh.PdhAddEnglishCounterW(consulta, new WString("\\GPU Adapter Memory(*)\\Dedicated Usage"),
                    null, c2) == ERROR_SUCCESS ? c2.getValue() : null;
            vramTotalMb = vramTotalDelRegistro();
            pdh.PdhCollectQueryData(consulta); // la primera lectura de un contador de tasa no tiene valor
        } catch (Throwable e) {
            LOG.info("LOD: sin contadores de GPU de Windows ({}); el HUD usa la medición del juego", e.toString());
            return;
        }
        LOG.info("LOD: uso de GPU y VRAM desde los contadores de Windows (VRAM total {} MB)", vramTotalMb);
        while (true) {
            try {
                Thread.sleep(1000);
                if (pdh.PdhCollectQueryData(consulta) != ERROR_SUCCESS) {
                    continue;
                }
                Map<String, Double> motores = leer(pdh, contadorGpu, PDH_FMT_DOUBLE | PDH_FMT_NOCAP100);
                gpuPorcentaje = motores == null ? -1 : usoGpu(motores);
                if (contadorVram != null) {
                    Map<String, Double> adaptadores = leer(pdh, contadorVram, PDH_FMT_DOUBLE);
                    vramUsadaMb = adaptadores == null || adaptadores.isEmpty() ? -1
                            : Math.round(adaptadores.values().stream().mapToDouble(Double::doubleValue).max()
                            .orElse(-1) / (1024 * 1024));
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                LOG.warn("LOD: falló la lectura de los contadores de GPU; se dejan de usar", e);
                gpuPorcentaje = -1;
                vramUsadaMb = -1;
                pdh.PdhCloseQuery(consulta);
                return;
            }
        }
    }

    private static void verificar(int resultado, String que) {
        if (resultado != ERROR_SUCCESS) {
            throw new IllegalStateException(que + ": 0x" + Integer.toHexString(resultado));
        }
    }

    /** Instancia → valor de un contador con comodín; null si no se pudo leer. */
    private static Map<String, Double> leer(Pdh pdh, Pointer contador, int formato) {
        IntByReference tamano = new IntByReference(0);
        IntByReference cantidad = new IntByReference(0);
        int r = pdh.PdhGetFormattedCounterArrayW(contador, formato, tamano, cantidad, null);
        if (r != PDH_MORE_DATA || tamano.getValue() <= 0) {
            return r == ERROR_SUCCESS ? Map.of() : null;
        }
        Memory items = new Memory(tamano.getValue());
        if (pdh.PdhGetFormattedCounterArrayW(contador, formato, tamano, cantidad, items) != ERROR_SUCCESS) {
            return null;
        }
        Map<String, Double> valores = new HashMap<>();
        for (int i = 0; i < cantidad.getValue(); i++) {
            long base = (long) i * TAMANO_ITEM;
            Pointer nombre = items.getPointer(base);
            int estado = items.getInt(base + 8);
            if (nombre == null || estado != 0) {
                continue; // PDH_CSTATUS_VALID_DATA / NEW_DATA = 0
            }
            valores.merge(nombre.getWideString(0), items.getDouble(base + 16), Double::sum);
        }
        return valores;
    }

    /**
     * Como el Administrador de tareas: por motor ({@code luid_..._phys_N_eng_M}) la
     * suma de todos los procesos, y de ahí el más ocupado, con tope 100.
     * Instancias: {@code pid_1234_luid_0x0_0x1_phys_0_eng_0_engtype_3D}.
     */
    static double usoGpu(Map<String, Double> porInstancia) {
        Map<String, Double> porMotor = new HashMap<>();
        for (Map.Entry<String, Double> e : porInstancia.entrySet()) {
            String instancia = e.getKey();
            int desde = instancia.indexOf("luid_");
            int hasta = instancia.indexOf("_engtype_");
            String motor = desde < 0 ? instancia
                    : instancia.substring(desde, hasta > desde ? hasta : instancia.length());
            porMotor.merge(motor, e.getValue(), Double::sum);
        }
        double maximo = 0;
        for (double v : porMotor.values()) {
            maximo = Math.max(maximo, v);
        }
        return Math.min(100, maximo);
    }

    /** VRAM dedicada de la placa más grande, del registro del driver de video (MB), o -1. */
    private static long vramTotalDelRegistro() {
        String clase = "SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e968-e325-11ce-bfc1-08002be10318}";
        long mayor = -1;
        try {
            for (String sub : com.sun.jna.platform.win32.Advapi32Util.registryGetKeys(
                    com.sun.jna.platform.win32.WinReg.HKEY_LOCAL_MACHINE, clase)) {
                String ruta = clase + "\\" + sub;
                try {
                    Object v = valor(ruta, "HardwareInformation.qwMemorySize");
                    if (v == null) {
                        v = valor(ruta, "HardwareInformation.MemorySize");
                    }
                    long bytes = bytes(v);
                    if (bytes > 0) {
                        mayor = Math.max(mayor, bytes / (1024 * 1024));
                    }
                } catch (RuntimeException ignorada) {
                    // subclave sin permiso de lectura (Properties): se saltea
                }
            }
        } catch (RuntimeException e) {
            return -1;
        }
        return mayor;
    }

    private static Object valor(String ruta, String nombre) {
        var hkey = com.sun.jna.platform.win32.WinReg.HKEY_LOCAL_MACHINE;
        return com.sun.jna.platform.win32.Advapi32Util.registryValueExists(hkey, ruta, nombre)
                ? com.sun.jna.platform.win32.Advapi32Util.registryGetValue(hkey, ruta, nombre) : null;
    }

    /** QWORD (Long), DWORD (Integer, sin signo) o binario little-endian de 4 u 8 bytes. */
    static long bytes(Object v) {
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof Integer i) {
            return Integer.toUnsignedLong(i);
        }
        if (v instanceof byte[] b && (b.length == 4 || b.length == 8)) {
            long r = 0;
            for (int i = b.length - 1; i >= 0; i--) {
                r = r << 8 | (b[i] & 0xFF);
            }
            return r;
        }
        return -1;
    }
}
