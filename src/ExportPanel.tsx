import { useState } from 'react'
import { useStore } from './store'
import { surfaces } from './surfaces'
export default function ExportPanel({ onClose }: { onClose: () => void }) {
  const [msg, setMsg] = useState('Exports a WebM file in real time (plays the project once).'), [pct, setPct] = useState(0), [busy, setBusy] = useState(false)
  const run = async () => {
    const st = useStore.getState(), g = surfaces.gl, tx = surfaces.txt, v = surfaces.video
    const end = Math.max(0, ...st.clips.map(c => c.start + c.dur)); if (!g || !tx || !v || !end) return setMsg('Add a clip first.')
    setBusy(true); const q = st.quality, au = st.auto; st.setQuality('full'); useStore.setState({ auto: false })
    const out = document.createElement('canvas'), ctx = out.getContext('2d')!, ms = new MediaStream()
    out.captureStream(30).getVideoTracks().forEach(t => ms.addTrack(t))
    try { (v as any).captureStream().getAudioTracks().forEach((t: MediaStreamTrack) => ms.addTrack(t)) } catch {}
    const rec = new MediaRecorder(ms, { mimeType: MediaRecorder.isTypeSupported('video/webm;codecs=vp9,opus') ? 'video/webm;codecs=vp9,opus' : 'video/webm', videoBitsPerSecond: 8_000_000 })
    const chunks: Blob[] = []; rec.ondataavailable = e => e.data.size && chunks.push(e.data)
    const done = new Promise<void>(r => (rec.onstop = () => r()))
    st.setPlayhead(0); st.setPlaying(true); rec.start(1000); setMsg('Exporting…')
    const tick = () => {
      const s = useStore.getState(); out.width = g.width; out.height = g.height
      ctx.drawImage(g, 0, 0); ctx.drawImage(tx, 0, 0); setPct(Math.min(100, (s.playhead / end) * 100))
      if (s.playing && s.playhead < end) requestAnimationFrame(tick); else rec.stop()
    }
    requestAnimationFrame(tick); await done
    const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob(chunks, { type: 'video/webm' })); a.download = 'lumora-export.webm'; a.click()
    st.setPlaying(false); st.setQuality(q); useStore.setState({ auto: au }); setBusy(false); setPct(100); setMsg('Done. File downloaded.')
  }
  return <div className="modal"><div className="card"><h3>Export</h3><p>{msg}</p><progress value={pct} max={100} />
    <div className="row"><button onClick={onClose} disabled={busy}>Close</button><button className="pri" onClick={run} disabled={busy}>Start export</button></div></div></div>
}
