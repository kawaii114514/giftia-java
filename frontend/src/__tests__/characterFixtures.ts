import type { CharacterCard, CharacterConfig } from '../types'

export function characterConfig(userId = 'user1', activeId = 'giftia'): CharacterConfig {
  const presets: CharacterCard[] = [
    ['giftia', 'Giftia'],
    ['zhixia', '知夏'],
    ['qingchuan', '晴川'],
    ['libai', '理白'],
    ['xingyao', '星遥'],
  ].map(([id, name]) => ({
    id,
    name,
    description: `${name}的身份`,
    personality: '自然、真诚',
    scenario: '日常陪伴',
    first_mes: `${name}的开场白`,
    mes_example: '用户：你好\n角色：我在',
    tags: ['陪伴'],
  }))
  return {
    user_id: userId,
    active_character: presets.find((card) => card.id === activeId)!,
    custom_character: null,
    presets,
    system_rules: '固定铁律：禁止编造记忆',
    scope: 'user',
  }
}
