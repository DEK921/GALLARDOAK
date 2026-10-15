package com.example.actividadesgestor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Pendiente según las historias de usuario.
 * - id                 -> id_pendiente (HU01) y también id_alarma (HU03)
 * - horaInicio/horaFin -> hora_programada en formato 24 h, en minutos desde medianoche (HU02)
 *                         horaInicio = null significa "sin horario"; en ese caso
 *                         se asigna por defecto el fin del día (23:59)
 * - notificacionActiva -> notificacion_activa (HU03)
 * - estatus, fechaCumplimiento -> estatus (Pendiente / Cumplido), fecha_cumplimiento (HU05)
 * - fecha              -> día al que pertenece el pendiente (pendientes diarios)
 */
data class Pendiente(
    val id: Int,
    val texto: String,
    val horaInicio: Int? = null,
    val horaFin: Int = FIN_POR_DEFECTO,
    val fecha: Long = inicioDelDia(),
    val estatus: String = PENDIENTE,
    val fechaCumplimiento: Long? = null,
    val notificacionActiva: Boolean = false
) {
    val sinHorario: Boolean get() = horaInicio == null

    fun inicioMillis(): Long? = horaInicio?.let { fecha + it * 60_000L }
    fun finMillis(): Long = fecha + horaFin * 60_000L

    /** Copia del pendiente marcada como cumplida (HU05). */
    fun cumplido() = copy(
        estatus = CUMPLIDO,
        fechaCumplimiento = System.currentTimeMillis(),
        notificacionActiva = false
    )

    companion object {
        const val PENDIENTE = "Pendiente"
        const val CUMPLIDO = "Cumplido"
        const val FIN_POR_DEFECTO = 23 * 60 + 59 // 23:59
    }
}

fun inicioDelDia(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

// ---------- Guardado en el teléfono ----------
object Almacen {
    private fun prefs(c: Context) = c.getSharedPreferences("pendientes", Context.MODE_PRIVATE)

    fun cargar(c: Context): List<Pendiente> {
        val arreglo = JSONArray(prefs(c).getString("lista", "[]") ?: "[]")
        return List(arreglo.length()) { i ->
            val o = arreglo.getJSONObject(i)
            Pendiente(
                id = o.getInt("id"),
                texto = o.getString("texto"),
                horaInicio = if (o.has("horaInicio")) o.getInt("horaInicio") else null,
                horaFin = o.optInt("horaFin", Pendiente.FIN_POR_DEFECTO),
                fecha = o.optLong("fecha", inicioDelDia()),
                estatus = o.optString("estatus", Pendiente.PENDIENTE),
                fechaCumplimiento = if (o.has("fechaCumplimiento")) o.getLong("fechaCumplimiento") else null,
                notificacionActiva = o.optBoolean("notificacionActiva", false)
            )
        }
    }

    fun guardar(c: Context, lista: List<Pendiente>) {
        val arreglo = JSONArray()
        lista.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id)
            o.put("texto", p.texto)
            p.horaInicio?.let { o.put("horaInicio", it) }
            o.put("horaFin", p.horaFin)
            o.put("fecha", p.fecha)
            o.put("estatus", p.estatus)
            p.fechaCumplimiento?.let { o.put("fechaCumplimiento", it) }
            o.put("notificacionActiva", p.notificacionActiva)
            arreglo.put(o)
        }
        prefs(c).edit().putString("lista", arreglo.toString()).apply()
    }

    fun buscar(c: Context, id: Int) = cargar(c).find { it.id == id }

    fun actualizar(c: Context, id: Int, cambio: (Pendiente) -> Pendiente) =
        guardar(c, cargar(c).map { if (it.id == id) cambio(it) else it })

    fun estaPendiente(c: Context, id: Int) = buscar(c, id)?.estatus == Pendiente.PENDIENTE

    fun nuevoId(c: Context): Int {
        val id = prefs(c).getInt("ultimoId", 0) + 1
        prefs(c).edit().putInt("ultimoId", id).apply()
        return id
    }
}

// ---------- Hora en formato 24 h (HU02) ----------
fun formatoHora(minutos: Int) = String.format("%02d:%02d", minutos / 60, minutos % 60)

fun minutosActuales(): Int {
    val c = Calendar.getInstance()
    return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
}

// ---------- Alarmas ----------
private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

// Alarma de la hora de inicio: suena el tono (HU03)
private fun intentInicio(c: Context, p: Pendiente): PendingIntent =
    PendingIntent.getBroadcast(
        c, p.id,
        Intent(c, AlarmaReceiver::class.java).putExtra("id", p.id).putExtra("texto", p.texto),
        FLAGS
    )

// Alarma de la hora de fin: retira el pendiente automáticamente (HU05)
private fun intentFin(c: Context, p: Pendiente): PendingIntent =
    PendingIntent.getBroadcast(
        c, p.id,
        Intent(c, AlarmaReceiver::class.java).setAction("FIN").putExtra("id", p.id),
        FLAGS
    )

private fun programar(c: Context, momento: Long, pi: PendingIntent) {
    val am = c.getSystemService(AlarmManager::class.java)
    try {
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, momento, pi)
    } catch (e: SecurityException) {
        am.set(AlarmManager.RTC_WAKEUP, momento, pi)
    }
}

/**
 * Programa la alarma de inicio (HU03) y la de fin (HU05).
 * Si la hora de inicio ya pasó pero la de fin no (la actividad ya está en curso),
 * la alarma de inicio suena de inmediato.
 * sonarSiYaInicio = false evita que vuelva a sonar al editar solo el texto.
 */
fun programarAlarmas(c: Context, p: Pendiente, sonarSiYaInicio: Boolean = true) {
    val ahora = System.currentTimeMillis()
    if (p.finMillis() <= ahora) return

    p.inicioMillis()?.let { inicio ->
        if (inicio > ahora) {
            programar(c, inicio, intentInicio(c, p))
        } else if (sonarSiYaInicio) {
            programar(c, ahora + 1_000, intentInicio(c, p)) // ya en curso: sonar ahora
        }
    }
    programar(c, p.finMillis(), intentFin(c, p))
}

/** Cancela las alarmas y detiene el sonido si está sonando (HU04 y HU05). */
fun cancelarAlarmas(c: Context, p: Pendiente) {
    val am = c.getSystemService(AlarmManager::class.java)
    am.cancel(intentInicio(c, p))
    am.cancel(intentFin(c, p))
    NotificationManagerCompat.from(c).cancel(p.id)
}