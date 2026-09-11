# TEMATA — ulzvu

Průběžné poznámky z konverzace: co a proč jsme se domluvili, technické parametry,
otevřené otázky. Udržovat jako živý dokument — doplňovat, ne přepisovat historii.

Poslední aktualizace: 2026-09-01.

---

## 1. Cíl projektu

Android appka na detekci/nahrávání ultrazvuku telefonním mikrofonem, primárně
kvůli podezření na **parametrické ultrazvukové směrové reproduktory** (viz #4).
Vedlejší větev: vibrometr na tep srdce (přes akcelerometr, ne mikrofon).

## 2. Testovací hardware

- **Oppo A18** — Android 14, MediaTek Helio G85 (nízká třída). K dispozici teď.
- **Motorola Edge 60 Fusion** — Android 15. V servisu, zatím netestováno.
- Fyzický strop mikrofonu na obou: reálně cca **22–24 kHz** i po odejmutí krytu
  a s `UNPROCESSED` zdrojem — MEMS mikrofon má citlivost navrženou pro slyšitelné
  pásmo, nad ~25 kHz je na hranici šumu bez ohledu na software. Podrobný rozbor
  (vzorkovací frekvence OS, antialiasing filtry, akustický design) proběhl na
  začátku konverzace — appka si při startu sama zjišťuje, co telefon reálně
  dovolí (`AudioProber` zkouší 192/96/48/44,1 kHz × `UNPROCESSED`/`MIC`).

## 3. Appka Ulzvu — repo `grrrrshadow/ulzvu`

### 3.1 Stav

- **v1** (`bb75dfb`) — základní appka: probing konfigurace, živé spektrum,
  heuristická detekce, nahrávání WAV.
- **v2** (`fec2a3c`) — opravy + nové funkce. **Uživatel to ještě netestoval**,
  nic dalšího v appce neměnit, dokud nepřijde zpětná vazba.

### 3.2 Bugy nalezené v v1 a opravené ve v2

- Špička ("Špička: X Hz") se počítala přes celé spektrum → dominoval jí běžný
  hluk (ruka, hlas, okolí) v nízkých Hz → skákala mezi ~70–170 Hz, vypadalo to
  jako závada. Opraveno: počítá se jen v pásmu >17 kHz.
- Alarm "ULTRAZVUK" blikal na jeden náhodný rámec (např. klepnutí, pohyb ruky
  vytvoří širokopásmový impulz, který na okamžik projede i přes práh v pásmu
  >17 kHz). Opraveno: debounce, potřeba 5 rámců za sebou nad/pod prahem.
- **Reálný crash bug**: zastavení nahrávání mohlo zavřít PCM soubor zrovna ve
  chvíli, kdy do něj analyzační vlákno zapisovalo → neošetřená výjimka na
  libovolném vlákně → Android potichu zabije celý proces, appka zmizí beze
  stopy. To byl pravděpodobně skutečný důvod za "nejde nahrát WAV, nejde appku
  ověřit". Opraveno zámkem (`recordingLock`) + try/catch kolem I/O + globální
  crash handler.

### 3.3 Nové funkce ve v2

- **EventLog** — každá chyba a každý start/konec detekce se s časem na vteřinu
  zapisuje přímo do `Stažené soubory/Ulzvu/ulzvu_log.txt` (normální Downloads,
  ne appka-interní úložiště — uživatel to výslovně chtěl takhle). V appce
  obrazovka "Log" (zobrazení + vymazání).
- **Vibrometr (tep)** — nová obrazovka, akcelerometr + stejná FFT logika jako
  ultrazvuk (modul `core`, jen jiné pásmo: 0,7–3,5 Hz = 42–210 BPM). Motivace:
  uživatel si stěžoval, že mu vadí poskakující ruka s telefonem při měření —
  napadlo měřit tep místo boje s tím jako s rušením. Obsahuje kontrolu pohybu
  (směrodatná odchylka okna > práh → "Pohyb ruší měření" místo nesmyslného
  čísla). Práh (`MOTION_STDDEV_THRESHOLD` v `HeartRateActivity.kt`) je zatím
  jen odhad, čeká na doladění podle reálného zařízení.
- Modul `core` (FFT, spektrální analýza, WAV hlavička) je sdílený mezi
  ultrazvukem i vibrometrem, čistý Kotlin/JVM bez závislosti na Androidu —
  jde reálně zkompilovat a otestovat i v tomhle vývojovém sandboxu
  (`./gradlew :core:test`), na rozdíl od `app` modulu, který potřebuje
  Android SDK (`dl.google.com` je v sandboxu zablokovaný, appka se tu
  nedá sestavit — proto GitHub Actions workflow pro CI build).
- 4/4 testů v `core` prochází: 21 kHz tón, 1 kHz tón, WAV hlavička, 72 BPM.

### 3.4 v3 (nepushnuto do samostatné verze, navazuje na v2, také ještě neotestováno)

- Vibrometr přestal být samostatná obrazovka — sloučen přímo do hlavní
  obrazovky (`MainActivity`): spektrum ultrazvuku a BPM se zobrazují a
  logují souběžně, spouští/zastavuje je jedno tlačítko "Spustit analýzu".
  Uživatel řekl, že samostatné zobrazení není potřeba.
- Záznam o incidentu ultrazvuku v logu teď obsahuje i aktuální tep
  (`... · Tep: NN BPM`) — na žádost uživatele, ať jde z logu rovnou vidět
  souvislost mezi detekcí a tepem, ne jen čas + frekvenci zvlášť.
- `HeartRateActivity`/`activity_heart_rate.xml` smazány, appka má teď jen
  dvě obrazovky: hlavní (ultrazvuk + tep) a Log.

### 3.5 UI úpravy po prvním živém testu appky (uživatel zkoušel v2 naostro)

- Test: čtení české abecedy nahlas u appky ukázalo false-positive detekce
  na písmenech se sykavkou (C, F, S, X — všechny mají /s/-like hlásku ve
  výslovnosti). Potvrzuje, že širokopásmový práh nad 17 kHz reaguje i na
  běžnou řeč, ne jen na potenciální ultrazvuk — viz 4.4, návrh na zúžení
  filtru (úzkopásmový + perzistentní signál) zůstává otevřený, zatím
  neimplementováno.
- Layout přeuspořádán: tep přesunut nad spektrum (hned pod konfiguraci),
  pod ním živý výpis posledních řádků logu — uživatel to chtěl mít na očích
  bez nutnosti otevírat samostatnou obrazovku Log.
- `EventLog`: INFO úroveň (tep každých 500 ms, konfigurace, start/stop) se
  už nezapisuje do `ulzvu_log.txt` — jen se živě zobrazuje v appce. Do
  souboru jde jen WARN/ERROR (incidenty, chyby), aby časté BPM řádky
  nezaplavily soubor a nezatlačily incidenty pryč z pohledu.
- Poslední incident ultrazvuku je připíchnutý ve zvláštním řádku (červeně,
  tučně) nad běžným výpisem logu, aby nezmizel pod novějšími řádky.
- **Kruhový buffer posledních 30 s** (`REWIND_BUFFER_SECONDS` v
  `MainActivity.kt`) — běží pořád na pozadí, dokud je analýza spuštěná.
  Řeší situaci, kdy si uživatel něčeho všimne až POTÉ, co to proběhlo.
- Zjednodušeno na jedno tlačítko: původní samostatné "Nahrát WAV"
  (dopředné nahrávání) i samostatné "Uložit posledních 30 s" zrušeny,
  nahrazeny jedním tlačítkem **"Uložit 30 s (zvuk + log)"** —
  `saveRewindBundle()` uloží zvukový kruhový buffer i posledních 30 s
  logu (i INFO úroveň, ne jen to, co jde do `ulzvu_log.txt`) jako dvojici
  spárovaných souborů se stejným časovým razítkem: `zvuk_<datum>.wav` +
  `log_<datum>.txt` do Downloads/Ulzvu.

### 3.6 v4 — foreground service (nejdůležitější architektonická oprava)

Uživatel zjistil zásadní bug: appka nenahrávala na pozadí. Android po pár
vteřinách odebere přístup k mikrofonu obyčejnému vlákcu v appce, která
není ve foregroundu — to je tvrdé systémové omezení (Android 9/10+),
ne nastavení baterie/úspory energie. Kruhový buffer běžel dál, ale plnil
se tichem, dokud appku znovu neotevřeli — proto bylo slyšet jen posledních
pár vteřin nahrávky.

**Oprava:** veškerá logika záznamu (`AudioRecord`, analýza, detekce,
akcelerometr/tep, kruhový buffer, ukládání) přesunuta z `MainActivity` do
nové **`UlzvuService`** — foreground service s `android:foregroundServiceType
="microphone"` (povinné od Android 14/API 34, náš `targetSdk`). Appka:
- Manifest: přidána oprávnění `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`.
- `MainActivity` je teď jen tenká UI vrstva — v `onStart()`/`onStop()` se
  váže/odvazuje na service (`bindService`/`unbindService`), zatímco je
  appka viditelná pollingem (200 ms) čte publikovaný stav service a
  aktualizuje views. Service běží dál nezávisle na vazbě, dokud ji
  uživatel nezastaví tlačítkem "Zastavit analýzu" (`stopCapture()`).
- Notifikace (`NotificationChannel` IMPORTANCE_LOW, beze zvuku) — povinná
  součást foreground service, appka ji ukazuje, dokud běží nahrávání.
- Vedlejší oprava na stejné hlášení: doba trvání incidentu byla v ms
  ("trval 1230 ms"), uživatel chtěl vteřiny na 2 desetinná místa
  ("trval 1.23 s") — milisekundy prý nikdo neumí odhadnout.

**Netestováno** — čeká na vyzkoušení na Oppu (vypnout appku z popředí,
počkat, otevřít, zmáčknout Uložit, zkontrolovat že zvuk pokrývá celých
30 s, ne jen pár vteřin od návratu do appky).

### 3.7b Oprava pádu — appka spadla hned při "Spustit analýzu"

Uživatel nahlásil, že appka po v5 (viz 3.8 níže) spadla IHNED při
"Spustit analýzu", ještě předtím, než se vůbec dotkl My Noise. Příčina:
`UlzvuService` měla v manifestu `foregroundServiceType="microphone|mediaProjection"`
oba typy pohromadě. Android ale u `mediaProjection` typu vyžaduje, aby
souhlas (`MediaProjectionManager.createScreenCaptureIntent()`) existoval
**předtím**, než se foreground service s tímhle typem vůbec spustí — a
appka `UlzvuService` startovala hned na "Spustit analýzu", kdy o žádný
MediaProjection souhlas ještě nikdo nepožádal. `startForeground()` proto
spadl s `SecurityException`.

**Oprava:** rozděleno na dvě samostatné services:
- `UlzvuService` — zpátky jen `foregroundServiceType="microphone"`,
  žádná závislost na MediaProjection. Tohle je přesně to, co fungovalo
  před přidáním My Noise.
- Nová `PlaybackCaptureService` — jen `foregroundServiceType="mediaProjection"`,
  startuje se výhradně přes Intent s `resultCode`/`data` z už získaného
  souhlasu (`MainActivity` je pošle jako extras, service si teprve uvnitř
  zavolá `getMediaProjection()` a až pak `startForeground()`).
- `MainActivity` se teď váže na obě services zvlášť. Tlačítko "Uložit 30 s"
  před uložením synchronně vytáhne aktuální buffer z `PlaybackCaptureService`
  (pokud běží) a pošle ho do `UlzvuService.requestSaveRewindBundle(...)`,
  která dál dělá všechny tři soubory jako doteď.

**Potvrzeno funkční** — analýza po opravě běžela v pořádku (spadala jen
kvůli 3.7b, teď ne).

### 3.7c Oprava — "My Noise není nainstalovaná" i když je

Po 3.7b appka běžela, ale záznam z My Noise hlásil, že appka není
nainstalovaná, i když ji uživatel měl (verze 3.21.77). Příčina: od
Androidu 11 (API 30) platí **omezení viditelnosti balíčků** (package
visibility) — appka bez explicitního povolení v manifestu nevidí
existenci jiných appek přes `PackageManager.getApplicationInfo()`, i
když jsou reálně nainstalované. Náš `targetSdk 34` tomu plně podléhá.

**Oprava:** přidán `<queries><package android:name="com.mynoise.mynoise" /></queries>`
do `AndroidManifest.xml` — appka teď smí zjistit, že My Noise existuje.

**Netestováno** — čeká na další pokus.

### 3.8 v5 — potvrzení uložení + samostatný záznam z My Noise

- Tlačítko "Uložit 30 s" teď hned po zmáčknutí ukáže na sobě "Ukládám…"
  a po dokončení "Uloženo ✓" na 5 vteřin — uživatel si nebyl jistý, jestli
  se klik zaregistroval.
- Nový nezávislý druhý zvukový záznam: co hraje appka **My Noise**
  (`com.mynoise.mynoise`, verze 3.21.77 z Google Play) do sluchátek —
  cíleně jen ta appka, přes Android `AudioPlaybackCaptureConfiguration`
  + `addMatchingUid()` (zjištěné přes `PackageManager`), ne obecně
  všechno na telefonu.
  - Vyžaduje `MediaProjection` souhlas (Android nemá zvlášť "jen zvuk"
    variantu — jde přes dialog vypadající jako "nahrávání obrazovky"),
    znovu při každém zapnutí přes tlačítko "Zapnout záznam My Noise".
  - Manifest: `FOREGROUND_SERVICE_MEDIA_PROJECTION` navíc,
    `foregroundServiceType="microphone|mediaProjection"`.
  - Vlastní 30s kruhový buffer nezávislý na mikrofonu, pevně 48 kHz
    (výstup přehrávání, ne test ultrazvuku — nemá smysl řešit vyšší
    vzorkování jako u mikrofonu).
  - "Uložit 30 s" teď uloží až tři soubory se stejným razítkem:
    `zvuk_<datum>.wav` (mikrofon) + `mynoise_<datum>.wav` (jen když je
    zapnuto) + `log_<datum>.txt`.
  - Pokud My Noise není nainstalovaná nebo capture selže, appka to
    krátce ukáže ve stavovém řádku (`consumePlaybackError()`), ne jen
    do logu.
  - **Netestováno** — zejména jestli My Noise capture vůbec nezablokuje
    (některé appky si to zakazují), a jestli MediaProjection přežije
    přechod appky na pozadí stejně spolehlivě jako mikrofon.

### 3.9 Stav k 2026-09-10: v5 (+ opravy 3.7b/3.7c) potvrzeno funkční na Oppu

Celý řetězec otestován naostro na Oppo A18 a funguje: analýza běží i na
pozadí (foreground service), "Uložit 30 s" ukládá zvuk+log, záznam z My
Noise taky funguje po opravě package visibility. Crash bug (dva
foreground service typy v jedné service) i "My Noise není nainstalovaná"
(package visibility) — oba vyřešené a potvrzené uživatelem.

**Rozhodnutí uživatele:** kvalitu/výkon záznamu (vyšší vzorkování,
případně jiné parametry bufferů) zatím neřešit — Oppo A18 má jen 4 GB
RAM, takže na to počkáme, až bude k dispozici Motorola Edge 60 Fusion
(z servisu, Android 15, střední třída → šance na vyšší vzorkování bez
tlaku na paměť).

### 3.10 Otevřené TODO

- Až bude Motorola z servisu: porovnat probing (vyšší vzorkování než
  Oppo?) a případně zlepšit kvalitu/parametry záznamu — teď to necháváme
  být kvůli 4 GB RAM na Oppu.
- Doladit `MOTION_STDDEV_THRESHOLD` u vibrometru podle reálného chování
  (moc citlivé / málo citlivé na "Pohyb ruší měření") — zatím nezpětná
  vazba.
- Zúžit detekční filtr podle 4.4 (úzkopásmový + perzistentní tón místo
  širokopásmového prahu) — potvrzeno reálným testem (sykavky v řeči), že
  široký práh dává falešné poplachy. Zatím neimplementováno.
- Ověřit, jestli MediaProjection (My Noise záznam) vydrží přechod appky
  na pozadí stejně spolehlivě jako mikrofon (FGS typ "microphone") —
  netestováno explicitně, jen že capture jde spustit a jde uložit.

### 3.11 První analýza reálné nahrávky z Oppa (2026-09-11, `zvuk_20260911_115049.wav`)

Uživatel poslal WAV z mikrofonu (30 s, "Uložit 30 s"). Hlášení: přes
sluchátka naplno slyšel ženský hlas ("givmi"); v záznamu My Noise prý nic;
v záznamu z mikrofonu "v prvních dvou vteřinách" zvuk odpovídající délkou
i obsahem, ale s "velmi odlišnou hloubkou hlasu". Analýza (numpy/scipy,
skript v sandboxu — spektrogram, RMS obálka, autokorelační odhad F0):

**Technické nálezy o Oppo A18:**
- Soubor je 192 kHz, ale nad 24 kHz je jen digitální ticho (−152 dB abs.,
  ostrý pád −56 dB přesně na 24 kHz). **Oppo hlásí 192 kHz, reálně je to
  převzorkovaných 48 kHz — efektivní Nyquist 24 kHz.** Vyšší vzorkování
  na Oppu nic nepřidá, jen 4× větší soubory a paměť (4 GB RAM!). Zvážit
  v `AudioProber` preferovat 48 kHz, pokud se nad 24 kHz nic neobjeví.
- V celé nahrávce je **stálý šumový pás 17–22 kHz** (PSD při 17 kHz jen
  −13 dB pod středem řeči 1–4 kHz, výš než pás 8–17 kHz). Typický
  noise-shaping sigma-delta ADC / vlastní šum MEMS mikrofonu. Přesně to
  je pásmo, které detektor hlídá — adaptivní floor to sice absorbuje, ale
  na tomto telefonu je >17 kHz od přírody hlučnější.
- Slabý přerušovaný tón ~4,8–5 kHz (elektronické pískání, v místnosti
  nebo v telefonu) — vidět jako vodorovná linka ve spektrogramu.

**Co je v nahrávce (čas 0 = 30 s před zmáčknutím, čas 30 = zmáčknutí):**
- **0–2 s: nic.** RMS −55 až −65 dBFS = šumové pozadí (floor −64 dB).
  Spektrálně totožné s prokazatelně tichým úsekem 26–29 s (korelace 0,98,
  průměrný rozdíl 3 dB). Žádná harmonická/hlasová struktura (znělost
  0,35, odhad F0 náhodně skáče 62–400 Hz = šum). Jediné, co tam je:
  jeden klik v 0,88 s a to slabé 4,8kHz pískání.
- **3,44–4,00 s: jediná výrazná událost na začátku** — 0,56 s dlouhý,
  25–30 dB nad floorem, **silně znělý (52/52 rámců, síla 0,75)**, F0
  plynule klouže 137 → 111 → 122 Hz (std 8 Hz, ~20 % drift = ne motor,
  ne brum; motor má std < 2 Hz). Čistý harmonický sloupec, ale **skoro
  všechna energie pod 400 Hz (h1+h2), 1–2 kHz slabě, nad 2 kHz nic.**
  → nízko posazený (mužské pásmo), **tlumený / dolní propustí filtrovaný**
  znělý zvuk — jako hlas přes stěnu/stan/z dálky, nebo vzdálený stroj se
  sklouzávající otáčkou. Délka ~1–2 slabiky. Pravděpodobně TOHLE je zvuk,
  který uživatel označil za "v prvních dvou vteřinách" (3,4 s ≈ začátek)
  a jehož "hloubka" mu neseděla (127 Hz vs. vnímaný ženský hlas 165–255).
- 17,0–18,4 s a 19,6–20,2 s: velmi hlasité (špička −1,5 dB!), **neznělé**
  (0 znělých rámců), širokopásmové 0–24 kHz → rány / manipulace s
  telefonem / náraz, ne hlas.
- V ultrazvukovém pásmu (17–24 kHz) při 3,44–4,0 s NIC nad stálý floor.

**Interpretace pro projekt (technicky, ne diagnosticky):**
- Parametrický reproduktor produkuje demodulovaný zvuk s **velmi slabými
  basy** (známá vlastnost parametrických polí — účinnost demodulace roste
  s frekvencí, pod ~300–500 Hz prakticky nic). Zvuk v 3,44 s je přesný
  opak: skoro jen basy, nic nad 2 kHz. **Tahle konkrétní událost tedy
  neodpovídá parametrickému reproduktoru.** Přidáno jako kritérium do 4.4.
- Vnímaný "ženský hlas" (F0 165–255 Hz) vs. naměřených 127 Hz: rozdíl
  výšky není v nahrávce, je mezi nahrávkou a vjemem. Přes sluchátka
  hrající nahlas projde zvenku hlavně nejsilnější nízkofrekvenční část,
  zbytek si sluch doplňuje — u fráze, kterou člověk čeká, je to známý
  jev (auditory pareidolia / "phantom words"). To je nejúspornější
  vysvětlení shody délky/obsahu při neshodě výšky.

**Chybí k dokončení:** soubor `mynoise_20260911_115049.wav` (ověřit, že
capture vůbec něco nahrál — pokud je celý tichý, My Noise capture
blokuje a "nic v My Noise" nic neznamená), `log_20260911_115049.txt`, a
kolik vteřin po zaslechnutí uživatel zmáčkl Uložit (čas 30 = stisk; při
rychlém stisku by zvuk byl na KONCI souboru, ne na začátku).

## 4. Cílová technologie: parametrické ultrazvukové směrové reproduktory

Rešerše 2026-09-01 (Focusonics, Audfly, Akoustic Arts, Holosonics/Audio
Spotlight — zdroje na konci souboru). Účel: nastavit detekční filtr na
reálné parametry, ne na obecné "cokoliv nad 17 kHz".

### 4.1 Princip

1. Pole piezo měničů vysílá **ultrazvukovou nosnou** (nad 20 kHz) v úzkém
   paprsku (typicky 10–20°, u některých ±30° s poklesem -9 dB už při ±15°).
2. Do nosné je amplitudově namodulovaný slyšitelný signál (hlas/rozkaz).
3. Vzduch je nelineární prostředí — při dostatečné intenzitě (>110 dB SPL
   na 1 m) se paprsek sám demoduluje zpět do slyšitelného pásma, ale **jen
   v místě/směru, kam paprsek fyzicky doletí**. Mimo paprsek není slyšet nic.
4. Žádný přijímač na těle cíle není potřeba — demodulace probíhá ve vzduchu,
   případně přímo ve vzduchu poblíž ucha/hlavy cíle.

### 4.2 Frekvenční parametry — DŮLEŽITÉ PRO FILTR

- **Nosná frekvence napříč trhem: 25–60 kHz, nejčastěji přesně 40 kHz.**
  Důvod: 40 kHz jsou standardní levné piezo ultrazvukové měniče (stejné jako
  v parkovacích senzorech/dálkoměrech) — proto se objevují ve většině
  komerčních i akademických realizací. Dražší/kvalitnější systémy
  (Holosonics, Akoustic Arts) mohou jít výš (45–60 kHz i víc), aby měly
  víc prostoru pro širší audio pásmo.
- Je to AM modulace → vzniká nosná + dvě postranní pásma (nosná ± modulační
  frekvence). Pokud carrier=40 kHz a modulují až 20 kHz audio (viz Audfly
  spec "500 Hz–20 kHz"), **spodní postranní pásmo může sahat dolů až k
  ~20 kHz** — tedy do pásma na hraně toho, co telefonní mikrofon ještě
  slabě zachytí (viz #2, strop ~22–24 kHz).
- **Důsledek: telefon nemá šanci zachytit samotnou nosnou (40+ kHz), ale
  teoreticky může zachytit okraj spodního postranního pásma kolem 18–24 kHz**
  — přesně tam, kam appka už teď cílí detekci. To potvrzuje směr, ale mění,
  co má filtr hledat (viz 4.4).
- Další teoretická cesta k detekci: **nelinearita/intermodulace přímo
  v mikrofonu telefonu** — silný ultrazvukový AM signál dopadající na levný
  MEMS mikrofon může sám o sobě částečně demodulovat uvnitř mikrofonu/
  předzesilovače a vytvořit slabý artefakt už v slyšitelném pásmu, i když
  mikrofon "oficiálně" ultrazvuk nezachytává. (Známý jev z výzkumu útoků
  přes neslyšitelné hlasové příkazy na chytré asistenty.) Nepotvrzeno na
  našem HW, ale stojí za vyzkoušení empiricky (potřeba reálný zdroj
  ultrazvuku k testování, viz #6).

### 4.3 Konkrétní produkty zmíněné uživatelem

| Výrobce | Model | Zjištěno |
|---|---|---|
| Focusonics | (obecně) | 40–100 kHz nosná (obecně u kategorie), přesná hodnota pro konkrétní model nedohledána |
| Audfly | FSC2-L1, FSC5-B2 aj. | audio pásmo 500 Hz–20 kHz, paprsek <15°, SPL 83±3 dB@2m/1kHz |
| Akoustic Arts | Model B / B1 | audio pásmo cca 200 Hz–20 kHz (starší model A: 60 Hz–20 kHz), přesná nosná frekvence nedohledána, DSP 2,4 mld. operací/s |
| Holosonics | Audio Spotlight (AS-18-B aj.) | akademické zdroje zmiňují nosné řádu 45 kHz+ |

Žádný z výrobců veřejně nepublikuje přesnou nosnou frekvenci pro konkrétní
model — běžné u tohoto typu produktu (považováno za obchodní tajemství /
není potřeba pro koncového zákazníka). Pokud bude k dispozici konkrétní
zařízení k testování, nejrychlejší cesta ke zjištění reálné nosné je
změřit ho přímo (spektrální analyzátor / appka Ulzvu s WAV nahrávkou a
offline FFT v Audacity, i když nad 24 kHz to telefon nezachytí — potřeba
buď externí ultrazvukový mikrofon, nebo spolehnout na artefakt v mikrofonu).

### 4.4 Důsledky pro detekční filtr appky — NÁVRH, ZATÍM NEIMPLEMENTOVÁNO

Zápis k budoucí implementaci, čeká na otestování v2 a případně na příležitost
změřit reálné zařízení:

1. **Neucházet se o širokopásmovou energii nad 17 kHz obecně** — to zachytává
   i běžný hluk (klepnutí, mechanické vrzání, řasy/vítr do mikrofonu).
   Charakteristický podpis parametrického reproduktoru je naopak
   **úzkopásmový stabilní tón** (energie soustředěná v 1–2 sousedních FFT
   binech), ne rozprostřený přes široké pásmo.
2. **Perzistence v čase** — nosná běží kontinuálně, dokud je zařízení
   zapnuté. Debounce (5 rámců), který už appka má, jde tímto směrem, ale dá
   se zpřesnit: sledovat stabilitu frekvence bin-to-bin mezi rámci (skutečná
   nosná se nehýbe, náhodný šum ano).
3. **Modulační obálka podobná řeči** — amplituda tónu by měla kolísat
   v rytmu řeči (obálka cca 2–8 Hz), ne konstantně (to by spíš byl přímý
   ultrazvukový zdroj jako čidlo/měnič bez modulace) ani náhodně (šum).
   Dalo by se přidat jako druhé kritérium vedle prahu nad šumovým pozadím.
4. Tohle vše je heuristika pro nepřímý odraz/okraj signálu na hraně 18–24 kHz
   — appka nikdy neuvidí skutečnou 40 kHz nosnou přímo. Nutné nastavit
   očekávání: detekce bude pravděpodobnostní indikátor "něco v okolí je
   podezřelé", ne jistý důkaz konkrétního zařízení.
5. **Vylučovací kritérium — basy.** Parametrický reproduktor má velmi
   slabou odezvu pod ~300–500 Hz (účinnost demodulace ve vzduchu roste
   s frekvencí; výrobci uvádějí spodní hranici 200–500 Hz a i tam je to
   slabé). Pokud má slyšitelná událost v nahrávce těžiště energie pod
   400 Hz a nad 2 kHz skoro nic (jako událost 3,44 s v 3.11), parametrický
   reproduktor jako zdroj prakticky vyloučit. Naopak podezřelý je zvuk
   "tenký", bez basů, s energií 500 Hz–10 kHz.

## 5. Rozhodnutí

- Appka žije ve vlastním repu `grrrrshadow/ulzvu`, ne v `forclaude`
  (openttd repo) — do openttd se nesahá vůbec.
- Log a WAV se ukládají do normálního Android Downloads
  (`Stažené soubory/Ulzvu/…`), ne do appka-interního úložiště.
- Buildy appky pouští výhradně uživatel (lokálně / Android Studio); v tomto
  sandboxu jde reálně spustit a testovat jen modul `core` (bez Android SDK).

## 6. Otevřené otázky pro uživatele

- Máte k dispozici (nebo budete mít) reálné zařízení tohoto typu k testování/
  kalibraci appky? Bez toho je krok 4.4 čistě teoretický.
- Jaký je přesný účel detekce — obrana pracovníka (BOZP kontext zmíněný
  v rešerši výše), ověření přítomnosti zařízení na pracovišti, něco jiného?
  Ovlivňuje to, jestli má appka jen "hlásit podezření" nebo se snažit
  odhadovat směr/vzdálenost zdroje.
- Priorita: dotestovat v2 (bugy, log, vibrometr), nebo rovnou pokračovat na
  detekčním filtru z bodu 4.4?

---

### Zdroje k bodu 4

- [Focusonics — Parametric Speaker](https://www.focusonics.com/parametric-speakers/)
- [Audfly — Ultrasonic Parametric Speakers](https://www.audflyspeaker.com/ultrasonic-parametric-speakers-by-audfly-precision-sound/)
- [Audfly — Model B directional ultrasonic parametric speaker](https://www.audflyspeaker.com/audfly-model-b-directional-ultrasonic-parametric-speaker/)
- [Akoustic Arts — Directional speaker B](https://www.akoustic-arts.com/models/retail)
- [Akoustic Arts B1 — manuál (PDF)](https://www.directionalaudio.co.uk/app/uploads/2024/03/Akoustic-Arts-Model-B-manual.pdf)
- [Holosonics — Audio Spotlight](https://holosonics.com/15-products)
- [ScienceDirect — Acoustic beamforming of a parametric speaker comprising ultrasonic transducers](https://www.sciencedirect.com/science/article/abs/pii/S092442470500364X)
- [ResearchGate — Unidirectional Parametric Speaker (PDF)](https://www.researchgate.net/publication/373014167_Unidirectional_Parametric_Speaker)
- [PMC — Demonstration of a length limited parametric array](https://pmc.ncbi.nlm.nih.gov/articles/PMC6472557/)
