// Media Session API - управление кнопками воспроизведения (браузер, блокировка экрана, Bluetooth)

export interface MediaSessionCallbacks {
  onPlay: () => void
  onPause: () => void
  onNext: () => void
  onPrev: () => void
  onSeekTo: (time: number) => void
}

export interface MediaMetadata {
  title: string
  artist: string
  album: string
  artworkUrl?: string
}

let currentCallbacks: MediaSessionCallbacks | null = null

/**
 * Инициализирует Media Session API с метаданными трека и обработчиками.
 * Вызывать при смене трека или при первом воспроизведении.
 */
export function setupMediaSession(
  metadata: MediaMetadata,
  callbacks: MediaSessionCallbacks
): void {
  if (!('mediaSession' in navigator)) {
    console.warn('Media Session API не поддерживается')
    return
  }

  currentCallbacks = callbacks

  // Обновляем метаданные
  navigator.mediaSession.metadata = new MediaMetadata({
    title: metadata.title,
    artist: metadata.artist,
    album: metadata.album,
    artwork: metadata.artworkUrl
      ? [
          { src: metadata.artworkUrl, sizes: '512x512', type: 'image/jpeg' },
          { src: metadata.artworkUrl, sizes: '256x256', type: 'image/jpeg' },
        ]
      : [],
  })

  // Регистрируем обработчики действий
  navigator.mediaSession.setActionHandler('play', callbacks.onPlay)
  navigator.mediaSession.setActionHandler('pause', callbacks.onPause)
  navigator.mediaSession.setActionHandler('nexttrack', callbacks.onNext)
  navigator.mediaSession.setActionHandler('previoustrack', callbacks.onPrev)
  navigator.mediaSession.setActionHandler('seekto', (details) => {
    if (details.seekTime !== undefined) {
      callbacks.onSeekTo(details.seekTime)
    }
  })
}

/**
 * Обновляет состояние воспроизведения (playing/paused).
 */
export function updatePlaybackState(state: 'playing' | 'paused' | 'none'): void {
  if (!('mediaSession' in navigator)) return
  navigator.mediaSession.playbackState = state
}

/**
 * Обновляет позицию воспроизведения и длительность трека.
 */
export function updatePositionState(
  duration: number,
  position: number,
  playbackRate: number = 1.0
): void {
  if (!('mediaSession' in navigator)) return
  if (!('setPositionState' in navigator.mediaSession)) return

  try {
    navigator.mediaSession.setPositionState({
      duration,
      position,
      playbackRate,
    })
  } catch (e) {
    console.warn('Не удалось обновить позицию:', e)
  }
}

/**
 * Очищает Media Session (при остановке воспроизведения).
 */
export function clearMediaSession(): void {
  if (!('mediaSession' in navigator)) return

  navigator.mediaSession.metadata = null
  navigator.mediaSession.playbackState = 'none'

  // Очищаем обработчики
  const actions: MediaSessionAction[] = [
    'play',
    'pause',
    'nexttrack',
    'previoustrack',
    'seekto',
  ]
  actions.forEach((action) => {
    try {
      navigator.mediaSession.setActionHandler(action, null)
    } catch (e) {
      // Игнорируем ошибки при очистке
    }
  })

  currentCallbacks = null
}
