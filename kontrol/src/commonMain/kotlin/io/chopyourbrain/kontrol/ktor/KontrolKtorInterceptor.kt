package io.chopyourbrain.kontrol.ktor

import io.chopyourbrain.kontrol.ServiceLocator
import io.chopyourbrain.kontrol.database.AppDatabase
import io.chopyourbrain.kontrol.repository.DBRepository
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.features.*
import io.ktor.client.features.observer.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.*
import io.ktor.util.date.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import kotlinx.atomicfu.atomic

class KontrolKtorInterceptor(val level: DetailLevel) {

    private suspend fun processRequest(pipeline: PipelineContext<Any, HttpRequestBuilder>) {
        val id = pipeline.context.attributes[requestIdKey]

        val entryBody = RequestBodyEntry()
        val entry = RequestEntry(timestamp = getTimeMillis(), body = entryBody)

        val content = pipeline.context.body as? OutgoingContent?

        if (level.info) {
            entry.url = Url(pipeline.context.url)
            entry.method = pipeline.context.method
        }

        if (level.headers) {
            val headers = mutableMapOf<String, String>()

            val requestHeaders = joinHeaders(pipeline.context.headers.entries())

            val lengthHeader = content?.contentLength?.let { HttpHeaders.ContentLength to it.toString() }
            val typeHeader = content?.contentType?.let { HttpHeaders.ContentType to it.toString() }

            val contentHeaders = content?.headers?.entries()?.let { joinHeaders(it) }

            headers += requestHeaders
            if (lengthHeader != null) headers += lengthHeader
            if (typeHeader != null) headers += typeHeader
            if (contentHeaders != null) headers += contentHeaders

            entry.headers = headers
        }

        val observedContent = if (level.body) {
            val charset = content?.contentType?.charset() ?: Charsets.UTF_8
            val contentType = content?.contentType?.toString()
            val bodyChannel = ByteChannel()

            entryBody.charset = charset
            entryBody.contentType = contentType
            entryBody.bodyChannel = bodyChannel

            content?.observe(bodyChannel)
        } else null

        runCatching {
            pipeline.proceedWith(observedContent ?: pipeline.subject)
        }.onFailure {
            entry.error = it
            consumer.saveRequest(id, entry)
            //throw?
        }.onSuccess {
            consumer.saveRequest(id, entry)
        }
    }

    private suspend fun processReceive(pipeline: PipelineContext<HttpResponse, HttpClientCall>) {
        val id = pipeline.context.attributes[requestIdKey]

        val entry = ResponseEntry()

        if (level.info) {
            entry.status = pipeline.context.response.status
            entry.method = pipeline.context.response.call.request.method
            entry.url = pipeline.context.response.call.request.url
        }

        if (level.headers) {
            entry.headers = joinHeaders(pipeline.context.response.headers.entries())
        }

        consumer.saveResponse(id, entry)

        runCatching {
            pipeline.proceedWith(pipeline.subject)
        }.onFailure {
            consumer.saveResponse(id, it)
            throw it
        }
    }

    private suspend fun processResponse(pipeline: PipelineContext<HttpResponseContainer, HttpClientCall>) {
        val id = pipeline.context.attributes[requestIdKey]

        runCatching {
            pipeline.proceed()
        }.onFailure {
            consumer.saveResponse(id, it)
            throw it
        }
    }

    private suspend fun processResponse(response: HttpResponse) {
        val id = response.call.attributes[requestIdKey]

        val charset = response.contentType()?.charset() ?: Charsets.UTF_8
        val contentType = response.contentType()?.toString()

        val entry = ResponseBodyEntry(
            response.content.tryReadText(charset),
            contentType,
            charset
        )

        consumer.saveResponse(id, entry)
    }

    companion object : HttpClientFeature<Config, KontrolKtorInterceptor> {
        private val requestIdKey = AttributeKey<Long>("RequestID")
        private val requestId = atomic(1000L)

        override val key: AttributeKey<KontrolKtorInterceptor> = AttributeKey("HttpLogging")

        override fun prepare(block: Config.() -> Unit): KontrolKtorInterceptor {
            val config = Config().apply(block)
            val databaseDriverFactory = config.databaseDriverFactory
                ?: throw IllegalStateException("Init databaseDriverFactory")
            ServiceLocator.DBRepository.value = DBRepository(AppDatabase(databaseDriverFactory.createDriver()))
            requestId.value = ServiceLocator.DBRepository.value?.getLastRequestId()?.plus(1L) ?: 1000
            return KontrolKtorInterceptor(config.level)
        }

        override fun install(feature: KontrolKtorInterceptor, scope: HttpClient) {
            scope.requestPipeline.intercept(HttpRequestPipeline.Before) {
                context.attributes.put(requestIdKey, requestId.getAndIncrement())
            }

            scope.sendPipeline.intercept(HttpSendPipeline.Monitoring) {
                feature.processRequest(this)
            }

            scope.receivePipeline.intercept(HttpReceivePipeline.State) {
                feature.processReceive(this)
            }

            scope.responsePipeline.intercept(HttpResponsePipeline.Receive) {
                feature.processResponse(this)
            }

            if (feature.level.body) {
                val observer: ResponseHandler = { response -> feature.processResponse(response) }
                ResponseObserver.install(ResponseObserver(observer), scope)
            }
        }
    }

    class Config {
        var level: DetailLevel = DetailLevel.ALL
        var databaseDriverFactory: io.chopyourbrain.kontrol.DatabaseDriverFactory? = null
    }
}

private fun joinHeaders(entries: Set<Map.Entry<String, List<String>>>): Map<String, String> {
    return entries.sortedBy { it.key }.map {
        it.key to it.value.joinToString("; ")
    }.toMap()
}
