package com.example.actividadesgestor

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class AlarmaReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val gestor = NotificationManagerCompat.from(context)

        when (intent.action) {
            // HU03: "Descartar" -> el sonido cesa
            "DESCARTAR" -> {
                gestor.cancel(id)
                Almacen.actualizar(context, id) { it.copy(notificacionActiva = false) }
                return
            }
            // HU03: "Completar" -> el sonido cesa y la tarea cambia de estado
            // HU05: "FIN" -> llegó la hora de fin; se retira automáticamente
            "COMPLETAR", "FIN" -> {
                Almacen.buscar(context, id)?.let { cancelarAlarmas(context, it) }
                gestor.cancel(id)
                Almacen.actualizar(context, id) {
                    if (it.estatus == Pendiente.PENDIENTE) it.cumplido() else it
                }
                return
            }
        }

        // HU03: llegó la hora de inicio
        if (!Almacen.estaPendiente(context, id)) return

        val abrir = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificacion = NotificationCompat.Builder(context, "alarma_pendientes")
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(intent.getStringExtra("texto"))
            .setContentText("⏰ Es hora de realizar este pendiente")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .addAction(0, "Completar", accion(context, "COMPLETAR", id))
            .addAction(0, "Descartar", accion(context, "DESCARTAR", id))
            .build()

        // El tono se repite hasta que el usuario interactúe con la notificación
        notificacion.flags = notificacion.flags or Notification.FLAG_INSISTENT

        gestor.notify(id, notificacion)
    }

    private fun accion(context: Context, accion: String, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, id,
            Intent(context, AlarmaReceiver::class.java).setAction(accion).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}