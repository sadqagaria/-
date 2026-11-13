import com.satelliteworld.tv.data.PlaylistParser
import java.io.StringReader

fun main() {
    val text = """#EXTM3U
#EXTINF:-1 tvg-id="EgyptTV.eg" tvg-logo="https://example.com/logo.png" group-title="Egypt",Egypt TV
https://example.com/live.m3u8
#EXTINF:-1 radio="true" group-title="Egypt",Cairo Radio
https://example.com/radio.mp3
"""
    val a = PlaylistParser.parse(StringReader(text), "testing")
    check(a.size == 2 && a[0].name == "Egypt TV" && a[0].group == "Egypt")
    check(a[1].radio)
    check(PlaylistParser.fromEnigmaNamespace("013E0000") == "31.8°E")
    check(PlaylistParser.cleanSatellite("Nilesat 7W") == "7°W · Nilesat")
    val csv = "name,url,satellite,number\n\"Test, Channel\",https://example.com/tv.m3u8,Nilesat,101"
    val b = PlaylistParser.parseCsv(StringReader(csv), "test-csv")
    check(b.size == 1 && b[0].name == "Test, Channel" && b[0].position == 101)
    val enigma = "#NAME Fav\n#SERVICE 1:0:1:abcd:0000:0000:01680000:0:0:0:\n#DESCRIPTION TV ONE\n"
    val c = PlaylistParser.parseEnigmaBouquet(StringReader(enigma), "bouquet")
    check(c.size == 1 && c[0].streamUrl.isEmpty() && c[0].satellite == "36.0°E")
    check(PlaylistParser.fromEnigmaNamespace("0DCA0000") == "7°W · Nilesat")
    println("PASSED: M3U, CSV, satellite tags, Enigma2 catalog parsing")
}
