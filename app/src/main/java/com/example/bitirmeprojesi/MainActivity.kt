package com.example.bitirmeprojesi

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// =====================================================================
//  FITNESS PERFORMANS SINIFI (A-D) TAHMINI — cihaz-ici (offline)
// ---------------------------------------------------------------------
//  Model: Gercek "Body performance Data" (Kore National Fitness Award)
//  uzerinde egitilmis 25-agac RandomForest. assets/rf_model.json'dan
//  yuklenir; Kotlin tarafinda olasilik-ortalamasi (soft voting) ile
//  degerlendirilir (sklearn ile %99.96 uyumlu). 5-kat CV dogrulugu ~%72.6.
//  Sentetik/uydurma veri YOKTUR; tum egitim gercek olcumlerdendir.
//
//  Ozellik sirasi (x): [Yas, Cinsiyet, Boy, Kilo, BMI, Yag, Esneklik, Mekik]
//  (Ablation: Pence ve Atlama cikarildi; dogruluk -2.2p, app sadelesti.)
// =====================================================================

class Agac(val f: IntArray, val t: DoubleArray, val l: IntArray, val r: IntArray, val p: Array<DoubleArray?>)
class RFModel(val classes: List<String>, val trees: List<Agac>, val cvAcc: Double)

object Performans {
    @Volatile private var model: RFModel? = null

    fun yukle(ctx: Context): RFModel {
        model?.let { return it }
        val js = ctx.assets.open("rf_model.json").bufferedReader().use { it.readText() }
        val o = JSONObject(js)
        val ca = o.getJSONArray("classes")
        val classes = List(ca.length()) { ca.getString(it) }
        val cv = o.optDouble("cv_accuracy", 0.0)
        val tarr = o.getJSONArray("trees")
        val trees = ArrayList<Agac>(tarr.length())
        for (ti in 0 until tarr.length()) {
            val to = tarr.getJSONObject(ti)
            val f = to.getJSONArray("f"); val t = to.getJSONArray("t")
            val l = to.getJSONArray("l"); val r = to.getJSONArray("r"); val p = to.getJSONArray("p")
            val n = f.length()
            val fa = IntArray(n) { f.getInt(it) }
            val ta = DoubleArray(n) { t.getDouble(it) }
            val la = IntArray(n) { l.getInt(it) }
            val ra = IntArray(n) { r.getInt(it) }
            val pa = arrayOfNulls<DoubleArray>(n)
            for (i in 0 until n) if (!p.isNull(i)) {
                val pj = p.getJSONArray(i); pa[i] = DoubleArray(pj.length()) { pj.getDouble(it) }
            }
            trees.add(Agac(fa, ta, la, ra, pa))
        }
        return RFModel(classes, trees, cv).also { model = it }
    }

    /** Soft voting: agac olasiliklarini topla, en yuksek sinifi dondur. */
    fun tahminEt(m: RFModel, x: DoubleArray): Pair<String, DoubleArray> {
        val agg = DoubleArray(m.classes.size)
        for (a in m.trees) {
            var n = 0
            while (a.f[n] != -2) n = if (x[a.f[n]] <= a.t[n]) a.l[n] else a.r[n]
            val pr = a.p[n] ?: continue
            for (i in agg.indices) agg[i] += pr[i]
        }
        val sum = agg.sum()
        val probs = DoubleArray(agg.size) { if (sum > 0) agg[it] / sum else 0.0 }
        var bi = 0; for (i in agg.indices) if (agg[i] > agg[bi]) bi = i
        return m.classes[bi] to probs
    }
}

class Besin(val ad: String, val kalori: Int, val protein: Double, val karb: Double?, val yag: Double?)
class BeslenmePlan(val kalori: Int, val protein: Int, val karb: Int, val yag: Int)

object Beslenme {
    @Volatile private var foods: Map<String, List<Besin>>? = null
    val AKTIVITE = linkedMapOf("Düşük" to 1.375, "Orta" to 1.55, "Yüksek" to 1.725)

