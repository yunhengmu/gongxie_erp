using System.Collections.Generic;
using UnityEngine;
using Core = AgvDemo.Core;
using Domain = AgvDemo.Warehouse.Domain;

namespace AgvDemo.Warehouse.Application
{
    /// <summary>
    /// 应用服务：编排一次取货。纯逻辑编排，不持有任何 MonoBehaviour / 场景 / 表现层引用。
    /// 依赖全部通过构造器注入（数据源 + 路径算法），由 Composition Root 装配。
    /// </summary>
    public sealed class WarehouseAppService
    {
        readonly IWarehouseLayoutSource _layout;
        readonly Domain.IPathFinder _pathFinder;
        readonly int _gridX;
        readonly int _gridZ;
        readonly float _cellSize;
        readonly IReadOnlyList<Vector2Int> _shelfCells;
        readonly IReadOnlyList<string> _shelfLabels;

        int _taskCounter;

        public WarehouseAppService(IWarehouseLayoutSource layout, Domain.IPathFinder pathFinder)
        {
            _layout = layout;
            _pathFinder = pathFinder;
            _gridX = layout.GridX;
            _gridZ = layout.GridZ;
            _cellSize = layout.CellSize;
            _shelfCells = layout.ShelfCells;
            _shelfLabels = layout.ShelfLabels;
        }

        public int GridX => _gridX;
        public int GridZ => _gridZ;
        public float CellSize => _cellSize;
        public IReadOnlyList<Vector2Int> ShelfCells => _shelfCells;
        public IReadOnlyList<string> ShelfLabels => _shelfLabels;

        /// <summary>四向网格距离，用来挑最近的取货点。</summary>
        public int Distance(Vector2Int a, Vector2Int b)
            => System.Math.Abs(a.x - b.x) + System.Math.Abs(a.y - b.y);

        /// <summary>
        /// 规划一次取货：找到离货架最近的通道格，再由路径算法算出路径。
        /// 返回的格子包含起点自身，供表现层直接转换成世界坐标。
        /// </summary>
        public List<Vector2Int> PlanPickup(Vector2Int from, Vector2Int shelfCell)
        {
            Vector2Int? best = null;
            int bestDistance = int.MaxValue;

            foreach (var candidate in Neighbours(shelfCell))
            {
                int d = Distance(from, candidate);
                if (d < bestDistance) { bestDistance = d; best = candidate; }
            }

            if (best == null) return new List<Vector2Int>();

            var path = _pathFinder.Find(from, best.Value);
            if (path.Count == 0) return path;

            _taskCounter++;
            Core.EventBus.Publish(new Domain.AgvTaskAssigned(_taskCounter, LabelOf(shelfCell)));
            return path;
        }

        public string LabelOf(Vector2Int cell)
        {
            for (int i = 0; i < _shelfCells.Count; i++)
                if (_shelfCells[i] == cell) return _shelfLabels[i];
            return $"({cell.x},{cell.y})";
        }

        IEnumerable<Vector2Int> Neighbours(Vector2Int cell)
        {
            if (cell.x > 0) yield return new Vector2Int(cell.x - 1, cell.y);
            if (cell.x < _gridX - 1) yield return new Vector2Int(cell.x + 1, cell.y);
            if (cell.y > 0) yield return new Vector2Int(cell.x, cell.y - 1);
            if (cell.y < _gridZ - 1) yield return new Vector2Int(cell.x, cell.y + 1);
        }
    }
}