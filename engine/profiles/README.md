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
