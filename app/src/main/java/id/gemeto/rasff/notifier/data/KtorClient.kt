package id.gemeto.rasff.notifier.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

val ktorClient = HttpClient(CIO)