import { useEffect, useMemo, useRef, useState } from 'react'
import { categoryName, layerNames, memoryDate, type GardenMemory } from './gardenData'
import GardenArtwork from './GardenArtwork'
import MemoryPlant from './MemoryPlant'

export default function MemoryGarden({
  memories,
  loading,
  error,
  reload,
  onChat,
}: {
  memories: GardenMemory[]
  loading: boolean
  error: string
  reload: () => void
  onChat: (text?: string) => void
}) {
  const [view, setView] = useState<'garden' | 'timeline'>('garden')
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState('all')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [expanded, setExpanded] = useState<string[]>([])
  const categories = [...new Set(memories.map((m) => m.category))]
  const filtered = useMemo(
    () =>
      memories
        .filter(
          (m) =>
            (category === 'all' || m.category === category) &&
            `${m.content} ${m.tags.join(' ')}`.toLowerCase().includes(query.toLowerCase())
        )
        .sort((a, b) => b.created_at - a.created_at),
    [memories, category, query]
  )
  const groups = [...new Set(filtered.map((m) => m.category))].map((name) => ({
    name,
    items: filtered.filter((m) => m.category === name),
  }))
  const selected = filtered.find((m) => m.id === selectedId)
  const detailRef = useRef<HTMLElement>(null)
  const triggerRef = useRef<HTMLElement | null>(null)
  const selectMemory = (id: string) => {
    triggerRef.current =
      document.activeElement instanceof HTMLElement ? document.activeElement : null
    setSelectedId(id)
  }
  const closeDetail = () => {
    setSelectedId(null)
    triggerRef.current?.focus({ preventScroll: true })
  }
  useEffect(() => {
    if (selected) detailRef.current?.focus({ preventScroll: true })
  }, [selected])
  return (
    <main className="memory-page page-enter">
      <header className="memory-page-heading">
        <div>
          <h1>
            我们的记忆花园<span> {memories.length.toString().padStart(2, '0')}</span>
          </h1>
          <p>那些说过的话，长成了关于你的风景。</p>
        </div>
        <div className="view-switch" aria-label="记忆展示方式">
          <button aria-pressed={view === 'garden'} onClick={() => setView('garden')}>
            ♧ 花园
          </button>
          <button aria-pressed={view === 'timeline'} onClick={() => setView('timeline')}>
            ≡ 时间线
          </button>
        </div>
      </header>
      <div className="memory-toolbar">
        <label className="memory-search">
          <span>⌕</span>
          <input
            aria-label="搜索记忆"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="搜索记忆…"
          />
        </label>
        <select
          aria-label="记忆分类"
          value={category}
          onChange={(e) => setCategory(e.target.value)}
        >
          <option value="all">全部分类</option>
          {categories.map((name) => (
            <option key={name} value={name}>
              {categoryName(name)}
            </option>
          ))}
        </select>
        <button className="text-button" onClick={reload}>
          刷新 ↻
        </button>
      </div>
      {loading ? (
        <div className="garden-state">
          <div className="seed-mark">♧</div>
          <h2>正在整理花园…</h2>
        </div>
      ) : error ? (
        <div className="garden-state" role="alert">
          <div className="seed-mark">☁</div>
          <h2>{error}</h2>
          <button className="primary-button" onClick={reload}>
            再试一次
          </button>
        </div>
      ) : memories.length === 0 ? (
        <div className="memory-empty">
          <GardenArtwork />
          <div>
            <h2>
              有些故事，
              <br />
              等着我们一起种下。
            </h2>
            <p>从一次聊天开始。被记住的点滴，会出现在这里。</p>
            <button className="primary-button" onClick={() => onChat()}>
              种下第一段故事 ↗
            </button>
          </div>
        </div>
      ) : (
        <>
          <div className={`memory-layout ${selected ? 'has-selection' : ''}`}>
            <section
              className={`memory-explorer ${view === 'timeline' ? 'is-timeline' : ''}`}
              aria-label={view === 'garden' ? '按类别探索记忆' : '按时间探索记忆'}
            >
              <div className="explorer-caption">
                <span>
                  {view === 'garden'
                    ? '每一株代表一个分类 · 轻点叶片，读一段记忆'
                    : '沿着时间，回到那些瞬间'}
                </span>
                <span>{filtered.length} 段</span>
              </div>
              {!filtered.length && (
                <div className="garden-state">
                  <h2>没有找到这段记忆</h2>
                  <p>换一个关键词，或看看其他分类。</p>
                </div>
              )}
              {view === 'garden' ? (
                <div className={`memory-plots ${groups.length === 1 ? 'single-plot' : ''}`}>
                  {groups.map((group, index) => (
                    <div key={group.name} className={`memory-plot plot-${index % 3}`}>
                      <div className="plot-heading">
                        <span>{String(index + 1).padStart(2, '0')}</span>
                        <h2>{categoryName(group.name)}</h2>
                        <small>{group.items.length} 段</small>
                      </div>
                      <MemoryPlant
                        memories={
                          expanded.includes(group.name) ? group.items : group.items.slice(0, 4)
                        }
                        selectedId={selectedId}
                        onSelect={selectMemory}
                        variation={index}
                      />
                      {group.items.length > 4 && (
                        <button
                          className="plot-expand"
                          onClick={() =>
                            setExpanded((list) =>
                              list.includes(group.name)
                                ? list.filter((n) => n !== group.name)
                                : [...list, group.name]
                            )
                          }
                        >
                          {expanded.includes(group.name)
                            ? '收起'
                            : `再展开 ${group.items.length - 4} 段`}{' '}
                          ↓
                        </button>
                      )}
                    </div>
                  ))}
                </div>
              ) : (
                <div className="memory-timeline">
                  {filtered.map((m) => (
                    <button
                      key={m.id}
                      aria-pressed={m.id === selectedId}
                      onClick={() => selectMemory(m.id)}
                    >
                      <time>{memoryDate(m.created_at)}</time>
                      <span className="timeline-dot" />
                      <div>
                        <small>
                          {categoryName(m.category)} · {layerNames[m.layer]}
                        </small>
                        <p>{m.content}</p>
                        <span>
                          {m.emotion_emoji} {m.tags.slice(0, 3).join(' / ')}
                        </span>
                      </div>
                      <b>↗</b>
                    </button>
                  ))}
                </div>
              )}
            </section>
            {selected ? (
              <aside
                className="memory-detail"
                aria-label="记忆详情"
                ref={detailRef}
                tabIndex={-1}
                onKeyDown={(event) => {
                  if (event.key === 'Escape') {
                    event.stopPropagation()
                    closeDetail()
                  }
                }}
              >
                <div className="detail-heading">
                  <span className="eyebrow">记忆片段</span>
                  <button onClick={closeDetail} aria-label="关闭记忆详情">
                    ×
                  </button>
                </div>
                <span className="detail-category">{categoryName(selected.category)}</span>
                <h2>{selected.content}</h2>
                <time>{memoryDate(selected.created_at)}</time>
                <div className="detail-tags">
                  {selected.tags.map((tag, i) => (
                    <span key={`${tag}-${i}`}>{tag}</span>
                  ))}
                </div>
                <details className="detail-extra">
                  <summary>更多信息</summary>
                  <dl>
                    <div>
                      <dt>记忆层级</dt>
                      <dd>{layerNames[selected.layer]}</dd>
                    </div>
                    <div>
                      <dt>被检索</dt>
                      <dd>{selected.access_count} 次</dd>
                    </div>
                  </dl>
                </details>
                <button
                  className="primary-button"
                  onClick={() => onChat(`我想再聊聊这件事：${selected.content}`)}
                >
                  聊聊这段记忆 ↗
                </button>
                <small className="detail-footnote">会放入对话草稿，由你决定发送。</small>
              </aside>
            ) : null}
          </div>
          <p className="memory-provenance">枝干连接同一分类的真实记忆。</p>
        </>
      )}
    </main>
  )
}
