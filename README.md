# WeltKarte – hochauflösende Karte für Paper 26.3

Reines Server-Plugin: **Spieler brauchen keinen Mod.**

## Was es kann

| Funktion | Wie |
|---|---|
| **Minimap links auf dem Bildschirm** | Die Karte liegt in der Zweithand. Dadurch zeigt Minecraft sie links an. Standard: **1 Pixel = 1 Block**, also ist jeder Block zu sehen. Beim Betreten des Servers bekommt man sie automatisch, sonst mit `/karte`. |
| **Zoom per Taste** | **[F]** zoomt hinein (bis 1 Block = 4×4 Pixel), **[Shift]+[F]** zoomt heraus (bis 1 Pixel = 16 Blöcke). Die Taste lässt sich ändern: *Optionen → Steuerung → Tastenbelegung → „Gegenstände in Hände tauschen“*. |
| **Web-Karte der ganzen Welt** | Im Browser unter `http://SERVER-IP:8100`: die ganze Welt Block für Block, bis 32-fach vergrößerbar. Mit Pins, Städten, Gebäude-Flächen, Wegsteinen, Live-Spielerpositionen und Suche. |
| **/karte control** (nur Admin/Owner) | Steuerungsmenü: Ort, Stadt, Gebäude oder Gebäude-Fläche markieren, Wegstein setzen, Entwurf verwalten, Welt rendern und **„Karte updaten“** |
| **Karten-Editor im Browser** (nur Admin) | Im Menü auf „Karten-Editor im Browser“ klicken. Dann bekommst du einen persönlichen Link, der 2 Stunden gilt. Dort setzt du Pins per Klick, verschiebst sie mit der Maus, ziehst Gebäude-Flächen auf und änderst Farben, Besitzer und Beschreibungen. Danach klickst du auf **„Karte updaten“**. |
| **Entwurf → „Karte updaten“** | Alles, was du markierst, landet zuerst in einem Entwurf, den nur Admins sehen. Erst mit „Karte updaten“ bekommt **jeder** Spieler die neue Karte (Minimap, Web-Karte und `/karte orte`). |
| **Wegsteine** | Ein Wegstein ist ein Magnetit-Block. Ein Spieler klickt ihn einmal an und hat ihn damit entdeckt. Steht er an einem Wegstein, kann er sich zu jedem entdeckten Wegstein teleportieren. In `/karte orte` reist er mit einem Rechtsklick auf einen Ort zum nächsten Wegstein dort. Mit einem Linksklick startet er die Navigation: ein Pfeil in der Aktionsleiste, und das Ziel erscheint auf der Minimap. |

## Installation

1. `WeltKarte-1.0.0.jar` in den Ordner `plugins/` legen und den Server neu starten.
2. **Port 8100 freigeben** (TCP), damit die Web-Karte erreichbar ist. Bei Hostern heißt das oft „zusätzlicher Port“.
3. In `plugins/WeltKarte/config.yml` unter `web.public-url` die Adresse eintragen, z. B. `http://123.45.67.89:8100`. Diese Adresse steht in den Links, die man im Chat anklicken kann.
4. Im Spiel `/karte render 5000` ausführen. Damit wird alles im Umkreis von 5000 Blöcken gerendert, was schon generiert ist. Danach wächst die Karte automatisch mit, wenn Spieler neue Gebiete erkunden oder bauen.

Java 25 wird benötigt (wie für Paper 26.x).

## Befehle

| Befehl | Wer | Was |
|---|---|---|
| `/karte` | alle | Karte in die linke Hand legen |
| `/karte orte` | alle | Liste aller Orte: Navigation starten oder per Wegstein reisen |
| `/karte web` | alle | Link zur Web-Karte |
| `/karte zoom <+\|-\|1-7>` | alle | Zoom der Minimap einstellen |
| `/karte stop` | alle | Navigation beenden |
| `/karte control` | Admin | Steuerungsmenü |
| `/karte editor` | Admin | Link zum Browser-Editor |
| `/karte pin <Name> [stadt\|gebaeude]` | Admin | Pin an der eigenen Position setzen |
| `/karte wegstein <Name>` | Admin | Wegstein setzen |
| `/karte update` | Admin | Karte für alle veröffentlichen |
| `/karte render [Radius] [Welt\|spawn\|stop\|status]` | Admin | Welt komplett rendern |

Alias: `/map`, `/weltkarte`

**Rechte:** `weltkarte.use` (Standard: alle), `weltkarte.admin` (Standard: OP), `weltkarte.hidden` (der Spieler erscheint nicht auf der Web-Karte).

## Hinweise

* Admins können Wegsteine nur abbauen, wenn sie dabei schleichen. Spieler und Explosionen können sie nicht zerstören.
* Wegsteine, die du im Browser setzt, werden beim „Karte updaten“ automatisch als Magnetit auf die oberste Blockhöhe gestellt.
* Die Karten-Kacheln liegen unter `plugins/WeltKarte/tiles/`, die Markierungen in `plugins/WeltKarte/markers.json`.
* Die Minecraft-Karte im Spiel hat technisch 128×128 Pixel. Deshalb zeigt die Minimap bei 1 Pixel = 1 Block einen Ausschnitt von 128×128 Blöcken um den Spieler. Die **ganze** Welt siehst du auf der Web-Karte.

## Selbst bauen

```bash
mvn package
```
