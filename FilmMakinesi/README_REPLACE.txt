FilmMakinesi CLEAN FINAL

GitHub'da FilmMakinesi/src/main/kotlin/com/pars/filmmakinesi/ klasöründeki ESKİ .kt dosyalarını önce sil.

Sadece bunlar kalmalı:
- FilmmakinesiPlugin.kt
- FilmmakinesiProvider.kt
- CloseloadFilmmakinesiToExtractor.kt
- RapidFilmmakinesiToExtractor.kt

Özellikle sil:
- CloseLoadExtractor.kt
- FilmMakinesiRapidExtractor.kt
- FilmMakinesi.kt
- FilmMakinesiPlugin.kt

Bu sürümde:
- SubtitleFile için cloudstream3.* importu var.
- generateM3u8 kullanılmıyor.
- HLS / master.txt, ExtractorLinkType.M3U8 ile gönderiliyor.
- CloseLoad + Rapid extractor register ediliyor.
