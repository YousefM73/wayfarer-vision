package com.example.glassesview

/** A credential from MultiSet's portal: the name it was given there and the pair that signs in. */
data class MultiSetCredential(val name: String, val clientId: String, val clientSecret: String)

private enum class Field {
  Name,
  Id,
  Secret,
}

/**
 * Reads the credential out of the CSV that MultiSet's portal offers when one is created. Its
 * layout isn't documented, so both likely ones are accepted: a header row above the values, or
 * one "label,value" pair per line. Values are found by their labels ("Client ID",
 * "clientSecret", "Name", ...) in any order. Returns null when no ID and secret can be found.
 */
fun parseCredentialCsv(csv: String): MultiSetCredential? {
  val lines = csv.removePrefix("﻿").lines().filter { it.isNotBlank() }
  val first = lines.firstOrNull() ?: return null
  // Spreadsheets in some locales export with semicolons or tabs instead of commas.
  val separator = listOf(',', ';', '\t').maxBy { candidate -> first.count { it == candidate } }
  val rows = lines.map { splitCsvLine(it, separator) }

  val labels = rows[0].map(::fieldOf)
  val pairs =
      if (Field.Id in labels && Field.Secret in labels) {
        labels.zip(rows.getOrNull(1) ?: return null)
      } else {
        rows.map { row -> fieldOf(row[0]) to row.getOrElse(1) { "" } }
      }
  val values = mutableMapOf<Field, String>()
  for ((field, value) in pairs) {
    if (field != null) values.putIfAbsent(field, value)
  }

  val id = values[Field.Id].orEmpty()
  val secret = values[Field.Secret].orEmpty()
  if (id.isEmpty() || secret.isEmpty()) return null
  return MultiSetCredential(values[Field.Name].orEmpty(), id, secret)
}

/** Which value a label stands for: "Client ID", "client_secret" and "Name" all count. */
private fun fieldOf(label: String): Field? {
  val key = label.lowercase().filter { it.isLetterOrDigit() }
  return when {
    "secret" in key -> Field.Secret
    key.endsWith("id") -> Field.Id
    "name" in key -> Field.Name
    else -> null
  }
}

/** Splits one CSV line into trimmed cells; a quoted cell may hold separators and doubled quotes. */
private fun splitCsvLine(line: String, separator: Char): List<String> {
  val cells = mutableListOf<String>()
  val cell = StringBuilder()
  var quoted = false
  var i = 0
  while (i < line.length) {
    val c = line[i]
    when {
      c == '"' && quoted && line.getOrNull(i + 1) == '"' -> {
        cell.append('"')
        i++
      }
      c == '"' -> quoted = !quoted
      c == separator && !quoted -> {
        cells += cell.toString().trim()
        cell.clear()
      }
      else -> cell.append(c)
    }
    i++
  }
  cells += cell.toString().trim()
  return cells
}
