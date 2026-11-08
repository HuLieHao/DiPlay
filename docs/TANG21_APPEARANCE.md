# Initial UI and map appearance

Author: 寒叙 (@Hanxu4131).

The local BYD Carplay implementation was tested on a 2023 BYD Tang DM-i Champion Edition, platform/controller 21. The owner confirmed that ordinary CarPlay followed light/dark head-unit changes after these initial display declarations were added. This is the source implementation's vehicle result, not a vehicle test of this upstream port, and is not a claim of CarPlay Ultra support.

Both main and alternate displays now declare automatic UI and map appearance in `/info`, before the existing runtime `setNightMode` commands. The upstream theme selection, sensors and configuration-change handling remain unchanged. No reconnect, Wi-Fi configuration, vehicle identifiers or extra polling are introduced.

The four fields describe the initial automatic UI/map mode; they are not a substitute for runtime theme updates or evidence that an iPhone acknowledged a particular command. Existing day/night controls still choose the desired runtime mode. The protocol test covers both displays. This upstream integration needs an in-car test of initial connection and live light/dark switching, including forced modes and the instrument display.

## BuickPlay code62 local override (2026-10-29)

This branch no longer advertises the four static UI/map appearance fields. On the attached Redmi/iPhone combination, advertising zero for all four kept CarPlay light despite `setNightMode(true)`. Removing only those declarations restored dark appearance and live day → night switching with CarPlay appearance set to Automatic. See `ACCEPTANCE-0.2.21-buick.2-code62.md`. The original contributor result above is historical, not the current branch behavior.
