package id.gemeto.rasff.notifier.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.HttpTimeout

val ktorClient = HttpClient(CIO){
    engine {
        endpoint {
            maxConnectionsPerRoute = 200
        }
    }
}