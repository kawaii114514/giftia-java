import { useEffect, useState } from 'react'
import { useStore } from '../store'
import GardenArtwork from './GardenArtwork'
import BreathingPause from './BreathingPause'
import { localDay, type GardenMemory } from './gardenData'

const moods = [
  { icon: '☀', label: '晴朗' },
  { icon: '☁', label: '平静' },
  { icon: '☂', label: '低落' },
  { icon: '≋', label: '纷乱' },
  { icon: '✧', label: '期待' },
]
type MoodEntry = { mood: string; note: string }
function readMood(userId: string, day: string): MoodEntry | null {
  try {
    return JSON.parse(
      localStorage.getItem(`giftia:mood:${encodeURIComponent(userId)}:${day}`) || 'null'
    )
  } catch {
    return null
  }
}
export default function GardenHome({
  memories,
  memoryLoading,
  memoryError,
  onChat,
  onMemories,
  onConversation,
  onHistory,
}: {
  memories: GardenMemory[]
  memoryLoading: boolean
  memoryError: string
  onChat: (draft?: string) => void
  onMemories: () => void
  onConversation: (id: string) => void
  onHistory: () => void
}) {
  const userId = useStore((s) => s.userId)
  const conversations = useStore((s) => s.conversations)
  const isLoading = useStore((s) => s.isLoading)
  const [day, setDay] = useState(localDay())
  const [mood, setMood] = useState<MoodEntry | null>(() => readMood(userId, day))
  const [note, setNote] = useState(mood?.note || '')
  const [storageError, setStorageError] = useState('')
  const [breathing, setBreathing] = useState(false)
  const [featuredId, setFeaturedId] = useState<string | null>(null)
  useEffect(() => {
    const entry = readMood(userId, day)
    setMood(entry)
    setNote(entry?.note || '')
    setStorageError('')
  }, [userId, day])
  useEffect(() => {
    const timer = window.setInterval(() => setDay(localDay()), 30000)
    return () => clearInterval(timer)
  }, [])
  const saveMood = (label: string, value = note) => {
    const entry = { mood: label, note: value }
    try {
      localStorage.setItem(
        `giftia:mood:${encodeURIComponent(userId)}:${day}`,
        JSON.stringify(entry)
      )
      setMood(entry)
      setStorageError('')
    } catch {
      setStorageError('浏览器未能保存，请检查存储设置。')
    }
  }
  const growing = [...memories].sort((a, b) => b.created_at - a.created_at).slice(0, 4)
  const latest = memories.find((m) => m.id === featuredId) || growing[0]
  return (
    <main className="garden-home page-enter">
      <div className="home-date">
        <span>
          {new Date(`${day}T12:00:00`).toLocaleDateString('zh-CN', {
            month: 'long',
            day: 'numeric',
            weekday: 'long',
          })}
        </span>
      </div>
      <section className="home-hero">
        <div className="home-intro">
          <span className="eyebrow">
            <i /> 留一点时间给自己
          </span>
          <h1>
            每一个你，
            <br />
            都值得<span>被记得。</span>
          </h1>
          <p>
            让心事落地，让日常生长。
            <br />
            今天，也想听听你的声音。
          </p>
          <button className="primary-button" onClick={() => onChat()}>
            和 Giftia 聊聊 <span>↗</span>
          </button>
          <div className="mood-checkin">
            <div className="mood-title">
              <h2>今天的心情，是什么天气？</h2>
              <span>{mood ? '已记下' : '每日一签'}</span>
            </div>
            <div className="mood-options">
              {moods.map((item) => (
                <button
                  key={item.label}
                  aria-pressed={mood?.mood === item.label}
                  onClick={() => saveMood(item.label)}
                >
                  <span>{item.icon}</span>
                  <small>{item.label}</small>
                </button>
              ))}
            </div>
            {mood && (
              <div className="mood-note">
                <input
                  aria-label="心情小记"
                  maxLength={140}
                  value={note}
                  onChange={(e) => setNote(e.target.value)}
                  placeholder="留一句话给今天的自己…"
                  onBlur={() => {
                    if (note !== mood.note) saveMood(mood.mood)
                  }}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') saveMood(mood.mood)
                  }}
                />
                <button
                  onClick={() => {
                    saveMood(mood.mood)
                    onChat(`今天我的心情有些${mood.mood}。${note}`)
                  }}
                  disabled={isLoading}
                >
                  聊聊 →
                </button>
              </div>
            )}
            <small className="mood-storage" role="status">
              {storageError || '心情签仅保存在此浏览器，按用户分别记录。'}
            </small>
          </div>
        </div>
        <div className="home-garden-scene">
          <div className="scene-label">
            <i>一点一滴，慢慢生长</i>
          </div>
          <GardenArtwork />
          <div className="living-leaves" aria-label="轻点植物，读一段真实记忆">
            {growing.map((m, index) => (
              <button
                key={m.id}
                className={`living-leaf living-leaf-${index}`}
                aria-label={`阅读记忆：${m.content}`}
                aria-pressed={latest?.id === m.id}
                onClick={() => setFeaturedId(m.id)}
              >
                <span>✧</span>
                <small>{m.content}</small>
              </button>
            ))}
          </div>
          <button className="garden-memory-note" onClick={onMemories}>
            <span className="note-pin" />
            <small>
              {memoryLoading
                ? '正在走进花园'
                : memoryError
                  ? '记忆暂未连接'
                  : latest
                    ? '花园里的一个片段'
                    : '故事从这里发芽'}
            </small>
            <p>{latest ? latest.content : '你分享过的点滴，会在这里留下痕迹。'}</p>
            <span className="note-link">走进记忆花园 ↗</span>
          </button>
          <div className="scene-caption">
            <span>{growing.length ? '轻点叶片，读一段记忆' : '从一次聊天，开始生长'}</span>
            <span>{memoryLoading ? '…' : memories.length} 段记忆</span>
          </div>
        </div>
      </section>
      <section className="home-bottom">
        <button className="pause-invitation" onClick={() => setBreathing(true)}>
          <span className="pause-symbol">◎</span>
          <span>
            <small>不急着开口，也没关系</small>
            <strong>一起，慢下来。</strong>
          </span>
          <span className="pause-duration">
            60 秒呼吸 <b>↗</b>
          </span>
        </button>
        <div className="recent-conversation">
          <div>
            <span className="eyebrow">接着上次的话</span>
            <button className="text-button" disabled={isLoading} onClick={onHistory}>
              全部对话 ↗
            </button>
          </div>
          {conversations
            .filter((c) => c.message_count > 0)
            .slice(0, 2)
            .map((c) => (
              <button disabled={isLoading} key={c.id} onClick={() => onConversation(c.id)}>
                <strong>{c.title}</strong>
                <span>
                  {c.message_count} 条消息 <b>→</b>
                </span>
              </button>
            ))}
          {!conversations.some((c) => c.message_count > 0) && (
            <p>还没有说出口的话，也可以从一句「你好」开始。</p>
          )}
        </div>
      </section>
      {breathing && <BreathingPause onClose={() => setBreathing(false)} />}
    </main>
  )
}
