package com.dji.recreate2

import dji.sdk.keyvalue.key.FlightControllerKey

fun main() {
    println("--- START OF SCRIPT ---")
    val fields = FlightControllerKey::class.java.declaredFields
    for (field in fields) {
        val name = field.name
        if (name.contains("Motor", ignoreCase = true) || name.contains("Engine", ignoreCase = true) || name.contains("Arm", ignoreCase = true)) {
            println("FOUND KEY: $name")
        }
    }
}
