package com.example.bitirmeprojesi.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

// --- VERİ MODELLERİ (DATA CLASSES) ---

// Python'a göndereceğimiz veri (Girdi - X)
data class KullaniciIstek(
    val cinsiyet: Int,       // 1: Erkek, 0: Kadın
    val yas: Int,
    val boy_cm: Float,
    val kilo_kg: Float,
    val hedef_kilo: Float
)

// Python'dan dönecek sonuç (Çıktı - y ve Uzman Tavsiyesi)
data class ApiCevap(
    val Durum: String,
    val Atanan_Kume_No: Int,
    val Profil_Tipi: String,
    val Makine_Ogrenmesi_Tahmini: Tahmin,
    val Uzman_Tavsiyesi: Tavsiye
)

data class Tahmin(
    val Onerilen_Haftalik_Gun: Int,
    val Spora_Devam_Etmesi_Gereken_Sure_Ay: Int
)

data class Tavsiye(
    val Antrenman: String,
    val Beslenme: String
)

// --- RETROFIT KURULUMU ---

interface FitnessApiService {
    // "tahmin_al" endpoint'ine POST isteği atarız
    @POST("tahmin_al")
    suspend fun tahminAl(@Body istek: KullaniciIstek): ApiCevap
}

object ApiClient {
    // DİKKAT: Emulator üzerinden bilgisayara bağlanmak için 10.0.2.2 kullanıyoruz.
    private const val BASE_URL = "http://10.0.2.2:8000/"

    val retrofit: FitnessApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FitnessApiService::class.java)
    }
}