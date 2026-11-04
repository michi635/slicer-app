# Slicer-App für Android

Eine schlanke Android-App (Handy und Tablet), die STL-Dateien für Anycubic Kobra S1 und Kobra 3 Max slict –
mit der Slicing-Engine von [OrcaSlicer](https://github.com/OrcaSlicer/OrcaSlicer), auf der auch Anycubic Slicer Next aufbaut.
Alles läuft offline auf dem Gerät.

**Installieren:** die neueste `slicer-app.apk` unter [Releases](../../releases/latest) herunterladen und öffnen.

## Aufbau

| Ordner | Inhalt |
| --- | --- |
| `engine/` | Engine-Build: OrcaSlicer-Version (`orca.env`), Android-Patches, Build-Skripte |
| `engine/core/` | `slicercore`, ein kleines Programm um OrcaSlicers `libslic3r`; die App startet es als eigenen Prozess |
| `engine/profiles/` | eigene Zusatzprofile (z. B. Extrudr), beim Build in OrcaSlicers Profile eingefügt |
| `android/` | Android-App (Kotlin, Jetpack Compose) |
| `.github/workflows/` | Baut Engine und APK auf GitHub Actions |

## Lizenz und Herkunft

AGPL-3.0, wie OrcaSlicer. Die Android-Anpassungen der Abhängigkeiten stammen aus
[LucasCoraca/orcaslicer-android](https://github.com/LucasCoraca/orcaslicer-android) (AGPL-3.0),
siehe `engine/patches/README.md`.
