# Eigene Zusatzprofile

Werden beim Build in OrcaSlicers Herstellerprofile eingefügt (`engine/scripts/merge-profiles.py`).

## Extrudr (`OrcaFilamentLibrary/filament/Extrudr/`)

OrcaSlicer bringt keine Extrudr-Profile mit. Diese hier erben OrcaSlicers Grundprofil des jeweiligen Materials
(Kühlung, Rückzug usw.) und setzen Extrudrs veröffentlichte Temperaturbereiche:
Düsentemperatur in der Mitte des Bereichs, Betttemperatur für eine PEI-Platte. Quellen:
[Extrudr-Übersicht bei SimplyPrint](https://simplyprint.io/filaments/extrudr),
[Datenblatt PLA NX2](https://cdn.shopify.com/s/files/1/0754/2171/2708/files/PLA_NX2.pdf).
Nach den ersten Drucken je Material nachjustieren.

## Anycubic Kobra S1: weitere Schichthöhen (`Anycubic/process/`)

OrcaSlicer bringt für den Kobra S1 nur „0.20mm Standard“ mit, für den baugleichen, größeren Kobra S1 Max dagegen
acht Stufen. Die sieben fehlenden S1-Profile (0,08 / 0,12 / 0,16 / 0,16 High Quality / 0,20 High Quality / 0,24 /
0,28 mm) erben das S1-Profil „0.20mm Standard“ und übernehmen nur, was beim S1 Max zwischen „0.20mm Standard“ und der
jeweiligen Stufe anders ist (Schichthöhe, Deckschichten, Geschwindigkeiten, Stützabstand). Anycubic macht es bei seinen
eigenen Profilen (Kobra 3 V2) genauso.

## Qualität „Grosse Platten“ (Kobra S1 und Kobra 3 Max)

Für grosse, flache Teile, die sich sonst vom Bett hochziehen. Das Profil erbt „0.20mm Standard“ und ändert:
20 % Gyroid ohne Füllungskombination, je 4 Boden- und Deckschichten (gleich dicke Häute oben und unten), Brim
aussen 8 mm, erste Schicht langsamer (30 mm/s). Dazu passt `filament_tweaks.json` jedes gewählte Filament an:
Bett +5 °C (erste Schicht +10 °C), Bauteillüfter erst ab Schicht 4 und höchstens 60 %, Seitenlüfter und
Abluft aus, damit es im Gehäuse warm bleibt.

## Weitere Qualitäten (Kobra S1 und Kobra 3 Max)

- **0.16mm Feine Teile** (erbt 0.16mm Standard): Arachne-Wände, dünne Wände und kleine Details werden gedruckt,
  Aussenwand 80 mm/s, kleine Umfänge halb so schnell, Brim 3 mm. Filament: jede Schicht mindestens 10 s.
- **0.20mm Extra stabil** (erbt 0.20mm Standard): 5 Wände, 6 Deck- und 5 Bodenschichten, 40 % Gyroid,
  25 % Überlappung Füllung/Wand. Filament: Düse +5 °C und Lüfter höchstens 70 % für bessere Schichthaftung.
- **0.16mm Überhänge** (erbt 0.16mm Standard): langsamer je steiler der Überhang (60/30/15/10 mm/s),
  Zusatzwand an Überhängen, Brücken 25 mm/s, erst Innen- dann Aussenwand. Filament: Überhang-Lüfter 100 %.
