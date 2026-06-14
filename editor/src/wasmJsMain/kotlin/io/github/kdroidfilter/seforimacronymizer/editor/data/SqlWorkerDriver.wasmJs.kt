package io.github.kdroidfilter.seforimacronymizer.editor.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import org.w3c.dom.Worker

internal actual fun createSqlWorkerDriver(): SqlDriver = WebWorkerDriver(Worker(sqljsWorkerUrl()))

// Resolve against the page origin (works in dev at http://localhost:8080/ and under the Pages
// subpath). Not import.meta.url, which is a file:// path under the dev server.
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun sqljsWorkerUrl(): String =
    js("new URL('sqljs.worker.js', document.baseURI).href")
