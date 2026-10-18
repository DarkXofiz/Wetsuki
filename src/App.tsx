import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import { useStore } from './store'
import { Preview } from './Preview'
import { Timeline } from './Timeline'
import { isPhone } from './perf'
const Export = lazy(() => import('./ExportPanel')) // heavy parts load only on first use

function Intro({ done }: { done: () => void }) {
  useEffect(() => { import('./ExportPanel'); const t = setTimeout(done, 2200); return () => clearTimeout(t) }, []) // preload core while animating
  return <div className="intro"><div className="orb" /><h1>Lumora</h1></div>
}

export function App() {
  const [intro, setIntro] = useState(true), [fps, setFps] = useState(0), [showExport, setShowExport] = useState(false)
  const s = useStore(), file = useRef<HTMLInputElement>(null)
  const sel = s.clips.find(c => c.id === s.selected)
  const end = Math.max(0, ...s.clips.map(c => c.start + c.dur))
  const importFiles = (fl: FileList | null) => [...(fl ?? [])].forEach(f => {
    const url = URL.createObjectURL(f), v = document.createElement('video') // Blob URL: file is never fully loaded into memory
    v.preload = 'metadata'; v.src = url
    v.onloadedmetadata = () => useStore.getState().addClip({ id: crypto.randomUUID(), name: f.name, url, start: end, dur: v.duration || 5, trim: 0, track: 0 })
  })
  useEffect(() => {
    const k = (e: KeyboardEvent) => {
      if ((e.target as HTMLElement).tagName === 'INPUT') return
      const st = useStore.getState()
      if (e.code === 'Space') { e.preventDefault(); st.setPlaying(!st.playing) }
      if (e.key === 's') st.split()
      if ((e.ctrlKey || e.metaKey) && e.key === 'z') { e.preventDefault(); e.shiftKey ? st.redo() : st.undo() }
      if ((e.ctrlKey || e.metaKey) && e.key === 'y') { e.preventDefault(); st.redo() }
      if ((e.key === 'Delete' || e.key === 'Backspace') && st.selected) st.removeClip(st.selected)
    }
    addEventListener('keydown', k); return () => removeEventListener('keydown', k)
  }, [])
  return <>
    {intro && <Intro done={() => setIntro(false)} />}
    <div className="app">
      <header>
        <b>Lumora</b>
        <button onClick={() => file.current?.click()}>Import</button>
        <button onClick={() => s.setPlaying(!s.playing)}>{s.playing ? 'Pause' : 'Play'}</button>
        <button onClick={s.undo} disabled={!s.past.length}>Undo</button>
        <button onClick={s.redo} disabled={!s.future.length}>Redo</button>
        <button onClick={() => { const t = prompt('Text'); if (t) s.addClip({ id: crypto.randomUUID(), kind: 'text', text: t, name: t, url: '', start: s.playhead, dur: 3, trim: 0, track: 1 }) }}>Text</button>
        <button onClick={s.split} disabled={!s.selected}>Split</button>
        <button onClick={() => s.selected && s.removeClip(s.selected)} disabled={!s.selected}>Delete</button>
        <span className="sp" />
        <label>Preview <select value={s.auto ? 'auto' : s.quality} onChange={e => e.target.value === 'auto' ? useStore.setState({ auto: true }) : s.setQuality(e.target.value as any)}>
          <option value="auto">Auto</option><option value="full">Full</option><option value="half">Half</option><option value="quarter">Quarter</option></select></label>
        <label><input type="checkbox" checked={s.perfMode} onChange={e => s.setPerf(e.target.checked)} /> Performance mode</label>
        <button className="pri" onClick={() => setShowExport(true)}>Export</button>
        <input ref={file} type="file" accept="video/*" multiple hidden onChange={e => { importFiles(e.target.files); e.target.value = '' }} />
      </header>
      <main>
        <Preview onFps={setFps} />
        <aside>
          <h3>Adjust</h3>
          {(['brightness', 'contrast', 'saturation'] as const).map(k => <label key={k}>{k}
            <input type="range" min={k === 'brightness' ? -0.5 : 0} max={k === 'brightness' ? 0.5 : 2} step={0.01} value={s.fx[k]} onChange={e => s.setFx({ [k]: +e.target.value })} /></label>)}
          {sel && sel.kind !== 'text' && <>
            <h3>Clip</h3>
            <label>Volume<input type="range" min={0} max={1} step={0.01} value={sel.vol ?? 1} onChange={e => s.updateClip(sel.id, { vol: +e.target.value })} /></label>
            <label>Speed {(sel.speed ?? 1).toFixed(2)}x<input type="range" min={0.25} max={4} step={0.25} value={sel.speed ?? 1} onChange={e => s.updateClip(sel.id, { speed: +e.target.value })} /></label></>}
          {sel?.kind === 'text' && <label>Text<input value={sel.text} onChange={e => s.updateClip(sel.id, { text: e.target.value })} /></label>}
          {s.clips.length === 0 && <p className="hint">Import a video to start. Drag clips on the timeline, press S to split, Space to play.</p>}
        </aside>
      </main>
      <Timeline />
      {import.meta.env.DEV && <div className="dbg">{fps} FPS · {s.quality}{(performance as any).memory ? ` · ${((performance as any).memory.usedJSHeapSize / 1048576) | 0} MB` : ''}</div>}
      {s.clips.length > 8 && <div className="warn">Large project: use proxy playback{isPhone ? ' and Performance mode' : ''}.</div>}
      {showExport && <Suspense fallback={null}><Export onClose={() => setShowExport(false)} /></Suspense>}
    </div>
  </>
}
