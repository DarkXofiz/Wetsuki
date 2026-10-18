import { useEffect, useRef } from 'react'
import { useStore } from './store'
const PX = 80, ROW = 44, HEAD = 22
export function Timeline() {
  const cv = useRef<HTMLCanvasElement>(null)
  const scroll = useRef(0), drag = useRef<null | { id: string; dx: number; mode: 'move' | 'ph' | 'l' | 'r'; c0?: any }>(null), dirty = useRef(true)
  useEffect(() => {
    const c = cv.current!, ctx = c.getContext('2d')!
    const unsub = useStore.subscribe(() => (dirty.current = true))
    const fit = () => { const r = c.getBoundingClientRect(); c.width = r.width * devicePixelRatio; c.height = r.height * devicePixelRatio; dirty.current = true }
    fit(); addEventListener('resize', fit)
    let raf = 0
    const draw = () => {
      raf = requestAnimationFrame(draw); if (!dirty.current) return; dirty.current = false
      const s = useStore.getState(), W = c.width / devicePixelRatio, H = c.height / devicePixelRatio
      ctx.setTransform(devicePixelRatio, 0, 0, devicePixelRatio, 0, 0); ctx.clearRect(0, 0, W, H)
      ctx.fillStyle = '#8a82a8'; ctx.font = '11px system-ui'
      for (let t = Math.floor(scroll.current / PX); t * PX - scroll.current < W; t++) { const x = t * PX - scroll.current; ctx.fillRect(x, HEAD - 6, 1, 6); ctx.fillText(t + 's', x + 3, 12) }
      for (const cl of s.clips) { // virtualized: skip clips outside the viewport
        const x = cl.start * PX - scroll.current, w = cl.dur * PX; if (x + w < 0 || x > W) continue
        const y = HEAD + cl.track * (ROW + 6)
        ctx.fillStyle = cl.id === s.selected ? '#ffcf5c' : cl.kind === 'text' ? '#b03a6a' : '#5b4fa8'; ctx.fillRect(x, y, w, ROW)
        ctx.fillStyle = cl.id === s.selected ? '#1a1530' : '#fff'; ctx.fillText(cl.kind === 'text' ? 'T: ' + cl.text : cl.name, x + 6, y + 26)
      }
      const px = s.playhead * PX - scroll.current; ctx.fillStyle = '#ff5d7a'; ctx.fillRect(px, 0, 2, H)
    }
    raf = requestAnimationFrame(draw)
    return () => { cancelAnimationFrame(raf); unsub(); removeEventListener('resize', fit) }
  }, [])
  const pos = (e: React.PointerEvent) => { const r = cv.current!.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top } }
  return <canvas ref={cv} className="timeline"
    onWheel={e => { scroll.current = Math.max(0, scroll.current + e.deltaX + e.deltaY); dirty.current = true }}
    onPointerDown={e => {
      cv.current!.setPointerCapture(e.pointerId); const { x, y } = pos(e), s = useStore.getState(), t = (x + scroll.current) / PX
      const hit = s.clips.find(c => t >= c.start && t <= c.start + c.dur && y >= HEAD + c.track * (ROW + 6) && y <= HEAD + c.track * (ROW + 6) + ROW)
      if (hit) {
        s.select(hit.id); s.snap(); const edge = 9 / PX
        const mode = t - hit.start < edge ? 'l' : hit.start + hit.dur - t < edge ? 'r' : 'move'
        drag.current = { id: hit.id, dx: t - hit.start, mode, c0: hit }
      } else { s.select(null); s.setPlayhead(t); drag.current = { id: '', dx: 0, mode: 'ph' } }
    }}
    onPointerMove={e => {
      const d = drag.current; if (!d) return; const t = (pos(e).x + scroll.current) / PX, st = useStore.getState()
      if (d.mode === 'ph') return st.setPlayhead(t)
      const c = d.c0!, sp = c.speed ?? 1
      if (d.mode === 'move') st.updateClip(d.id, { start: Math.max(0, t - d.dx) })
      else if (d.mode === 'r') st.updateClip(d.id, { dur: Math.max(0.2, t - c.start) })
      else { const ns = Math.min(Math.max(0, t), c.start + c.dur - 0.2), dl = ns - c.start; if (c.trim + dl * sp >= 0) st.updateClip(d.id, { start: ns, dur: c.dur - dl, trim: c.trim + dl * sp }) }
    }}
    onPointerUp={() => (drag.current = null)} />
}
