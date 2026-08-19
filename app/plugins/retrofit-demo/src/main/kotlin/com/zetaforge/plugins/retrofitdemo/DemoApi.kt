package com.zetaforge.plugins.retrofitdemo

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Ordinary Retrofit service interface.
 *
 * Nothing here is ZetaForge specific: this is the exact code you would write in
 * a normal app module, which is the point of the experiment.
 */
interface DemoApi {

    /** `GET https://httpbin.org/get` - echoes headers and query parameters. */
    @GET("get")
    fun get(): Call<ResponseBody>

    /** Used by the failure scenario: `GET /status/{code}` returns that HTTP code. */
    @GET("status/{code}")
    fun status(@Path("code") code: Int): Call<ResponseBody>
}
