import { useState, useRef, useCallback } from 'react';
import { recordingToWav } from './wav';

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

      recorder.onstop = async () => {
        // 마이크 스트림 트랙 종료 (브라우저 녹음 표시 끄기)
        stream.getTracks().forEach((track) => track.stop());
        const recordedBlob = new Blob(audioChunksRef.current, { type: mimeType.split(';')[0] });
        try {
          // webm/mp4 는 CLOVA Speech 가 못 읽고 백엔드도 m4a·mp3·wav 만 받아서 진짜 WAV 로 바꿔 올린다.
          const wav = await recordingToWav(recordedBlob);
          setRecordedFile(new File([wav], `meeting_mic_${Date.now()}.wav`, { type: 'audio/wav' }));
        } catch (err) {
          console.error('녹음 변환 실패:', err);
          alert('녹음을 WAV로 바꾸지 못했습니다. 다시 녹음하거나 음성 파일 업로드를 써 주세요.');
        }
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