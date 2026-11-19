package com.aurareels.ocrlab

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Local loopback mock for an OpenAI-compatible /v1/chat/completions gateway. */
class CompatibleApiTransportTest {
    @Test fun sendsChatCompletionsWithMultilingualTimedBatchAndMatchesIds() {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val calls=AtomicInteger(0)
        server.createContext("/v1/chat/completions") { exchange ->
            calls.incrementAndGet()
            assertEquals("POST",exchange.requestMethod)
            val req=JSONObject(exchange.requestBody.bufferedReader().readText())
            assertEquals("local-subtitle-model",req.getString("model"))
            assertFalse(req.getBoolean("stream"))
            val messages=req.getJSONArray("messages")
            assertEquals("system",messages.getJSONObject(0).getString("role"))
            val input=JSONObject(messages.getJSONObject(1).getString("content"))
            assertEquals("zh",input.getString("source_language"))
            assertEquals("es",input.getString("target_language"))
            val items=input.getJSONArray("items")
            assertEquals(3,items.length())
            val rows=JSONArray()
            for(i in items.length()-1 downTo 0) rows.put(JSONObject().put("id",items.getJSONObject(i).getString("id"))
                .put("status","ok").put("translation","Traducción $i"))
            val inner=JSONObject().put("schema","translation_batch_result_v1")
                .put("batch_id",input.getString("batch_id")).put("items",rows).toString()
            val response=JSONObject().put("choices",JSONArray().put(JSONObject().put("message",
                JSONObject().put("role","assistant").put("content",inner)))).toString()
            val data=response.toByteArray()
            exchange.sendResponseHeaders(200,data.size.toLong())
            exchange.responseBody.use { it.write(data) }
        }
        server.start()
        try {
            val client=CompatibleApiSubtitleTranslator(CompatibleApiConfig("http://127.0.0.1:${server.address.port}/v1/chat/completions","local-subtitle-model"))
            client.use {
                val rows=(0..2).map { i -> ApiTimedLine("id$i", i*3000L, i*3000L+2000,"测试字幕 $i") }
                val result=it.translateLines(rows,"zh","es")
                assertEquals(listOf("Traducción 0","Traducción 1","Traducción 2"),result.map { row -> row.text })
            }
            assertEquals(1,calls.get())
        } finally { server.stop(0) }
    }
    @Test fun rejectsUnconfiguredInsecureOrMalformedEndpoint() {
        for(uri in listOf("", "http://example.com/v1/chat/completions", "https://user:pass@server.com/v1/chat/completions", "https://example.com/api")) {
            assertThrows(IllegalArgumentException::class.java) { CompatibleApiConfig(uri,"model").validated() }
        }
        assertEquals("gateway.example",CompatibleApiConfig("https://gateway.example/v1/chat/completions","model").validated().host)
    }
    @Test fun recognizesHttpCooldownWithoutLeakingContents() {
        val err=java.util.concurrent.ExecutionException(ProviderHttpException(429,45_000L))
        assertEquals(45_000L,ApiFailure.http(err)!!.retryAfterMs)
        assertEquals("HTTP_429@response",ApiFailure.reason(err))
        assertEquals("CONTRACT_BATCH_ID@response",ApiFailure.reason(ApiContractException("BATCH_ID")))
    }
}

class CompatibleApiContractTest {
    private fun output(batch: String="b",id: String="cue",text: Any="Olá") =
        JSONObject().put("schema","translation_batch_result_v1").put("batch_id",batch).put("items",
            JSONArray().put(JSONObject().put("id",id).put("status","ok").put("translation",text))).toString()
    @Test fun checksBatchAndLineIdsAndUncertain() {
        val line=listOf(ApiTimedLine("cue",0L,1500L,"Hello"))
        assertEquals("Olá",ApiBatchContract.decode(output(),"b",line).single().text)
        assertThrows(ApiContractException::class.java) { ApiBatchContract.decode(output(batch="other"),"b",line) }
        assertThrows(ApiContractException::class.java) { ApiBatchContract.decode(output(id="other"),"b",line) }
        val uncertain=JSONObject().put("schema","translation_batch_result_v1").put("batch_id","b").put("items",
            JSONArray().put(JSONObject().put("id","cue").put("status","uncertain").put("translation",JSONObject.NULL)))
        assertNull(ApiBatchContract.decode(uncertain.toString(),"b",line).single().text)
    }
    @Test fun acceptsStandardChoicesEnvelopeAndRejectsMissingChoices() {
        val api=CompatibleApiSubtitleTranslator(CompatibleApiConfig())
        api.use {
            assertEquals(output(),it.extractAnswer(JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("content",output())))).toString()))
            assertThrows(java.io.IOException::class.java) { it.extractAnswer("{\"choices\":[]}") }
        }
    }
}
