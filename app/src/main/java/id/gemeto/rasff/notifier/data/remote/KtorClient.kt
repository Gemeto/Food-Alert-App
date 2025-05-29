package id.gemeto.rasff.notifier.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint

val ktorClient = HttpClient(CIO){
    engine {
        endpoint {
            maxConnectionsPerRoute = 200
        }
    }
}