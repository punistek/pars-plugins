UNCUTMAZA CLOUDSTREAM PROVIDER

Yapı:
- Ana sayfa: article.thumb-block.video-preview-item
- Sayfalama: /page/2/, /page/3/ ...
- Arama: /?s=ARAMA
- Detay başlığı: h1.entry-title
- Poster: og:image
- Gerçek video: meta[itemprop=contentUrl]
- Fallback: video/source src
- Player başlıkları: Referer=https://uncutmaza.cc/ + normal Chrome User-Agent

ÖNEMLİ:
Bu paket mevcut CloudStream extensions repo yapısında "UncutMaza" modülü olarak
kullanılmak üzere hazırlanmıştır. Repo kökündeki settings.gradle.kts içindeki
includeAll() sistemi modülleri otomatik alıyorsa klasörü doğrudan köke koy.

Log etiketi:
UNCUTMAZA

Beklenen log:
UNCUTMAZA MAIN items=...
UNCUTMAZA LOAD ...
UNCUTMAZA LINKS direct[0]=https://cdn...mp4
