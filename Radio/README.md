# PARS Radyo Deposu

PARS uygulaması için kurulabilir radyo provider deposu.

## Repo girişi
Uygulamaya GitHub RAW `repo.json` adresi eklenir.

## Yapı
- `repo.json`: depo tanımı
- `plugins.json`: eklenti kataloğu
- `plugins/*/provider.json`: her providerın bağımsız manifesti

İlk providerlar: Türkiye, Almanya, Avusturya, Dünya, Pop, Rock, Haber.

Provider manifestleri yayın URL'lerini depolamaz. PARS'ın yerleşik `radio_browser`
motoruna sorgu tanımı verir; böylece providerlar APK güncellemeden kurulup kaldırılabilir.
