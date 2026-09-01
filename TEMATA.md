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
  `MainActivity.kt`) — běží pořád na pozadí, dokud je analýza spuštěná,
  nezávisle na tlačítku "Nahrát WAV". Tlačítko "Uložit posledních 30 s"
  ho zpětně uloží jako WAV do Downloads/Ulzvu — řeší situaci, kdy si
  uživatel něčeho všimne až POTÉ, co to proběhlo.

### 3.6 Otevřené TODO (až přijde zpětná vazba na v2/v3)

- Ověřit, jestli oprava crash bugu skutečně vyřešila "nejde nahrát WAV".
- Podívat se do `ulzvu_log.txt` po prvním testu — hledat cokoliv na úrovni
  ERROR, hlavně kolem nahrávání a AudioRecord.
- Doladit `MOTION_STDDEV_THRESHOLD` u vibrometru podle reálného chování na
  Oppu (moc citlivé / málo citlivé na "Pohyb ruší měření").
- Až bude Motorola z servisu, otestovat probing (jestli dostane vyšší
  vzorkování než Oppo — střední třída má šanci na 96 kHz, kde Oppo možná
  spadne na 48 kHz).
- Zúžit detekční filtr podle 4.4 (úzkopásmový + perzistentní tón místo
  širokopásmového prahu), až bude čas — teď potvrzeno reálným testem
  (sykavky v řeči), že široký práh dává falešné poplachy.

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
