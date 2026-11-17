import { useEffect, useState } from 'react'
import { useStore } from '../store'
import type { CharacterCard, CharacterFields } from '../types'
import { useDialogFocus } from './useDialogFocus'
import './PromptDialog.css'

const blankCard: CharacterFields = {
  name: '',
  description: '',
  personality: '',
  scenario: '',
  first_mes: '',
  mes_example: '',
  tags: [],
}
const fields = [
  ['description', '身份与背景', '描述角色是谁。虚构背景不会成为用户的真实记忆。', 5000],
  ['personality', '性格与说话方式', '例如：安静、直接，先倾听，再给一两个具体建议。', 1000],
  ['scenario', '交流场景', '例如：日常陪伴，或由用户选择进入的想象场景。', 1000],
  ['first_mes', '开场白', '显示在空白对话页，不会自动发送或写入聊天记录。', 1000],
  ['mes_example', '示例对话', '用户：今天很累。\n角色：那就先歇一会儿，想说的时候我听着。', 2000],
] as const

function editable(card: CharacterCard): CharacterFields {
  return {
    name: card.name,
    description: card.description,
    personality: card.personality,
    scenario: card.scenario,
    first_mes: card.first_mes,
    mes_example: card.mes_example,
    tags: [...card.tags],
  }
}

