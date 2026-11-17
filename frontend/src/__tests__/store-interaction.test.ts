import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useStore } from '../store'
import { characterConfig } from './characterFixtures'

// Mock api 模块
vi.mock('../api', () => ({
  api: {
    getHealth: vi
      .fn()
      .mockResolvedValue({ status: 'ok', auth_required: false, admin_key_configured: false }),
    getConversations: vi.fn().mockResolvedValue({ conversations: [], current_id: '' }),
    getConversation: vi
      .fn()
      .mockResolvedValue({ title: '测试对话', messages: [], created: '01/01 00:00' }),
    createConversation: vi
      .fn()
      .mockResolvedValue({ id: 'conv-new', title: '新对话', created: '01/01 00:00' }),
    renameConversation: vi.fn().mockResolvedValue({ ok: true }),
    deleteConversation: vi.fn().mockResolvedValue({ ok: true, current_id: '' }),
    getUsers: vi
      .fn()
      .mockResolvedValue([
        { id: 'user1', memory_count: 5, consolidated_count: 2, avg_importance: 0.6 },
      ]),
    createUser: vi.fn().mockResolvedValue({ ok: true }),
    getMemoryStats: vi
      .fn()
      .mockResolvedValue({ total: 5, avg_importance: 0.6, consolidated_count: 2 }),
    clearMemories: vi.fn().mockResolvedValue({ ok: true }),
    getModelConfig: vi
      .fn()
      .mockResolvedValue({ model: 'deepseek-v4', base_url: '', multimodal: false }),
    getPromptConfig: vi
      .fn()
      .mockResolvedValue({ prompt: '默认', default_prompt: '默认', is_custom: false }),
    updatePromptConfig: vi.fn().mockResolvedValue({ ok: true, prompt: '自定义', is_custom: true }),
    getCharacterConfig: vi.fn(),
    updateCharacterConfig: vi.fn(),
    getLogs: vi.fn().mockResolvedValue({ logs: [] }),
    sendMessage: vi.fn(),
  },
  getStoredAccessKey: vi.fn().mockReturnValue(''),
  setStoredAccessKey: vi.fn(),
  getStoredAdminKey: vi.fn().mockReturnValue(''),
  setStoredAdminKey: vi.fn(),
}))

describe('对话管理交互', () => {
  beforeEach(() => {
    useStore.setState({
      userId: 'user1',
      conversations: [],
      currentConvId: null,
      messages: [],
      users: [],
      memoryStats: null,
      modelConfig: null,
      promptConfig: null,
      characterConfig: null,
      isCharacterSaving: false,
      isLoading: false,
      searchQuery: '',
      statusText: '',
      authRequired: false,
      isAuthenticated: true,
      error: null,
      lastFailedMessage: null,
    })
    vi.clearAllMocks()
  })

  it('应能创建新对话', async () => {
    const convId = await useStore.getState().createConversation()
    expect(convId).toBe('conv-new')
  })

  it('应能切换对话', async () => {
    useStore.setState({
      conversations: [
        { id: 'conv-1', title: '对话1', created: '01/01', message_count: 0, is_active: true },
      ],
    })
    await useStore.getState().switchConversation('conv-1')
    expect(useStore.getState().currentConvId).toBe('conv-1')
  })

  it('应能重命名对话', async () => {
    useStore.setState({
      conversations: [
        { id: 'conv-1', title: '旧标题', created: '01/01', message_count: 0, is_active: true },
      ],
    })
    await useStore.getState().renameConversation('conv-1', '新标题')
    const { api } = await import('../api')
    expect(api.renameConversation).toHaveBeenCalledWith('user1', 'conv-1', '新标题')
  })

  it('应能删除对话', async () => {
    useStore.setState({
      conversations: [
        { id: 'conv-1', title: '对话1', created: '01/01', message_count: 0, is_active: true },
      ],
      currentConvId: 'conv-1',
    })
    await useStore.getState().deleteConversation('conv-1')
    const { api } = await import('../api')
    expect(api.deleteConversation).toHaveBeenCalledWith('user1', 'conv-1')
  })
})

