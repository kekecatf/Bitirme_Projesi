package com.example.bitirmeprojesi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sqrt

// -----------------------------------------------------------------------
// VERİ MODELİ
// -----------------------------------------------------------------------
data class YapayZekaSonucu(
    val profil: String,
    val gun: Int,
    val ay: Int,
    val antrenman: String,
    val beslenme: String
)

// -----------------------------------------------------------------------
// SAF KOTLIN MAKİNE ÖĞRENMESİ MOTORU
// (Chaquopy/Python gerektirmez — sklearn'den çıkarılan parametreler)
// -----------------------------------------------------------------------
object FitnessMotoru {

    // --- StandardScaler parametreleri (sklearn'den alındı) ---
    private val scalerMean = doubleArrayOf(
        0.5252,   // Cinsiyet
        38.6835,  // Yaş
        172.2580, // Boy (cm)
        73.8547,  // Kilo (kg)
        65.6675,  // Hedef Kilo
        10.2312,  // Antrenman Süresi (Ay) — sabit 12 ile gönderilir
        3.3217    // Haftalık Gün — sabit 4 ile gönderilir
    )
    private val scalerScale = doubleArrayOf(
        0.4994,
        12.1747,
        12.7654,
        21.1966,
        9.8198,
        8.5516,
        0.9126
    )

    // --- KMeans küme merkezleri (ölçeklendirilmiş uzayda, sklearn'den alındı) ---
    private val kumeMerkezleri = arrayOf(
        doubleArrayOf(-0.4152,  0.0114, -0.5036, -0.2681, -0.5026,  1.8304,  1.3719), // Küme 0
        doubleArrayOf(-1.0517, -0.0247, -0.6004, -0.6005, -0.5956, -0.4176, -0.2880), // Küme 1
        doubleArrayOf( 0.9508,  0.1853, -0.1738,  0.4118, -0.2014, -0.4140, -0.3954), // Küme 2
        doubleArrayOf( 0.9508, -0.1451,  1.3729,  0.6852,  1.3914,  0.0636,  0.0904)  // Küme 3
    )

    // --- Profil ve tavsiye tablosu ---
    private val tavsiyeler = mapOf(
        0 to Triple(
            "Fit / Kas Kazanımı Odaklı",
            "Haftada 4-5 gün hipertrofi odaklı ağırlık antrenmanı yapılması önerilir. Compound hareketler (squat, deadlift, bench press) temel alınmalıdır.",
            "Protein ağırlıklı beslenme diyeti uygulanmalıdır. Günlük protein hedefi vücut ağırlığının 1.8-2.2 katı gram olmalıdır."
        ),
        1 to Triple(
            "Hafif Kilo Kaybı / Sıkılaşma",
            "Haftada 3 gün ağırlık antrenmanı ve antrenman sonu 20 dakika HIIT kardiyo önerilir.",
            "Hafif kalori açığı (300-500 kcal/gün) uygulanmalıdır. Kompleks karbonhidratlar ve yüksek protein tüketimine özen gösterilmelidir."
        ),
        2 to Triple(
            "Yüksek Kilo Kaybı (Erkek Odaklı)",
            "Haftada 3 gün serbest ağırlık temel hareketleri ve orta tempo kardiyo önerilir. Süreçte yoğunluk kademeli artırılmalıdır.",
            "Kas kaybını önlemek için yüksek proteinli kalori açığı diyeti uygulanmalıdır. Günlük kalori açığı 500 kcal'ı geçmemelidir."
        ),
        3 to Triple(
            "Yüksek Kilo Kaybı (Uzun Boy Odaklı)",
            "Haftada 3-4 gün eklem dostu LISS kardiyo ve tam vücut makine egzersizleri önerilir.",
            "Sürdürülebilir kalori açığı ile bol lifli besinler tüketilmelidir. Aşırı kısıtlamadan kaçınılmalıdır."
        )
    )

    // --- Küme atama (Öklid mesafesi ile en yakın merkez) ---
    private fun kumeAta(cinsiyet: Int, yas: Int, boy: Float, kilo: Float, hedef: Float): Int {
        // Sabit değerler (eğitim verisindeki varsayılan girdiler gibi)
        val ham = doubleArrayOf(
            cinsiyet.toDouble(), yas.toDouble(),
            boy.toDouble(), kilo.toDouble(), hedef.toDouble(),
            12.0, 4.0
        )
        // StandardScaler uygula
        val olcekli = DoubleArray(7) { i -> (ham[i] - scalerMean[i]) / scalerScale[i] }

        // En yakın küme merkezini bul
        var enYakinKume = 0
        var enKucukMesafe = Double.MAX_VALUE
        for (k in 0..3) {
            val mesafe = olcekli.indices.sumOf { i ->
                val fark = olcekli[i] - kumeMerkezleri[k][i]
                fark * fark
            }.let { sqrt(it) }
            if (mesafe < enKucukMesafe) {
                enKucukMesafe = mesafe
                enYakinKume = k
            }
        }
        return enYakinKume
    }

