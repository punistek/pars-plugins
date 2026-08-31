# PARS ortak altyapı entegrasyonu

Bu paket kullanıcının mevcut reposu baz alınarak hazırlanmıştır.

## Eklenenler
- `common` Android library modülü.
- `ParsUrl`: HTTP/HTTPS URL doğrulama ve origin üretimi.
- `ChannelNormalizer`: HD/FHD/UHD gibi kalite eklerini yok sayan sabit kanal anahtarı ve stable ID.
- `HlsQuality`: HLS master playlist içindeki varyantları çözme ve yüksek kaliteden düşüğe sıralama.
- `DomainCandidates`: last-good/current/remote/default aday sıralama altyapısı.
- `config/domains.json`: yalnız public site domainleri için şablon. Gizli M3U/list URL koymayın.
- `config/channel-rules.json`: ileride uzaktan hide/rename için güvenli şablon.
- `common` unit testleri ve GitHub Actions test adımı.

## IzleMac entegrasyonu
- Kanal tekrarları kalite eki normalize edilerek azaltılır.
- Yayın URL'si master M3U8 ise 1080p/720p vb. varyantlar CloudStream'e ayrı kalite olarak gönderilir.
- Media/mono playlist ise eski tek-link davranışı aynen korunur.

## Bilerek henüz bağlanmayanlar
- Remote `channel-rules.json` indirme: mevcut provider davranışını uzaktan yanlış bir config ile bozma riski nedeniyle yalnız altyapı/şablon bırakıldı.
- Otomatik domain değiştirme: ArdaSpor'da `mainUrl`, API Origin/Referer ve ayrı `channelApi` birbirine bağlı. Canlı doğrulama yapılmadan domaini otomatik değiştirmek yayını bozabilir. `DomainCandidates` hazırdır; güvenli verify katmanı sonraki provider bazlı adımda bağlanabilir.
- Providerlar arası kanal birleştirme: CloudStream providerları ayrı MainAPI olarak çalıştığı için ortak repo seviyesinde gerçek birleştirme yapılamaz. IzleMac içindeki duplicate normalizasyonu uygulanmıştır.

## Güvenlik
OpenSheet, gerçek kaynak M3U registry'si veya gizli liste URL'si eklenmemiştir.
