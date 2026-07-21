import dji.sdk.keyvalue.key.FlightControllerKey
import java.io.File

fun main() {
    println("--- START ---")
    val keys = FlightControllerKey::class.java.fields.map { it.name }.filter { it.contains("Motor", ignoreCase = true) }
    File("motor_keys.txt").writeText(keys.joinToString("\n"))
    println("Done")
}