    // --- Gün / Ay tahmini (RF'den çıkarılan kural tabanlı yaklaşım) ---
    private fun gunAyTahmin(kumeNo: Int, kilo: Float, hedef: Float): Pair<Int, Int> {
        val kilofark = kilo - hedef

        return when (kumeNo) {
            0 -> {
                // Küme 0: Fit/Kas — sabit yüksek gün, uzun süre
                val gun = 5
                val ay = when {
                    kilofark <= 0  -> 25
                    kilofark <= 5  -> 27
                    kilofark <= 10 -> 27
                    else           -> 25
                }
                Pair(gun, ay)
            }
            1 -> {
                // Küme 1: Hafif kilo kaybı
                val gun = 3
                val ay = when {
                    kilofark <= 0  -> 6
                    kilofark <= 5  -> 7
                    kilofark <= 10 -> 7
                    kilofark <= 20 -> 7
                    else           -> 8
                }
                Pair(gun, ay)
            }
            2 -> {
                // Küme 2: Yüksek kilo kaybı (erkek)
                val gun = when {
                    kilofark in 10.0..20.0 -> 3
                    else -> 3
                }
                val ay = when {
                    kilofark <= 0  -> 6
                    kilofark <= 5  -> 6
                    kilofark <= 10 -> 7
                    kilofark <= 20 -> 9
                    else           -> 7
                }
                Pair(gun, ay)
            }
            else -> {
                // Küme 3: Uzun boy / yüksek kilo
                val gun = when {
                    kilofark <= 0  -> 3
                    kilofark <= 5  -> 4
                    kilofark <= 10 -> 4
                    else           -> 3
                }
                val ay = when {
                    kilofark <= 0  -> 11
                    kilofark <= 5  -> 17
                    kilofark <= 10 -> 20
                    kilofark <= 20 -> 12
                    else           -> 7
                }
                Pair(gun, ay)
            }
        }
    }

    // --- Ana fonksiyon (eski Python'daki tahmin_et'in birebir Kotlin karşılığı) ---
    fun tahminEt(cinsiyet: Int, yas: Int, boy: Float, kilo: Float, hedef: Float): YapayZekaSonucu {
        val kumeNo = kumeAta(cinsiyet, yas, boy, kilo, hedef)
        val (gun, ay) = gunAyTahmin(kumeNo, kilo, hedef)
        val tavsiye = tavsiyeler[kumeNo]!!

        return YapayZekaSonucu(
            profil    = tavsiye.first,
            gun       = gun,
            ay        = ay,
            antrenman = tavsiye.second,
            beslenme  = tavsiye.third
        )
    }
}

// -----------------------------------------------------------------------
// ACTIVITY & COMPOSABLE (Chaquopy tamamen kaldırıldı)
// -----------------------------------------------------------------------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FitnessUygulamasiEkrani()
                }
            }
        }
    }
}

@Composable
fun FitnessUygulamasiEkrani() {
    var yas           by remember { mutableStateOf("24") }
    var boy           by remember { mutableStateOf("175") }
    var mevcutKilo    by remember { mutableStateOf("80") }
    var hedefKilo     by remember { mutableStateOf("75") }
    var cinsiyetErkekMi by remember { mutableStateOf(true) }

    var aiSonucu    by remember { mutableStateOf<YapayZekaSonucu?>(null) }
    var hataMesaji  by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "AI Fitness Asistanı",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Cinsiyet seçimi
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cinsiyet: ", fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.width(8.dp))
            RadioButton(selected = cinsiyetErkekMi, onClick = { cinsiyetErkekMi = true })
            Text("Erkek")
            Spacer(modifier = Modifier.width(16.dp))
            RadioButton(selected = !cinsiyetErkekMi, onClick = { cinsiyetErkekMi = false })
            Text("Kadın")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = yas, onValueChange = { yas = it },
            label = { Text("Yaş") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = boy, onValueChange = { boy = it },
            label = { Text("Boy (cm)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = mevcutKilo, onValueChange = { mevcutKilo = it },
            label = { Text("Mevcut Kilo (kg)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = hedefKilo, onValueChange = { hedefKilo = it },
            label = { Text("Hedef Kilo (kg)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                hataMesaji = ""
                aiSonucu = null

                val yasInt   = yas.toIntOrNull()
                val boyFloat = boy.toFloatOrNull()
                val kiloF    = mevcutKilo.toFloatOrNull()
                val hedefF   = hedefKilo.toFloatOrNull()

                if (yasInt == null || boyFloat == null || kiloF == null || hedefF == null) {
                    hataMesaji = "Lütfen tüm alanları doğru doldurun."
                } else {
                    // Tamamen senkron — arka plan thread'ine gerek yok
                    aiSonucu = FitnessMotoru.tahminEt(
                        cinsiyet = if (cinsiyetErkekMi) 1 else 0,
                        yas      = yasInt,
                        boy      = boyFloat,
                        kilo     = kiloF,
                        hedef    = hedefF
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Bana Özel Program Oluştur")
        }

        if (hataMesaji.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(hataMesaji, color = MaterialTheme.colorScheme.error)
        }

        // Sonuç kartı
        aiSonucu?.let { sonuc ->
            Spacer(modifier = Modifier.height(24.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "💡 Profil Tipi: ${sonuc.profil}",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 18.sp
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text("📊 Makine Öğrenmesi Tahminleri:", fontWeight = FontWeight.SemiBold)
                    Text("• Önerilen Antrenman: Haftada ${sonuc.gun} Gün")
                    Text("• Ulaşma Süresi: ${sonuc.ay} Ay")

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("🏋️ Antrenman Önerisi:", fontWeight = FontWeight.SemiBold)
                    Text(sonuc.antrenman)

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("🥗 Beslenme Önerisi:", fontWeight = FontWeight.SemiBold)
                    Text(sonuc.beslenme)
                }
            }
        }
    }
}