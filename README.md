# Lumora
Browser video editor. No login, opens straight to the editor.

## Run
    npm install
    npm run dev

## Deploy (GitHub Pages)
Push to `main`; Settings > Pages > Source: GitHub Actions. The service worker adds COOP/COEP on first load (page reloads once).

## Done
WebGL preview + color adjust, adaptive quality, performance mode, virtualized canvas timeline (move, trim both edges, split, delete), undo/redo (Ctrl+Z / Ctrl+Y), text overlays, per-clip volume and speed, real WebM export (real time), service worker cache + COOP/COEP, dev FPS/memory meter.

## Not done yet
WebCodecs decode, 480p proxies, IndexedDB thumbnails/waveforms, ffmpeg.wasm export (faster than real time, MP4), auto captions, motion tracking, background removal, transitions, multi-track audio, project save/load.