export default function PromptDialog({ onClose }: { onClose: () => void }) {
  const dialogRef = useDialogFocus(onClose)
  const userId = useStore((s) => s.userId)
  const config = useStore((s) => s.characterConfig)
  const loadConfig = useStore((s) => s.loadCharacterConfig)
  const updateConfig = useStore((s) => s.updateCharacterConfig)
  const isReplying = useStore((s) => s.isLoading)
  const saving = useStore((s) => s.isCharacterSaving)
  const [loading, setLoading] = useState(true)
  const [selectedId, setSelectedId] = useState('giftia')
  const [custom, setCustom] = useState<CharacterFields>(blankCard)
  const [loadFailed, setLoadFailed] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [revision, setRevision] = useState(0)

  useEffect(() => {
    let alive = true
    setLoading(true)
    setLoadFailed(false)
    setError('')
    setMessage('')
    loadConfig()
      .then(() => {
        if (!alive) return
        const current = useStore.getState().characterConfig
        if (!current || current.user_id !== userId) throw new Error('角色配置暂不可用')
        setSelectedId(current.active_character.id)
        setCustom(
          current.custom_character ? editable(current.custom_character) : { ...blankCard, tags: [] }
        )
      })
      .catch(() => {
        if (alive) {
          setLoadFailed(true)
          setError('角色加载失败，请重试。')
        }
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [loadConfig, userId, revision])

  const selected =
    selectedId === 'custom' ? custom : config?.presets.find((p) => p.id === selectedId)
  const total = Object.values(custom).reduce<number>(
    (sum, value) => sum + (typeof value === 'string' ? value.length : 0),
    0
  )
  const choose = (id: string) => {
    setSelectedId(id)
    setMessage('')
    setError('')
  }
  const copyPreset = () => {
    if (!selected || selectedId === 'custom') return
    setCustom({
      ...editable(selected as CharacterCard),
      name: `${selected.name} · 自定义`.slice(0, 60),
    })
    choose('custom')
  }
  const save = async () => {
    if (!selected || (selectedId === 'custom' && (!custom.name.trim() || total > 10000))) return
    setError('')
    setMessage('')
    try {
      await updateConfig(
        selectedId === 'custom' ? { character: custom } : { character_id: selectedId }
      )
      if (useStore.getState().userId !== userId) return
      setMessage(`已使用「${selected.name}」，下一条回复生效。已有对话和记忆会保留。`)
    } catch {
      if (useStore.getState().userId === userId) setError('保存失败，请稍后重试。')
    }
  }

  return (
    <div className="dialog-overlay" onClick={onClose}>
      <div
        className="dialog-content prompt-dialog character-dialog"
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="character-title"
        tabIndex={-1}
        onClick={(event) => event.stopPropagation()}
      >
        <header className="character-heading">
          <div>
            <span className="character-kicker">陪伴，也可以有不同的模样</span>
            <h2 id="character-title">角色与人设</h2>
          </div>
          <button className="close-round" aria-label="关闭角色与人设" onClick={onClose}>
            ×
          </button>
        </header>
        <p className="prompt-dialog-desc">为当前用户选择一位伙伴，或从预设出发制作角色卡。</p>
        {loading ? (
          <p role="status" className="character-loading">
            正在翻开角色册…
          </p>
        ) : loadFailed || !config || config.user_id !== userId ? (
          <div role="alert">
            <p>{error || '角色配置暂不可用'}</p>
            <button
              className="dialog-btn dialog-btn--secondary"
              onClick={() => setRevision((n) => n + 1)}
            >
              重新加载
            </button>
          </div>
        ) : (
          <>
            <fieldset className="character-controls" disabled={saving}>
              <div className="character-layout">
                <section className="character-library" aria-label="角色选择">
                  <h3>
                    系统预设 <span>{config.presets.length} 位伙伴</span>
                  </h3>
                  <div className="character-options">
                    {config.presets.map((card, index) => (
                      <button
                        className={`character-option character-color-${index}`}
                        key={card.id}
                        aria-pressed={selectedId === card.id}
                        onClick={() => choose(card.id)}
                      >
                        <span className="character-avatar" aria-hidden="true">
                          {card.name.slice(0, 1)}
                        </span>
                        <span className="character-option-copy">
                          <strong>{card.name}</strong>
                          <small>{card.tags.join(' · ')}</small>
                        </span>
                        {config.active_character.id === card.id && (
                          <span className="character-in-use">使用中</span>
                        )}
                      </button>
                    ))}
                  </div>
                  <button
                    className="character-custom-choice"
                    aria-pressed={selectedId === 'custom'}
                    onClick={() => choose('custom')}
                  >
                    <span>＋ 我的角色卡</span>
                    <small>{config.custom_character?.name || '自定义身份与风格'}</small>
                  </button>
                </section>
                <section
                  className="character-sheet"
                  aria-label={selectedId === 'custom' ? '自定义角色卡编辑' : '预设角色卡详情'}
                >
                  {selectedId === 'custom' ? (
                    <>
                      <div className="character-sheet-top">
                        <h3>制作你的角色卡</h3>
                        <span>自定义</span>
                      </div>
                      <label className="character-field">
                        角色名字
                        <input
                          aria-label="角色名字"
                          value={custom.name}
                          maxLength={60}
                          onChange={(e) => setCustom({ ...custom, name: e.target.value })}
                          placeholder="为伙伴取个名字"
                        />
                      </label>
                      {fields.map(([key, label, placeholder, limit]) => (
                        <label className="character-field" key={key}>
                          <span>
                            {label}
                            <small>
                              {custom[key].length} / {limit}
                            </small>
                          </span>
                          <textarea
                            aria-label={label}
                            className="prompt-dialog-textarea"
                            value={custom[key]}
                            maxLength={limit}
                            onChange={(e) => setCustom({ ...custom, [key]: e.target.value })}
                            rows={key === 'description' || key === 'mes_example' ? 3 : 2}
                            placeholder={placeholder}
                          />
                        </label>
                      ))}
                      <p className="character-count">
                        角色文字 {total} / 10000 字。系统规则不属于角色卡。
                      </p>
                    </>
                  ) : (
                    selected && (
                      <>
                        <div className="character-sheet-top">
                          <h3>{selected.name}</h3>
                          <span>系统预设 · 只读</span>
                        </div>
                        <p className="character-description">{selected.description}</p>
                        <dl className="character-definition">
                          {fields
                            .filter(([key]) => key !== 'description')
                            .map(
                              ([key, label]) =>
                                selected[key] && (
                                  <div key={key}>
                                    <dt>{label}</dt>
                                    <dd className={key === 'first_mes' ? 'character-greeting' : ''}>
                                      {selected[key]}
                                    </dd>
                                  </div>
                                )
                            )}
                        </dl>
                        <button className="character-copy-button" onClick={copyPreset}>
                          复制并自定义 ↗
                        </button>
                      </>
                    )
                  )}
                </section>
              </div>
            </fieldset>
            <details className="character-system-rules">
              <summary>
                <span aria-hidden="true">▣</span> 系统规则 · 固定，不随角色切换
              </summary>
              <p>真实性、记忆边界与危险情境处理由服务统一管理。这里可以查看，角色卡不能覆盖。</p>
              <pre>{config.system_rules}</pre>
            </details>
            {isReplying && (
              <p className="character-notice" role="status">
                正在回复中，请等待回复完成后再应用角色。
              </p>
            )}
            {error && (
              <p className="prompt-dialog-message prompt-dialog-message--error" role="alert">
                {error}
              </p>
            )}
            {message && (
              <p className="prompt-dialog-message prompt-dialog-message--success" role="status">
                {message}
              </p>
            )}
            <footer className="character-footer">
              <small>仅对当前用户生效 · 切换角色会保留聊天与记忆</small>
              <button
                className="dialog-btn dialog-btn--primary"
                onClick={save}
                disabled={
                  saving ||
                  isReplying ||
                  !selected ||
                  (selectedId === 'custom' && (!custom.name.trim() || total > 10000))
                }
              >
                {saving ? '保存中…' : selectedId === 'custom' ? '保存并使用' : '使用这个角色'}
              </button>
            </footer>
          </>
        )}
      </div>
    </div>
  )
}
