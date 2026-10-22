# Changelog

## 1.0.24 - 2026-10-22

- New command `/farout here`: the start area is built where you're standing instead of at spawn, in the run's copy of your world. Found a beautiful spot? Start your next run from there. It works in the overworld and during a session (before the run starts, or in the few seconds after a finish), not in the Nether or the End.
- Runs from `/farout here` keep their own personal bests per spot, so they're never compared against your runs from spawn.
- The settings screen (`/farout menu`) now lists every run you've finished, newest first, with its time, date, seed and the coordinates it started from. **Copy /tp** puts a `/tp` to that start on your clipboard: paste it into chat, then type `/farout here` to run the same course again. `/tp` needs cheats on.
- `results.csv` has a new `start` column with those coordinates. Your existing file is updated once, the first time Farout reads or writes it; runs from before this version keep an empty `start`, since it wasn't recorded then.

## 1.0.23 - 2026-10-20

- Personal bests and splits are now kept per direction and per seed. Your splits only compare you against a run over the same terrain, so a seed with three mountain ranges in a row is no longer measured against one with a 3,000-block ocean.
- Starting a run tells you when there's no personal best yet for that direction on that seed.
- Personal bests from earlier versions weren't tied to a seed, so they no longer count. Your first finish on each seed and direction sets a new one.

## 1.0.22 - 2026-10-18

Farout no longer touches your own world, so it's safe to try in a survival world you care about.

- The start area and Airtime now happen in the run's copy of your world. Before, `/farout start` built the start area at your world's spawn, clearing builds, pets and other entities there.
- Your inventory, ender chest, XP, health, hunger, effects and bed are put away when you start and given back when the run ends: finished, failed or given up. You land back exactly where you were standing. Before, starting a run emptied your inventory and ender chest for good.
- This holds when you die or log out mid-run, and even if the game crashes.
- Portals don't work during a run, so nothing from it reaches your own Nether or End.
- Runs keep their own clock, so starting one no longer resets your world's time of day. Weather still clears, because Minecraft has one sky for the whole world.
- Already have a start area at your spawn from an earlier version? It stays, as a harmless leftover; Farout doesn't use it anymore.

## 1.0.21 - 2026-10-18

- Every run now happens in a fresh copy of your world, made from its seed and deleted when the run ends. Whatever you built, broke or looted on one run is gone on the next, so the same seed and direction is always the same course.
- When a run ends, Farout asks if you want to save the seed. Saved seeds keep every direction you've run them in, with no limit on how many.
- New settings screen with the four settings and your saved seeds: Mod Menu's Configure button, or `/farout menu`.
- Finishing a run takes you back to the start area after 5 seconds. You have to leave the run's world because it gets deleted, and the 5 seconds give you time to take in the finish, your time and your splits first.

## 1.0.20 - 2026-10-18

- Clears mobs and dropped items from the spawn area when you run `/farout start`.
- The source code is now licensed under MIT, matching the CurseForge listing.
- Mod Menu now links to the CurseForge page, the source code and the issue tracker.

## 1.0.19 - 2026-10-17

First release of Farout on CurseForge. It's a beta: the core race is done, more is coming.

- Race to 10,000 blocks in any direction, with splits every 1,000 blocks and a personal best per direction.
- Airtime: 30 seconds above the start area, with the world paused, to pick your direction.
- A 7-wide lane with 200 blocks of leeway. Stray too far and you're struck by lightning.
- Four craftable 30-second power-ups.
- Boat straightening on the **I** key, eased in over a short turn. Key is configurable in Key Bindings.
