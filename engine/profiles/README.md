# Eigene Zusatzprofile

Werden beim Build in OrcaSlicers Herstellerprofile eingefügt (`engine/scripts/merge-profiles.py`).

## Extrudr (`OrcaFilamentLibrary/filament/Extrudr/`)

OrcaSlicer bringt keine Extrudr-Profile mit. Diese hier erben OrcaSlicers Grundprofil des jeweiligen Materials
(Kühlung, Rückzug usw.) und setzen Extrudrs veröffentlichte Temperaturbereiche:
Düsentemperatur in der Mitte des Bereichs, Betttemperatur für eine PEI-Platte. Quellen:
[Extrudr-Übersicht bei SimplyPrint](https://simplyprint.io/filaments/extrudr),
[Datenblatt PLA NX2](https://cdn.shopify.com/s/files/1/0754/2171/2708/files/PLA_NX2.pdf).
Nach den ersten Drucken je Material nachjustieren.
