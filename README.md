# AE2 UEL Things

Type-unlimited storage cells (DISK Cells) addon for AE2 Unofficial Extended Life.

## Features

- **Type-Unlimited Storage**: DISK cells can store an unlimited number of item/fluid types, limited only by available capacity (not AE2's standard 63-type cap)
- **Item & Fluid Variants**: Both item storage and fluid storage cells available
- **4 Capacity Tiers**: 1k, 4k, 16k, and 64k variants for both item and fluid cells
- **Shift+Right-Click Disassembly**: Empty cells can be disassembled back into housing and component

## Storage Capacity

### Item Storage Cells
- **1k DISK Cell**: 1,000 bytes
- **4k DISK Cell**: 4,000 bytes
- **16k DISK Cell**: 16,000 bytes
- **64k DISK Cell**: 64,000 bytes

### Fluid Storage Cells
- **1k DISK Fluid Cell**: 1,000 mB
- **4k DISK Fluid Cell**: 4,000 mB
- **16k DISK Fluid Cell**: 16,000 mB
- **64k DISK Fluid Cell**: 64,000 mB

Note: the fluid model uses a simplified 1 mB = 1 byte scale (rather than AE2's internal fluid channel scaling) so tier capacities line up 1:1 with their item counterparts.

## Installation

### Requirements
- Minecraft 1.12.2
- Forge 14.23.5+
- **AE2 Unofficial Extended Life v0.56.7 or later**
- MixinBooter (required by AE2 Unofficial Extended Life, not by this addon directly)

### Steps
1. Download the latest JAR from CurseForge / Modrinth
2. Place in your `mods` folder
3. Launch Minecraft

## Usage

### Crafting
- Each tier's DISK Cell can be crafted directly in a shaped recipe: AE2 quartz glass + the tier's storage component + AE2 fluix block + other AE2 crafting materials.
- Alternatively, an empty DISK Housing can be combined with the tier's storage component (shapeless) to produce the same cell.
- (The recipe for crafting the DISK Housing itself isn't documented here yet — add it once finalized.)

### Disassembly
Empty cells can be disassembled:
1. With the cell in hand, hold Shift
2. Right-click (on ground or in air)
3. Receive Housing (+ storage component, for item cells) back

## Technical Details

- Item cells use a custom `ICellHandler`/`ICellInventoryHandler` implementation, rather than AE2's built-in `BasicCellHandler`, specifically to avoid AE2's standard 63-type-per-cell limit.
- Cell contents are stored off-item, referenced by a UUID kept in the cell's own NBT, rather than inline in the item's NBT. This keeps the item's own NBT small regardless of how much is stored, and avoids inflating ME network inventory-sync packets.
- Both item and fluid variants share this architecture.

## Compatibility

Should work alongside other AE2-UEL addons and any mod that interacts with AE2 through its standard storage-cell API. Not yet tested against other large-storage addons or profiled under heavy load — reports welcome via GitHub issues.

## Development Notes

Large parts of this addon's code (architecture drafts, boilerplate, refactors,
and documentation) were written with the help of Claude (Anthropic's AI
assistant). The overall design, testing, and final decisions were done by the
author; where the AE2-UEL API wasn't documented, method signatures were
verified directly against the decompiled source (via IntelliJ's Structure
panel) rather than guessed, and corrected iteratively against real compiler
errors and in-game testing.

If you run into bugs or rough edges, please open a GitHub issue — feedback is
very welcome.

## Credits

This addon's concept — type-unlimited storage cells for AE2 — is not original;
it follows the same idea as two existing mods:

- [AE2Things](https://github.com/ProjectET/AE2Things) (MIT)
- [AE2MEGAThings](https://github.com/Lapis256/AE2MEGAThings) (LGPL-3.0)

This is an independent reimplementation for the AE2-UEL rv6 API — no source
code from either project is reused — but their architecture was reviewed
during development to understand the general approach.

## License

This project is licensed under the **GNU Lesser General Public License v3.0 (LGPLv3)**, consistent with AE2 Unofficial Extended Life.

For full license text, see the [LICENSE](LICENSE) file or the [GNU LGPL v3.0](https://www.gnu.org/licenses/lgpl-3.0.html).

### Dependencies

This project depends on:
- **AE2 Unofficial Extended Life** (LGPLv3)
- **Minecraft Forge**
- **MixinBooter** (MIT), required transitively via AE2 Unofficial Extended Life

## Links

- **GitHub**: [tene11/ae2uel_things](https://github.com/tene11/ae2uel_things)
- **AE2 UEL**: [AE2 Unofficial Extended Life](https://www.curseforge.com/minecraft/mc-mods/ae2-extended-life)

---

Created by **tene11**