# hardware/ — the enclosures

The two shells shown in the video. Both are printable as-is.

| File | Triangles |
|---|---|
| `PolyMO_BMO.stl` | 169,102 |
| `PolyMO_C0F-E.stl` | 138,560 |

**The zero in `C0F-E` is deliberate — do not "fix" it.** The source CAD spells
the same shape `COF-E`, with the letter O, so the two look like a typo sitting
next to each other and the STL looks like the wrong one. It is not. The name is
stylised, in the way a robot's name is.

## What goes inside

A **Waveshare ESP32-S3 Touch-AMOLED-1.8** board and a LiPo cell on the board's
MX1.25 header. A 1300 mAh cell measured **21 h 52 m** on one charge, at about
59 mA average; a 500 mAh cell gives roughly six hours, which is not a day.

The board's own PWR and BOOT buttons stay reachable — PWR switches the pet off
and on, and BOOT held for five seconds is how a dead pet is reset without a
phone in the room.

## What is not here

**Print settings.** Layer height, orientation, supports, infill and material
were never written down, so nothing is claimed about them. If you print one and
work out a good recipe, that is worth contributing back.

**Source CAD.** These are exported meshes, not editable models.

## A note on the shapes

`BMO` is a fan shape, named after the character it resembles. The face set and
persona of the same name are likewise an homage. Nothing here is affiliated
with or endorsed by the rights holders of that character.