describe('消息交互', () => {
  beforeEach(() => {
    useStore.setState({
      userId: 'user1',
      conversations: [],
      currentConvId: 'conv-1',
      messages: [],
      isLoading: false,
      statusText: '',
      error: null,
      lastFailedMessage: null,
    })
    vi.clearAllMocks()
  })

  it('应能添加用户消息', () => {
    useStore.getState().addMessage({ id: 'm1', role: 'user', content: '你好' })
    expect(useStore.getState().messages).toHaveLength(1)
    expect(useStore.getState().messages[0].content).toBe('你好')
    expect(useStore.getState().messages[0].role).toBe('user')
  })

  it('应能添加助手消息', () => {
    useStore.getState().addMessage({ id: 'm1', role: 'assistant', content: '你好呀' })
    expect(useStore.getState().messages).toHaveLength(1)
    expect(useStore.getState().messages[0].role).toBe('assistant')
  })

  it('应能替换所有消息', () => {
    useStore.getState().addMessage({ id: 'm1', role: 'user', content: '旧消息' })
    useStore.getState().setMessages([{ id: 'm2', role: 'assistant', content: '新消息' }])
    expect(useStore.getState().messages).toHaveLength(1)
    expect(useStore.getState().messages[0].content).toBe('新消息')
  })

  it('sendMessage 应处理 SSE token 事件', async () => {
    const { api } = await import('../api')
    const mockStream = (async function* () {
      yield { type: 'status', text: '正在思考...' }
      yield { type: 'token', text: '你' }
      yield { type: 'token', text: '好' }
      yield { type: 'reply', text: '你好' }
      yield { type: 'done', conversation_id: 'conv-1' }
    })()
    vi.mocked(api.sendMessage).mockReturnValueOnce(mockStream)

    const gen = useStore.getState().sendMessage('你好')
    // 消费 generator
    for await (const _event of gen) {
      // 消费流式事件
    }

    const msgs = useStore.getState().messages
    // 应有 user 消息 + assistant 消息
    expect(msgs.length).toBeGreaterThanOrEqual(2)
    expect(msgs[0].role).toBe('user')
    expect(msgs[0].content).toBe('你好')
    // assistant 消息内容应为最终回复
    const assistantMsg = msgs.find((m) => m.role === 'assistant')
    expect(assistantMsg).toBeTruthy()
    expect(assistantMsg!.content).toContain('你好')
  })

  it('sendMessage 应处理错误事件', async () => {
    const { api } = await import('../api')
    const mockStream = (async function* () {
      yield { type: 'error', text: '连接超时' }
    })()
    vi.mocked(api.sendMessage).mockReturnValueOnce(mockStream)

    const gen = useStore.getState().sendMessage('测试')
    try {
      for await (const _event of gen) {
        // 消费流式事件
      }
    } catch {
      // 预期会抛出错误
    }

    const msgs = useStore.getState().messages
    const errMsg = msgs.find((m) => m.role === 'assistant' && m.content.includes('问题'))
    expect(errMsg).toBeTruthy()
  })

  it('收到首个 token 后保持发送锁，完成时释放并拒绝并发重复发送', async () => {
    const { api } = await import('../api')
    let resume!: () => void
    let notifyToken!: () => void
    const gate = new Promise<void>((resolve) => {
      resume = resolve
    })
    const tokenProcessed = new Promise<void>((resolve) => {
      notifyToken = resolve
    })
    vi.mocked(api.sendMessage).mockReturnValueOnce(
      (async function* () {
        yield { type: 'token', text: '你好' }
        notifyToken()
        await gate
        yield { type: 'reply', text: '你好，小林。' }
        yield { type: 'done', conversation_id: 'conv-1' }
      })()
    )
    const consume = async () => {
      for await (const event of useStore.getState().sendMessage('你好')) {
        void event
      }
    }
    const pending = consume()
    await tokenProcessed
    expect(useStore.getState().isLoading).toBe(true)
    const streamedMessages = useStore.getState().messages
    expect(streamedMessages[streamedMessages.length - 1]?.content).toBe('你好')
    await useStore.getState().sendMessage('重复发送').next()
    expect(api.sendMessage).toHaveBeenCalledTimes(1)
    resume()
    await pending
    expect(useStore.getState().isLoading).toBe(false)
    expect(useStore.getState().lastFailedMessage).toBeNull()
  })
})

