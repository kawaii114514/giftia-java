import { useEffect, useRef, useState } from 'react'
import { useStore } from '../store'
import MessageBubble from './MessageBubble'
import ChatInput from './ChatInput'
import ConversationList from './ConversationList'
import { useDialogFocus } from './useDialogFocus'
import './ChatArea.css'

export default function ChatArea({
  draft: incomingDraft,
  historyRequest = 0,
}: {
  draft?: { text: string; id: number }
  historyRequest?: number
}) {
  const [draft, setDraft] = useState<{ text: string; id: number }>()
  const [historyOpen, setHistoryOpen] = useState(false)
  const createConversation = useStore((s) => s.createConversation)
  const messages = useStore((s) => s.messages)
  const isLoading = useStore((s) => s.isLoading)
  const character = useStore((s) => s.characterConfig?.active_character)
  const statusText = useStore((s) => s.statusText)
  const error = useStore((s) => s.error)
  const lastFailedMessage = useStore((s) => s.lastFailedMessage)
  const retryLastMessage = useStore((s) => s.retryLastMessage)
  const conversations = useStore((s) => s.conversations)
  const currentConvId = useStore((s) => s.currentConvId)
  const bottomRef = useRef<HTMLDivElement>(null)
  const lastHistoryRequest = useRef(historyRequest)

  const currentConv = conversations.find((c) => c.id === currentConvId)
  useEffect(() => {
    if (incomingDraft) setDraft(incomingDraft)
  }, [incomingDraft])
  useEffect(() => {
    if (historyRequest !== lastHistoryRequest.current) setHistoryOpen(true)
    lastHistoryRequest.current = historyRequest
  }, [historyRequest])

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'auto' })
  }, [messages, statusText])

  return (
    <div className={`chat-area ${messages.length === 0 && !isLoading ? 'chat-area--empty' : ''}`}>
      <header className="chat-header">
        <div className="chat-header-main">
          <h2 className="chat-header-title">{currentConv?.title || 'Giftia'}</h2>
          {character && <span className="chat-character-label">与 {character.name} 对话</span>}
        </div>
        <div className="chat-header-meta">
          <button
            className="text-button"
            disabled={isLoading}
            onClick={async () => {
              try {
                await createConversation()
              } catch {
                useStore.setState({ error: '新对话创建失败，请重试。' })
              }
            }}
          >
            ＋ 新对话
          </button>
          <button
            className="history-trigger"
            disabled={isLoading}
            onClick={() => setHistoryOpen(true)}
          >
            往日对话 <span>☷</span>
          </button>
        </div>
      </header>

      <div className="chat-messages">
        {messages.length === 0 && !isLoading && (
          <div className="chat-welcome">
            <div className="chat-presence">
              <span>❧</span>
              <i />
            </div>
            <h2 className="chat-welcome-title">
              {character?.name || 'Giftia'}，<em>在这里。</em>
            </h2>
            <p className="chat-welcome-text">
              {character?.first_mes ||
                '开心、难过，或是还没想好怎么说。这里有一个位置，留给此刻的你。'}
            </p>
            <div className="conversation-starters" aria-label="选择一个话题">
              {[
                ['01', '说说今天', '今天有件事，我想和你分享。', '分享一个微小的瞬间'],
                ['02', '放下烦恼', '我最近有些烦恼，想找你聊聊。', '让心事有个落脚的地方'],
                ['03', '随便聊聊', '没有特别的事，就是想和你聊聊天。', '不必准备好一个话题'],
              ].map(([number, title, text, subtitle]) => (
                <button
                  key={number}
                  title={subtitle}
                  onClick={() => setDraft({ text, id: Date.now() })}
                >
                  <strong>
                    {title} <span>↗</span>
                  </strong>
                </button>
              ))}
            </div>
          </div>
        )}

        {messages.map((msg) => (
          <MessageBubble key={msg.id || `${msg.role}_${msg.content.slice(0, 20)}`} message={msg} />
        ))}

        {isLoading && (
          <div className="chat-thinking">
            <div className="chat-thinking-avatar">忆</div>
            <div className="chat-thinking-bubble">
              <span className="chat-thinking-dots">
                <span />
                <span />
                <span />
              </span>
              {statusText && <span className="chat-thinking-text">{statusText}</span>}
            </div>
          </div>
        )}

        {error && !isLoading && (
          <div className="chat-error-banner">
            <span>{error}</span>
            {lastFailedMessage && (
              <button
                className="chat-retry-btn"
                onClick={async () => {
                  for await (const _ of retryLastMessage()) {
                    void _
                  }
                }}
              >
                重试
              </button>
            )}
            <button className="chat-dismiss-btn" onClick={() => useStore.setState({ error: null })}>
              ✕
            </button>
          </div>
        )}

        <div ref={bottomRef} />
      </div>

      <ChatInput key={useStore.getState().userId} draft={draft} />
      {historyOpen && <HistoryDrawer onClose={() => setHistoryOpen(false)} />}
    </div>
  )
}

function HistoryDrawer({ onClose }: { onClose: () => void }) {
  const ref = useDialogFocus(onClose)
  return (
    <div className="history-overlay" onClick={onClose}>
      <div
        className="history-drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby="history-title"
        tabIndex={-1}
        ref={ref}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="history-heading">
          <div>
            <h2 id="history-title">往日对话</h2>
          </div>
          <button className="close-round" aria-label="关闭往日对话" onClick={onClose}>
            ×
          </button>
        </div>
        <ConversationList onSelect={onClose} />
      </div>
    </div>
  )
}
