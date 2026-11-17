import { useState } from 'react'
import { useStore } from '../store'
import UserSection from './UserSection'
import ModelDialog from './ModelDialog'
import PromptDialog from './PromptDialog'
import LogViewerModal from './LogViewerModal'
import MemoryViewerModal from './MemoryViewerModal'
import ConfirmDialog from './ConfirmDialog'
import { useDialogFocus } from './useDialogFocus'

export default function CompanionSettings({ onClose }: { onClose: () => void }) {
  const [panel, setPanel] = useState<'model' | 'prompt' | 'logs' | 'profile' | 'clear' | null>(null)
  const [error, setError] = useState('')
  const model = useStore((s) => s.modelConfig)
  const isLoading = useStore((s) => s.isLoading)
  const clearMemories = useStore((s) => s.clearMemories)
  if (panel === 'model') return <ModelDialog onClose={() => setPanel(null)} />
  if (panel === 'prompt') return <PromptDialog onClose={() => setPanel(null)} />
  if (panel === 'logs') return <LogViewerModal onClose={() => setPanel(null)} />
  if (panel === 'profile') return <MemoryViewerModal onClose={() => setPanel(null)} />
  if (panel === 'clear')
    return (
      <ConfirmDialog
        title="清除记忆"
        message="确定清除此用户的所有记忆？此操作不可撤销。"
        onCancel={() => setPanel(null)}
        onConfirm={async () => {
          try {
            await clearMemories()
            setPanel(null)
          } catch {
            setError('记忆清除失败，请稍后重试。')
            setPanel(null)
          }
        }}
      />
    )
  return (
    <SettingsShell onClose={onClose}>
      <span className="eyebrow">MAKE YOURSELF AT HOME</span>
      <h2 id="settings-title">你的陪伴空间</h2>
      <fieldset disabled={isLoading} className="settings-user">
        <UserSection />
      </fieldset>
      {isLoading && <p className="settings-notice">回复完成后，即可切换用户或清除记忆。</p>}
      <div className="settings-menu">
        <button onClick={() => setPanel('prompt')}>
          <span>
            角色与人设<small>选择陪伴角色，或制作自己的角色卡</small>
          </span>
          <b>↗</b>
        </button>
        <button onClick={() => setPanel('profile')}>
          <span>
            关于你的画像<small>查看已保存的偏好与记忆详情</small>
          </span>
          <b>↗</b>
        </button>
        <button onClick={() => setPanel('model')}>
          <span>
            当前模型<small>{model?.model || '尚未连接'}</small>
          </span>
          <b>↗</b>
        </button>
        <button onClick={() => setPanel('logs')}>
          <span>运行日志</span>
          <b>↗</b>
        </button>
        <button className="settings-danger" disabled={isLoading} onClick={() => setPanel('clear')}>
          <span>清除当前用户的记忆</span>
          <b>↗</b>
        </button>
      </div>
      {error && <p role="alert">{error}</p>}
    </SettingsShell>
  )
}
function SettingsShell({ onClose, children }: { onClose: () => void; children: React.ReactNode }) {
  const ref = useDialogFocus(onClose)
  return (
    <div className="dialog-overlay" onClick={onClose}>
      <div
        ref={ref}
        tabIndex={-1}
        className="settings-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="settings-title"
        onClick={(e) => e.stopPropagation()}
      >
        <button className="close-round" aria-label="关闭设置" onClick={onClose}>
          ×
        </button>
        {children}
      </div>
    </div>
  )
}