    fun yukle(ctx: Context): Map<String, List<Besin>> {
        foods?.let { return it }
        val js = ctx.assets.open("beslenme_foods.json").bufferedReader().use { it.readText() }
        val kat = JSONObject(js).getJSONObject("kategoriler")
        val m = LinkedHashMap<String, List<Besin>>()
        val keys = kat.keys()
        while (keys.hasNext()) {
            val key = keys.next(); val arr = kat.getJSONArray(key)
            val list = ArrayList<Besin>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Besin(o.getString("ad"), o.getInt("kalori"), o.getDouble("protein"),
                    if (o.isNull("karb")) null else o.getDouble("karb"),
                    if (o.isNull("yag")) null else o.getDouble("yag")))
            }
            m[key] = list
        }
        return m.also { foods = it }
    }

    private fun bmr(cinsiyet: Int, kg: Double, cm: Double, yas: Int): Double =
        10.0 * kg + 6.25 * cm - 5.0 * yas + if (cinsiyet == 1) 5.0 else -161.0

    fun plan(cinsiyet: Int, kg: Double, cm: Double, yas: Int, aktF: Double, hedef: String): BeslenmePlan {
        val tdee = bmr(cinsiyet, kg, cm, yas) * aktF
        val kal = when (hedef) { "ver" -> tdee - 500; "al" -> tdee + 350; else -> tdee }
        val pf = when (hedef) { "ver" -> 2.0; "al" -> 1.8; else -> 1.6 }
        val protein = Math.round(pf * kg).toInt()
        val yag = Math.round(0.27 * kal / 9.0).toInt()
        val karb = maxOf(0, Math.round((kal - protein * 4 - yag * 9) / 4.0).toInt())
        return BeslenmePlan(Math.round(kal).toInt(), protein, karb, yag)
    }

    fun kategoriAd(key: String): String = when (key) {
        "Protein Kaynaklari" -> "Protein Kaynakları"
        "Sebzeler (Dusuk Kalori)" -> "Sebzeler (Düşük Kalori)"
        "Saglikli Yaglar" -> "Sağlıklı Yağlar"
        else -> key
    }
}

class Egzersiz(val ad: String, val ekipman: String, val seviye: String)
class Program(val kaslar: Map<String, List<Egzersiz>>, val kardiyo: List<Egzersiz>)

object Antrenman {
    @Volatile private var data: Pair<Map<String, List<Egzersiz>>, List<Egzersiz>>? = null

    fun yukle(ctx: Context): Pair<Map<String, List<Egzersiz>>, List<Egzersiz>> {
        data?.let { return it }
        val js = ctx.assets.open("antrenman_exercises.json").bufferedReader().use { it.readText() }
        val o = JSONObject(js)
        val kasObj = o.getJSONObject("kaslar")
        val kaslar = LinkedHashMap<String, List<Egzersiz>>()
        val keys = kasObj.keys()
        while (keys.hasNext()) {
            val key = keys.next(); val arr = kasObj.getJSONArray(key)
            val list = ArrayList<Egzersiz>(arr.length())
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                list.add(Egzersiz(e.getString("ad"), e.getString("ekipman"), e.getString("seviye")))
            }
            kaslar[key] = list
        }
        val kArr = o.getJSONArray("kardiyo")
        val kardiyo = ArrayList<Egzersiz>(kArr.length())
        for (i in 0 until kArr.length()) {
            val e = kArr.getJSONObject(i)
            kardiyo.add(Egzersiz(e.getString("ad"), e.getString("ekipman"), e.getString("seviye")))
        }
        return (kaslar to kardiyo).also { data = it }
    }

    private fun seviyeler(sinif: String): Set<String> = when (sinif) {
        "A", "B" -> setOf("Orta", "İleri")
        "C" -> setOf("Başlangıç", "Orta")
        else -> setOf("Başlangıç")
    }

    fun program(kaslar: Map<String, List<Egzersiz>>, kardiyo: List<Egzersiz>, sinif: String, hedef: String): Program {
        val lvls = seviyeler(sinif)
        val perMuscle = if (hedef == "al") 2 else 1
        val secim = LinkedHashMap<String, List<Egzersiz>>()
        for ((kas, list) in kaslar) {
            val uygun = list.filter { it.seviye in lvls }.ifEmpty { list }
            secim[kas] = uygun.take(perMuscle)
        }
        val kardiyoN = when (hedef) { "ver" -> 3; "koru" -> 1; else -> 0 }
        val kSec = kardiyo.filter { it.seviye in lvls }.ifEmpty { kardiyo }.take(kardiyoN)
        return Program(secim, kSec)
    }
}

private fun sinifYorum(s: String): String = when (s) {
    "A" -> "Çok iyi — üst düzey fiziksel uygunluk. Mevcut performansı koruyun."
    "B" -> "İyi — ortalamanın üzerinde. Hedefli çalışmayla A seviyesine ilerlenebilir."
    "C" -> "Orta — geliştirilebilir alanlar var; düzenli antrenman önerilir."
    else -> "Düşük — düzenli antrenman ve esneklik/kor çalışmasıyla belirgin gelişim mümkün."
}
private fun sinifRenk(s: String): Color = when (s) {
    "A" -> Color(0xFF2E7D32); "B" -> Color(0xFF1565C0); "C" -> Color(0xFFEF6C00); else -> Color(0xFFC62828)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Ekran() } } }
    }
}

