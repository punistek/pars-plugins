# PARS CloudStream Plugin Deposu

Tek repo altında film, dizi ve canlı yayın CloudStream eklentileri.

## PARS eklentileri
- FilmMakinesi
- FilmizleHell
- ShowTV
- Tizam
- DiziFilmizle
- IzleMac
- TEST
- TEST2

## TurkSpor kaynak paketinden eklenenler
- ArdaSpor
- BeyazElma
- Crex
- InatBox
- InatTV
- InterSporTV
- MacKeyfi
- MahsunSports
- SelcukSports
- Taraftarium24
- TurkSporDestek
- ZbahisTV

`AslanTV` özellikle dahil edilmemiştir.

## Ortak yapı
- `common/`: PARS ortak Kotlin modülü
- `turkspor-core/common/`: TurkSpor ortak kaynakları
- `turkspor-core/shared/`: ilgili TurkSpor providerlarının ortak katmanı
- `domains.json`: TurkSpor domain/fallback manifesti
- `channel-rules.json`: TurkSpor kanal kuralları
- `config/`: PARS config dosyaları
- `assets/providers/`: provider ikonları

## Lisans / atıf
TurkSpor kökenli modüllerin orijinal yazar bilgileri korunmuştur. Bu modüller GPL-3.0
kapsamındadır. Ayrıntılar için `THIRD_PARTY_NOTICES.md` ve
`LICENSE-TURKSPOR-GPL-3.0` dosyalarına bakın.
