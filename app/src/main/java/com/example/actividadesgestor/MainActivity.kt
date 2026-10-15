package com.example.actividadesgestor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    // Recarga la lista al volver a la app (por si algo cambió desde la notificación)
    private var recargar by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        crearCanal()
        setContent { MaterialTheme { Pantalla(recargar) } }
    }

    override fun onResume() {
        super.onResume()
        recargar++
    }

    // HU03: canal de notificación con sonido de alarma
    private fun crearCanal() {
        val audio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        val canal = NotificationChannel(
            "alarma_pendientes", "Alarmas de pendientes", NotificationManager.IMPORTANCE_HIGH
        )
        canal.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), audio)
        canal.enableVibration(true)
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }
}

@Composable
fun Pantalla(recargar: Int) {
    val context = LocalContext.current
    val pendientes = remember { mutableStateListOf<Pendiente>() } // todos (pendientes y cumplidos)
    var ahora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var mostrarDialogo by remember { mutableStateOf(false) }
    var enEdicion by remember { mutableStateOf<Pendiente?>(null) }
    var porCancelar by remember { mutableStateOf<Pendiente?>(null) }

    // Los datos guardados son la única fuente de verdad: la pantalla siempre se
    // refresca desde ahí (así se reflejan también los cambios hechos desde la notificación)
    fun refrescar() {
        pendientes.clear()
        pendientes.addAll(Almacen.cargar(context))
    }

    // HU05: al llegar la hora de fin, el pendiente se retira automáticamente
    fun retirarVencidos() {
        ahora = System.currentTimeMillis()
        Almacen.cargar(context)
            .filter { it.estatus == Pendiente.PENDIENTE && it.finMillis() <= ahora }
            .forEach { p ->
                cancelarAlarmas(context, p)
                Almacen.actualizar(context, p.id) { it.cumplido() }
            }
        refrescar()
    }

    // Cargar pendientes guardados
    LaunchedEffect(recargar) { retirarVencidos() }

    // Revisar cada 15 segundos si algún pendiente llegó a su hora de fin
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            retirarVencidos()
        }
    }

    // HU03: solicitar permiso de notificaciones
    val permiso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (!concedido) {
            Toast.makeText(context, "Sin permiso de notificaciones no sonarán las alarmas", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permiso.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // HU02: solo activos, en orden cronológico y "sin horario" al final
    val activos = pendientes
        .filter { it.estatus == Pendiente.PENDIENTE }
        .sortedBy { it.horaInicio ?: Int.MAX_VALUE }

    val hoy = SimpleDateFormat("EEEE d 'de' MMMM", Locale.forLanguageTag("es-MX"))
        .format(Date()).replaceFirstChar { it.uppercase() }

    Scaffold(
        floatingActionButton = {
            // HU01: botón "Agregar pendiente"
            ExtendedFloatingActionButton(onClick = { enEdicion = null; mostrarDialogo = true }) {
                Text("+  Agregar pendiente")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {

            Spacer(Modifier.height(16.dp))
            Text("Mis pendientes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(hoy, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                when (activos.size) {
                    0 -> "Todo al día"
                    1 -> "Tienes 1 pendiente"
                    else -> "Tienes ${activos.size} pendientes"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(12.dp))

            if (activos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "🎉\nNo tienes pendientes.\nPresiona \"Agregar pendiente\" para crear uno.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(activos, key = { it.id }) { p ->
                        TarjetaPendiente(
                            p = p,
                            ahora = ahora,
                            onEditar = { enEdicion = p; mostrarDialogo = true },
                            onCancelar = { porCancelar = p },
                            onCompletar = {
                                // HU05: se retira de inmediato y se detiene alarma/sonido
                                cancelarAlarmas(context, p)
                                Almacen.actualizar(context, p.id) { it.cumplido() }
                                refrescar()
                                Toast.makeText(context, "Pendiente completado", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }

    // HU01 / HU02: crear o editar
    if (mostrarDialogo) {
        DialogoPendiente(
            existente = enEdicion,
            onCerrar = { mostrarDialogo = false },
            onGuardar = { texto, inicio, fin ->
                val anterior = enEdicion
                if (anterior == null) {
                    val nuevo = Pendiente(
                        id = Almacen.nuevoId(context),
                        texto = texto,
                        horaInicio = inicio,
                        horaFin = fin,
                        notificacionActiva = inicio != null
                    )
                    Almacen.guardar(context, Almacen.cargar(context) + nuevo)
                    programarAlarmas(context, nuevo)
                    Toast.makeText(context, "Pendiente agregado", Toast.LENGTH_SHORT).show()
                } else {
                    cancelarAlarmas(context, anterior)
                    Almacen.actualizar(context, anterior.id) {
                        it.copy(
                            texto = texto,
                            horaInicio = inicio,
                            horaFin = fin,
                            notificacionActiva = inicio != null
                        )
                    }
                    // Si cambió la hora de inicio y la actividad ya está en curso, suena de inmediato
                    Almacen.buscar(context, anterior.id)?.let {
                        programarAlarmas(context, it, sonarSiYaInicio = inicio != anterior.horaInicio)
                    }
                }
                refrescar()
                mostrarDialogo = false
            }
        )
    }

    // HU04: confirmación antes de cancelar (eliminar) un pendiente
    porCancelar?.let { p ->
        AlertDialog(
            onDismissRequest = { porCancelar = null },
            text = { Text("¿Deseas eliminar este pendiente?") },
            confirmButton = {
                TextButton(onClick = {
                    cancelarAlarmas(context, p)                                          // cancela la alarma
                    Almacen.guardar(context, Almacen.cargar(context).filter { it.id != p.id }) // borra el registro
                    refrescar()
                    porCancelar = null
                }) { Text("Sí, cancelar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { porCancelar = null }) { Text("No") } }
        )
    }
}

@Composable
fun TarjetaPendiente(
    p: Pendiente,
    ahora: Long,
    onEditar: () -> Unit,
    onCancelar: () -> Unit,
    onCompletar: () -> Unit
) {
    val inicio = p.inicioMillis()
    val enCurso = inicio != null && ahora >= inicio

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onEditar() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Text(p.texto, style = MaterialTheme.typography.titleMedium)

            // HU02: horario visible en la tarjeta
            val h = p.horaInicio
            Text(
                text = when {
                    h == null -> "Sin horario"
                    enCurso -> "⏰ ${formatoHora(h)} – ${formatoHora(p.horaFin)} · En curso"
                    else -> "⏰ ${formatoHora(h)} – ${formatoHora(p.horaFin)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    h == null -> MaterialTheme.colorScheme.outline
                    enCurso -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                }
            )

            // HU05: opciones explícitas "Completar" y "Cancelar" en cada tarjeta
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancelar) {
                    Text("Cancelar", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onCompletar) { Text("Completar") }
            }
        }
    }
}

@Composable
fun DialogoPendiente(
    existente: Pendiente?,
    onCerrar: () -> Unit,
    onGuardar: (texto: String, inicio: Int?, fin: Int) -> Unit
) {
    val context = LocalContext.current
    var texto by remember { mutableStateOf(existente?.texto ?: "") }
    var inicio by remember { mutableStateOf(existente?.horaInicio) }
    var fin by remember { mutableStateOf(existente?.let { if (it.sinHorario) null else it.horaFin }) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(if (existente == null) "Nuevo pendiente" else "Editar pendiente") },
        text = {
            Column {
                OutlinedTextField(
                    value = texto,
                    onValueChange = { texto = it; error = null },
                    label = { Text("Nombre o descripción *") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                // HU02: selector de hora de inicio y de fin (24 h)
                SelectorHora("Inicio", inicio) { inicio = it; error = null }
                SelectorHora("Fin", fin) { fin = it; error = null }

                if (inicio != null || fin != null) {
                    TextButton(onClick = { inicio = null; fin = null }) { Text("Quitar horario") }
                } else {
                    Text(
                        "Sin horario: se mostrará al final de la lista y se retirará al terminar el día.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val i = inicio
                val f = fin
                // HU01 y HU02: validación de los datos
                val mensaje = when {
                    texto.isBlank() -> "La descripción es obligatoria"
                    (i == null) != (f == null) -> "Asigna hora de inicio y de fin, o quita el horario"
                    i != null && f != null && f <= i -> "La hora de fin debe ser posterior a la de inicio"
                    f != null && f <= minutosActuales() -> "La hora de fin ya pasó"
                    else -> null
                }
                if (mensaje != null) {
                    error = mensaje
                    Toast.makeText(context, mensaje, Toast.LENGTH_SHORT).show()
                } else {
                    // Si no hay horario se asigna por defecto el fin del día
                    onGuardar(texto.trim(), i, f ?: Pendiente.FIN_POR_DEFECTO)
                }
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cerrar") } }
    )
}

@Composable
fun SelectorHora(etiqueta: String, valor: Int?, onElegir: (Int) -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$etiqueta: ${valor?.let { formatoHora(it) } ?: "--:--"}", Modifier.weight(1f))
        TextButton(onClick = {
            val c = Calendar.getInstance()
            TimePickerDialog(
                context,
                { _, hh, mm -> onElegir(hh * 60 + mm) },
                valor?.div(60) ?: c.get(Calendar.HOUR_OF_DAY),
                valor?.rem(60) ?: c.get(Calendar.MINUTE),
                true // formato 24 h
            ).show()
        }) { Text("Elegir") }
    }
}