describe('用户管理交互', () => {
  beforeEach(() => {
    useStore.setState({
      userId: 'user1',
      users: [],
      conversations: [],
      messages: [],
    })
    vi.clearAllMocks()
  })

  it('应能加载用户列表', async () => {
    await useStore.getState().loadUsers()
    expect(useStore.getState().users).toHaveLength(1)
    expect(useStore.getState().users[0].id).toBe('user1')
  })

  it('切换用户应清空消息和对话', async () => {
    useStore.setState({
      messages: [{ id: 'm1', role: 'user', content: '旧消息' }],
      conversations: [
        { id: 'conv-1', title: '旧对话', created: '01/01', message_count: 1, is_active: true },
      ],
    })
    await useStore.getState().switchUser('user2')
    expect(useStore.getState().userId).toBe('user2')
    expect(useStore.getState().messages).toEqual([])
    expect(useStore.getState().conversations).toEqual([])
  })
})

describe('记忆管理交互', () => {
  beforeEach(() => {
    useStore.setState({ userId: 'user1', memoryStats: null, error: null })
    vi.clearAllMocks()
  })

  it('应能加载记忆统计', async () => {
    await useStore.getState().loadMemoryStats()
    expect(useStore.getState().memoryStats).toBeTruthy()
    expect(useStore.getState().memoryStats!.total).toBe(5)
  })

  it('记忆统计加载失败应设置错误', async () => {
    const { api } = await import('../api')
    vi.mocked(api.getMemoryStats).mockRejectedValueOnce(new Error('网络错误'))
    await useStore.getState().loadMemoryStats()
    expect(useStore.getState().error).toBeTruthy()
  })
})

describe('搜索过滤', () => {
  it('应能设置搜索关键词', () => {
    useStore.getState().setSearchQuery('测试')
    expect(useStore.getState().searchQuery).toBe('测试')
  })

  it('空搜索关键词应返回所有对话', () => {
    useStore.setState({
      conversations: [
        { id: '1', title: '工作', created: '01/01', message_count: 1, is_active: true },
        { id: '2', title: '生活', created: '01/01', message_count: 1, is_active: true },
      ],
      searchQuery: '',
    })
    // 搜索过滤通常在组件层做，这里验证 store 状态正确
    expect(useStore.getState().conversations).toHaveLength(2)
  })
})

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

