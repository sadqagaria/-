package com.satelliteworld.tv.recording

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashSet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records clear, MPEG-TS HLS segments from a user-accessible stream to a user-picked file.
 * Never decrypts protected video; skips unsupported DASH/fMP4/master manifests with separate audio.
 * Saving a stream may be restricted by law or by a broadcaster's terms. Use only when authorized.
 */
class HlsRecorder(private val context: Context, private val report: (String) -> Unit) {
    private val job = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val ui = Handler(Looper.getMainLooper())
    val active get() = running.get()
    private fun update(s: String) { ui.post { report(s) } }

    fun start(streamUrl: String, document: Uri, targetHeight: Int) {
        if (!running.compareAndSet(false,true)) { update("يوجد تسجيل جارٍ بالفعل"); return }
        job.execute {
            var written = 0
            var total = 0L
            try {
                var url = streamUrl
                if (!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("صيغة الرابط غير صالحة")
                var source = downloadText(url)
                if (!source.lineSequence().firstOrNull()?.contains("#EXTM3U", true).orFalse())
                    throw IOException("المصدر لا يقدّم قائمة HLS قابلة للتسجيل")
                val choices = HlsPlaylist.variants(url, source)
                if (choices.isNotEmpty()) {
                    val selected = HlsPlaylist.choose(choices,targetHeight) ?: throw IOException("لم تُوجد جودة صالحة")
                    // When audio is in a separate rendition, a simple .ts concatenation will omit it.
                    if (selected.audioGroup.isNotBlank() && source.contains("TYPE=AUDIO"))
                        throw IOException("الصوت في مسار منفصل؛ التسجيل الآمن غير مدعوم لهذه القناة")
                    url=selected.url
                    update("التسجيل بجودة المصدر المتاحة: ${selected.height.takeIf { it > 0 } ?: "تلقائية"}p")
                }
                context.contentResolver.openOutputStream(document,"w")?.use { out ->
                    val seen=LinkedHashSet<String>()
                    var errors=0
                    while (running.get()) {
                        try {
                            source=downloadText(url)
                            val media=HlsPlaylist.media(url,source)
                            if (media.encrypted) throw IOException("البث محمي؛ لن يُفك تشفيره أو يُسجل")
                            if (media.fragmentedMp4) throw IOException("البث fMP4 وليس TS؛ التسجيل غير مدعوم")
                            if (media.segments.isEmpty()) throw IOException("ملف البث لا يحتوي على مقاطع TS")
                            val toWrite=media.segments.filter { it !in seen }
                            for (segment in toWrite) {
                                if (!running.get()) break
                                val bytes=downloadBinary(segment)
                                if (bytes.isEmpty() || bytes.size>20_000_000) throw IOException("مقطع بث غير صالح")
                                out.write(bytes)
                                total+=bytes.size
                                written++
                                seen.add(segment)
                                if (seen.size>1400) {
                                    val iterator=seen.iterator()
                                    repeat(200) { if (iterator.hasNext()) {iterator.next();iterator.remove()} }
                                }
                                if (total>2_000_000_000L) {running.set(false);update("توقّف التسجيل عند حدّ 2 GB");break}
                            }
                            out.flush()
                            if (written>0 && written%10==0) update("● تسجيل جارٍ • ${total/1024/1024} MB")
                            errors=0
                            if (media.ended)break
                            if (running.get()) Thread.sleep((media.targetDuration*750L).coerceIn(950,6000))
                        }catch(e:InterruptedException){break}
                        catch(e:Exception){
                            // Fail fast for protected or structurally incompatible streams.
                            if (e.message?.contains("غير مدعوم") == true || e.message?.contains("محمي") == true ||
                                e.message?.contains("تشفير") == true) throw e
                            errors++
                            if (errors>=3) throw e
                            update("الاتصال انقطع؛ إعادة محاولة $errors/3")
                            Thread.sleep(1800)
                        }
                    }
                } ?: throw IOException("لم يمكن إنشاء الملف في المسار المختار")
                if(written==0)throw IOException("لم تُحفظ مقاطع صالحة من البث")
                update("تم حفظ ${total/1024/1024} MB في المكان الذي اخترته")
            } catch(e:Exception) {
                if(written==0) try { context.contentResolver.delete(document,null,null) } catch (_: Exception) {}
                update("تعذّر التسجيل: ${e.message ?: "خطأ في الشبكة أو تنسيق البث"}")
            } finally {running.set(false)}
        }
    }

    fun stop() { if(running.get()) { running.set(false);update("جاري إنهاء التسجيل وحفظ الملف...") } }
    fun shutdown() {stop();job.shutdown()}

    private fun downloadText(url:String):String {
        val bytes=fetch(url, 3_000_000)
        return bytes.toString(Charsets.UTF_8)
    }
    private fun downloadBinary(url:String) = fetch(url,20_000_000)
    private fun fetch(link:String,max:Int):ByteArray {
        if(!link.startsWith("https://") && !link.startsWith("http://")) throw IOException("رابط غير آمن")
        val c=(URL(link).openConnection() as HttpURLConnection).apply {
            connectTimeout=15_000;readTimeout=25_000;instanceFollowRedirects=true
            setRequestProperty("User-Agent","SatelliteWorldTV/0.3 Android")
        }
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.use { stream ->
                val buffer=ByteArray(8192)
                val output=java.io.ByteArrayOutputStream()
                var n:Int
                while(stream.read(buffer).also { n=it }!=-1) {
                    if (output.size()+n>max) throw IOException("مقطع أكبر من الحد المسموح")
                    output.write(buffer,0,n)
                }
                return output.toByteArray()
            }
        } finally {c.disconnect()}
    }
}
private fun Boolean?.orFalse() = this == true
