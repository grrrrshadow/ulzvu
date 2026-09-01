# Ulzvu

Android testovací appka na detekci a nahrávání ultrazvuku telefonním mikrofonem
(Oppo A18 / Android 14, Motorola Edge 60 Fusion / Android 15).

## Co to dělá

- **Zjistí reálný strop telefonu** — zkusí `AudioRecord` postupně na 192 kHz, 96 kHz,
  48 kHz, 44,1 kHz, se zdrojem `UNPROCESSED` (obchází AGC/potlačení šumu/část DSP
  řetězce, pokud to ROM podporuje) i s běžným `MIC`. Použije nejvyšší kombinaci,
  kterou telefon skutečně inicializuje — žádný pevný předpoklad, appka to ověří
  na místě.
- **Živé spektrum** do naměřeného Nyquistova limitu, pásmo nad 17 kHz zvýrazněné.
- **Heuristická detekce** — adaptivní odhad šumového pozadí na bin, alarm při
  převýšení o 12 dB v ultrazvukovém pásmu. Je to orientační test, ne kalibrovaný
  přístroj.
- **Nahrávání do WAV** (`Stažené soubory/Ulzvu/…`) pro pozdější analýzu
  v Audacity/Spectroidu nebo porovnání mezi oběma telefony.
- **Log chyb a incidentů** — každá chyba (výjimka, pád, selhání zápisu) i každý
  začátek/konec detekce ultrazvuku se s časem na vteřinu zapisuje rovnou do
  `Stažené soubory/Ulzvu/ulzvu_log.txt`. V appce je pod tlačítkem „Log“.
- **Vibrometr (tep)** — druhá obrazovka používající akcelerometr telefonu
  (ballistokardiografie): přiložený na zápěstí/hruď zachytí periodický otřes
  tepu. Stejná FFT logika jako u ultrazvuku, jen v pásmu 0,7–3,5 Hz (42–210
  BPM). Při velkém pohybu (poskakující ruka) se měření sama označí jako
  nespolehlivé, místo aby vypsala nesmyslné číslo.

## Fyzické limity (viz rozbor v konverzaci)

I s `UNPROCESSED` zdrojem a vyšším vzorkováním zůstává tvrdý strop v hardwaru:
- OEM audio ovladač může vyšší vzorkování odmítnout (appka to detekuje a spadne
  na nižší frekvenci).
- MEMS mikrofon má citlivost navrženou pro slyšitelné pásmo — nad ~25 kHz je
  i s otevřeným tělem telefonu prakticky na hranici šumu.
- Na 40 kHz zařízení (parkovací senzory, průmyslový ultrazvuk) telefon nestačí
  ani teoreticky — na to je potřeba externí mikrofon (bat detector).

## Struktura projektu

- `core/` — čistý Kotlin/JVM modul: FFT, spektrální analýza, WAV hlavička.
  Bez závislosti na Androidu, takže se dá sestavit a otestovat samostatně
  (`./gradlew :core:test`).
- `app/` — Android appka (Kotlin, minSdk 29, target/compileSdk 34).

## Build

### Lokálně (Android Studio)

Otevřít složku jako projekt, Android Studio si stáhne SDK samo. Sestavení:

```
./gradlew :app:assembleDebug
```

### CI (GitHub Actions)

`.github/workflows/android-build.yml` sestaví debug APK na každém pushi a
uloží ho jako artefakt ke stažení — GitHub runner má na rozdíl od tohoto
vývojového sandboxu neomezený přístup k `dl.google.com`, takže tam Android
SDK stáhne bez problémů.

### Poznámka k tomuto repozitáři

Appka byla napsaná v izolovaném prostředí bez přístupu k `dl.google.com`
(Android SDK). Modul `core` (FFT/detekční logika, sdílená mezi ultrazvukem
i vibrometrem) je proto reálně zkompilovaný a otestovaný přímo tady
(4/4 testů prošlo — 21 kHz tón, 1 kHz tón, WAV hlavička, 1,2 Hz/72 BPM
vibrace). Modul `app` (Android UI, `AudioRecord`, senzory) čeká na první
build přes GitHub Actions nebo Android Studio.

### Známé limity v1.1

- Práh pro rozpoznání pohybu ve vibrometru (`MOTION_STDDEV_THRESHOLD` v
  `HeartRateActivity.kt`) je odhad, ne změřená hodnota — na reálném telefonu
  ho možná bude potřeba doladit nahoru/dolů podle toho, jak moc citlivě
  hlásí „Pohyb ruší měření“.
- Detekce ultrazvuku je pořád heuristika (adaptivní šumové pozadí + práh),
  ne kalibrovaný přístroj — dobrá na „něco tu je/není“, ne na přesnou
  hladinu.
