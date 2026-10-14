// 브라우저 녹음(webm/mp4)은 CLOVA Speech가 못 읽어서, 디코딩한 소리를 16bit PCM WAV로 다시 쓴다.
// 16kHz 모노면 1분에 약 1.9MB. 업로드 상한 100MB 기준 약 54분까지 올라간다.
export const WAV_SAMPLE_RATE = 16000

export function encodeWav(samples: Float32Array, sampleRate: number): ArrayBuffer {
  const buffer = new ArrayBuffer(44 + samples.length * 2)
  const view = new DataView(buffer)
  const text = (offset: number, value: string) => {
    for (let i = 0; i < value.length; i++) view.setUint8(offset + i, value.charCodeAt(i))
  }
  text(0, 'RIFF')
  view.setUint32(4, 36 + samples.length * 2, true)
  text(8, 'WAVE')
  text(12, 'fmt ')
  view.setUint32(16, 16, true)
  view.setUint16(20, 1, true) // PCM
  view.setUint16(22, 1, true) // 모노
  view.setUint32(24, sampleRate, true)
  view.setUint32(28, sampleRate * 2, true)
  view.setUint16(32, 2, true)
  view.setUint16(34, 16, true)
  text(36, 'data')
  view.setUint32(40, samples.length * 2, true)
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]))
    view.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true)
  }
  return buffer
}

// 여러 채널을 평균 내서 모노로 만든다.
export function toMono(channels: Float32Array[]): Float32Array {
  if (channels.length === 1) return channels[0]
  const mono = new Float32Array(channels[0].length)
  for (const channel of channels) {
    for (let i = 0; i < mono.length; i++) mono[i] += channel[i] / channels.length
  }
  return mono
}

export async function recordingToWav(recorded: Blob): Promise<Blob> {
  const context = new AudioContext({ sampleRate: WAV_SAMPLE_RATE })
  try {
    const audio = await context.decodeAudioData(await recorded.arrayBuffer())
    const channels = Array.from({ length: audio.numberOfChannels }, (_, i) => audio.getChannelData(i))
    return new Blob([encodeWav(toMono(channels), audio.sampleRate)], { type: 'audio/wav' })
  } finally {
    await context.close()
  }
}
