import { useEffect, useRef } from 'react'
import { useStore } from './store'
import { GLRenderer } from './gl'
import { surfaces } from './surfaces'
import { makeAdaptor, scaleOf } from './perf'

export function Preview({ onFps }: { onFps?: (f: number) => void }) {
  const cv = useRef<HTMLCanvasElement>(null), tx = useRef<HTMLCanvasElement>(null)
  const vid = useRef<HTMLVideoElement>(null)
  useEffect(() => {
    const gl = new GLRenderer(cv.current!); const v = vid.current!; const t2 = tx.current!.getContext('2d')!
    surfaces.gl = cv.current; surfaces.txt = tx.current; surfaces.video = v
    const adapt = makeAdaptor(() => useStore.getState().quality, q => useStore.getState().auto && useStore.getState().setQuality(q, true))
    let raf = 0, last = performance.now(), curUrl = '', frames = 0, fpsT = last
    const loop = (now: number) => {
      raf = requestAnimationFrame(loop)
      const s = useStore.getState(); const dt = now - last; last = now
      const clip = s.clips
      .filter(c => c.kind !== 'text').filter(c => s.playhead >= c.start && s.playhead < c.start + c.dur).sort((a, b) => b.track - a.track)[0]
      if (clip) {
        if (curUrl !== clip.url) { curUrl = clip.url; v.src = clip.url }
        const sp = clip.speed ?? 1; v.playbackRate = sp; v.volume = Math.min(1, clip.vol ?? 1)
        const want = (s.playhead - clip.start) * sp + clip.trim
        if (s.playing) { if (v.paused) v.play().catch(() => {}); if (Math.abs(v.currentTime - want) > 0.3) v.currentTime = want }
        else { v.pause(); if (Math.abs(v.currentTime - want) > 0.03) v.currentTime = want }
        if (s.playing) s.setPlayhead(clip.start + (v.currentTime - clip.trim) / sp)
      } else if (s.playing) { v.pause(); const end = Math.max(0, ...s.clips.map(c => c.start + c.dur)); if (s.playhead >= end) s.setPlaying(false); else s.setPlayhead(s.playhead + dt / 1000) }
      const k = scaleOf(s.quality), box = cv.current!.parentElement!.getBoundingClientRect()
      gl.resize(Math.max(2, (box.width * k * devicePixelRatio) | 0), Math.max(2, (box.height * k * devicePixelRatio) | 0))
      const T = tx.current!; if (T.width !== cv.current!.width || T.height !== cv.current!.height) { T.width = cv.current!.width; T.height = cv.current!.height }
      t2.clearRect(0, 0, T.width, T.height); t2.textAlign = 'center'; t2.font = `700 ${T.height * 0.08}px system-ui`; t2.lineWidth = T.height * 0.01
      for (const c of s.clips) if (c.kind === 'text' && s.playhead >= c.start && s.playhead < c.start + c.dur) { t2.strokeStyle = '#000'; t2.fillStyle = '#fff'; t2.strokeText(c.text ?? '', T.width / 2, T.height * 0.88); t2.fillText(c.text ?? '', T.width / 2, T.height * 0.88) }
      gl.draw(clip && v.readyState >= 2 ? v : null, s.fx)
      if (s.playing) adapt(dt)
      frames++; if (now - fpsT > 1000) { onFps?.(frames); frames = 0; fpsT = now }
    }
    raf = requestAnimationFrame(loop)
    return () => cancelAnimationFrame(raf)
  }, [])
  return <div className="preview"><canvas ref={cv} /><canvas ref={tx} className="ov" /><video ref={vid} playsInline preload="auto" hidden /></div>
}
