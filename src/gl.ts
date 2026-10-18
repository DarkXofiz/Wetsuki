const VS = `attribute vec2 p;varying vec2 v;void main(){v=vec2(p.x*.5+.5,.5-p.y*.5);gl_Position=vec4(p,0,1);}`
const FS = `precision mediump float;varying vec2 v;uniform sampler2D t;uniform float b,c,s;
void main(){vec3 x=texture2D(t,v).rgb+b;x=(x-.5)*c+.5;float l=dot(x,vec3(.299,.587,.114));gl_FragColor=vec4(mix(vec3(l),x,s),1.);}`
export class GLRenderer {
  gl: WebGLRenderingContext; tex: WebGLTexture; u: Record<string, WebGLUniformLocation | null> = {}
  constructor(public canvas: HTMLCanvasElement) {
    const gl = canvas.getContext('webgl', { antialias: false, powerPreference: 'high-performance' })!
    this.gl = gl
    const sh = (t: number, s: string) => { const o = gl.createShader(t)!; gl.shaderSource(o, s); gl.compileShader(o); return o }
    const pr = gl.createProgram()!; gl.attachShader(pr, sh(gl.VERTEX_SHADER, VS)); gl.attachShader(pr, sh(gl.FRAGMENT_SHADER, FS))
    gl.linkProgram(pr); gl.useProgram(pr)
    gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer())
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW)
    const loc = gl.getAttribLocation(pr, 'p'); gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0)
    for (const k of ['b', 'c', 's']) this.u[k] = gl.getUniformLocation(pr, k)
    this.tex = gl.createTexture()!; gl.bindTexture(gl.TEXTURE_2D, this.tex)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
  }
  resize(w: number, h: number) { if (this.canvas.width !== w || this.canvas.height !== h) { this.canvas.width = w; this.canvas.height = h } this.gl.viewport(0, 0, w, h) }
  draw(src: TexImageSource | null, fx: { brightness: number; contrast: number; saturation: number }) {
    const gl = this.gl
    if (src) gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGB, gl.RGB, gl.UNSIGNED_BYTE, src)
    gl.uniform1f(this.u.b, fx.brightness); gl.uniform1f(this.u.c, fx.contrast); gl.uniform1f(this.u.s, fx.saturation)
    gl.clearColor(0, 0, 0, 1); gl.clear(gl.COLOR_BUFFER_BIT); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4)
  }
}
