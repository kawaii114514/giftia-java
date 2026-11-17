import { useEffect, useState } from 'react'
import { useDialogFocus } from './useDialogFocus'

export default function BreathingPause({ onClose }: { onClose: () => void }) {
  const ref = useDialogFocus(onClose)
  const [elapsed, setElapsed] = useState(0)
  const [running, setRunning] = useState(false)
  useEffect(() => {
    if (!running) return
    const timer = window.setInterval(() => setElapsed((n) => Math.min(n + 1, 60)), 1000)
    return () => window.clearInterval(timer)
  }, [running])
  useEffect(() => {
    if (elapsed === 60) setRunning(false)
  }, [elapsed])
  const inhale = elapsed % 10 < 4
  return (
    <div className="dialog-overlay" onClick={onClose}>
      <div
        ref={ref}
        tabIndex={-1}
        className="breathing-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="breathing-title"
        onClick={(e) => e.stopPropagation()}
      >
        <button className="close-round" onClick={onClose} aria-label="关闭呼吸练习">
          ×
        </button>
        <span className="eyebrow">A MOMENT OF STILLNESS</span>
        <h2 id="breathing-title">给自己，一分钟。</h2>
        <p>跟随自己的舒适节奏，轻轻呼吸。</p>
        <div
          className={`breath-orbit ${running ? 'is-running' : ''} ${inhale ? 'is-inhale' : 'is-exhale'}`}
        >
          <div className="breath-circle">
            <span>
              {elapsed === 60
                ? '完成了'
                : running
                  ? inhale
                    ? '吸气'
                    : '呼气'
                  : elapsed
                    ? '已暂停'
                    : '准备好了吗'}
            </span>
            <small>{60 - elapsed} 秒</small>
          </div>
        </div>
        <div className="breath-controls">
          <button
            className="primary-button"
            onClick={() => {
              if (elapsed === 60) setElapsed(0)
              setRunning(!running)
            }}
          >
            {elapsed === 60 ? '再来一次' : running ? '暂停' : elapsed ? '继续' : '开始呼吸'}
          </button>
          <button
            className="text-button"
            onClick={() => {
              setRunning(false)
              setElapsed(0)
            }}
          >
            重新开始
          </button>
        </div>
        <small className="breath-note">吸气 4 秒 · 呼气 6 秒。随时可以暂停。</small>
      </div>
    </div>
  )
}
