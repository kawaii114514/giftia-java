import type { CSSProperties } from 'react'
import { layerNames, memoryDate, type GardenMemory } from './gardenData'

/** Branches connect memories in one saved category; positions have no semantic meaning. */
export default function MemoryPlant({
  memories,
  selectedId,
  onSelect,
  variation,
}: {
  memories: GardenMemory[]
  selectedId: string | null
  onSelect: (id: string) => void
  variation: number
}) {
  const rows = Math.ceil(memories.length / 2)
  const height = memories.length * 158 + 60
  const wideHeight = rows * 225 + 145
  function branches(wide: boolean) {
    const h = wide ? wideHeight : height
    return (
      <svg
        className={`memory-tree ${wide ? 'memory-tree--wide' : 'memory-tree--narrow'}`}
        viewBox={`0 0 1000 ${h}`}
        preserveAspectRatio="none"
        aria-hidden="true"
      >
        <path
          className="plant-trunk"
          d={`M 505 ${h - 10} C ${variation % 2 ? 420 : 590} ${h * 0.69}, ${variation % 2 ? 560 : 440} ${h * 0.4}, 510 48`}
        />
        {memories.map((memory, index) => {
          const left = index % 2 === 0
          const y = wide ? Math.floor(index / 2) * 225 + (left ? 30 : 118) : index * 158 + 16
          const endX = wide ? (left ? 260 : 740) : left ? 330 : 670
          const stemY = Math.min(h - 15, y + 150)
          const endY = y + 105
          return (
            <g key={memory.id}>
              <path
                className="plant-branch"
                d={`M 505 ${stemY} C ${left ? 470 : 540} ${y + 118}, ${left ? 400 : 600} ${endY + 18}, ${endX} ${endY}`}
              />
              <path
                className="plant-shoot"
                d={
                  left
                    ? `M 505 ${stemY} C 560 ${stemY - 25}, 590 ${stemY - 20}, 618 ${stemY - 47} C 568 ${stemY - 60}, 532 ${stemY - 37}, 505 ${stemY}`
                    : `M 505 ${stemY} C 460 ${stemY - 22}, 430 ${stemY - 20}, 396 ${stemY - 46} C 447 ${stemY - 54}, 478 ${stemY - 35}, 505 ${stemY}`
                }
              />
            </g>
          )
        })}
      </svg>
    )
  }
  return (
    <div
      className="memory-branches"
      style={
        { '--plant-height': `${height}px`, '--wide-height': `${wideHeight}px` } as CSSProperties
      }
    >
      {branches(false)}
      {branches(true)}
      {memories.map((memory, index) => (
        <button
          key={memory.id}
          aria-pressed={memory.id === selectedId}
          className={`memory-leaf leaf-${index % 2}`}
          style={
            {
              '--leaf-y': `${index * 158 + 16}px`,
              '--wide-y': `${Math.floor(index / 2) * 225 + (index % 2 ? 118 : 30)}px`,
            } as CSSProperties
          }
          onClick={() => onSelect(memory.id)}
        >
          <small>
            <span className="leaf-bud" />
            {layerNames[memory.layer]}
          </small>
          <span>{memory.content}</span>
          <i>
            {memoryDate(memory.created_at)} <b>↗</b>
          </i>
        </button>
      ))}
    </div>
  )
}
