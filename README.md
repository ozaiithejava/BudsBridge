# Buds Bridge
OnePlus Buds 3 (CN) için Fast Pair tarzı pop-up, bildirim ve widget köprüsü.

## APK üretme
1. Bu klasörü GitHub'a push et -> Actions > "Build APK" > artifact `buds-bridge-debug-apk`.
2. Veya Android Studio'da aç -> Build > Build APK(s).

## İlk kurulum
Uygulamayı aç -> "İzinleri ver" (Bluetooth, Bildirim, Overlay) -> "Demo" ile arayüzü test et.
OxygenOS: Ayarlar > Uygulamalar > Buds Bridge > Pil > "Kısıtlama yok" yap.

## Önemli
`Protocol.kt` MOCK'tur. Gerçek batarya/ANC paketleri için HCI snoop log + HeyMelody ile
frame'leri yakalayıp `Protocol.kt` ve `HeadsetService.SPP_UUID` değerlerini güncelle.
