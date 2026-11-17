import { useStore } from '../store'
import './StatsCard.css'

export default function StatsCard() {
  const memoryStats = useStore((s) => s.memoryStats)

  if (!memoryStats) return null

  const { total, consolidated_count, avg_importance } = memoryStats

  return (
    <div className="stats-card">
      {total > 0 ? (
        <>
          <div className="stats-card-grid">
            <div className="stats-card-item">
              <div className="stats-card-value">{total}</div>
              <div className="stats-card-key">记忆总数</div>
            </div>
            <div className="stats-card-item">
              <div className="stats-card-value">{consolidated_count}</div>
              <div className="stats-card-key">巩固记忆</div>
            </div>
            <div className="stats-card-item">
              <div className="stats-card-value">{avg_importance.toFixed(1)}</div>
              <div className="stats-card-key">重要性</div>
            </div>
          </div>
        </>
      ) : (
        <div className="stats-card-empty">暂无记忆数据</div>
      )}
    </div>
  )
}
