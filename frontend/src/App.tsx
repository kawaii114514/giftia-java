import { useEffect, useState } from 'react'
import { useStore } from './store'
import { getStoredAccessKey, getStoredAdminKey } from './api'
import ChatArea from './components/ChatArea'
import GardenHome from './components/GardenHome'
import MemoryGarden from './components/MemoryGarden'
import CompanionSettings from './components/CompanionSettings'
import { useGardenMemories } from './components/gardenData'
import ErrorBoundary from './components/ErrorBoundary'
import './App.css'

function AuthGate() {
  const setAuthKeys = useStore((s) => s.setAuthKeys)
  const [accessKey, setAccessKey] = useState(getStoredAccessKey())
  const [adminKey, setAdminKey] = useState(getStoredAdminKey())
  const [error, setError] = useState('')

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!accessKey.trim()) {
      setError('请输入访问密钥')
      return
    }
    setAuthKeys(accessKey.trim(), adminKey.trim())
  }

  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="auth-icon">
          <svg width="40" height="40" viewBox="0 0 24 24" fill="var(--color-accent)">
            <path d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 10.99h7c-.53 4.12-3.28 7.79-7 8.94V12H5V6.3l7-3.11v8.8z" />
          </svg>
        </div>
        <h2 className="auth-title">Giftia 需要验证</h2>
        <p className="auth-desc">此服务已启用访问认证，请输入密钥以继续</p>
        <form className="auth-form" onSubmit={handleSubmit}>
          <label className="auth-label">
            访问密钥
            <input
              type="password"
              className="auth-input"
              value={accessKey}
              onChange={(e) => {
                setAccessKey(e.target.value)
                setError('')
              }}
              placeholder="输入访问密钥"
              autoFocus
            />
          </label>
          <label className="auth-label">
            管理员密钥 <span className="auth-optional">（可选）</span>
            <input
              type="password"
              className="auth-input"
              value={adminKey}
              onChange={(e) => setAdminKey(e.target.value)}
              placeholder="输入管理员密钥"
            />
          </label>
          {error && <div className="auth-error">{error}</div>}
          <button type="submit" className="auth-submit">
            验证并进入
          </button>
        </form>
      </div>
    </div>
  )
}

export default function App() {
  const [page, setPage] = useState<'home' | 'chat' | 'memory'>('home')
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [draft, setDraft] = useState<{ text: string; id: number; userId: string }>()
  const [historyRequest, setHistoryRequest] = useState(0)
  const loadConversations = useStore((s) => s.loadConversations)
  const loadUsers = useStore((s) => s.loadUsers)
  const loadMemoryStats = useStore((s) => s.loadMemoryStats)
  const loadModelConfig = useStore((s) => s.loadModelConfig)
  const currentConvId = useStore((s) => s.currentConvId)
  const loadConversation = useStore((s) => s.loadConversation)
  const checkAuth = useStore((s) => s.checkAuth)
  const authRequired = useStore((s) => s.authRequired)
  const isAuthenticated = useStore((s) => s.isAuthenticated)
  const userId = useStore((s) => s.userId)
  const memoryStats = useStore((s) => s.memoryStats)
  const isLoading = useStore((s) => s.isLoading)
  const switchConversation = useStore((s) => s.switchConversation)
  const garden = useGardenMemories(isAuthenticated ? userId : '', memoryStats?.total || 0)
  const loadCharacterConfig = useStore((s) => s.loadCharacterConfig)
  const openChat = (text?: string) => {
    if (text) setDraft({ text, id: Date.now(), userId })
    setPage('chat')
  }
  useEffect(() => {
    setDraft(undefined)
  }, [userId])

  useEffect(() => {
    if (isAuthenticated)
      loadCharacterConfig().catch(() => {
        /* Settings provides a retry. */
      })
  }, [userId, isAuthenticated, loadCharacterConfig])

  useEffect(() => {
    checkAuth()
  }, [checkAuth])

  useEffect(() => {
    if (!isAuthenticated) return
    void (async () => {
      try {
        await loadUsers()
        await Promise.all([loadConversations(), loadMemoryStats(), loadModelConfig()])
      } catch {
        useStore.setState({ error: '暂时无法连接服务，请稍后重试。' })
      }
    })()
  }, [isAuthenticated, loadConversations, loadUsers, loadMemoryStats, loadModelConfig])

  useEffect(() => {
    if (currentConvId && isAuthenticated) {
      loadConversation(currentConvId).catch(() =>
        useStore.setState({ error: '对话加载失败，请稍后重试。' })
      )
    }
    // 移除 loadConversation 依赖，避免无限循环
  }, [currentConvId, isAuthenticated])

  if (authRequired && !isAuthenticated) {
    return <AuthGate />
  }

  return (
    <ErrorBoundary>
      <div className={`app-layout page-${page}`}>
        <header className="app-nav">
          <button className="brand" onClick={() => setPage('home')} aria-label="Giftia 首页">
            <span className="brand-leaf">❧</span>
            <span>
              Giftia<small>给心事一个生长的地方</small>
            </span>
          </button>
          <nav aria-label="主导航">
            <button
              aria-current={page === 'home' ? 'page' : undefined}
              onClick={() => setPage('home')}
            >
              今日<span>Today</span>
            </button>
            <button
              aria-current={page === 'chat' ? 'page' : undefined}
              onClick={() => setPage('chat')}
            >
              对话<span>Talk</span>
            </button>
            <button
              aria-current={page === 'memory' ? 'page' : undefined}
              onClick={() => setPage('memory')}
            >
              记忆花园<span>Garden</span>
            </button>
          </nav>
          <button
            className="account-button"
            onClick={() => setSettingsOpen(true)}
            aria-label="个人与设置"
          >
            <span>{userId.slice(0, 1).toUpperCase()}</span>
            <i>我的空间</i>
            <b>⌄</b>
          </button>
        </header>
        <div className="page-content" hidden={page !== 'home'}>
          {page === 'home' && (
            <GardenHome
              memories={garden.memories}
              memoryLoading={garden.loading}
              memoryError={garden.error}
              onChat={openChat}
              onHistory={() => {
                setPage('chat')
                setHistoryRequest((n) => n + 1)
              }}
              onMemories={() => {
                garden.reload()
                setPage('memory')
              }}
              onConversation={async (id) => {
                if (isLoading) return
                try {
                  await switchConversation(id)
                  setPage('chat')
                } catch {
                  useStore.setState({ error: '对话加载失败，请稍后重试。' })
                }
              }}
            />
          )}
        </div>
        <div className="chat-page-container" hidden={page !== 'chat'}>
          <ChatArea
            key={userId}
            draft={draft?.userId === userId ? draft : undefined}
            historyRequest={historyRequest}
          />
        </div>
        <div className="page-content" hidden={page !== 'memory'}>
          {page === 'memory' && <MemoryGarden key={userId} {...garden} onChat={openChat} />}
        </div>
        {settingsOpen && <CompanionSettings onClose={() => setSettingsOpen(false)} />}
      </div>
    </ErrorBoundary>
  )
}