@Composable
fun Ekran() {
    val ctx = LocalContext.current
    // Model (1 MB JSON) ana thread'i bloklamasin: arka planda (IO) yuklenir.
    val modelState by produceState<Result<RFModel>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { runCatching { Performans.yukle(ctx) } }
    }
    val model = modelState?.getOrNull()
    val yukleniyor = modelState == null
    val foods by produceState<Map<String, List<Besin>>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { runCatching { Beslenme.yukle(ctx) }.getOrNull() }
    }
    val antrenman by produceState<Pair<Map<String, List<Egzersiz>>, List<Egzersiz>>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { runCatching { Antrenman.yukle(ctx) }.getOrNull() }
    }

    var erkek by remember { mutableStateOf(true) }
    var yas by remember { mutableStateOf("30") }
    var boy by remember { mutableStateOf("172") }
    var kilo by remember { mutableStateOf("70") }
    var yag by remember { mutableStateOf("22") }
    var esneklik by remember { mutableStateOf("15") }
    var mekik by remember { mutableStateOf("40") }

    var sinif by remember { mutableStateOf<String?>(null) }
    var olasilik by remember { mutableStateOf<DoubleArray?>(null) }
    var bmiTxt by remember { mutableStateOf("") }
    var hata by remember { mutableStateOf("") }
    var hedef by remember { mutableStateOf("ver") }
    var aktivite by remember { mutableStateOf("Orta") }
    var plan by remember { mutableStateOf<BeslenmePlan?>(null) }
    var program by remember { mutableStateOf<Program?>(null) }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Fitness Performans Sınıfı Tahmini", fontSize = 22.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Text(
            when {
                yukleniyor -> "Model yükleniyor…"
                model != null -> "Cihaz-içi model · gerçek veri · ~%${(model.cvAcc * 100).toInt()} CV doğruluğu"
                else -> "HATA: model (assets/rf_model.json) yüklenemedi"
            },
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (yukleniyor) { Spacer(Modifier.height(10.dp)); CircularProgressIndicator() }
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cinsiyet: ", fontWeight = FontWeight.SemiBold)
            RadioButton(selected = erkek, onClick = { erkek = true }); Text("Erkek")
            Spacer(Modifier.width(12.dp))
            RadioButton(selected = !erkek, onClick = { erkek = false }); Text("Kadın")
        }
        @Composable fun alan(v: String, on: (String) -> Unit, lbl: String) {
            OutlinedTextField(v, on, label = { Text(lbl) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        alan(yas, { yas = it }, "Yaş")
        alan(boy, { boy = it }, "Boy (cm)")
        alan(kilo, { kilo = it }, "Kilo (kg)")
        alan(yag, { yag = it }, "Vücut Yağ Oranı (%)")
        alan(esneklik, { esneklik = it }, "Esneklik / Otur-Uzan (cm)")
        alan(mekik, { mekik = it }, "Mekik (adet/dk)")

        Spacer(Modifier.height(2.dp))
        Text("Beslenme hedefi:", fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            listOf("ver" to "Kilo Ver", "koru" to "Koru", "al" to "Kilo Al").forEach { (v, lbl) ->
                RadioButton(selected = hedef == v, onClick = { hedef = v }); Text(lbl, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
            }
        }
        Text("Aktivite düzeyi:", fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Beslenme.AKTIVITE.keys.forEach { a ->
                RadioButton(selected = aktivite == a, onClick = { aktivite = a }); Text(a, fontSize = 13.sp)
                Spacer(Modifier.width(10.dp))
            }
        }
        Spacer(Modifier.height(10.dp))

        Button(
            onClick = {
                hata = ""; sinif = null; olasilik = null; plan = null; program = null
                val m = model
                val y = yas.toDoubleOrNull(); val b = boy.toDoubleOrNull(); val k = kilo.toDoubleOrNull()
                val yg = yag.toDoubleOrNull()
                val es = esneklik.toDoubleOrNull(); val mk = mekik.toDoubleOrNull()
                if (m == null) {
                    hata = "Model yüklenemedi."
                } else if (y == null || b == null || k == null || yg == null ||
                           es == null || mk == null) {
                    hata = "Lütfen tüm alanları geçerli doldurun."
                } else if (b <= 0.0 || k <= 0.0) {
                    hata = "Boy ve kilo sıfırdan büyük olmalı."
                } else {
                    val bmi = k / ((b / 100.0) * (b / 100.0))
                    bmiTxt = "BMI = ${"%.1f".format(bmi)}"
                    // Sira: [Yas, Cinsiyet, Boy, Kilo, BMI, Yag, Esneklik, Mekik]
                    val x = doubleArrayOf(y, if (erkek) 1.0 else 0.0, b, k, bmi, yg, es, mk)
                    val (s, p) = Performans.tahminEt(m, x); sinif = s; olasilik = p
                    plan = Beslenme.plan(if (erkek) 1 else 0, k, b, y.toInt(),
                        Beslenme.AKTIVITE[aktivite] ?: 1.55, hedef)
                    antrenman?.let { (kaslar, kardiyo) ->
                        program = Antrenman.program(kaslar, kardiyo, s, hedef)
                    }
                }
            },
            enabled = model != null, modifier = Modifier.fillMaxWidth()
        ) { Text("Performans Sınıfını Tahmin Et") }

        if (hata.isNotEmpty()) { Spacer(Modifier.height(12.dp)); Text(hata, color = MaterialTheme.colorScheme.error) }

        val s = sinif
        if (s != null) {
            Spacer(Modifier.height(20.dp))
            Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(8.dp)) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Tahmini Performans Sınıfı", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(s, fontSize = 52.sp, fontWeight = FontWeight.Bold, color = sinifRenk(s))
                    Text(bmiTxt, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(sinifYorum(s), fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    olasilik?.let { p ->
                        Text("Sınıf olasılıkları:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        model?.classes?.forEachIndexed { i, c ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(c, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp), color = sinifRenk(c))
                                Box(
                                    Modifier.weight(1f).height(14.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(7.dp))
                                ) {
                                    Box(
                                        Modifier.fillMaxWidth(p[i].toFloat().coerceIn(0f, 1f)).height(14.dp)
                                            .background(sinifRenk(c), RoundedCornerShape(7.dp))
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text("%${(p[i] * 100).toInt()}", fontSize = 12.sp, modifier = Modifier.width(40.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Not: Tahmin, gerçek ulusal fitness verisiyle eğitilmiş cihaz-içi bir modeldir; " +
                        "tıbbi/uzman değerlendirmesi yerine geçmez.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        plan?.let { pl ->
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Beslenme Önerisi (kural-tabanlı)", fontWeight = FontWeight.Bold,
                        fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                    Text("Günlük hedef: ${pl.kalori} kcal", fontWeight = FontWeight.SemiBold)
                    Text("Protein ${pl.protein} g · Karbonhidrat ${pl.karb} g · Yağ ${pl.yag} g", fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("Örnek yiyecekler (100 g başına):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    val cats = when (hedef) {
                        "ver" -> listOf("Protein Kaynaklari", "Sebzeler (Dusuk Kalori)", "Saglikli Yaglar")
                        "al" -> listOf("Protein Kaynaklari", "Kompleks Karbonhidrat", "Saglikli Yaglar")
                        else -> listOf("Protein Kaynaklari", "Kompleks Karbonhidrat", "Sebzeler (Dusuk Kalori)")
                    }
                    val fm = foods
                    if (fm != null) {
                        cats.forEach { c ->
                            fm[c]?.let { lst ->
                                Spacer(Modifier.height(6.dp))
                                Text(Beslenme.kategoriAd(c), fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                lst.take(3).forEach { f ->
                                    Text("• ${f.ad} — ${f.kalori} kcal, P ${f.protein} g", fontSize = 12.sp)
                                }
                            }
                        }
                    } else {
                        Text("(yiyecek listesi yüklenemedi)", fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Not: Kalori Mifflin-St Jeor formülüyle; yiyecek değerleri USDA verisinden. " +
                        "Öneri kural-tabanlıdır, tıbbi tavsiye değildir.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        program?.let { prog ->
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(6.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Antrenman Önerisi (kural-tabanlı)", fontWeight = FontWeight.Bold,
                        fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                    Text(when (hedef) {
                        "ver" -> "Odak: yağ yakımı — tam vücut + kardiyo"
                        "al" -> "Odak: kas/güç — hipertrofi ağırlıklı"
                        else -> "Odak: form koruma — dengeli"
                    }, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    prog.kaslar.forEach { (kas, lst) ->
                        if (lst.isNotEmpty()) {
                            Text(kas, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary)
                            lst.forEach { e -> Text("• ${e.ad}  (${e.ekipman} · ${e.seviye})", fontSize = 12.sp) }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    if (prog.kardiyo.isNotEmpty()) {
                        Text("Kardiyo", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary)
                        prog.kardiyo.forEach { e -> Text("• ${e.ad}  (${e.ekipman})", fontSize = 12.sp) }
                        Spacer(Modifier.height(4.dp))
                    }
                    Text("Not: Egzersizler gerçek katalogdan (megaGym); öneri kural-tabanlıdır. " +
                        "Sakatlık/sağlık durumunda uzman gözetimi gerekir.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
