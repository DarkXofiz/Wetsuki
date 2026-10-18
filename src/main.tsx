import { createRoot } from 'react-dom/client'
import { App } from './App'
import './styles.css'
// Service worker: offline cache + COOP/COEP (needed for multithreaded ffmpeg.wasm on GitHub Pages)
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('./sw.js').then(() => {
    if (!crossOriginIsolated && !sessionStorage.getItem('coi')) { sessionStorage.setItem('coi', '1'); navigator.serviceWorker.ready.then(() => location.reload()) }
  }).catch(() => {})
}
createRoot(document.getElementById('root')!).render(<App />)
