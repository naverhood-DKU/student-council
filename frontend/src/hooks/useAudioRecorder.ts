import { useState, useRef, useCallback } from 'react';

export interface UseAudioRecorderReturn {
  isRecording: boolean;
  recordingTime: number;
  recordedFile: File | null;
  startRecording: () => Promise<void>;
  stopRecording: () => void;
  resetRecording: () => void;
  formattedTime: string;
}

export const useAudioRecorder = (): UseAudioRecorderReturn => {
  const [isRecording, setIsRecording] = useState(false);
  const [recordingTime, setRecordingTime] = useState(0);
  const [recordedFile, setRecordedFile] = useState<File | null>(null);

  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const audioChunksRef = useRef<Blob[]>([]);
  const timerIntervalRef = useRef<number | null>(null);

  const startRecording = useCallback(async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      audioChunksRef.current = [];

      const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
        ? 'audio/webm;codecs=opus'
        : MediaRecorder.isTypeSupported('audio/webm')
        ? 'audio/webm'
        : 'audio/mp4';

      const recorder = new MediaRecorder(stream, { mimeType });
      mediaRecorderRef.current = recorder;

      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          audioChunksRef.current.push(event.data);
        }
      };

      recorder.onstop = () => {
        // 실제 인코딩된 mimeType을 그대로 사용
        const actualType = mimeType.split(';')[0]; // 'audio/webm' 또는 'audio/mp4'
        const extension = actualType.includes('mp4') ? 'mp4' : 'webm';

        const recordedBlob = new Blob(audioChunksRef.current, { type: actualType });
        
        // 실제 데이터 포맷에 맞게 .webm(또는 .mp4) 파일로 생성
        const file = new File(
          [recordedBlob],
          `meeting_mic_${Date.now()}.${extension}`,
          { type: actualType }
        );

        setRecordedFile(file);
        // 마이크 스트림 트랙 종료 (브라우저 녹음 표시 끄기)
        stream.getTracks().forEach((track) => track.stop());
      };

      recorder.start(1000);
      setIsRecording(true);
      setRecordingTime(0);

      timerIntervalRef.current = window.setInterval(() => {
        setRecordingTime((prev) => prev + 1);
      }, 1000);
    } catch (err) {
      console.error('마이크 접근 권한 실패:', err);
      alert('마이크 접근 권한이 필요합니다. 브라우저 설정에서 마이크를 허용해 주세요.');
    }
  }, []);

  const stopRecording = useCallback(() => {
    if (mediaRecorderRef.current && mediaRecorderRef.current.state !== 'inactive') {
      mediaRecorderRef.current.stop();
      setIsRecording(false);
      if (timerIntervalRef.current) {
        clearInterval(timerIntervalRef.current);
        timerIntervalRef.current = null;
      }
    }
  }, []);

  const resetRecording = useCallback(() => {
    setRecordedFile(null);
    setRecordingTime(0);
    setIsRecording(false);
    if (timerIntervalRef.current) {
      clearInterval(timerIntervalRef.current);
      timerIntervalRef.current = null;
    }
  }, []);

  const minutes = Math.floor(recordingTime / 60);
  const seconds = recordingTime % 60;
  const formattedTime = `${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`;

  return {
    isRecording,
    recordingTime,
    recordedFile,
    startRecording,
    stopRecording,
    resetRecording,
    formattedTime,
  };
};