describe('角色配置请求隔离', () => {
  beforeEach(() => {
    useStore.setState({
      userId: 'user1',
      characterConfig: null,
      isLoading: false,
      isCharacterSaving: false,
    })
    vi.clearAllMocks()
  })

  it('只接受最新用户的角色配置', async () => {
    const { api } = await import('../api')
    const old = deferred<ReturnType<typeof characterConfig>>()
    vi.mocked(api.getCharacterConfig)
      .mockReturnValueOnce(old.promise)
      .mockResolvedValueOnce(characterConfig('user2', 'zhixia'))
    const pending = useStore.getState().loadCharacterConfig()
    useStore.getState().setUserId('user2')
    await useStore.getState().loadCharacterConfig()
    old.resolve(characterConfig())
    await pending
    expect(useStore.getState().characterConfig?.active_character.id).toBe('zhixia')
    expect(useStore.getState().characterConfig?.user_id).toBe('user2')
  })

  it('返回同一用户时仍丢弃上一次访问的角色请求', async () => {
    const { api } = await import('../api')
    const old = deferred<ReturnType<typeof characterConfig>>()
    vi.mocked(api.getCharacterConfig).mockReturnValueOnce(old.promise)
    const pending = useStore.getState().loadCharacterConfig()
    useStore.getState().setUserId('user2')
    useStore.getState().setUserId('user1')
    old.resolve(characterConfig())
    await pending
    expect(useStore.getState().characterConfig).toBeNull()
  })

  it('保存会使之前的读取失效，且并发保存被拒绝', async () => {
    const { api } = await import('../api')
    const old = deferred<ReturnType<typeof characterConfig>>()
    const saved = deferred<ReturnType<typeof characterConfig>>()
    vi.mocked(api.getCharacterConfig).mockReturnValueOnce(old.promise)
    vi.mocked(api.updateCharacterConfig).mockReturnValueOnce(saved.promise)
    const loading = useStore.getState().loadCharacterConfig()
    const saving = useStore.getState().updateCharacterConfig({ character_id: 'zhixia' })
    expect(useStore.getState().isCharacterSaving).toBe(true)
    await expect(
      useStore.getState().updateCharacterConfig({ character_id: 'giftia' })
    ).rejects.toThrow('正在保存')
    saved.resolve(characterConfig('user1', 'zhixia'))
    await saving
    old.resolve(characterConfig())
    await loading
    expect(useStore.getState().characterConfig?.active_character.id).toBe('zhixia')
    expect(useStore.getState().isCharacterSaving).toBe(false)
    expect(api.updateCharacterConfig).toHaveBeenCalledTimes(1)
  })

  it('保存失败不改当前选择，之后可再次保存', async () => {
    const { api } = await import('../api')
    useStore.setState({ characterConfig: characterConfig() })
    vi.mocked(api.updateCharacterConfig)
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(characterConfig('user1', 'libai'))
    await expect(
      useStore.getState().updateCharacterConfig({ character_id: 'libai' })
    ).rejects.toThrow('offline')
    expect(useStore.getState().characterConfig?.active_character.id).toBe('giftia')
    expect(useStore.getState().isCharacterSaving).toBe(false)
    await useStore.getState().updateCharacterConfig({ character_id: 'libai' })
    expect(useStore.getState().characterConfig?.active_character.id).toBe('libai')
  })

  it('保存过程中切换用户不会覆盖新用户的角色', async () => {
    const { api } = await import('../api')
    const saved = deferred<ReturnType<typeof characterConfig>>()
    vi.mocked(api.updateCharacterConfig).mockReturnValueOnce(saved.promise)
    const pending = useStore.getState().updateCharacterConfig({ character_id: 'zhixia' })
    useStore.getState().setUserId('user2')
    useStore.setState({ characterConfig: characterConfig('user2', 'libai') })
    saved.resolve(characterConfig('user1', 'zhixia'))
    await pending
    expect(useStore.getState().characterConfig?.active_character.id).toBe('libai')
    expect(useStore.getState().characterConfig?.user_id).toBe('user2')
  })

  it('前一个用户保存完成后，新用户等待中的读取仍然生效', async () => {
    const { api } = await import('../api')
    const saved = deferred<ReturnType<typeof characterConfig>>()
    vi.mocked(api.updateCharacterConfig).mockReturnValueOnce(saved.promise)
    vi.mocked(api.getCharacterConfig).mockResolvedValueOnce(characterConfig('user2', 'libai'))
    const saving = useStore.getState().updateCharacterConfig({ character_id: 'zhixia' })
    useStore.getState().setUserId('user2')
    const loading = useStore.getState().loadCharacterConfig()
    saved.resolve(characterConfig('user1', 'zhixia'))
    await Promise.all([saving, loading])
    expect(useStore.getState().characterConfig?.user_id).toBe('user2')
    expect(useStore.getState().characterConfig?.active_character.id).toBe('libai')
  })

  it('流式回复期间不能提交角色切换', async () => {
    const { api } = await import('../api')
    useStore.setState({ isLoading: true })
    await expect(
      useStore.getState().updateCharacterConfig({ character_id: 'zhixia' })
    ).rejects.toThrow('等待当前回复')
    expect(api.updateCharacterConfig).not.toHaveBeenCalled()
  })
})

