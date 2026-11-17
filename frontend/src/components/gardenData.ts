import { useEffect, useState } from 'react'
import { api } from '../api'
import type { MemoryDetail } from '../types'

export type GardenMemory = MemoryDetail & { layer: 'core' | 'important' | 'regular' }
export const categoryNames: Record<string, string> = {
  personal: '关于你',
  identity: '关于你',
  goal: '小小心愿',
  preference: '喜欢的事',
  event: '生活片段',
  relationship: '重要的人',
  emotion: '心情与感受',
  fact: '关于你',
  experience: '生活片段',
  other: '日常点滴',
}
export const layerNames = { core: '核心记忆', important: '重要记忆', regular: '日常记忆' }
export function categoryName(category: string) {
  return categoryNames[category] || category || '日常点滴'
}
export function memoryDate(timestamp: number) {
  return new Date(timestamp < 1e12 ? timestamp * 1000 : timestamp).toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  })
}
export function useGardenMemories(userId: string, revision: number) {
  const [state, setState] = useState<{
    userId: string
    memories: GardenMemory[]
    loading: boolean
    error: string
  }>({ userId, memories: [], loading: true, error: '' })
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    let alive = true
    setState({ userId, memories: [], loading: true, error: '' })
    if (!userId) return
    api
      .getMemoryDetail(userId)
      .then((data) => {
        if (!data.layers) throw new Error('记忆数据暂不可用')
        const memories = (['core', 'important', 'regular'] as const).flatMap((layer) =>
          (data.layers[layer] || []).map((m) => ({ ...m, layer }))
        )
        if (alive) setState({ userId, memories, loading: false, error: '' })
      })
      .catch(() => {
        if (alive) setState({ userId, memories: [], loading: false, error: '花园暂时没有连接上。' })
      })
    return () => {
      alive = false
    }
  }, [userId, revision, retry])
  return {
    ...(state.userId === userId ? state : { memories: [], loading: true, error: '' }),
    reload: () => setRetry((n) => n + 1),
  }
}

export function localDay(date = new Date()) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}
