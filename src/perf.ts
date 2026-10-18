export const isPhone = /Android|iPhone|iPad|Mobi/i.test(navigator.userAgent) || innerWidth < 700
export type Quality = 'full' | 'half' | 'quarter'
export const scaleOf = (q: Quality) => (q === 'full' ? 1 : q === 'half' ? 0.5 : 0.25)
/** Adaptive quality: step down if avg frame time > 36ms, step up when comfortably fast. */
export function makeAdaptor(get: () => Quality, set: (q: Quality) => void) {
  let acc = 0, n = 0
  return (dt: number) => {
    acc += dt; if (++n < 45) return
    const avg = acc / n; acc = 0; n = 0
    const q = get()
    if (avg > 36) set(q === 'full' ? 'half' : 'quarter')
    else if (avg < 18 && q !== 'full') set(q === 'quarter' ? 'half' : 'full')
  }
}