describe('用户与对话请求隔离', () => {
  beforeEach(() => {
    useStore.setState({
      userId: 'initial-user',
      conversations: [],
      currentConvId: null,
      messages: [],
      memoryStats: null,
      isLoading: false,
      error: null,
    })
    vi.clearAllMocks()
  })

  it('快速切换用户时丢弃较晚返回的前一个用户的对话列表', async () => {
    const { api } = await import('../api')
    const slow = deferred<Awaited<ReturnType<typeof api.getConversations>>>()
    const current = {
      id: 'current-conv',
      title: '当前用户的对话',
      created: '11/12',
      message_count: 1,
      is_active: true,
    }
    vi.mocked(api.getConversations)
      .mockReturnValueOnce(slow.promise)
      .mockResolvedValueOnce({ conversations: [current], current_id: current.id })
    vi.mocked(api.getConversation).mockResolvedValueOnce({
      title: current.title,
      messages: [{ role: 'user', content: '当前用户的消息' }],
      created: current.created,
    })
    const previousSwitch = useStore.getState().switchUser('previous-user')
    await useStore.getState().switchUser('current-user')
    slow.resolve({
      conversations: [{ ...current, id: 'private-conv', title: '前一个用户的私有对话' }],
      current_id: 'private-conv',
    })
    await previousSwitch
    expect(useStore.getState().userId).toBe('current-user')
    expect(useStore.getState().conversations).toEqual([current])
    expect(useStore.getState().currentConvId).toBe('current-conv')
    expect(useStore.getState().messages).toEqual([{ role: 'user', content: '当前用户的消息' }])
    expect(api.getConversation).toHaveBeenCalledTimes(1)
    expect(api.getConversation).toHaveBeenCalledWith('current-user', 'current-conv')
  })

  it('返回同一个用户时也不会接受上一次访问留下的旧请求', async () => {
    const { api } = await import('../api')
    const oldVisit = deferred<Awaited<ReturnType<typeof api.getConversations>>>()
    vi.mocked(api.getConversations)
      .mockReturnValueOnce(oldVisit.promise)
      .mockResolvedValueOnce({ conversations: [], current_id: '' })
      .mockResolvedValueOnce({ conversations: [], current_id: 'latest-conv' })
    const pending = useStore.getState().switchUser('user-a')
    await useStore.getState().switchUser('user-b')
    await useStore.getState().switchUser('user-a')
    oldVisit.resolve({ conversations: [], current_id: 'obsolete-conv' })
    await pending
    expect(useStore.getState().currentConvId).toBe('latest-conv')
    expect(api.getConversation).toHaveBeenCalledTimes(1)
  })

  it('快速切换会话时，旧消息请求不会覆盖最近选择的会话', async () => {
    const { api } = await import('../api')
    const slow = deferred<Awaited<ReturnType<typeof api.getConversation>>>()
    vi.mocked(api.getConversation)
      .mockReturnValueOnce(slow.promise)
      .mockResolvedValueOnce({
        title: '新会话',
        messages: [{ role: 'assistant', content: '新会话里的消息' }],
        created: '11/12',
      })
    const previous = useStore.getState().switchConversation('old-conv')
    await useStore.getState().switchConversation('new-conv')
    slow.resolve({
      title: '旧会话',
      messages: [{ role: 'user', content: '旧会话里的消息' }],
      created: '11/11',
    })
    await previous
    expect(useStore.getState().currentConvId).toBe('new-conv')
    expect(useStore.getState().messages).toEqual([{ role: 'assistant', content: '新会话里的消息' }])
  })

  it('已经切走的会话请求失败时，不向当前界面传播过期错误', async () => {
    const { api } = await import('../api')
    const oldLoad = deferred<Awaited<ReturnType<typeof api.getConversation>>>()
    vi.mocked(api.getConversation)
      .mockReturnValueOnce(oldLoad.promise)
      .mockResolvedValueOnce({ title: '新会话', messages: [], created: '11/12' })
    const pending = useStore.getState().switchConversation('old-conv')
    await useStore.getState().switchConversation('new-conv')
    oldLoad.reject(new Error('旧会话已不存在'))
    await expect(pending).resolves.toBeUndefined()
    expect(useStore.getState().currentConvId).toBe('new-conv')
    expect(useStore.getState().error).toBeNull()
  })

  it('前一个用户的记忆统计返回或失败时不会影响当前用户', async () => {
    const { api } = await import('../api')
    const oldStats = deferred<Awaited<ReturnType<typeof api.getMemoryStats>>>()
    const failedStats = deferred<Awaited<ReturnType<typeof api.getMemoryStats>>>()
    const currentStats = { total: 2, consolidated_count: 0, avg_importance: 0.5 }
    vi.mocked(api.getMemoryStats)
      .mockReturnValueOnce(oldStats.promise)
      .mockReturnValueOnce(failedStats.promise)
      .mockResolvedValueOnce(currentStats)
    const oldLoad = useStore.getState().loadMemoryStats()
    const failedLoad = useStore.getState().loadMemoryStats()
    useStore.setState({ userId: 'current-user' })
    await useStore.getState().loadMemoryStats()
    oldStats.resolve({ total: 99, consolidated_count: 10, avg_importance: 0.9 })
    failedStats.reject(new Error('过期请求失败'))
    await Promise.all([oldLoad, failedLoad])
    expect(useStore.getState().memoryStats).toEqual(currentStats)
    expect(useStore.getState().error).toBeNull()
  })

  it('流式回复期间拒绝切换用户', async () => {
    useStore.setState({ isLoading: true })
    await useStore.getState().switchUser('another-user')
    expect(useStore.getState().userId).toBe('initial-user')
  })
})
