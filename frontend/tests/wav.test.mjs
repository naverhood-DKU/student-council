import test from 'node:test'
import assert from 'node:assert/strict'
import { encodeWav, toMono } from '../src/hooks/wav.ts'

test('CLOVA가 읽는 16bit PCM 모노 WAV 헤더를 쓴다', () => {
  const view = new DataView(encodeWav(new Float32Array([0, 1, -1, 2]), 16000))
  const text = (o) => String.fromCharCode(...new Uint8Array(view.buffer, o, 4))
  assert.equal(text(0), 'RIFF')
  assert.equal(text(8), 'WAVE')
  assert.equal(view.getUint16(20, true), 1)
  assert.equal(view.getUint16(22, true), 1)
  assert.equal(view.getUint32(24, true), 16000)
  assert.equal(view.getUint16(34, true), 16)
  assert.equal(view.getUint32(40, true), 8)
  assert.equal(view.byteLength, 52)
  assert.deepEqual([1, 2, 3, 4].map((i) => view.getInt16(42 + i * 2, true)), [0, 32767, -32768, 32767])
})

test('여러 채널은 평균 내서 모노로 만든다', () => {
  assert.deepEqual(Array.from(toMono([new Float32Array([1, 0]), new Float32Array([0, 0.5])])), [0.5, 0.25])
})
