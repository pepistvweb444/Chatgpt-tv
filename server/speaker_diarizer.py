from __future__ import annotations

import math
import threading
from dataclasses import dataclass, field

import numpy as np


def _hz_to_mel(hz: float) -> float:
    return 2595.0 * math.log10(1.0 + hz / 700.0)


def _mel_to_hz(mel: float) -> float:
    return 700.0 * (10.0 ** (mel / 2595.0) - 1.0)


def _dct_matrix(n_mels: int, n_mfcc: int) -> np.ndarray:
    n = np.arange(n_mels, dtype=np.float32)
    k = np.arange(n_mfcc, dtype=np.float32)[:, None]
    mat = np.cos(np.pi / n_mels * (n + 0.5) * k)
    mat[0] *= 1.0 / math.sqrt(2.0)
    mat *= math.sqrt(2.0 / n_mels)
    return mat.astype(np.float32)


_DCT = _dct_matrix(26, 14)


def _mel_filterbank(sr: int = 16000, n_fft: int = 512, n_mels: int = 26) -> np.ndarray:
    lo = _hz_to_mel(80.0)
    hi = _hz_to_mel(7600.0)
    mel_points = np.linspace(lo, hi, n_mels + 2)
    hz_points = np.array([_mel_to_hz(m) for m in mel_points], dtype=np.float32)
    bins = np.floor((n_fft + 1) * hz_points / sr).astype(int)
    bins = np.clip(bins, 0, n_fft // 2)

    fb = np.zeros((n_mels, n_fft // 2 + 1), dtype=np.float32)
    for m in range(1, n_mels + 1):
        left, center, right = bins[m - 1], bins[m], bins[m + 1]
        if center <= left:
            center = min(left + 1, n_fft // 2)
        if right <= center:
            right = min(center + 1, n_fft // 2)
        for k in range(left, center):
            fb[m - 1, k] = (k - left) / max(1, center - left)
        for k in range(center, right):
            fb[m - 1, k] = (right - k) / max(1, right - center)
    return fb


_MEL_FB = _mel_filterbank()


def _estimate_pitch(frame: np.ndarray, sr: int = 16000) -> float:
    frame = frame - np.mean(frame)
    energy = float(np.dot(frame, frame))
    if energy < 1e-5:
        return 0.0

    min_lag = max(1, int(sr / 350.0))
    max_lag = min(len(frame) - 2, int(sr / 70.0))
    if max_lag <= min_lag:
        return 0.0

    corr = np.correlate(frame, frame, mode="full")[len(frame) - 1 :]
    corr[:min_lag] = 0.0
    corr[max_lag + 1 :] = 0.0
    lag = int(np.argmax(corr))
    if lag <= 0:
        return 0.0

    confidence = corr[lag] / max(corr[0], 1e-8)
    if confidence < 0.18:
        return 0.0
    return float(sr / lag)


def pcm48_to_float16k(pcm: bytes) -> np.ndarray:
    if not pcm:
        return np.zeros(0, dtype=np.float32)
    x = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0
    # 48 kHz -> 16 kHz. The preceding Android/codec chain is band-limited enough
    # for speaker fingerprints; averaging three samples gives a tiny anti-alias filter.
    n = (len(x) // 3) * 3
    if n == 0:
        return np.zeros(0, dtype=np.float32)
    return x[:n].reshape(-1, 3).mean(axis=1)


def speaker_embedding(pcm: bytes) -> np.ndarray | None:
    x = pcm48_to_float16k(pcm)
    if len(x) < 8000:
        return None

    # Pre-emphasis and robust amplitude normalization.
    x = np.append(x[0], x[1:] - 0.97 * x[:-1])
    peak = float(np.max(np.abs(x)))
    if peak < 0.006:
        return None
    x = x / max(peak, 1e-6)

    frame_len = 400
    hop = 160
    n_fft = 512
    if len(x) < frame_len:
        return None

    count = 1 + (len(x) - frame_len) // hop
    idx = np.arange(frame_len)[None, :] + hop * np.arange(count)[:, None]
    frames = x[idx]
    window = np.hamming(frame_len).astype(np.float32)
    frames = frames * window

    rms = np.sqrt(np.mean(frames * frames, axis=1) + 1e-9)
    keep = rms > max(0.02, float(np.percentile(rms, 35)) * 0.8)
    frames = frames[keep]
    if len(frames) < 4:
        return None

    spec = np.fft.rfft(frames, n=n_fft, axis=1)
    power = (np.abs(spec) ** 2).astype(np.float32)
    mel = np.maximum(power @ _MEL_FB.T, 1e-8)
    logmel = np.log(mel)

    mfcc = logmel @ _DCT.T
    mfcc = mfcc[:, 1:14]

    # Keep the MFCC mean: it carries vocal-tract/timbre information that helps
    # distinguish actors. Device/room effects are handled by final vector
    # normalization and by the slowly-adapting online cluster centroids.
    delta = np.diff(mfcc, axis=0)

    freqs = np.linspace(0.0, 8000.0, power.shape[1], dtype=np.float32)
    p_sum = np.sum(power, axis=1) + 1e-8
    centroid = np.sum(power * freqs[None, :], axis=1) / p_sum
    flatness = np.exp(np.mean(np.log(power + 1e-8), axis=1)) / (
        np.mean(power + 1e-8, axis=1)
    )

    pitches = []
    for frame in frames[:: max(1, len(frames) // 10)]:
        p = _estimate_pitch(frame)
        if p > 0:
            pitches.append(p)

    if pitches:
        pitch_mean = float(np.mean(pitches))
        pitch_std = float(np.std(pitches))
    else:
        pitch_mean = 0.0
        pitch_std = 0.0

    features = np.concatenate(
        [
            np.mean(mfcc, axis=0),
            np.std(mfcc, axis=0),
            np.mean(np.abs(delta), axis=0) if len(delta) else np.zeros(13),
            np.array(
                [
                    float(np.mean(centroid)) / 8000.0,
                    float(np.std(centroid)) / 8000.0,
                    float(np.mean(flatness)),
                    float(np.std(flatness)),
                    pitch_mean / 350.0,
                    pitch_std / 150.0,
                    float(np.mean(rms)),
                    float(np.std(rms)),
                ],
                dtype=np.float32,
            ),
        ]
    ).astype(np.float32)

    norm = float(np.linalg.norm(features))
    if norm < 1e-7:
        return None
    return features / norm


@dataclass
class SpeakerCluster:
    speaker_id: int
    centroid: np.ndarray
    count: int = 1
    reference_pcm: bytearray = field(default_factory=bytearray)
    profile: str = ""
    profile_ready: bool = False
    enrolling: bool = False
    enroll_error: str = ""


class OnlineSpeakerDiarizer:
    """Small online diarizer written for INIT.

    It deliberately avoids an external speaker-ID model. It clusters a compact
    acoustic fingerprint and is intended for real-time assignment, not forensic
    speaker identification.
    """

    def __init__(
        self,
        session_id: str,
        similarity_threshold: float = 0.78,
        max_speakers: int = 16,
    ):
        self.session_id = session_id
        self.threshold = similarity_threshold
        self.max_speakers = max_speakers
        self.clusters: list[SpeakerCluster] = []
        self.last_speaker_id: int | None = None
        self.lock = threading.Lock()

    @staticmethod
    def _cos(a: np.ndarray, b: np.ndarray) -> float:
        return float(np.dot(a, b) / max(np.linalg.norm(a) * np.linalg.norm(b), 1e-8))

    def assign(self, pcm: bytes) -> SpeakerCluster | None:
        emb = speaker_embedding(pcm)
        if emb is None:
            return None

        with self.lock:
            if not self.clusters:
                c = SpeakerCluster(
                    speaker_id=1,
                    centroid=emb.copy(),
                    profile=f"init_{self.session_id[:18]}_spk1",
                )
                self.clusters.append(c)
                self.last_speaker_id = 1
                return c

            sims = [self._cos(emb, c.centroid) for c in self.clusters]
            best_idx = int(np.argmax(sims))
            best_sim = sims[best_idx]

            # Hysteresis: keep the previous speaker if nearly tied.
            if self.last_speaker_id is not None:
                last_idx = next(
                    (i for i, c in enumerate(self.clusters) if c.speaker_id == self.last_speaker_id),
                    None,
                )
                if last_idx is not None and sims[last_idx] >= best_sim - 0.035:
                    best_idx = last_idx
                    best_sim = sims[last_idx]

            if best_sim < self.threshold and len(self.clusters) < self.max_speakers:
                sid = len(self.clusters) + 1
                c = SpeakerCluster(
                    speaker_id=sid,
                    centroid=emb.copy(),
                    profile=f"init_{self.session_id[:18]}_spk{sid}",
                )
                self.clusters.append(c)
                self.last_speaker_id = sid
                return c

            c = self.clusters[best_idx]
            c.count += 1
            # Exponential centroid update: stable identity, still adapts to the movie mix.
            alpha = min(0.16, 1.0 / max(2, c.count))
            centroid = (1.0 - alpha) * c.centroid + alpha * emb
            norm = float(np.linalg.norm(centroid))
            if norm > 1e-8:
                c.centroid = centroid / norm
            self.last_speaker_id = c.speaker_id
            return c

    def snapshot(self) -> list[dict]:
        with self.lock:
            return [
                {
                    "speaker_id": c.speaker_id,
                    "segments": c.count,
                    "reference_seconds": len(c.reference_pcm) / (48000 * 2),
                    "profile": c.profile,
                    "profile_ready": c.profile_ready,
                    "enrolling": c.enrolling,
                    "enroll_error": c.enroll_error,
                }
                for c in self.clusters
            ]
