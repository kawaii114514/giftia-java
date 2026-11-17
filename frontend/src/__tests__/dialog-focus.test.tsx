import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import PromptDialog from '../components/PromptDialog'
import { useStore } from '../store'
import { characterConfig } from './characterFixtures'

const originalState = useStore.getState()

beforeEach(() => {
  useStore.setState({
    ...originalState,
    userId: 'user1',
    characterConfig: characterConfig(),
    loadCharacterConfig: vi.fn().mockResolvedValue(undefined),
    updateCharacterConfig: vi.fn().mockResolvedValue(undefined),
  })
})

afterEach(() => {
  cleanup()
  useStore.setState(originalState)
})

describe('角色与人设', () => {
  it('复制角色后编辑并保存，Escape 只关闭弹窗并归还焦点', async () => {
    const update = useStore.getState().updateCharacterConfig
    const onNavigationEscape = vi.fn()
    function Harness() {
      const [open, setOpen] = useState(false)
      return (
        <div
          onKeyDown={(event) => {
            if (event.key === 'Escape') onNavigationEscape()
          }}
        >
          <button onClick={() => setOpen(true)}>打开人设</button>
          {open && <PromptDialog onClose={() => setOpen(false)} />}
        </div>
      )
    }
    render(<Harness />)
    const trigger = screen.getByRole('button', { name: '打开人设' })
    trigger.focus()
    fireEvent.click(trigger)
    fireEvent.click(await screen.findByRole('button', { name: /复制并自定义/ }))
    fireEvent.change(screen.getByRole('textbox', { name: '角色名字' }), {
      target: { value: '小芽' },
    })
    fireEvent.change(screen.getByRole('textbox', { name: '性格与说话方式' }), {
      target: { value: '耐心倾听' },
    })
    const save = screen.getByRole('button', { name: '保存并使用' })
    save.focus()
    fireEvent.click(save)
    await screen.findByText(/已使用「小芽」/)
    expect(update).toHaveBeenCalledWith({
      character: expect.objectContaining({
        name: '小芽',
        personality: '耐心倾听',
        description: 'Giftia的身份',
      }),
    })
    await waitFor(() =>
      expect(screen.getByRole('dialog').contains(document.activeElement)).toBe(true)
    )
    fireEvent.keyDown(document.activeElement!, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(document.activeElement).toBe(trigger)
    expect(onNavigationEscape).not.toHaveBeenCalled()
  })

  it('预设与固定规则只读，选择预览后才应用', async () => {
    const update = useStore.getState().updateCharacterConfig
    render(<PromptDialog onClose={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: /知夏/ }))
    expect(screen.queryAllByRole('textbox')).toHaveLength(0)
    expect(screen.getByText('系统预设 · 只读')).toBeTruthy()
    expect(screen.getByText('固定铁律：禁止编造记忆')).toBeTruthy()
    expect(update).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '使用这个角色' }))
    await screen.findByText(/已使用「知夏」/)
    expect(update).toHaveBeenCalledWith({ character_id: 'zhixia' })
  })

  it('回复中无法应用角色，空名字也不能保存', async () => {
    useStore.setState({ isLoading: true })
    render(<PromptDialog onClose={vi.fn()} />)
    const apply = await screen.findByRole('button', { name: '使用这个角色' })
    expect(apply.hasAttribute('disabled')).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: /我的角色卡/ }))
    expect(screen.getByRole('button', { name: '保存并使用' }).hasAttribute('disabled')).toBe(true)
    expect(useStore.getState().updateCharacterConfig).not.toHaveBeenCalled()
  })

  it('已有缓存时加载失败也能重新加载', async () => {
    const load = vi
      .fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(undefined)
    useStore.setState({ loadCharacterConfig: load })
    render(<PromptDialog onClose={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: '重新加载' }))
    await screen.findByRole('button', { name: /复制并自定义/ })
    expect(load).toHaveBeenCalledTimes(2)
  })
})
